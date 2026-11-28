"""Camera 360: put an external 360 camera's frames on the phone's track and cut them into ordinary views
(docs/camera360.md section 3). The output is a sweep folder that prepare.py reads unchanged.

usage: python camera360.py <ride folder | zip from the phone> <camera files or folders ...> <work>
                           [--views front,left,right] [--every-m 5] [--offset-s X] [--front-deg D]
                           [--size 1600x1200] [--sync-search-s 90]

Camera files: equirectangular 360 photos (JPG, 2:1; time from EXIF DateTimeOriginal + SubSecTimeOriginal +
OffsetTimeOriginal, a time without an offset is read in this PC's time zone) or equirectangular 360 videos (MP4 / MOV
exported from the camera's app, 2:1; time = the file's creation_time + each frame's timestamp, ffprobe). A raw .360
(GoPro) / .insv / .insp (Insta360) file is refused: export it as an equirectangular 360 video / photo first.

Clock: track time = camera time + offset (track time = the GPS fix times `t` of the phone's track). The offset comes
from the sync QR codes ("QPSYNC1 <sessionId> <epochMs>", epochMs = the phone's wall clock, moved onto the track's
clock by the window's gpsMinusPhoneMs) the phone showed to the camera (session.camera360.syncs: when to look); start
and end codes give a linear drift.
No code found: --offset-s, else the camera's clock as is (said loudly: positions may be off by the clock error x
speed).

Front: the direction of the 360 image that looks along the travel, from the motion (optical flow on the horizon:
the point the scene flows out of), else --front-deg (degrees clockwise from the image centre).

Writes into <work>:
  ride/           the ride (unzipped)
  sweep/          the sweep folder: manifest.json (kind "sweep", source "camera360"), photos/<id>.jpg (one per kept
                  frame and view), track.jsonl (the phone's): run prepare.py on it as on a phone sweep
  camera360.json  what was done: clock (codes found, offset, drift), front direction, frames kept, warnings
Then: python prepare.py <work>/sweep <work>
"""
import argparse, bisect, datetime as dt, json, math, os, re, shutil, subprocess, sys, zipfile

sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from paths import arg_path  # noqa: E402
from safety import extract  # noqa: E402
import prepare  # noqa: E402

PHOTO_EXT = {'.jpg', '.jpeg'}
VIDEO_EXT = {'.mp4', '.mov'}
RAW_EXT = {'.360': 'a raw GoPro MAX file: export it as an equirectangular 360 video from GoPro Player / Quik first',
           '.insv': 'a raw Insta360 video: export it as an equirectangular 360 video from Insta360 Studio / the app first',
           '.insp': 'a raw Insta360 photo: export it as an equirectangular 360 JPG from Insta360 Studio / the app first',
           '.lrv': 'a low-resolution preview the camera writes next to the video: use the exported 360 video instead'}
SKIP_EXT = {'.lrv', '.thm', '.xml', '.json', '.txt', '.db'}   # camera side files, skipped inside a folder
VIEW_ANGLES = {'front': 0.0, 'right': 90.0, 'back': 180.0, 'left': -90.0}
QR_RE = re.compile(r'^QPSYNC1 (\S+) (\d{10,})$')
STANDING_MPS = 0.7      # the app's "waiting" speed: nothing is kept below it
QR_REDRAW_MS = 100      # the phone redraws the code every 100 ms: a code is 0-100 ms old when seen (+50 ms on average)
MAX_GAP_S = 5.0         # interpolate between fixes at most this far apart


def log(*a):
    print(*a, flush=True)


# ---------------------------------------------------------------- camera files and their times

class Refused(SystemExit):
    pass


def camera_files(args):
    """The camera files from the arguments (files, or folders read one level deep), sorted; raw camera files are
    refused with what to do."""
    out = []
    for a in args:
        if os.path.isdir(a):
            for n in sorted(os.listdir(a)):
                ext = os.path.splitext(n)[1].lower()
                if ext in RAW_EXT and ext not in SKIP_EXT:
                    raise Refused(f"{os.path.join(a, n)}: {RAW_EXT[ext]}")
                if ext in PHOTO_EXT | VIDEO_EXT:
                    out.append(os.path.join(a, n))
            continue
        ext = os.path.splitext(a)[1].lower()
        if ext in RAW_EXT:
            raise Refused(f"{a}: {RAW_EXT[ext]}")
        if ext not in PHOTO_EXT | VIDEO_EXT:
            raise Refused(f"{a}: not a 360 photo (.jpg) or video (.mp4 / .mov)")
        if not os.path.isfile(a):
            raise Refused(f"{a}: no such file")
        out.append(a)
    if not out:
        raise Refused('no camera files given')
    return out


def parse_offset(s):
    """'+07:00' / '-0530' / 'Z' -> a timezone, None when missing or unreadable."""
    if not s:
        return None
    s = str(s).strip().rstrip('\x00')
    if s in ('Z', 'z'):
        return dt.timezone.utc
    m = re.match(r'^([+-])(\d{1,2}):?(\d{2})$', s)
    if not m:
        return None
    d = dt.timedelta(hours=int(m.group(2)), minutes=int(m.group(3)))
    return dt.timezone(d if m.group(1) == '+' else -d)


def exif_time_ms(dto, subsec=None, offset=None, local_tz=None):
    """EXIF DateTimeOriginal ('2026:11:28 10:00:01') + SubSecTimeOriginal ('25' = .25 s) + OffsetTimeOriginal
    ('+07:00') -> (epoch ms, has_offset). Without an offset the time is read in local_tz (default: this PC's)."""
    t = dt.datetime.strptime(str(dto).strip().rstrip('\x00')[:19], '%Y:%m:%d %H:%M:%S')
    sub = re.sub(r'\D', '', str(subsec or ''))
    frac = float('0.' + sub) if sub else 0.0
    tz = parse_offset(offset)
    if tz is not None:
        t = t.replace(tzinfo=tz)
    elif local_tz is not None:
        t = t.replace(tzinfo=local_tz)
    else:
        t = t.astimezone()   # naive -> this PC's zone
    return int(round(t.timestamp() * 1000 + frac * 1000)), tz is not None


