"""Blur faces and vehicle number plates in a processed place folder, before it leaves the PC (SKILL.md "Privacy").

usage:
  python blur.py detect <out> <check>     find faces and plates; crop sheets of the plates to check
  python blur.py apply  <out> <check>     pixelate them (all but those the agent kept); sheets of the result

<out> is a built place folder (build.py / build_place.py); <check> is a private folder OUTSIDE it (never uploaded):
the unblurred originals (<check>/orig/), the boxes (<check>/boxes.json), the crop sheets and the agent's
<check>/decisions.json. Photos are always blurred from the originals, so `apply` can run again after a change.

Detection (models from the OpenCV model zoo, free for commercial use, downloaded once to ~/.cache/poi-capture/models):
- faces: YuNet (MIT), every face scoring >= 0.6 is blurred;
- vehicles: YOLOX-S (Apache 2.0): bicycle, car, motorbike, bus, truck, on the whole photo and on two overlapping halves;
- plates: LPD-YuNet (Apache 2.0), on the whole photo and again on each vehicle, enlarged (small plates on far bikes).
  A plate on a vehicle scoring >= 0.7, plate-sized beside it and under 0.6 % of the photo, is blurred without asking. The rest worth a look (on a vehicle >= 0.3, or anywhere
  >= 0.6: the plate model also likes shop signs and house numbers) go on the crop sheets `<check>/plates_NN.jpg`.

The agent looks at every crop sheet and writes <check>/decisions.json:
  {"keep": ["b0012", "b0040"],                       # boxes NOT to blur: a shop sign, a house number, a phone number
   "add": [{"file": "07_Pho_Vu/COVER_#38_p_x.jpg", "box": [0.61, 0.70, 0.70, 0.78], "why": "plate missed"}]}
Every box on the sheets is blurred unless kept: when unsure, blur (privacy first; the agent has read the signs already).
`add` boxes are fractions of the photo's width / height (x0, y0, x1, y1), e.g. a plate or face seen on the after-sheets.

`apply` writes the blurred photos (EXIF kept, orientation applied), `privacy` in each place.json (counts and method)
and `<check>/after_NN.jpg`: the blurred photos, 3 x 3, to look for a face or plate still readable (then `add` and
apply again). Website images (WEB_*) are not touched: they are already public.
"""
import json, os, shutil, sys, urllib.request

sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from paths import arg_path, place_folders  # noqa: E402

import numpy as np  # noqa: E402
import cv2  # noqa: E402
from PIL import Image, ImageDraw, ImageFont, ImageOps  # noqa: E402

ZOO = 'https://github.com/opencv/opencv_zoo/raw/main/models/'
MODELS = {
    'face': 'face_detection_yunet/face_detection_yunet_2023mar.onnx',
    'plate': 'license_plate_detection_yunet/license_plate_detection_lpd_yunet_2023mar.onnx',
    'vehicle': 'object_detection_yolox/object_detection_yolox_2022nov.onnx',
}
FACE_MIN = 0.6
PLATE_AUTO, PLATE_ON_VEHICLE, PLATE_ANYWHERE = 0.7, 0.3, 0.6
AUTO_MAX_AREA = 0.006   # a plate blurred without asking is under 0.6 % of the photo; bigger boxes are checked
VEHICLE_CLASSES = {1: 'bicycle', 2: 'car', 3: 'motorbike', 5: 'bus', 7: 'truck'}
METHOD = 'YuNet faces, YOLOX vehicles + LPD-YuNet plates, agent-checked; pixelated'


def model_path(key):
    d = os.environ.get('POI_MODELS') or os.path.join(os.path.expanduser('~'), '.cache', 'poi-capture', 'models')
    p = os.path.join(d, os.path.basename(MODELS[key]))
    if not os.path.exists(p):
        os.makedirs(d, exist_ok=True)
        print(f'downloading {os.path.basename(p)} ...')
        urllib.request.urlretrieve(ZOO + MODELS[key], p + '.part')
        os.replace(p + '.part', p)
    return p


