"""camera360.py on synthetic 360 material (no real camera file in the repo): EXIF times, the sync QR -> clock offset
and drift, positions on the phone's track and the every-m selection, the equirectangular -> perspective geometry,
the front from the motion, the raw-file refusal, and whole runs (photos from a session folder, a video with a wrong
time zone from an export zip) followed by prepare.py.

    python -m unittest discover -s .claude/skills/process-sweep/scripts/tests
"""
import datetime as dt, json, math, os, shutil, subprocess, sys, tempfile, unittest, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPTS = os.path.dirname(HERE)
sys.path.insert(0, SCRIPTS)
sys.path.insert(0, HERE)

try:
    import numpy as np
    import cv2
    HAVE_CV = True
except ImportError:   # the skill's step 4b dependency; camera360 needs it
    HAVE_CV = False

if HAVE_CV:
    import camera360 as C  # noqa: E402
    import synth360 as S  # noqa: E402
HAVE_QR = HAVE_CV and hasattr(cv2, 'QRCodeEncoder')
HAVE_FFMPEG = shutil.which('ffmpeg') is not None and shutil.which('ffprobe') is not None
SID = 's_20261128_cam360'
T0 = 1795000000000   # the ride's first fix (track time, ms)


def utc_ms(s):
    return int(dt.datetime.fromisoformat(s.replace('Z', '+00:00')).timestamp() * 1000)


def load(path):
    with open(path, encoding='utf-8') as f:
        return json.load(f)


def run_py(script, *args):
    r = subprocess.run([sys.executable, '-E', '-P', '-X', 'utf8', os.path.join(SCRIPTS, script), *args],
                       capture_output=True, text=True, encoding='utf-8')
    return r


@unittest.skipUnless(HAVE_CV, 'needs numpy + opencv-python')
class ExifTimes(unittest.TestCase):
    def test_subseconds_and_offset(self):
        ms, known = C.exif_time_ms('2026:11:28 10:00:01', '25', '+07:00')
        self.assertTrue(known)
        self.assertEqual(ms, utc_ms('2026-11-28T03:00:01.250Z'))
        self.assertEqual(C.exif_time_ms('2026:11:28 10:00:01', '005', '-05:30')[0], utc_ms('2026-11-28T15:30:01.005Z'))
        self.assertEqual(C.exif_time_ms('2026:11:28 10:00:01\x00', None, 'Z')[0], utc_ms('2026-11-28T10:00:01Z'))

    def test_no_offset_reads_the_given_zone(self):
        ms, known = C.exif_time_ms('2026:11:28 10:00:01', '5', None, local_tz=dt.timezone.utc)
        self.assertFalse(known)
        self.assertEqual(ms, utc_ms('2026-11-28T10:00:01.500Z'))

    def test_photo_frame_from_a_written_jpg(self):
        d = tempfile.mkdtemp()
        try:
            f = os.path.join(d, 'IMG_0001.jpg')
            cam = utc_ms('2026-11-28T03:00:01.375Z')
            S.save_jpg(f, np.zeros((128, 256, 3), np.uint8), cam, tz_h=7)
            fr = C.photo_frame(f)
            self.assertEqual(fr['camMs'], cam)
            self.assertTrue(fr['tzKnown'])
            flat = os.path.join(d, 'flat.jpg')
            S.save_jpg(flat, np.zeros((120, 160, 3), np.uint8), cam)
            with self.assertRaises(C.Refused):   # 4:3, not equirectangular
                C.photo_frame(flat)
        finally:
            shutil.rmtree(d)


@unittest.skipUnless(HAVE_CV, 'needs numpy + opencv-python')
class RawRefused(unittest.TestCase):
    def test_raw_files(self):
        d = tempfile.mkdtemp()
        try:
            for name in ('VID_20261128_100000_00_001.insv', 'GS010123.360', 'IMG_1.insp'):
                p = os.path.join(d, name)
                open(p, 'wb').close()
                with self.assertRaises(C.Refused) as cm:
                    C.camera_files([p])
                self.assertIn('export', str(cm.exception.code))
            with self.assertRaises(C.Refused):   # inside a folder too
                C.camera_files([d])
        finally:
            shutil.rmtree(d)

    def test_command_line_refuses_before_writing(self):
        d = tempfile.mkdtemp()
        try:
            raw = os.path.join(d, 'GS010123.360')
            open(raw, 'wb').close()
            work = os.path.join(d, 'work')
            r = run_py('camera360.py', os.path.join(d, 'ride'), raw, work)
            self.assertEqual(r.returncode, 2, r.stderr)
            self.assertIn('GoPro', r.stderr)
            self.assertIn('equirectangular', r.stderr)
            self.assertFalse(os.path.exists(work))
        finally:
            shutil.rmtree(d)