def photo_frame(path):
    """One 360 photo: {src, kind, index, camMs, width, height, tzKnown}."""
    from PIL import Image
    with Image.open(path) as im:
        w, h = im.size
        ex = im.getexif()
        sub = ex.get_ifd(0x8769)
    dto = sub.get(36867) or ex.get(306)
    if not dto:
        raise Refused(f"{path}: no EXIF DateTimeOriginal: cannot time it")
    ms, tz_known = exif_time_ms(dto, sub.get(37521) or sub.get(37520), sub.get(36881) or sub.get(36880))
    check_equirect(path, w, h)
    return {'src': path, 'kind': 'photo', 'index': 0, 'camMs': ms, 'width': w, 'height': h, 'tzKnown': tz_known}


def check_equirect(path, w, h):
    if not h or abs(w / h - 2.0) > 0.02:
        raise Refused(f"{path}: {w}x{h} is not an equirectangular 360 image (2:1): export it from the camera's app "
                      f"as a 360 equirectangular photo / video (not dual fisheye, not a flat reframed one)")


def ffprobe_json(path, *args):
    r = subprocess.run(['ffprobe', '-v', 'error', '-of', 'json', *args, path], capture_output=True, text=True)
    if r.returncode != 0:
        raise Refused(f"{path}: ffprobe failed ({r.stderr.strip()[:200]}); is ffmpeg installed?")
    return json.loads(r.stdout or '{}')


def iso_ms(s):
    s = s.strip().replace('Z', '+00:00')
    t = dt.datetime.fromisoformat(s)
    if t.tzinfo is None:
        t = t.replace(tzinfo=dt.timezone.utc)
    return int(round(t.timestamp() * 1000))


def video_frames(path):
    """The frames of a 360 video: [{src, kind, index, camMs, ...}] in presentation order. camMs = creation_time (UTC,
    as MP4 stores it) + the frame's timestamp (ffprobe packet pts, from the first frame)."""
    info = ffprobe_json(path, '-show_entries', 'format=duration:format_tags=creation_time:'
                        'stream=codec_type,width,height,avg_frame_rate:stream_tags=creation_time')
    vs = [s for s in info.get('streams', []) if s.get('codec_type') == 'video']
    if not vs:
        raise Refused(f"{path}: no video stream")
    w, h = int(vs[0]['width']), int(vs[0]['height'])
    check_equirect(path, w, h)
    ct = (info.get('format', {}).get('tags') or {}).get('creation_time') or (vs[0].get('tags') or {}).get('creation_time')
    warn = None
    if ct:
        start = iso_ms(ct)
    else:   # no creation time: the file's last write is about the end of the recording
        dur = float(info.get('format', {}).get('duration') or 0)
        start = int(os.path.getmtime(path) * 1000 - dur * 1000)
        warn = f"{os.path.basename(path)}: no creation_time, started at the file time - its duration (rough)"
    r = subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries', 'packet=pts_time',
                        '-of', 'csv=p=0', path], capture_output=True, text=True)
    pts = sorted(float(x.split(',')[0]) for x in r.stdout.split() if x and x.split(',')[0] not in ('N/A', ''))
    if not pts:
        num, den = (vs[0].get('avg_frame_rate') or '30/1').split('/')
        fps = float(num) / float(den or 1)
        n = int(float(info.get('format', {}).get('duration') or 0) * fps)
        pts = [i / fps for i in range(n)]
    p0 = pts[0]
    _REL_MS[path] = [(p - p0) * 1000 for p in pts]
    fr = [{'src': path, 'kind': 'video', 'index': i, 'camMs': start + int(round((p - p0) * 1000)), 'width': w,
           'height': h, 'tzKnown': bool(ct)} for i, p in enumerate(pts)]
    return fr, warn


# ---------------------------------------------------------------- reading frames

def imread(path):
    import cv2, numpy as np
    im = cv2.imdecode(np.fromfile(path, dtype=np.uint8), cv2.IMREAD_COLOR)
    if im is None:
        raise Refused(f"{path}: cannot read the image")
    return im


def imwrite(path, im, quality=92):
    import cv2
    ok, buf = cv2.imencode('.jpg', im, [cv2.IMWRITE_JPEG_QUALITY, quality])
    if not ok:
        raise RuntimeError(f'cannot encode {path}')
    with open(path, 'wb') as f:
        f.write(buf.tobytes())


class VideoReader:
    """Frames of one video by index (sequential grab; a seek for a jump back or far ahead, checked against the frame's
    timestamp: an inexact seek rewinds and walks, so a frame is never mistaken for another)."""

    def __init__(self, path, rel_ms=None):
        import cv2
        self.cv2, self.path, self.rel_ms = cv2, path, rel_ms
        self.cap = cv2.VideoCapture(path)
        if not self.cap.isOpened():
            raise Refused(f"{path}: OpenCV cannot open this video (codec?). Re-export it as H.264 / H.265 MP4")
        self.pos = 0   # index of the next frame grab() returns
        self.bad_seeks = 0

    def _rewind(self):
        self.cap.release()
        self.cap = self.cv2.VideoCapture(self.path)
        self.pos = 0

    def _seek_ok(self, i):
        """After a seek to i: read frame i and check its timestamp (None when it cannot be checked)."""
        if not self.rel_ms or i >= len(self.rel_ms):
            return None
        ok, im = self.cap.read()
        if not ok:
            return False
        t = self.cap.get(self.cv2.CAP_PROP_POS_MSEC)
        dt_ms = (self.rel_ms[1] - self.rel_ms[0]) if len(self.rel_ms) > 1 else 33
        if abs(t - self.rel_ms[i]) <= 0.5 * dt_ms:
            self.pos = i + 1
            return im
        return False

    def frames(self, indices):
        for i in sorted(set(indices)):
            im = None
            if i < self.pos or i - self.pos > 150:
                self.cap.set(self.cv2.CAP_PROP_POS_FRAMES, i)
                r = self._seek_ok(i)
                if r is None or r is False:
                    if r is False:
                        self.bad_seeks += 1
                    self._rewind()
                else:
                    im = r
            if im is None:
                while self.pos < i:
                    if not self.cap.grab():
                        return
                    self.pos += 1
                ok, im = self.cap.read()
                self.pos += 1
                if not ok:
                    return
            yield i, im

    def close(self):
        self.cap.release()