# ---------- detectors ----------

class Faces:
    def __init__(self):
        self.det = cv2.FaceDetectorYN.create(model_path('face'), '', (320, 320), FACE_MIN, 0.3, 5000)

    def __call__(self, bgr):
        h, w = bgr.shape[:2]
        s = min(1.0, 2400 / max(w, h))   # very large photos: the smallest faces are unreadable anyway
        img = cv2.resize(bgr, (int(w * s), int(h * s))) if s < 1 else bgr
        self.det.setInputSize((img.shape[1], img.shape[0]))
        _, faces = self.det.detect(img)
        out = []
        for f in (faces if faces is not None else []):
            x, y, fw, fh = (float(v) / s for v in f[:4])
            out.append(([x, y, x + fw, y + fh], float(f[-1])))
        return out


class Vehicles:
    def __init__(self):
        self.net = cv2.dnn.readNet(model_path('vehicle'))
        grids, strides = [], []
        for st in (8, 16, 32):
            n = 640 // st
            xv, yv = np.meshgrid(np.arange(n), np.arange(n))
            grids.append(np.stack((xv, yv), 2).reshape(-1, 2))
            strides.append(np.full((n * n, 1), st))
        self.grids, self.strides = np.concatenate(grids), np.concatenate(strides)

    def _run(self, bgr):
        h, w = bgr.shape[:2]
        r = min(640 / h, 640 / w)
        pad = np.full((640, 640, 3), 114, np.uint8)
        pad[:int(h * r), :int(w * r)] = cv2.resize(cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB), (int(w * r), int(h * r)))
        self.net.setInput(pad.transpose(2, 0, 1)[None].astype(np.float32))
        d = self.net.forward(self.net.getUnconnectedOutLayersNames())[0][0]
        d[:, :2] = (d[:, :2] + self.grids) * self.strides
        d[:, 2:4] = np.exp(d[:, 2:4]) * self.strides
        sc = d[:, 4:5] * d[:, 5:]
        cls, best = sc.argmax(1), sc.max(1)
        out = []
        for i in np.where(best >= 0.3)[0]:
            if int(cls[i]) in VEHICLE_CLASSES:
                cx, cy, bw, bh = d[i, :4] / r
                out.append(([cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2], float(best[i]), VEHICLE_CLASSES[int(cls[i])]))
        return out

    def __call__(self, bgr):
        h, w = bgr.shape[:2]
        found = self._run(bgr)
        cw = int(w * 0.6)
        for x0 in (0, w - cw):   # two overlapping halves: vehicles too small at 640 on the whole photo
            for b, s, c in self._run(bgr[:, x0:x0 + cw]):
                found.append(([b[0] + x0, b[1], b[2] + x0, b[3]], s, c))
        return nms(found, 0.5)