@unittest.skipUnless(HAVE_CV, 'needs numpy + opencv-python')
class Geometry(unittest.TestCase):
    @staticmethod
    def dot(lon, lat, W=2048, H=1024):
        eq = np.zeros((H, W, 3), np.uint8)
        x = (lon / 360 + 0.5) * W - 0.5
        y = (0.5 - lat / 180) * H - 0.5
        cv2.circle(eq, (int(round(x)), int(round(y))), 3, (255, 255, 255), -1)
        return eq

    @staticmethod
    def centroid(im):
        g = im[..., 0].astype(np.float64)
        ys, xs = np.nonzero(g > 60)
        w = g[ys, xs]
        return float((xs * w).sum() / w.sum()), float((ys * w).sum() / w.sum())

    def test_known_point_lands_where_expected(self):
        eq = self.dot(30, 10)
        im = C.cut_view(eq, 1600, 1200, 90, 0)
        f = 800.0
        ex = 800 - 0.5 + f * math.tan(math.radians(30))
        ey = 600 - 0.5 - f * math.tan(math.radians(10)) / math.cos(math.radians(30))
        x, y = self.centroid(im)
        self.assertAlmostEqual(x, ex, delta=2.0)
        self.assertAlmostEqual(y, ey, delta=2.0)
        im = C.cut_view(eq, 1600, 1200, 90, 30)   # looking at it: centre column, 10 deg up
        x, y = self.centroid(im)
        self.assertAlmostEqual(x, 799.5, delta=2.0)
        self.assertAlmostEqual(y, 599.5 - f * math.tan(math.radians(10)), delta=2.0)

    def test_across_the_seam_and_right_is_clockwise(self):
        eq = self.dot(-175, 0)   # just left of the back
        x, y = self.centroid(C.cut_view(eq, 800, 600, 90, 180))
        self.assertAlmostEqual(x, 399.5 + 400 * math.tan(math.radians(5)), delta=2.0)
        self.assertAlmostEqual(y, 299.5, delta=2.0)
        eq = self.dot(90, 0)     # the right view (yaw +90) has it in its centre
        x, y = self.centroid(C.cut_view(eq, 800, 600, 90, 90))
        self.assertAlmostEqual(x, 399.5, delta=2.0)