_READERS = {}
_REL_MS = {}   # video -> each frame's time from the first one (ms), from ffprobe


def video_reader(src):
    """One open reader per video for the whole run (the QR scan jumps around; reopening costs a decode from the
    start each time)."""
    if src not in _READERS:
        _READERS[src] = VideoReader(src, _REL_MS.get(src))
    return _READERS[src]


def close_readers():
    for r in _READERS.values():
        r.close()
    _READERS.clear()


def read_frames(frames):
    """(frame, image) for the given frames, grouped by file (each video's frames in increasing order)."""
    by_src = {}
    for f in frames:
        by_src.setdefault(f['src'], []).append(f)
    for src, fs in by_src.items():
        if fs[0]['kind'] == 'photo':
            for f in fs:
                yield f, imread(src)
        else:
            want = {}
            for f in fs:
                want.setdefault(f['index'], []).append(f)
            for i, im in video_reader(src).frames(list(want)):
                for f in want[i]:
                    yield f, im


# ---------------------------------------------------------------- geometry: equirectangular -> rectilinear

def view_maps(eq_w, eq_h, out_w, out_h, hfov_deg, yaw_deg, pitch_deg=0.0):
    """cv2.remap maps cutting a perspective view (hfov_deg wide, out_w x out_h, looking yaw_deg clockwise from the
    equirectangular image's centre and pitch_deg up) out of an eq_w x eq_h equirectangular image. Longitude grows to
    the right (x = eq_w / 2 is yaw 0), latitude up (y = 0 is the zenith)."""
    import numpy as np
    f = (out_w / 2) / math.tan(math.radians(hfov_deg) / 2)
    u = (np.arange(out_w, dtype=np.float64) + 0.5 - out_w / 2) / f
    v = (np.arange(out_h, dtype=np.float64) + 0.5 - out_h / 2) / f
    X, Y = np.meshgrid(u, -v)
    Z = np.ones_like(X)
    p, y = math.radians(pitch_deg), math.radians(yaw_deg)
    Y2 = Y * math.cos(p) + Z * math.sin(p)
    Z2 = -Y * math.sin(p) + Z * math.cos(p)
    X3 = X * math.cos(y) + Z2 * math.sin(y)
    Z3 = -X * math.sin(y) + Z2 * math.cos(y)
    lon = np.arctan2(X3, Z3)
    lat = np.arctan2(Y2, np.hypot(X3, Z3))
    mx = ((lon / (2 * math.pi) + 0.5) * eq_w - 0.5).astype(np.float32)
    my = ((0.5 - lat / math.pi) * eq_h - 0.5).astype(np.float32)
    return mx, my


def cut_view(eq, out_w, out_h, hfov_deg, yaw_deg, pitch_deg=0.0):
    import cv2
    mx, my = view_maps(eq.shape[1], eq.shape[0], out_w, out_h, hfov_deg, yaw_deg, pitch_deg)
    return cv2.remap(eq, mx, my, cv2.INTER_LINEAR, borderMode=cv2.BORDER_WRAP)


def angle_diff(a, b):
    return (a - b + 180.0) % 360.0 - 180.0