class Plates:
    def __init__(self):
        self.net = cv2.dnn.readNet(model_path('plate'))
        self.size = None

    def _priors(self, w, h):
        if self.size == (w, h):
            return
        f = [int(int((h + 1) / 2) / 2), int(int((w + 1) / 2) / 2)]
        maps = []
        for _ in range(4):
            f = [f[0] // 2, f[1] // 2]
            maps.append(f)
        mins, steps, pri = [[10, 16, 24], [32, 48], [64, 96], [128, 192, 256]], [8, 16, 32, 64], []
        for k, (fh, fw) in enumerate(maps):
            ii, jj = np.meshgrid(np.arange(fh), np.arange(fw), indexing='ij')
            cx = (jj.ravel() + 0.5) * steps[k] / w
            cy = (ii.ravel() + 0.5) * steps[k] / h
            per = [np.stack([cx, cy, np.full_like(cx, m / w), np.full_like(cx, m / h)], 1) for m in mins[k]]
            pri.append(np.stack(per, 1).reshape(-1, 4))   # cell by cell, each cell's sizes in order (as the zoo)
        self.priors, self.size = np.concatenate(pri).astype(np.float32), (w, h)

    def __call__(self, bgr, min_score=0.3):
        h, w = bgr.shape[:2]
        self._priors(w, h)
        self.net.setInput(cv2.dnn.blobFromImage(bgr))
        loc, conf, iou = self.net.forward(['loc', 'conf', 'iou'])
        score = np.sqrt(conf[:, 1] * np.clip(iou[:, 0], 0, 1))
        keep = np.where(score >= min_score)[0]
        out = []
        p, scale = self.priors, np.array([w, h], np.float32)
        for i in keep:
            pts = [(p[i, :2] + loc[i, a:a + 2] * 0.1 * p[i, 2:]) * scale for a in (4, 6, 10, 12)]
            xs, ys = [q[0] for q in pts], [q[1] for q in pts]
            out.append(([min(xs), min(ys), max(xs), max(ys)], float(score[i])))
        return nms(out, 0.3)


def nms(items, thr):
    """items: (box, score, ...) -> highest-scoring first, overlapping ones (IoU > thr) dropped."""
    kept = []
    for it in sorted(items, key=lambda t: -t[1]):
        if all(iou(it[0], k[0]) <= thr for k in kept):
            kept.append(it)
    return kept


def iou(a, b):
    ix = max(0.0, min(a[2], b[2]) - max(a[0], b[0]))
    iy = max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
    i = ix * iy
    u = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - i
    return i / u if u > 0 else 0.0


def on_vehicle(box, vehicles, plate_sized=False):
    """The box's centre is on a vehicle (its box + 10 %). With plate_sized: and the box is small enough beside that
    vehicle to be its plate (a shopfront taken for a "vehicle" holds sign-sized text boxes)."""
    cx, cy = (box[0] + box[2]) / 2, (box[1] + box[3]) / 2
    for v, _, _ in vehicles:
        mx, my = (v[2] - v[0]) * 0.1, (v[3] - v[1]) * 0.1
        if v[0] - mx <= cx <= v[2] + mx and v[1] - my <= cy <= v[3] + my:
            if not plate_sized or (box[2] - box[0] <= 0.5 * (v[2] - v[0]) and box[3] - box[1] <= 0.35 * (v[3] - v[1])):
                return True
    return False


def plates_in(bgr, vehicles, plates):
    """Plate candidates: the whole photo (long edge 1600) plus every vehicle, enlarged so its long edge is >= 480."""
    h, w = bgr.shape[:2]
    s = min(1.0, 1600 / max(w, h))
    small = cv2.resize(bgr, (int(w * s) // 2 * 2, int(h * s) // 2 * 2))
    sx, sy = w / small.shape[1], h / small.shape[0]
    found = [([b[0] * sx, b[1] * sy, b[2] * sx, b[3] * sy], sc) for b, sc in plates(small)]
    for v, _, _ in vehicles:
        mx, my = (v[2] - v[0]) * 0.1, (v[3] - v[1]) * 0.1
        x0, y0 = max(0, int(v[0] - mx)), max(0, int(v[1] - my))
        x1, y1 = min(w, int(v[2] + mx)), min(h, int(v[3] + my))
        if x1 - x0 < 16 or y1 - y0 < 16:
            continue
        z = min(4.0, max(1.0, 480 / max(x1 - x0, y1 - y0)))
        crop = cv2.resize(bgr[y0:y1, x0:x1], (max(32, int((x1 - x0) * z)) // 2 * 2, max(32, int((y1 - y0) * z)) // 2 * 2))
        zx, zy = (x1 - x0) / crop.shape[1], (y1 - y0) / crop.shape[0]
        found += [([x0 + b[0] * zx, y0 + b[1] * zy, x0 + b[2] * zx, y0 + b[3] * zy], sc) for b, sc in plates(crop)]
    return nms(found, 0.3)


# ---------- files ----------

def photos_of(out):
    """(relative path, place folder) of every street photo in the place folders: the place's own photos and more/."""
    res = []
    for name in place_folders(out):
        d = os.path.join(out, name)
        for root, _, files in os.walk(d):
            for f in sorted(files):
                if f.lower().endswith(('.jpg', '.jpeg', '.png')) and not f.startswith('WEB_'):
                    res.append((os.path.relpath(os.path.join(root, f), out).replace('\\', '/'), d))
    return res


def load(path):
    """RGB image with its EXIF orientation applied, and the EXIF to write back (orientation reset)."""
    im = ImageOps.exif_transpose(Image.open(path))
    return im.convert('RGB'), im.getexif()


def detect(out, check):
    if os.path.abspath(check).startswith(os.path.abspath(out) + os.sep):
        raise SystemExit('the check folder must be outside the place folder (it holds the unblurred originals)')
    items = photos_of(out)
    if not items:
        raise SystemExit(f'no place photos in {out}')
    orig = os.path.join(check, 'orig')
    faces, vehicles, plates = Faces(), Vehicles(), Plates()
    boxes, n = [], 0
    for rel, _ in items:
        src, keep = os.path.join(out, rel), os.path.join(orig, rel)
        if not os.path.exists(keep):   # the first detect keeps the original; later ones read it from there
            os.makedirs(os.path.dirname(keep), exist_ok=True)
            shutil.copy2(src, keep)
        im, _ = load(keep)
        bgr = cv2.cvtColor(np.asarray(im), cv2.COLOR_RGB2BGR)
        H, W = bgr.shape[:2]

        def add(kind, b, sc, state):
            nonlocal n
            b = [max(0.0, float(b[0])), max(0.0, float(b[1])), min(float(W), float(b[2])), min(float(H), float(b[3]))]
            if b[2] - b[0] >= 3 and b[3] - b[1] >= 3:
                n += 1
                boxes.append({'id': f'b{n:04d}', 'file': rel, 'kind': kind, 'box': [round(float(v), 1) for v in b],
                              'score': round(sc, 3), 'state': state, 'size': [W, H]})

        for b, sc in faces(bgr):
            add('face', b, sc, 'auto')
        vs = vehicles(bgr)
        for b, sc in plates_in(bgr, vs, plates):
            onv = on_vehicle(b, vs)
            small = (b[2] - b[0]) * (b[3] - b[1]) <= AUTO_MAX_AREA * W * H
            if small and on_vehicle(b, vs, plate_sized=True) and sc >= PLATE_AUTO:
                add('plate', b, sc, 'auto')
            elif (onv and sc >= PLATE_ON_VEHICLE) or sc >= PLATE_ANYWHERE:
                add('plate', b, sc, 'check')
        print(f'{rel}: {sum(1 for x in boxes if x["file"] == rel and x["kind"] == "face")} faces, '
              f'{len(vs)} vehicles, {sum(1 for x in boxes if x["file"] == rel and x["kind"] == "plate")} plates')
    json.dump({'boxes': boxes}, open(os.path.join(check, 'boxes.json'), 'w', encoding='utf-8'), indent=1)
    for f in os.listdir(check):
        if f.startswith(('plates_', 'after_')) and f.endswith('.jpg'):
            os.remove(os.path.join(check, f))
    todo = [b for b in boxes if b['state'] == 'check']
    sheets = crop_sheets(check, todo)
    dec = os.path.join(check, 'decisions.json')
    if not os.path.exists(dec):
        json.dump({'keep': [], 'add': []}, open(dec, 'w', encoding='utf-8'), indent=1)
    print(f'\n{len(items)} photos: {sum(b["kind"] == "face" for b in boxes)} faces, '
          f'{sum(b["kind"] == "plate" and b["state"] == "auto" for b in boxes)} plates on vehicles (blurred), '
          f'{len(todo)} boxes to check on {sheets} sheet(s): {os.path.join(check, "plates_00.jpg")} ...\n'
          f'write the ids that are NOT plates (signs, house numbers) in {dec} "keep", then: blur.py apply')


def font(size):
    for f in ('arialbd.ttf', 'DejaVuSans-Bold.ttf', 'Arial Bold.ttf'):
        try:
            return ImageFont.truetype(f, size)
        except OSError:
            pass
    return ImageFont.load_default()


def crop_sheets(check, boxes, cols=5, rows=4, tw=300, th=220):
    """Each box with its surroundings (3x its size, at least 160 px), the box outlined, labelled with its id."""
    cache, fnt, per = {}, font(20), cols * rows
    for s in range(0, len(boxes), per):
        sheet = Image.new('RGB', (cols * tw, rows * th), (40, 40, 40))
        dr = ImageDraw.Draw(sheet)
        for k, b in enumerate(boxes[s:s + per]):
            if b['file'] not in cache:
                cache = {b['file']: load(os.path.join(check, 'orig', b['file']))[0]}
            im = cache[b['file']]
            x0, y0, x1, y1 = b['box']
            cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
            half = max(80.0, (x1 - x0) * 1.5, (y1 - y0) * 1.5 * tw / th)
            hw, hh = half, half * th / tw
            crop = im.crop((int(cx - hw), int(cy - hh), int(cx + hw), int(cy + hh)))
            z = tw / (2 * hw)
            tile = crop.resize((tw - 4, th - 4))
            td = ImageDraw.Draw(tile)
            td.rectangle([(x0 - (cx - hw)) * z, (y0 - (cy - hh)) * z, (x1 - (cx - hw)) * z, (y1 - (cy - hh)) * z],
                         outline=(255, 40, 40), width=2)
            px, py = (k % cols) * tw + 2, (k // cols) * th + 2
            sheet.paste(tile, (px, py))
            dr.rectangle([px, py, px + 70, py + 24], fill=(0, 0, 0))
            dr.text((px + 4, py + 1), b['id'], fill=(255, 255, 0), font=fnt)
        sheet.save(os.path.join(check, f'plates_{s // per:02d}.jpg'), quality=88)
    return (len(boxes) + per - 1) // per


PAD = {'face': (0.15, 0.15), 'plate': (0.2, 0.6)}   # plates: the model's box often holds only one of a motorbike's two lines


def pixelate(im, box, kind='face'):
    """Pixelate the box (padded) into blocks ~1/5 of its short side: not recoverable."""
    W, H = im.size
    x0, y0, x1, y1 = box
    fx, fy = PAD.get(kind, (0.15, 0.15))
    px, py = (x1 - x0) * fx, (y1 - y0) * fy
    x0, y0, x1, y1 = max(0, int(x0 - px)), max(0, int(y0 - py)), min(W, int(x1 + px + 1)), min(H, int(y1 + py + 1))
    if x1 - x0 < 2 or y1 - y0 < 2:
        return
    region = im.crop((x0, y0, x1, y1))
    block = max(3, min(x1 - x0, y1 - y0) // 5)
    small = region.resize((max(1, (x1 - x0) // block), max(1, (y1 - y0) // block)), Image.BILINEAR)
    im.paste(small.resize(region.size, Image.NEAREST), (x0, y0))


def apply(out, check):
    data = json.load(open(os.path.join(check, 'boxes.json'), encoding='utf-8'))
    dec_path = os.path.join(check, 'decisions.json')
    dec = json.load(open(dec_path, encoding='utf-8')) if os.path.exists(dec_path) else {}
    keep = set(dec.get('keep', []))
    unknown = keep - {b['id'] for b in data['boxes']}
    if unknown:
        raise SystemExit(f'decisions.json keeps unknown boxes: {sorted(unknown)}')
    per_file = {}
    for b in data['boxes']:
        if b['id'] not in keep:
            per_file.setdefault(b['file'], []).append((b['kind'], b['box']))
    for a in dec.get('add', []):
        per_file.setdefault(a['file'], []).append(('added', a['box'], 'fraction'))
    photos = photos_of(out)
    # every photo must have been through detect: a photo missing from the check folder would stay unblurred while
    # its place folder is stamped as blurred (and cloud.py push would let it out)
    missing = [rel for rel, _ in photos if not os.path.exists(os.path.join(check, 'orig', rel))]
    if missing:
        raise SystemExit(f'{len(missing)} photo(s) not in the check folder, e.g. {missing[0]}: run detect again '
                         f'(after a rebuild or added photos); nothing was blurred or marked')
    counts, done, files = {}, [], {}
    for rel, folder in photos:
        src = os.path.join(check, 'orig', rel)
        im, exif = load(src)
        W, H = im.size
        c = counts.setdefault(folder, {'faces': 0, 'plates': 0, 'added': 0, 'photos': 0})
        for kind, box, *frac in per_file.get(rel, []):
            if frac:
                box = [box[0] * W, box[1] * H, box[2] * W, box[3] * H]
            pixelate(im, box, kind)
            c[{'face': 'faces', 'plate': 'plates'}.get(kind, 'added')] += 1
        im.save(os.path.join(out, rel), quality=92, exif=exif)
        c['photos'] += 1
        done.append(rel)
        files.setdefault(folder, []).append(os.path.relpath(os.path.join(out, rel), folder).replace(os.sep, '/'))
    kept = sum(1 for b in data['boxes'] if b['id'] in keep)
    for folder, c in counts.items():
        p = os.path.join(folder, 'place.json')
        m = json.load(open(p, encoding='utf-8'))
        m['privacy'] = {'blurred': True, **c, 'method': METHOD, 'files': sorted(files.get(folder, []))}
        json.dump(m, open(p, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
    for f in os.listdir(check):
        if f.startswith('after_') and f.endswith('.jpg'):
            os.remove(os.path.join(check, f))
    after_sheets(out, check, done)
    tot = {k: sum(c[k] for c in counts.values()) for k in ('faces', 'plates', 'added', 'photos')}
    print(f'blurred {tot["photos"]} photos: {tot["faces"]} faces, {tot["plates"]} plates, {tot["added"]} added by hand; '
          f'{kept} boxes kept as not plates. Look over {os.path.join(check, "after_00.jpg")} ... for anything missed.')


def after_sheets(out, check, files, cols=3, rows=3, tw=640):
    fnt, per = font(18), cols * rows
    for s in range(0, len(files), per):
        tiles = []
        for rel in files[s:s + per]:
            im = Image.open(os.path.join(out, rel))
            im.thumbnail((tw, tw))
            tiles.append((rel, im.convert('RGB')))
        th = max(t.size[1] for _, t in tiles)
        sheet = Image.new('RGB', (cols * tw, rows * (th + 24)), (30, 30, 30))
        dr = ImageDraw.Draw(sheet)
        for k, (rel, t) in enumerate(tiles):
            x, y = (k % cols) * tw, (k // cols) * (th + 24)
            sheet.paste(t, (x, y + 24))
            dr.text((x + 4, y + 2), rel[-60:], fill=(255, 255, 0), font=fnt)
        sheet.save(os.path.join(check, f'after_{s // per:02d}.jpg'), quality=85)


if __name__ == '__main__':
    if len(sys.argv) != 4 or sys.argv[1] not in ('detect', 'apply'):
        raise SystemExit(__doc__)
    out_dir, check_dir = arg_path(sys.argv[2]), arg_path(sys.argv[3])
    os.makedirs(check_dir, exist_ok=True)
    (detect if sys.argv[1] == 'detect' else apply)(out_dir, check_dir)