@unittest.skipUnless(HAVE_CV, 'needs numpy + opencv-python')
class Clock(unittest.TestCase):
    @unittest.skipUnless(HAVE_QR, 'this OpenCV has no QRCodeEncoder (and the qrcode package is not used)')
    def test_qr_on_the_sphere_is_read(self):
        text = f'QPSYNC1 {SID} 1795000001200'
        for yaw, pitch in ((60, -5), (180, 0), (-100, -20)):   # 180: across the seam
            eq = S.render_street(2048, 1024, 0.0, 40.0)
            S.draw_card(eq, text, yaw, pitch, 28)
            self.assertEqual(C.find_qr(eq), text, (yaw, pitch))
        stats = {'otherRide': 0}
        self.assertEqual(C.decode_sync(text, SID, stats), 1795000001200)
        self.assertIsNone(C.decode_sync('QPSYNC1 s_other 1795000001200', SID, stats))
        self.assertEqual(stats['otherRide'], 1)

    def test_qr_to_offset(self):
        # camera 3.25 s behind the track; codes redrawn every 100 ms (each 0-100 ms old when seen)
        syncs = [{'fromMs': T0, 'toMs': T0 + 3000, 'when': 'start', 'gpsMinusPhoneMs': 120}]
        dets = []
        for k in range(20):
            track = T0 + 500 + k * 100 + 37
            phone = track - 120
            dets.append((track - 3250, phone // 100 * 100))
        m = C.clock_model(dets, syncs)
        self.assertIsNone(m['driftPpm'])
        self.assertAlmostEqual(m['offsetMs'], 3250, delta=50)
        self.assertEqual(m['groups'][0]['when'], 'start')
        m0 = C.clock_model(dets, [{k: v for k, v in syncs[0].items() if k != 'gpsMinusPhoneMs'}])
        self.assertAlmostEqual(m['offsetMs'] - m0['offsetMs'], 120, delta=1)
        self.assertIsNone(C.clock_model([], syncs))

    def test_drift_from_start_and_end(self):
        hour = 3600_000
        syncs = [{'fromMs': T0, 'toMs': T0 + 3000, 'when': 'start'},
                 {'fromMs': T0 + hour, 'toMs': T0 + hour + 3000, 'when': 'end'}]
        # the camera loses 0.36 s in the hour (100 ppm): offset 2.000 s at the start, 2.360 s at the end
        dets = []
        for base, off in ((T0 + 1000, 2000), (T0 + hour + 1000, 2360)):
            for k in range(5):
                tr = base + k * 100
                dets.append((tr - off, tr - 50))   # epoch drawn 50 ms before: the +50 average cancels it
        m = C.clock_model(dets, syncs)
        self.assertEqual(len(m['groups']), 2)
        self.assertAlmostEqual(m['driftPpm'], 100, delta=2)
        cam_mid = T0 + hour // 2 - 2180
        self.assertAlmostEqual(m['fn'](cam_mid), 2180, delta=3)
        self.assertAlmostEqual(m['fn'](T0 + 1000 - 2000), 2000, delta=3)

    def test_coarse_shift_finds_the_time_zone(self):
        frames = [{'camMs': T0 + 7 * 3600_000 + k * 1000} for k in range(60)]
        self.assertEqual(C.coarse_shift(frames, (T0, T0 + 60_000)), -7 * 3600_000)
        self.assertIsNone(C.coarse_shift([{'camMs': T0 + 40 * 3600_000}], (T0, T0 + 60_000)))


@unittest.skipUnless(HAVE_CV, 'needs numpy + opencv-python')
class TrackAndSelection(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        # 10 s at 5 m/s, 5 s standing, 10 s at 5 m/s
        self.pts = S.write_track(os.path.join(self.d, 'track.jsonl'), T0, [(10, 5.0), (5, 0.0), (10, 5.0)])
        self.fixes = C.load_track_t(self.d, self.d)
        self.track = C.Track(self.fixes)

    def tearDown(self):
        shutil.rmtree(self.d)

    def test_interpolation_between_fixes(self):
        p = self.track.at(T0 + 3400)
        lat, lon = S.ll(17.0)
        self.assertAlmostEqual(p['lat'], lat, places=7)
        self.assertAlmostEqual(p['lon'], lon, places=7)
        self.assertEqual(p['how'], 'interpolated')
        self.assertAlmostEqual(p['s'], 17.0, delta=0.05)
        self.assertAlmostEqual(p['speedMps'], 5.0, delta=0.05)
        self.assertEqual(p['en'], 5_000_000_000 + 3400 * 1_000_000)
        self.assertIsNone(self.track.at(T0 - 5000))
        self.assertAlmostEqual(self.track.travel_bearing(T0 + 3400), 0.0, delta=0.5)
        self.assertAlmostEqual(self.track.at(T0 + 12000)['s'], 50.0, delta=0.05)   # standing adds nothing

    def test_every_m_and_nothing_while_standing(self):
        frames = []
        for k in range(0, 251):   # 10 frames a second over the whole ride
            ms = T0 + k * 100
            p = self.track.at(ms)
            if p:
                frames.append({'phoneMs': ms, **p})
        kept = C.select_every(frames, 5.0)
        ss = [f['s'] for f in kept]
        self.assertEqual(len(kept), 21)   # 100 m ridden: 0, 5, ..., 100
        for a, b in zip(ss, ss[1:]):
            self.assertAlmostEqual(b - a, 5.0, delta=0.6)
        self.assertFalse([f for f in kept if T0 + 10_000 < f['phoneMs'] < T0 + 15_000], 'a frame kept while standing')
        self.assertEqual(len(C.select_every(frames, 10.0)), 11)


@unittest.skipUnless(HAVE_CV, 'needs numpy + opencv-python')
class FrontFromMotion(unittest.TestCase):
    def test_focus_of_expansion(self):
        for fy in (40.0, -120.0, 175.0):
            a = S.render_street(1024, 512, 0.0, fy)
            b = S.render_street(1024, 512, 1.2, fy)
            foe, k, q = C.foe_yaw(a, b)
            self.assertLess(abs(C.angle_diff(foe, fy)), 3.0, (fy, foe))
            self.assertGreater(q, 0.6)
            back, _, _ = C.foe_yaw(b, a)   # riding backwards: the other way
            self.assertLess(abs(C.angle_diff(back, fy + 180)), 3.0)

    def test_circular_median(self):
        self.assertAlmostEqual(C.circ_median([358, 2, 4, 1, 359]), 1.0, delta=1e-6)


def frame_times(t_from, t_to, step):
    t, out = t_from, []
    while t <= t_to:
        out.append(t)
        t += step
    return out


@unittest.skipUnless(HAVE_QR, 'needs opencv-python with QRCodeEncoder')
class EndToEnd(unittest.TestCase):
    """A whole ride: frames rendered along a straight street going north; the rider stands for the sync codes at
    the start and the end. The camera's clock is off the track by a known amount (and drifts)."""

    def setUp(self):
        self.d = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.d, ignore_errors=True)

    segments = [(2, 0.0), (8, 5.0), (2, 0.0)]   # stand 2 s (start code), 40 m at 5 m/s, stand 2 s (end code)
    FRONT = 40.0
    GM = 120   # gpsMinusPhoneMs: the track's GPS time is 120 ms ahead of the phone's clock

    def syncs(self):
        return [{'fromMs': T0 + 200 - self.GM, 'toMs': T0 + 1900 - self.GM, 'when': 'start', 'gpsMinusPhoneMs': self.GM},
                {'fromMs': T0 + 10200 - self.GM, 'toMs': T0 + 11900 - self.GM, 'when': 'end',
                 'gpsMinusPhoneMs': self.GM}]

    def frame(self, pts, tr, W=2048):
        eq = S.render_street(W, W // 2, S.y_at(pts, tr), self.FRONT)
        if any(s['fromMs'] + self.GM <= tr <= s['toMs'] + self.GM for s in self.syncs()):
            epoch = (tr - self.GM) // 100 * 100   # the phone's clock, redrawn every 100 ms
            S.draw_card(eq, f'QPSYNC1 {SID} {epoch}', 150.0, -10.0, 30)
        return eq

    def check_sweep(self, work, expect_offset, n_kept_min):
        man = load(os.path.join(work, 'sweep', 'manifest.json'))
        rep = load(os.path.join(work, 'camera360.json'))
        self.assertEqual(man['kind'], 'sweep')
        self.assertEqual(man['session']['kind'], 'sweep')
        self.assertEqual(man['session']['source'], 'camera360')
        self.assertEqual(rep['clock']['source'], 'sync codes')
        for g in rep['clock']['groups']:
            self.assertAlmostEqual(g['offsetMs'], expect_offset(g['camMs']), delta=60, msg=g)
        self.assertAlmostEqual(rep['front']['deg'], self.FRONT, delta=4)
        self.assertGreaterEqual(rep['kept'], n_kept_min)
        items = man['items']
        self.assertEqual(len(items), 3 * rep['kept'])
        self.assertEqual([b['view'] for b in rep['views']], ['front', 'left', 'right'])
        pts = self.pts
        for it in items:
            self.assertEqual(it['bearingSource'], 'camera360')
            self.assertEqual(it['positionFrom'], 'phone track')
            self.assertEqual(it['hFovDeg'], 90.0)
            exp = {'front': 0.0, 'left': 270.0, 'right': 90.0}[it['camera360']['view']]
            self.assertLess(abs(C.angle_diff(it['cameraBearing'], exp)), 1.0, it)
            lat, _ = S.ll(S.y_at(pts, it['tMs']))
            self.assertAlmostEqual(it['lat'], lat, delta=0.3 / 110540)
            self.assertGreaterEqual(it['speedMps'], C.STANDING_MPS)
        # prepare.py takes it unchanged
        prep = os.path.join(work, 'prep')
        r = run_py('prepare.py', os.path.join(work, 'sweep'), prep)
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        self.assertIn(f'{len(items)} photos', r.stdout)
        photos = load(os.path.join(prep, 'photos.json'))
        self.assertEqual(len(photos), len(items))
        for p, it in zip(photos, items):
            self.assertEqual(p['id'], it['id'])
            self.assertTrue(os.path.isfile(p['file']))
            self.assertEqual(p['cameraBearing'], it['cameraBearing'])
            self.assertEqual(p['cameraBearingSource'], 'camera360')
            self.assertAlmostEqual(p['lat'], it['lat'], delta=0.05 / 110540)   # re-placed by time: the same spot
        self.assertTrue(os.listdir(os.path.join(prep, 'sheets')))
        return man, rep

    def test_photos_from_a_session_folder(self):
        ride = os.path.join(self.d, 'ride')
        self.pts = S.write_ride(ride, SID, T0, self.segments, self.syncs())
        cam_dir = os.path.join(self.d, 'DCIM')
        os.makedirs(cam_dir)
        off = lambda tr: 3250 + 10 * (tr - T0) / 12000   # track - camera: 3.25 s, drifting 10 ms over the ride
        for n, tr in enumerate(frame_times(T0, T0 + 12000, 250)):   # a 4 Hz time-lapse
            cam = int(round(tr - off(tr)))
            S.save_jpg(os.path.join(cam_dir, f'IMG_{n:04d}.jpg'), self.frame(self.pts, tr), cam, tz_h=7)
        open(os.path.join(cam_dir, 'IMG_0000.thm'), 'wb').close()   # side files in the folder are skipped
        work = os.path.join(self.d, 'work')
        r = run_py('camera360.py', ride, cam_dir, work, '--size', '640x480')
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        self.assertNotIn('NO SYNC CODE', r.stdout)
        man, rep = self.check_sweep(work, lambda cam: 3250 + 10 * (cam + 3250 - T0) / 12000, 8)
        self.assertEqual(len(rep['clock']['groups']), 2)
        self.assertEqual(rep['clock']['groups'][0]['when'], 'start')

    @unittest.skipUnless(HAVE_FFMPEG, 'needs ffmpeg / ffprobe')
    def test_video_with_a_wrong_time_zone_from_an_export_zip(self):
        # the export zip: manifest.json (kind camera360, no items) + track.geojson
        tmp = os.path.join(self.d, 'tmp')
        self.pts = S.write_ride(tmp, SID, T0, self.segments, self.syncs())
        feats = []
        with open(os.path.join(tmp, 'track.jsonl'), encoding='utf-8') as fh:
            lines = fh.readlines()
        for line in lines:
            o = json.loads(line)
            feats.append({'type': 'Feature', 'geometry': {'type': 'Point', 'coordinates': [o['lon'], o['lat']]},
                          'properties': {'t': o['t'], 'accuracyM': o['acc'], 'speedMps': o['spd'],
                                         'headingDeg': o['hdg'], 'elapsedNanos': o['en']}})
        session = load(os.path.join(tmp, 'session.json'))
        session.update({'mode': 'photo-manual', 'target': None})
        zpath = os.path.join(self.d, 'poi-capture_camera360_202611281000.zip')
        with zipfile.ZipFile(zpath, 'w') as z:
            z.writestr('manifest.json', json.dumps({'schema': 'poi-capture/3', 'kind': 'camera360',
                                                    'session': session, 'items': []}, ensure_ascii=False))
            z.writestr('track.geojson', json.dumps({'type': 'FeatureCollection', 'features': feats}))
        # the camera wrote its local time (UTC+7) as UTC, and is 1.5 s behind on top
        off = -7 * 3600_000 + 1500
        start_cam = T0 - off
        frames_dir = os.path.join(self.d, 'frames')
        os.makedirs(frames_dir)
        for n, tr in enumerate(frame_times(T0, T0 + 12000, 100)):   # 10 frames a second
            cv2.imwrite(os.path.join(frames_dir, f'f{n:04d}.png'), self.frame(self.pts, tr))
        ct = dt.datetime.fromtimestamp(start_cam / 1000, dt.timezone.utc).strftime('%Y-%m-%dT%H:%M:%S.%f') + 'Z'
        video = os.path.join(self.d, 'VID_360_export.mp4')
        r = subprocess.run(['ffmpeg', '-v', 'error', '-y', '-framerate', '10', '-i', os.path.join(frames_dir, 'f%04d.png'),
                            '-c:v', 'libx264', '-crf', '8', '-g', '10', '-pix_fmt', 'yuv420p',
                            '-metadata', f'creation_time={ct}', video], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        work = os.path.join(self.d, 'work')
        r = run_py('camera360.py', zpath, video, work, '--size', '640x480')
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        self.assertIn('shifted -7 h', r.stdout)
        # MP4 creation_time keeps whole seconds: the camera clock read from the file loses start_cam's milliseconds
        man, rep = self.check_sweep(work, lambda cam: off + start_cam % 1000, 8)
        self.assertEqual(man['session']['target'], 'shops')
        self.assertEqual(man['session']['rideKind'], 'camera360')


if __name__ == '__main__':
    unittest.main()


class ForwardPhotosKeep360Views(unittest.TestCase):
    """A signs ride from a 360 camera: build.forward_photos must not turn the left / right views to the front."""

    def test_views_keep_their_bearing(self):
        import build
        photos = [{'travelBearing': 10.0, 'cameraBearing': 100.0, 'bearingSource': 'camera360'},
                  {'travelBearing': 10.0, 'cameraBearing': 280.0, 'cameraBearingSource': 'camera360'},
                  {'travelBearing': 10.0, 'cameraBearing': 95.0}]
        out = build.forward_photos(photos)
        self.assertEqual([p['cameraBearing'] for p in out], [100.0, 280.0, 10.0])