def circ_median(angles):
    """A median of angles (deg): the plain median after unwrapping them around their circular mean."""
    if not angles:
        return None
    c = math.degrees(math.atan2(sum(math.sin(math.radians(a)) for a in angles),
                                sum(math.cos(math.radians(a)) for a in angles)))
    d = sorted(angle_diff(a, c) for a in angles)
    n = len(d)
    m = d[n // 2] if n % 2 else (d[n // 2 - 1] + d[n // 2]) / 2
    return (c + m) % 360.0


# ---------------------------------------------------------------- sync QR -> clock

QR_CUTS = [(y, 0.0) for y in range(0, 360, 45)] + [(y, -30.0) for y in range(0, 360, 90)] + [(0.0, -70.0)]


def find_qr(eq):
    """The sync code's text on one equirectangular frame, or None: the frame itself, then perspective cuts around
    the horizon (the code may sit across the seam or be bent by the projection)."""
    import cv2
    det = cv2.QRCodeDetector()
    alt = cv2.QRCodeDetectorAruco() if hasattr(cv2, 'QRCodeDetectorAruco') else None

    def look(img):
        for d in (det, alt):
            if d is None:
                continue
            try:
                txt = d.detectAndDecode(img)[0]
            except cv2.error:
                txt = ''
            if txt and QR_RE.match(txt.strip()):
                return txt.strip()
        return None

    g = cv2.cvtColor(eq, cv2.COLOR_BGR2GRAY) if eq.ndim == 3 else eq
    k = min(1.0, 4096 / g.shape[1])
    t = look(cv2.resize(g, None, fx=k, fy=k, interpolation=cv2.INTER_AREA) if k < 1 else g)
    if t:
        return t
    side = max(256, min(1600, g.shape[1] // 4))
    for yaw, pitch in QR_CUTS:
        t = look(cut_view(g, side, side, 90.0, yaw, pitch))
        if t:
            return t
    return None


def coarse_shift(frames, ride_span):
    """Whole hours (ms) to add to the camera clock so its span overlaps the ride most (a camera set to another
    time zone, or local time written as UTC): only to know where to look for the codes. None when no shift makes
    them overlap."""
    c0, c1 = frames[0]['camMs'], frames[-1]['camMs']
    best, best_ov = None, 0
    for h in sorted(range(-14, 15), key=abs):
        s = h * 3600_000
        ov = min(c1 + s, ride_span[1]) - max(c0 + s, ride_span[0])
        if ov > best_ov:
            best, best_ov = s, ov
    return best


def scan_qr(frames, center_cam, half_ms, step_ms, dense_ms, step_dense_ms, session_id, stats):
    """Look for the sync code in the frames within half_ms of center_cam (camera time): sampled every step_ms from
    the centre outwards until one is found, then every step_dense_ms within dense_ms of it.
    -> [(frame, epochMs)]. stats counts the frames looked at and the codes of another ride."""
    cand = [f for f in frames if abs(f['camMs'] - center_cam) <= half_ms]
    cand = thin(cand, step_ms)
    cand.sort(key=lambda f: abs(f['camMs'] - center_cam))
    hit = None
    for f, im in _in_order(cand):
        stats['looked'] += 1
        r = decode_sync(find_qr(im), session_id, stats)
        if r is not None:
            hit = (f, r)
            break
    if not hit:
        return []
    near = thin([f for f in frames if abs(f['camMs'] - hit[0]['camMs']) <= dense_ms], step_dense_ms)
    out = []
    for f, im in read_frames(near):
        stats['looked'] += 1
        r = hit[1] if f is hit[0] else decode_sync(find_qr(im), session_id, stats)
        if r is not None:
            out.append((f, r))
    return out or [hit]


def _in_order(frames):
    """read_frames, but in the given order (for the outward scan): one frame at a time."""
    for f in frames:
        for pair in read_frames([f]):
            yield pair


def thin(frames, step_ms):
    """Frames at least step_ms apart (in camera time), keeping the first."""
    out, last = [], None
    for f in sorted(frames, key=lambda f: f['camMs']):
        if last is None or f['camMs'] - last >= step_ms:
            out.append(f)
            last = f['camMs']
    return out


def decode_sync(text, session_id, stats):
    if not text:
        return None
    m = QR_RE.match(text)
    if not m:
        return None
    if session_id and m.group(1) != session_id:
        stats['otherRide'] += 1
        return None
    return int(m.group(2))


def clock_model(detections, syncs, redraw_ms=QR_REDRAW_MS):
    """Offset (track ms - camera ms) as a function of camera time, from the codes seen: [(camMs, epochMs)].
    epochMs is the phone's wall clock; the track's t is the GPS fix time: a sync window's gpsMinusPhoneMs (the app's
    measure of the difference, docs/camera360.md App notes) is added (the windows' median for a code outside every
    window, 0 when none has it).
    A code is drawn every redraw_ms, so it is on average redraw_ms / 2 old when the camera sees it. The detections
    are grouped by sync window (the epoch inside a syncs entry, else gaps over 20 s); each group gives its median
    offset at its median camera time. One group: a constant offset; two or more: a straight line (least squares) =
    the drift. -> {'groups': [...], 'offsetMs', 'driftPpm', 'fn'} or None without detections."""
    if not detections:
        return None
    gms = sorted(s['gpsMinusPhoneMs'] for s in syncs or [] if isinstance(s.get('gpsMinusPhoneMs'), (int, float)))
    gm_default = gms[len(gms) // 2] if gms else 0
    dets = sorted((c, e + redraw_ms / 2.0 - c, e) for c, e in detections)
    groups = {}
    for c, off, e in dets:
        key, gm = None, gm_default
        for k, s in enumerate(syncs or []):
            if s.get('fromMs') is not None and s['fromMs'] - 5000 <= e <= (s.get('toMs') or s['fromMs']) + 5000:
                key = ('sync', k)
                if isinstance(s.get('gpsMinusPhoneMs'), (int, float)):
                    gm = s['gpsMinusPhoneMs']
                break
        if key is None:
            key = ('gap', int(e // 20000))
        groups.setdefault(key, []).append((c, off + gm))   # the code's phone time -> the track's (GPS) time
    gs = []
    for key, v in groups.items():
        cs, os_ = sorted(x[0] for x in v), sorted(x[1] for x in v)
        when = (syncs[key[1]].get('when') if key[0] == 'sync' else None)
        gs.append({'camMs': cs[len(cs) // 2], 'offsetMs': os_[len(os_) // 2], 'codes': len(v), 'when': when,
                   'spreadMs': round(os_[-1] - os_[0])})
    gs.sort(key=lambda g: g['camMs'])
    if len(gs) == 1 or gs[-1]['camMs'] - gs[0]['camMs'] < 30_000:
        o = sorted(g['offsetMs'] for g in gs)[len(gs) // 2]
        return {'groups': gs, 'offsetMs': o, 'driftPpm': None, 'fn': lambda c, o=o: o}
    n = len(gs)
    mc = sum(g['camMs'] for g in gs) / n
    mo = sum(g['offsetMs'] for g in gs) / n
    sxx = sum((g['camMs'] - mc) ** 2 for g in gs)
    r = sum((g['camMs'] - mc) * (g['offsetMs'] - mo) for g in gs) / sxx
    return {'groups': gs, 'offsetMs': mo, 'driftPpm': r * 1e6, 'refCamMs': mc,
            'fn': lambda c, mc=mc, mo=mo, r=r: mo + r * (c - mc)}


# ---------------------------------------------------------------- the phone's track

def load_track_t(src, work):
    """GPS fixes with their wall time: [{t, lat, lon, acc, spd, hdg, en}] sorted by t, from track.jsonl (a session
    folder) or track.geojson (an export), looked for as prepare.load_track does. Also the raw lines to copy."""
    for d in (src, os.path.join(work, 'raw')):
        f = os.path.join(d, 'track.jsonl') if os.path.isdir(d) else None
        if f and os.path.exists(f):
            out = []
            with open(f, encoding='utf-8') as fh:
                lines = fh.readlines()
            for line in lines:
                try:
                    o = json.loads(line)
                except ValueError:
                    continue
                if o.get('t') is not None and o.get('lat') is not None and o.get('lon') is not None:
                    out.append({'t': o['t'], 'lat': o['lat'], 'lon': o['lon'], 'acc': o.get('acc'),
                                'spd': o.get('spd'), 'hdg': o.get('hdg'), 'en': o.get('en')})
            return sorted(out, key=lambda x: x['t'])
    for d in (src, os.path.join(work, 'raw')):
        f = os.path.join(d, 'track.geojson') if os.path.isdir(d) else None
        if f and os.path.exists(f):
            out = []
            with open(f, encoding='utf-8') as fh:
                feats = json.load(fh).get('features', [])
            for ft in feats:
                g, pr = ft.get('geometry') or {}, ft.get('properties') or {}
                if g.get('type') == 'Point' and pr.get('t') is not None:
                    lon, lat = g['coordinates'][:2]
                    out.append({'t': pr['t'], 'lat': lat, 'lon': lon, 'acc': pr.get('accuracyM'),
                                'spd': pr.get('speedMps'), 'hdg': pr.get('headingDeg'), 'en': pr.get('elapsedNanos')})
            return sorted(out, key=lambda x: x['t'])
    return []


def dist_m(lat1, lon1, lat2, lon2):
    from build import to_xy
    return math.hypot(*to_xy(lat2, lon2, lat1, lon1))


def bearing_deg(lat1, lon1, lat2, lon2):
    from build import to_xy
    x, y = to_xy(lat2, lon2, lat1, lon1)
    return math.degrees(math.atan2(x, y)) % 360.0


class Track:
    """The phone's track on its wall clock (ms): positions between fixes as prepare.py places photos
    (prepare.place_in_time, keyed by t in ns), the distance ridden (segments under STANDING_MPS count nothing) and
    the travel bearing."""

    def __init__(self, fixes, max_gap_s=MAX_GAP_S):
        self.fx = fixes
        self.t = [f['t'] for f in fixes]
        self.max_gap_s = max_gap_s
        self.key = [(f['t'] * 1_000_000, f['lat'], f['lon'], f.get('spd'), f.get('hdg')) for f in fixes]
        self.seg_v, self.cum = [], [0.0]
        for a, b in zip(fixes, fixes[1:]):
            d = dist_m(a['lat'], a['lon'], b['lat'], b['lon'])
            span = (b['t'] - a['t']) / 1000.0
            v = d / span if span > 0 else 0.0
            self.seg_v.append(v)
            self.cum.append(self.cum[-1] + (d if v >= STANDING_MPS and span <= max_gap_s else 0.0))

    def at(self, ms):
        """{lat, lon, how, s (m ridden), speedMps, accuracyM, en} at phone time ms, None off the track."""
        p = prepare.place_in_time({'elapsedNanos': ms * 1_000_000}, self.key, max_gap_s=self.max_gap_s)
        if p is None and self.t and ms == self.t[0]:   # exactly on the first fix (place_in_time needs one before)
            p = (self.fx[0]['lat'], self.fx[0]['lon'], 'interpolated')
        if p is None:
            return None
        k = bisect.bisect_right(self.t, ms) - 1
        out = {'lat': p[0], 'lon': p[1], 'how': p[2]}
        if 0 <= k < len(self.fx) - 1:
            a, b = self.fx[k], self.fx[k + 1]
            f = (ms - a['t']) / (b['t'] - a['t']) if b['t'] > a['t'] else 0.0
            out['s'] = self.cum[k] + f * (self.cum[k + 1] - self.cum[k])
            out['speedMps'] = self.seg_v[k]
            out['accuracyM'] = max(a.get('acc') or 0, b.get('acc') or 0) or None
            out['en'] = (int(a['en'] + f * (b['en'] - a['en'])) if a.get('en') is not None and b.get('en') is not None
                         else None)
        else:   # just past the last fix (extrapolated)
            a = self.fx[-1]
            out.update({'s': self.cum[-1], 'speedMps': a.get('spd') or 0.0, 'accuracyM': a.get('acc'),
                        'en': int(a['en'] + (ms - a['t']) * 1_000_000) if a.get('en') is not None else None})
        return out

    def travel_bearing(self, ms, half_ms=2000):
        """Bearing of the travel at ms: from the position half_ms before to half_ms after (the containing fixes
        when those are off the track); the fix heading when it moved under 1.5 m. None when unknown."""
        a, b = self.at(ms - half_ms), self.at(ms + half_ms)
        if a is None or b is None:
            k = bisect.bisect_right(self.t, ms) - 1
            if not 0 <= k < len(self.fx) - 1:
                return None
            a, b = self.fx[k], self.fx[k + 1]
        if dist_m(a['lat'], a['lon'], b['lat'], b['lon']) >= 1.5:
            return bearing_deg(a['lat'], a['lon'], b['lat'], b['lon'])
        k = bisect.bisect_right(self.t, ms) - 1
        h = self.fx[max(0, k)].get('hdg')
        return float(h) if h is not None else None


def select_every(frames, every_m):
    """Frames kept every every_m metres ridden: for each mark along the ride the nearest frame (within every_m / 2),
    only moving frames (none while standing). frames: sorted by time, each with 's' and 'speedMps'."""
    mv = [f for f in frames if f.get('s') is not None and (f.get('speedMps') or 0) >= STANDING_MPS]
    if not mv:
        return []
    ss = [f['s'] for f in mv]
    out, seen = [], set()
    mark = ss[0]
    while mark <= ss[-1] + 1e-9:
        k = bisect.bisect_left(ss, mark)
        best = min((j for j in (k - 1, k) if 0 <= j < len(mv)), key=lambda j: (abs(ss[j] - mark), j))
        if abs(ss[best] - mark) <= every_m / 2 and best not in seen:
            seen.add(best)
            out.append(mv[best])
        mark += every_m
    return out


# ---------------------------------------------------------------- front direction from the motion

def foe_yaw(img_a, img_b, width=1024, band_deg=20.0):
    """The yaw (deg clockwise from the equirectangular image's centre) the camera moved towards between two frames:
    the focus of expansion. On the horizon band the horizontal flow of a point at yaw t is a + k sin(t - foe)
    (a = the camera's own turn, k > 0 = speed / depth: the scene flows out of the FOE); fitted robustly over textured
    pixels. -> (foe deg, k, fit quality 0..1) or None."""
    import cv2, numpy as np

    def prep(im):
        g = cv2.cvtColor(im, cv2.COLOR_BGR2GRAY) if im.ndim == 3 else im
        return cv2.resize(g, (width, width // 2), interpolation=cv2.INTER_AREA)
    a, b = prep(img_a), prep(img_b)
    W, H = width, width // 2
    pad = W // 8
    ap = np.concatenate([a[:, -pad:], a, a[:, :pad]], axis=1)   # wrap the seam so the flow crosses it
    bp = np.concatenate([b[:, -pad:], b, b[:, :pad]], axis=1)
    flow = cv2.calcOpticalFlowFarneback(ap, bp, None, 0.5, 5, 21, 5, 7, 1.5, 0)[:, pad:pad + W]
    rows = np.arange(H)
    lat = (0.5 - (rows + 0.5) / H) * 180.0
    band = np.abs(lat) <= band_deg
    gx = cv2.Sobel(a, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(a, cv2.CV_32F, 0, 1, ksize=3)
    mag = np.hypot(gx, gy)[band]
    u = flow[band, :, 0] * (2 * math.pi / W)
    theta = ((np.arange(W) + 0.5) / W - 0.5) * 2 * math.pi
    T = np.broadcast_to(theta, u.shape)
    keep = mag > np.percentile(mag, 50)
    u, T = u[keep], T[keep]
    if u.size < 200:
        return None
    A = np.stack([np.ones_like(T), np.sin(T), np.cos(T)], axis=1)
    w = np.ones_like(u)
    for _ in range(4):   # iteratively reweighted (Huber-ish) least squares
        coef = np.linalg.lstsq(A * w[:, None], u * w, rcond=None)[0]
        r = u - A @ coef
        s = 1.4826 * np.median(np.abs(r)) + 1e-9
        w = 1.0 / np.maximum(1.0, np.abs(r) / (2.0 * s))
    _, B, C = coef
    k = math.hypot(B, C)
    q = 1.0 - float(np.sum(w * r ** 2) / max(1e-12, np.sum(w * (u - np.average(u, weights=w)) ** 2)))
    if not np.isfinite(k) or k <= 0:
        return None
    return math.degrees(math.atan2(-C, B)) % 360.0, k, max(0.0, min(1.0, q))


def front_estimates(frames, kept, pairs_max=30, min_speed=2.0):
    """FOE estimates for up to pairs_max kept frames spread over the ride: each with its next frame about 0.2 s
    later (video) or the next photo (time-lapse, up to 3 s). -> [(phoneMs, foe deg, k, q)]."""
    by_src = {}
    for f in frames:
        by_src.setdefault(f['src'], []).append(f)
    cands = [f for f in kept if (f.get('speedMps') or 0) >= min_speed]
    if len(cands) > pairs_max:
        step = len(cands) / pairs_max
        cands = [cands[int(i * step)] for i in range(pairs_max)]
    pairs = []
    for f in cands:
        fs = by_src[f['src']]
        if f['kind'] == 'video':
            fps = (len(fs) - 1) / max(1e-3, (fs[-1]['camMs'] - fs[0]['camMs']) / 1000.0) if len(fs) > 1 else 30
            j = f['index'] + max(1, round(0.2 * fps))
            if j < len(fs):
                pairs.append((f, fs[j]))
        else:
            later = [g for g in frames if g['kind'] == 'photo' and 0 < g['camMs'] - f['camMs'] <= 3000]
            if later:
                pairs.append((f, min(later, key=lambda g: g['camMs'])))
    if not pairs:
        return []
    need = [p[0] for p in pairs] + [p[1] for p in pairs]
    imgs = {}
    for fr, im in read_frames(need):
        import cv2
        imgs[id(fr)] = cv2.resize(im, (1024, 512), interpolation=cv2.INTER_AREA)
    out = []
    for a, b in pairs:
        if id(a) in imgs and id(b) in imgs:
            r = foe_yaw(imgs[id(a)], imgs[id(b)])
            if r and r[2] >= 0.6:   # a poor fit (too far apart: > ~2-3 m ridden at street depth) is dropped
                out.append((a['phoneMs'], r[0], r[1], r[2]))
    return out


def front_for(ms, ests, glob, local):
    """The front yaw for a frame at phone time ms: the global median, or (local) the median of the 3 nearest
    estimates in time (a helmet mount, or a video kept pointing north by the export)."""
    if not local or not ests:
        return glob
    near = sorted(ests, key=lambda e: abs(e[0] - ms))[:3]
    return circ_median([e[1] for e in near])


# ---------------------------------------------------------------- main

def parse_views(s):
    out = []
    for v in (s or 'front,left,right').split(','):
        v = v.strip().lower()
        if not v:
            continue
        if v in VIEW_ANGLES:
            out.append((v, VIEW_ANGLES[v]))
        else:
            try:
                a = float(v)
            except ValueError:
                raise SystemExit(f"--views: {v!r} is not front / left / right / back or an angle in degrees")
            out.append((('v' if a >= 0 else 'vm') + f'{abs(a):g}'.replace('.', 'p'), a))
    if not out:
        raise SystemExit('--views: none')
    return out


def load_ride(src, work):
    """(session, ride folder) from the phone's ride: a zip (unzipped into <work>/ride) or a folder."""
    ride = os.path.join(work, 'ride')
    os.makedirs(ride, exist_ok=True)
    if zipfile.is_zipfile(src):
        extract(src, ride)
        src = ride
    session, items = prepare.load(src, ride)
    return session, items, src


def iso(ms):
    return dt.datetime.fromtimestamp(ms / 1000, dt.timezone.utc).isoformat(timespec='milliseconds').replace('+00:00', 'Z')


def run(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('paths', nargs='+', help='<ride> <camera files or folders ...> <work>')
    ap.add_argument('--views', default='front,left,right')
    ap.add_argument('--every-m', type=float, default=5.0)
    ap.add_argument('--offset-s', type=float, default=None, help="track (phone GPS) time - camera time, seconds, when no code is found")
    ap.add_argument('--front-deg', type=float, default=None,
                    help='the yaw of the 360 image (deg clockwise from its centre) that looks along the travel')
    ap.add_argument('--size', default='1600x1200')
    ap.add_argument('--hfov', type=float, default=90.0)
    ap.add_argument('--sync-search-s', type=float, default=90.0,
                    help='look for the code this far around each sync window (camera clock error)')
    ap.add_argument('--scan-s', type=float, default=180.0,
                    help='no code near the windows: scan this much of the start and end of the camera material')
    a = ap.parse_args(argv)
    if len(a.paths) < 3:
        ap.error('need <ride> <camera files ...> <work>')
    src, work = arg_path(a.paths[0]), arg_path(a.paths[-1])
    cams = [arg_path(p) for p in a.paths[1:-1]]
    try:
        ow, oh = (int(x) for x in a.size.lower().split('x'))
    except ValueError:
        ap.error('--size WxH')
    views = parse_views(a.views)
    warnings = []

    def warn(msg):
        warnings.append(msg)
        log('WARNING: ' + msg)

    files = camera_files(cams)   # refuses raw files before anything is written
    os.makedirs(work, exist_ok=True)
    session, items, ride_dir = load_ride(src, work)
    sid = session.get('id')
    if session.get('kind') != 'camera360':
        warn(f"the ride's kind is {session.get('kind')!r}, not 'camera360'")
    if items:
        warn(f"the ride has {len(items)} photo(s) of its own: ignored (only its track is used)")
    fixes = load_track_t(ride_dir, os.path.join(work, 'ride'))
    if len(fixes) < 2:
        raise SystemExit("the ride has no track (track.jsonl / track.geojson with t): nothing to place the frames on")
    track = Track(fixes)
    ride_span = (fixes[0]['t'], fixes[-1]['t'])
    syncs = [s for s in ((session.get('camera360') or {}).get('syncs') or []) if isinstance(s, dict)]

    # 1. frames and camera times
    frames = []
    for f in files:
        if os.path.splitext(f)[1].lower() in VIDEO_EXT:
            fr, w = video_frames(f)
            frames += fr
            if w:
                warn(w)
        else:
            frames.append(photo_frame(f))
    frames.sort(key=lambda f: (f['camMs'], f['src'], f['index']))
    n_photo = sum(1 for f in frames if f['kind'] == 'photo')
    log(f"camera: {len(files)} file(s), {len(frames)} frames ({n_photo} photos), "
        f"{iso(frames[0]['camMs'])} .. {iso(frames[-1]['camMs'])} (camera clock)")
    if n_photo and not all(f['tzKnown'] for f in frames if f['kind'] == 'photo'):
        warn("some photos have no OffsetTimeOriginal: their time was read in this PC's time zone")

    # 2. clock offset
    stats = {'looked': 0, 'otherRide': 0}
    shift = coarse_shift(frames, ride_span)
    dets, how_found = [], None
    if a.offset_s is None or syncs:
        if shift is not None and syncs:
            fps_step = 500   # 2 frames a second while searching; codes stay on screen 2-3 s
            for s in syncs:
                if s.get('fromMs') is None:
                    continue
                to = s.get('toMs') or s['fromMs']
                centre = (s['fromMs'] + to) / 2 - shift
                half = (to - s['fromMs']) / 2 + a.sync_search_s * 1000
                dets += scan_qr(frames, centre, half, fps_step, (to - s['fromMs']) / 2 + 3000, 100, sid, stats)
            how_found = 'around the sync windows' + (f' (camera clock shifted {shift / 3600_000:+.0f} h to look)'
                                                     if shift else '')
        if not dets:   # the camera clock is far off: the start and the end of the material
            for centre in (frames[0]['camMs'] + a.scan_s * 500, frames[-1]['camMs'] - a.scan_s * 500):
                dets += scan_qr(frames, centre, a.scan_s * 500, 1000, 5000, 100, sid, stats)
            how_found = f'in the first / last {a.scan_s:.0f} s of the camera material'
    seen, uniq = set(), []
    for f, e in dets:
        k = (f['src'], f['index'])
        if k not in seen:
            seen.add(k)
            uniq.append((f['camMs'], e))
    if stats['otherRide']:
        warn(f"{stats['otherRide']} sync code(s) of another ride seen: ignored")
    model = clock_model(uniq, syncs)
    if model:
        clock = {'source': 'sync codes', 'found': how_found, 'codes': len(uniq), 'framesLooked': stats['looked'],
                 'offsetMs': round(model['offsetMs']),
                 'driftPpm': round(model['driftPpm'], 1) if model['driftPpm'] is not None else None,
                 'groups': [{**g, 'camMs': g['camMs'], 'offsetMs': round(g['offsetMs'])} for g in model['groups']]}
        off_fn = model['fn']
        log(f"clock: {len(uniq)} sync code(s) in {len(model['groups'])} group(s) ({how_found}); track = camera "
            f"{model['offsetMs'] / 1000:+.3f} s" + (f", drift {model['driftPpm']:+.0f} ppm" if model['driftPpm']
                                                     is not None else (' (one sync: no drift measured)' if len(model['groups']) == 1
                                                                  else ' (syncs under 30 s apart: no drift measured)')))
        for g in model['groups']:
            log(f"  {g['when'] or '?'}: {g['codes']} code(s) at camera {iso(g['camMs'])}, offset "
                f"{g['offsetMs'] / 1000:+.3f} s (spread {g['spreadMs']} ms)")
        if model['driftPpm'] is not None and abs(model['driftPpm']) > 1000:
            warn(f"clock drift {model['driftPpm']:+.0f} ppm is implausible for a camera clock (1000 ppm = 3.6 s an "
                 f"hour): check the codes (camera360.json clock.groups)")
        if a.offset_s is not None:
            warn(f"--offset-s {a.offset_s} ignored: the sync codes give the offset")
    elif a.offset_s is not None:
        clock = {'source': '--offset-s', 'offsetMs': round(a.offset_s * 1000), 'framesLooked': stats['looked']}
        off_fn = lambda c, o=a.offset_s * 1000: o
        log(f"clock: no sync code used; --offset-s {a.offset_s:+.3f} s")
    else:
        clock = {'source': 'camera clock as is', 'offsetMs': 0, 'framesLooked': stats['looked']}
        off_fn = lambda c: 0.0
        warn("NO SYNC CODE FOUND and no --offset-s: the camera's own clock is used AS IS. Positions are off by the "
             "camera's clock error x the speed (1 s at 30 km/h = 8 m). Look for the code by hand (the frames at the "
             "start), then run again with --offset-s")

    # 3. positions
    placed = []
    for f in frames:
        f['phoneMs'] = int(round(f['camMs'] + off_fn(f['camMs'])))
        p = track.at(f['phoneMs'])
        if p is not None:
            f.update(p)
            placed.append(f)
    log(f"positions: {len(placed)} of {len(frames)} frames on the phone's track "
        f"({iso(ride_span[0])} .. {iso(ride_span[1])})")
    if not placed:
        raise SystemExit("no frame falls on the phone's track: wrong ride, or the clock is off (try --offset-s)")
    kept = select_every(placed, a.every_m)
    log(f"kept {len(kept)} frames, one every {a.every_m:g} m ridden (none while standing)")
    if not kept:
        raise SystemExit('no frame kept: the ride never moved?')
    for f in kept:
        f['travelBearing'] = track.travel_bearing(f['phoneMs'])

    # 4. front direction
    front = {'source': None}
    ests = []
    if a.front_deg is not None:
        glob, local = a.front_deg % 360.0, False
        front.update(source='--front-deg', deg=glob)
        log(f"front: --front-deg {glob:g}")
    else:
        ests = front_estimates(frames, kept)
        glob = circ_median([e[1] for e in ests])
        if glob is None:
            raise SystemExit('front direction: the motion gave no estimate (too slow, too dark?): give --front-deg '
                             '(the yaw of the 360 image, deg clockwise from its centre, that looks along the travel)')
        dev = sorted(abs(angle_diff(e[1], glob)) for e in ests)
        mad = dev[len(dev) // 2]
        local = mad > 10.0
        front.update(source='motion', deg=round(glob, 1), estimates=len(ests), madDeg=round(mad, 1), local=local,
                     each=[{'phoneMs': e[0], 'deg': round(e[1], 1), 'q': round(e[3], 2)} for e in ests])
        log(f"front: {glob:.0f} deg in the 360 image (from the motion, {len(ests)} frame pairs, half within "
            f"{mad:.0f} deg)" + ('; they vary along the ride: each frame takes its nearest estimates' if local else ''))
        if len(ests) < 3:
            warn(f'front from only {len(ests)} frame pair(s): check the front views (else --front-deg)')

    # 5. views and the sweep folder
    out = os.path.join(work, 'sweep')
    if os.path.isdir(out):
        shutil.rmtree(out)
    os.makedirs(os.path.join(out, 'photos'))
    import cv2
    by_view = {name: [] for name, _ in views}
    kept_no = {id(f): k for k, f in enumerate(kept)}
    for n, (f, eq) in enumerate(read_frames(kept)):
        fy = front_for(f['phoneMs'], ests, glob, local)
        f['frontDeg'] = fy
        for name, ang in views:
            im = cut_view(eq, ow, oh, a.hfov, fy + ang)
            pid = f"c{kept_no[id(f)]:05d}_{name}"
            imwrite(os.path.join(out, 'photos', pid + '.jpg'), im)
            g = cv2.cvtColor(cv2.resize(im, (640, 480)), cv2.COLOR_BGR2GRAY)
            sharp = float(cv2.Laplacian(g, cv2.CV_64F).var())
            tb = f.get('travelBearing')
            by_view[name].append({
                'id': pid, 'kind': 'photo', 'file': f'photos/{pid}.jpg', 'trigger': 'auto',
                't': iso(f['phoneMs']), 'tMs': f['phoneMs'], 'elapsedNanos': f.get('en'),
                'lat': round(f['lat'], 7), 'lon': round(f['lon'], 7), 'accuracyM': f.get('accuracyM'),
                'speedMps': round(f.get('speedMps') or 0.0, 2), 'travelBearing': round(tb, 1) if tb is not None else None,
                'cameraBearing': round((tb + ang) % 360.0, 1) if tb is not None else None,
                'cameraYawToTravelDeg': ang, 'cameraBearingSource': 'camera360', 'bearingSource': 'camera360',
                'sideOfTravel': 'right' if 0 < ang < 180 else 'left' if -180 < ang < 0 else None,
                'hFovDeg': a.hfov, 'width': ow, 'height': oh, 'orientation': 'landscape', 'exifOrientation': 1,
                'positionFrom': 'phone track', 'positionHow': f.get('how'), 'roadName': None,
                'blur': {'sharpness': round(sharp, 1), 'blurry': None},
                'camera360': {'view': name, 'viewYawDeg': ang, 'frontDeg': round(fy, 1), 'file': os.path.basename(f['src']),
                              'frameIndex': f['index'], 'cameraMs': f['camMs'], 's': round(f['s'], 1)},
            })
        if (n + 1) % 50 == 0:
            log(f"  {n + 1} / {len(kept)} frames cut")
    items_out = [it for name, _ in views for it in sorted(by_view[name], key=lambda it: it['tMs'])]   # one block per view, each in riding order
    blocks = []
    i0 = 0
    for name, ang in views:
        blocks.append({'view': name, 'yawDeg': ang, 'fromIdx': i0, 'toIdx': i0 + len(by_view[name]) - 1})
        i0 += len(by_view[name])
    proc = {'files': [os.path.basename(f) for f in files], 'frames': len(frames), 'placed': len(placed),
            'kept': len(kept), 'everyM': a.every_m, 'views': blocks, 'size': [ow, oh], 'hFovDeg': a.hfov,
            'clock': clock, 'front': front, 'warnings': warnings}
    sess = dict(session)
    sess.update({'kind': 'sweep', 'source': 'camera360', 'rideKind': session.get('kind'),
                 'camera': {'source': 'camera360', 'hFovDeg': a.hfov, 'width': ow, 'height': oh},
                 'camera360': {**(session.get('camera360') or {}), 'processing': proc}})
    if not sess.get('target'):   # a camera360 ride has target null
        sess['target'] = 'shops'
    manifest = {'schema': 'poi-capture/3', 'kind': 'sweep', 'source': 'camera360', 'exportedAt': iso(
        int(dt.datetime.now(dt.timezone.utc).timestamp() * 1000)), 'session': sess, 'items': items_out,
        'trackFile': 'track.jsonl', 'trackPoints': len(fixes)}
    with open(os.path.join(out, 'manifest.json'), 'w', encoding='utf-8') as fo:
        json.dump(manifest, fo, ensure_ascii=False, indent=1)
    with open(os.path.join(out, 'track.jsonl'), 'w', encoding='utf-8') as fo:
        for x in fixes:
            fo.write(json.dumps({k: v for k, v in x.items() if v is not None}) + '\n')
    with open(os.path.join(work, 'camera360.json'), 'w', encoding='utf-8') as fo:
        json.dump(proc, fo, ensure_ascii=False, indent=1)
    log(f"sweep: {len(items_out)} photos in {out} (" + ', '.join(
        f"{b['view']} #{b['fromIdx']}-{b['toIdx']}" for b in blocks) + ')')
    log(f"next: python -E -P -X utf8 {os.path.join(os.path.dirname(os.path.abspath(__file__)), 'prepare.py')} "
        f"{out} {work}")
    if warnings:
        log(f"{len(warnings)} warning(s): see above and {os.path.join(work, 'camera360.json')}")
    return proc


def main():
    try:
        run()
    except Refused as e:
        print(f"refused: {e.code}", file=sys.stderr)
        sys.exit(2)
    finally:
        close_readers()


if __name__ == '__main__':
    main()
