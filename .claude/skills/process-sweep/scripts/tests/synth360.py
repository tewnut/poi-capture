"""Synthetic 360 material for the camera360 tests: an equirectangular "street" (two textured house fronts 6 m either
side of the road, a textured road, sky), seen from a camera 1.5 m up riding along the road, with the 360 image's
front turned by front_yaw; a QR code drawn on a flat card somewhere around the camera; a phone track; EXIF times."""
import json, math, os

import numpy as np
import cv2

_TEX = {}


def texture(seed=7, n=512):
    if seed not in _TEX:
        r = np.random.default_rng(seed)
        a = cv2.GaussianBlur(r.random((n, n)).astype(np.float32), (0, 0), 2.0)
        b = cv2.GaussianBlur(r.random((n, n)).astype(np.float32), (0, 0), 9.0)
        t = 0.5 * (a - a.mean()) / a.std() + 0.8 * (b - b.mean()) / b.std()
        _TEX[seed] = np.clip(128 + 45 * t, 0, 255).astype(np.float32)
    return _TEX[seed]


def directions(W, H):
    """Unit rays of an equirectangular image in the image frame: x right, y up, z = the image centre."""
    th = ((np.arange(W) + 0.5) / W - 0.5) * 2 * math.pi
    la = (0.5 - (np.arange(H) + 0.5) / H) * math.pi
    TH, LA = np.meshgrid(th, la)
    return np.cos(LA) * np.sin(TH), np.sin(LA), np.cos(LA) * np.cos(TH), TH


def render_street(W, H, cam_y, front_yaw_deg, wall_x=6.0, cam_z=1.5, px_per_m=25.0):
    """BGR uint8 equirectangular frame. The travel direction (+y along the road) is at yaw front_yaw_deg (clockwise
    from the image centre)."""
    x, up, z, TH = directions(W, H)
    h = TH - math.radians(front_yaw_deg)           # world heading of each ray, 0 = along the road
    cl = np.hypot(x, z)
    dx, dy, dz = cl * np.sin(h), cl * np.cos(h), up
    big = 1e9
    with np.errstate(divide='ignore', invalid='ignore'):
        tw = np.where(np.abs(dx) > 1e-9, wall_x / np.abs(dx), big)
        zw = cam_z + tw * dz
        tw = np.where((zw >= 0) & (zw <= 9), tw, big)
        tg = np.where(dz < -1e-9, cam_z / -dz, big)
    t = np.minimum(tw, tg)
    yw = cam_y + t * dy
    xw = t * dx
    tex = texture()
    n = tex.shape[0]
    # walls: (along-road, height); the left wall shifted in texture; ground: (across, along)
    u = np.where(tw <= tg, yw * px_per_m + np.where(dx < 0, 200, 0), xw * px_per_m + 100)
    v = np.where(tw <= tg, (cam_z + t * dz) * px_per_m, yw * px_per_m + 300)
    u = np.where(np.isfinite(u) & (t < big), u, 0) % n
    v = np.where(np.isfinite(v) & (t < big), v, 0) % n
    val = cv2.remap(tex, u.astype(np.float32), v.astype(np.float32), cv2.INTER_LINEAR, borderMode=cv2.BORDER_WRAP)
    val = np.where(t < big, val, 215 + 20 * up)        # sky
    val = np.where(tw <= tg, val, 0.7 * val + 20)      # road darker than the houses
    g = np.clip(val, 0, 255).astype(np.uint8)
    return cv2.merge([g, g, g])


def qr_image(text, module_px=1, quiet=4):
    q = cv2.QRCodeEncoder.create().encode(text)
    q = cv2.copyMakeBorder(q, quiet, quiet, quiet, quiet, cv2.BORDER_CONSTANT, value=255)
    return q


def draw_card(eq, text, yaw_deg, pitch_deg=0.0, size_deg=28.0):
    """Draw the QR code on a flat card facing the camera at (yaw, pitch), size_deg across (in place)."""
    q = qr_image(text).astype(np.float32)
    n = q.shape[0]
    H, W = eq.shape[:2]
    x, y3, z, _ = directions(W, H)
    yw, p = math.radians(yaw_deg), math.radians(pitch_deg)
    X = x * math.cos(yw) - z * math.sin(yw)        # undo the yaw, then the pitch (inverse of camera360.view_maps)
    Z2 = x * math.sin(yw) + z * math.cos(yw)
    Y = y3 * math.cos(p) - Z2 * math.sin(p)
    Z = y3 * math.sin(p) + Z2 * math.cos(p)
    half = math.tan(math.radians(size_deg) / 2)
    with np.errstate(divide='ignore', invalid='ignore'):
        px, py = X / Z, Y / Z
    m = (Z > 0) & (np.abs(px) < half) & (np.abs(py) < half)
    u = ((px / half + 1) / 2 * n).astype(np.float32)
    v = ((1 - (py / half + 1) / 2) * n).astype(np.float32)
    val = cv2.remap(q, np.where(m, u, 0).astype(np.float32), np.where(m, v, 0).astype(np.float32),
                    cv2.INTER_NEAREST, borderMode=cv2.BORDER_REPLICATE)
    for c in range(eq.shape[2] if eq.ndim == 3 else 1):
        ch = eq[..., c] if eq.ndim == 3 else eq
        ch[m] = val[m].astype(np.uint8)
    return eq


LAT0, LON0 = 10.78, 106.70


def ll(y_m, x_m=0.0):
    return LAT0 + y_m / 110540, LON0 + x_m / (111320 * math.cos(math.radians(LAT0)))


def write_track(path, t0_ms, segments, en0=5_000_000_000):
    """track.jsonl with one fix a second along +y (north): segments = [(seconds, speed m/s)]. -> [(t ms, y m)]."""
    pts, t, y = [], t0_ms, 0.0
    pts.append((t, y))
    for sec, v in segments:
        for _ in range(int(sec)):
            t += 1000
            y += v
            pts.append((t, y))
    with open(path, 'w', encoding='utf-8') as f:
        prev = None
        for t, y in pts:
            la, lo = ll(y)
            spd = 0.0 if prev is None else (y - prev) / 1.0
            f.write(json.dumps({'t': t, 'lat': la, 'lon': lo, 'acc': 4.0, 'spd': spd, 'hdg': 0.0,
                                'en': en0 + (t - t0_ms) * 1_000_000}) + '\n')
            prev = y
    return pts


def y_at(pts, t_ms):
    for (ta, ya), (tb, yb) in zip(pts, pts[1:]):
        if ta <= t_ms <= tb:
            return ya + (t_ms - ta) / (tb - ta) * (yb - ya)
    return pts[0][1] if t_ms < pts[0][0] else pts[-1][1]


def write_ride(folder, sid, t0_ms, segments, syncs, kind='camera360', camera360=None):
    """A raw session folder from the phone (session.json, empty items.jsonl, track.jsonl). camera360: more fields of
    session.camera360 (a Theta ride: control, everyM, camera, shots). -> track points."""
    os.makedirs(folder, exist_ok=True)
    pts = write_track(os.path.join(folder, 'track.jsonl'), t0_ms, segments)
    with open(os.path.join(folder, 'session.json'), 'w', encoding='utf-8') as f:
        json.dump({'id': sid, 'kind': kind, 'name': 'Đường Thử 2026-11-28 10h00',
                   'camera360': {'syncs': syncs, **(camera360 or {})}}, f, ensure_ascii=False)
    open(os.path.join(folder, 'items.jsonl'), 'w').close()
    return pts


def exif_bytes(cam_ms, tz_h=7, gps=None):
    """EXIF with DateTimeOriginal (local camera time), SubSecTimeOriginal and OffsetTimeOriginal (tz_h None: no
    offset, as a camera that does not write it); gps = (lat, lon) adds a GPS IFD (as a Theta with gpsInfo set)."""
    from PIL import Image
    from PIL.TiffImagePlugin import IFDRational
    import datetime as dt
    ex = Image.Exif()
    if cam_ms is not None:
        tz = dt.timezone(dt.timedelta(hours=tz_h or 0))
        t = dt.datetime.fromtimestamp(cam_ms / 1000, tz)
        ifd = ex.get_ifd(0x8769)
        ifd[36867] = t.strftime('%Y:%m:%d %H:%M:%S')
        ifd[37521] = f'{t.microsecond // 1000:03d}'
        if tz_h is not None:
            ifd[36881] = f'{"+" if tz_h >= 0 else "-"}{abs(tz_h):02d}:00'
    if gps is not None:
        def dms(v):
            v = abs(v)
            d = int(v)
            m = int((v - d) * 60)
            s = (v - d - m / 60) * 3600
            return (IFDRational(d, 1), IFDRational(m, 1), IFDRational(int(round(s * 10000)), 10000))
        g = ex.get_ifd(0x8825)
        g[1] = 'N' if gps[0] >= 0 else 'S'
        g[2] = dms(gps[0])
        g[3] = 'E' if gps[1] >= 0 else 'W'
        g[4] = dms(gps[1])
    return ex


def save_jpg(path, bgr, cam_ms=None, tz_h=7, gps=None):
    from PIL import Image
    im = Image.fromarray(cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB))
    if cam_ms is None and gps is None:
        im.save(path, quality=95)
    else:
        im.save(path, quality=95, exif=exif_bytes(cam_ms, tz_h, gps))
