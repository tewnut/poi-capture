"""Full-resolution crop sheets for reading small print: house-number plates, phones, the street line under a plate.

usage:
  python crops.py plates <work> [--from N] [--to M]
        a numbered tile around each of the phone's house-number hints (photos.json plateHints, app 0.17+: the box of
        each number the phone read), at full resolution, 12 per sheet
  python crops.py boxes <work> <photo>:<x0>,<y0>,<x1>,<y1> [...] [--name NAME]
        your own crops: photo index and the box as fractions of the upright photo (0 = left / top, 1 = right /
        bottom), e.g. 38:0.55,0.40,0.80,0.60; each at full resolution (shrunk only when wider than a tile)
  python crops.py band <work> [--from N] [--to M] [--y 0.40,0.80] [--width 1400]
        the band of each photo where doors, gate plates and the kerb are (default 40-80 % of the height, the
        whole width), one photo per row, 2 rows per sheet, with a ruler in photo fractions (for sightings x and
        blur.py "add" boxes); for photos without hints, or plates the phone missed (most gate plates)

Writes <work>/crops/<mode>_NN.jpg and <work>/crops/<mode>.json ([{"tile", "sheet", "photo", "box", "hint"}]; box
as fractions of the upright photo). Each tile is labelled "<tile> #<photo> <hint>". Contact sheets show a house
plate ~10 px high; these show it at the photo's own pixels. Read the number here, then write it in places.json.
"""
import json, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from paths import arg_path  # noqa: E402

TILE_W, TILE_H, COLS, ROWS = 480, 360, 4, 3
LABEL_H = 30


def _font(size):
    from PIL import ImageFont
    for f in ('arialbd.ttf', 'DejaVuSans-Bold.ttf', 'Arial Bold.ttf'):
        try:
            return ImageFont.truetype(f, size)
        except OSError:
            pass
    try:
        return ImageFont.load_default(size=size)
    except TypeError:
        return ImageFont.load_default()


def _upright(path):
    from PIL import Image, ImageOps
    return ImageOps.exif_transpose(Image.open(path)).convert('RGB')


def crop_box(box, W, H, min_w=360, min_h=270, k=3.0):
    """The region to show around box (pixels): k x its size, at least min_w x min_h, in the tile's 4:3 shape, kept
    inside the photo. -> (x0, y0, x1, y1) in pixels."""
    x0, y0, x1, y1 = box
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    w = max(min_w, (x1 - x0) * k, (y1 - y0) * k * TILE_W / TILE_H)
    h = w * TILE_H / TILE_W
    w, h = min(w, W), min(h, H)
    ax = min(max(0, cx - w / 2), W - w)
    ay = min(max(0, cy - h / 2), H - h)
    return int(ax), int(ay), int(ax + w), int(ay + h)


def hint_tiles(photos, lo=0, hi=None):
    """[(photo idx, box px, hint text)] from photos.json plateHints (boxes in the upright photo's pixels); hints
    without a box are skipped (counted in the second value)."""
    out, nobox = [], 0
    for p in photos:
        if p['idx'] < lo or (hi is not None and p['idx'] > hi):
            continue
        for h in p.get('plateHints') or []:
            b = h.get('box')
            if isinstance(b, (list, tuple)) and len(b) == 4 and all(isinstance(v, (int, float)) for v in b):
                tag = ' '.join(x for x in (h.get('src') if h.get('src') not in (None, 'plate') else None,
                                           '?' if h.get('kind') == 'doubt' else None) if x)
                out.append((p['idx'], [float(v) for v in b], (h.get('text') or '') + (f' ({tag})' if tag else '')))
            else:
                nobox += 1
    return out, nobox


def montage(tiles, out_dir, mode, photos):
    """tiles: [(photo idx, region px (x0, y0, x1, y1) or None, mark px or None, label text)] -> sheets + index."""
    from PIL import Image, ImageDraw
    os.makedirs(out_dir, exist_ok=True)
    for f in os.listdir(out_dir):
        if f.startswith(mode + '_') and f.endswith('.jpg'):
            os.remove(os.path.join(out_dir, f))
    by_idx = {p['idx']: p for p in photos}
    fnt, per, index, cache = _font(20), COLS * ROWS, [], {}
    for s in range(0, len(tiles), per):
        sheet = Image.new('RGB', (COLS * TILE_W, ROWS * (TILE_H + LABEL_H)), (35, 35, 35))
        dr = ImageDraw.Draw(sheet)
        for k, (idx, region, mark, text) in enumerate(tiles[s:s + per]):
            if idx not in cache:
                cache = {idx: _upright(by_idx[idx]['file'])}
            im = cache[idx]
            W, H = im.size
            x0, y0, x1, y1 = region
            crop = im.crop((x0, y0, x1, y1))
            z = min(TILE_W / crop.size[0], TILE_H / crop.size[1], 2.0)   # shrink a big region, enlarge up to 2x
            crop = crop.resize((max(1, int(crop.size[0] * z)), max(1, int(crop.size[1] * z))), Image.LANCZOS)
            if mark:
                ImageDraw.Draw(crop).rectangle([(mark[0] - x0) * z - 3, (mark[1] - y0) * z - 3,
                                                (mark[2] - x0) * z + 3, (mark[3] - y0) * z + 3], outline=(255, 60, 60), width=2)
            n = s + k + 1
            px, py = (k % COLS) * TILE_W, (k // COLS) * (TILE_H + LABEL_H)
            sheet.paste(crop, (px, py + LABEL_H))
            dr.text((px + 4, py + 3), f'{n}  #{idx}  {text}'[:40], fill=(255, 230, 0), font=fnt)
            index.append({'tile': n, 'sheet': f'{mode}_{s // per:02d}.jpg', 'photo': idx,
                          'box': [round(x0 / W, 4), round(y0 / H, 4), round(x1 / W, 4), round(y1 / H, 4)], 'hint': text})
        sheet.save(os.path.join(out_dir, f'{mode}_{s // per:02d}.jpg'), quality=90)
    json.dump(index, open(os.path.join(out_dir, mode + '.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    return (len(tiles) + per - 1) // per


def plates(work, lo=0, hi=None):
    """Sheets of the phone's plate hints. -> (sheets, tiles, hints without a box)."""
    photos = json.load(open(os.path.join(work, 'photos.json'), encoding='utf-8'))
    found, nobox = hint_tiles(photos, lo, hi)
    tiles, sizes = [], {}
    for idx, box, text in found:
        p = next(q for q in photos if q['idx'] == idx)
        if idx not in sizes:
            sizes[idx] = _upright(p['file']).size
        W, H = sizes[idx]
        if box[2] > W * 1.02 or box[3] > H * 1.02:
            nobox += 1            # not in the upright photo's pixels: cannot place it
            continue
        tiles.append((idx, crop_box(box, W, H), box, text))
    n = montage(tiles, os.path.join(work, 'crops'), 'plates', photos) if tiles else 0
    return n, len(tiles), nobox


def parse_box(arg):
    """"38:0.55,0.40,0.80,0.60" -> (38, [0.55, 0.40, 0.80, 0.60])."""
    idx, _, rest = arg.partition(':')
    b = [float(v) for v in rest.split(',')]
    if len(b) != 4 or not all(0 <= v <= 1 for v in b) or b[2] <= b[0] or b[3] <= b[1]:
        raise SystemExit(f'{arg}: give <photo>:<x0>,<y0>,<x1>,<y1> as fractions, x0 < x1, y0 < y1')
    return int(idx), b


def boxes(work, specs, name='boxes'):
    photos = json.load(open(os.path.join(work, 'photos.json'), encoding='utf-8'))
    tiles = []
    for arg in specs:
        idx, b = parse_box(arg)
        p = next((q for q in photos if q['idx'] == idx), None)
        if p is None:
            raise SystemExit(f'no photo #{idx}')
        W, H = _upright(p['file']).size
        region = (int(b[0] * W), int(b[1] * H), int(b[2] * W), int(b[3] * H))
        tiles.append((idx, region, None, arg.partition(':')[2]))
    return montage(tiles, os.path.join(work, 'crops'), name, photos)


def band(work, lo=0, hi=None, y=(0.40, 0.80), width=1400):
    """One row per photo: the band y[0]..y[1] of its height, `width` px wide, ruler ticks every 0.05 (labels every
    0.1) in photo fractions along the top and the left."""
    photos = [p for p in json.load(open(os.path.join(work, 'photos.json'), encoding='utf-8'))
              if p['idx'] >= lo and (hi is None or p['idx'] <= hi)]
    return band_sheets([(f"#{p['idx']}", p['file'], p['idx']) for p in photos], os.path.join(work, 'crops'), 'band',
                       y, width)


def band_sheets(items, out_dir, prefix, y=(0.40, 0.80), width=1400, per=2):
    """items: [(label, image path, photo id for the index)] -> <out_dir>/<prefix>_NN.jpg (per rows each) and
    <prefix>.json; each row the band y[0]..y[1] of the photo's height, `width` px wide, with rulers in photo
    fractions (x along the top, y down the left). Also blur.py's zoomed after-sheets."""
    from PIL import Image, ImageDraw
    os.makedirs(out_dir, exist_ok=True)
    for f in os.listdir(out_dir):
        if f.startswith(prefix + '_') and f.endswith('.jpg'):
            os.remove(os.path.join(out_dir, f))
    fnt, small, index, n = _font(22), _font(14), [], 0
    gut = 44
    for s in range(0, len(items), per):
        part = []
        for label, path, ref in items[s:s + per]:
            im = _upright(path)
            W, H = im.size
            c = im.crop((0, int(y[0] * H), W, int(y[1] * H)))
            part.append((label, ref, c.resize((width, max(1, int(c.size[1] * width / W))), Image.LANCZOS)))
        sheet = Image.new('RGB', (width + gut, sum(r.size[1] + LABEL_H + 18 for _l, _r, r in part)), (35, 35, 35))
        dr = ImageDraw.Draw(sheet)
        yy = 0
        for label, ref, r in part:
            dr.text((4, yy + 3), f'{label}   band y {y[0]:.2f}-{y[1]:.2f} of the photo', fill=(255, 230, 0), font=fnt)
            top = yy + LABEL_H + 18
            for t in range(0, 21):           # x ruler: photo fractions
                x = gut + int(t / 20 * (width - 1))
                dr.line([(x, top - (12 if t % 2 == 0 else 6)), (x, top - 1)], fill=(220, 220, 220), width=1)
                if t % 2 == 0 and t < 20:
                    dr.text((x + 2, top - 18), f'{t / 20:.1f}', fill=(200, 200, 200), font=small)
            sheet.paste(r, (gut, top))
            h = r.size[1]
            for t in range(0, 11):           # y ruler: photo fractions within the band
                fy = y[0] + (y[1] - y[0]) * t / 10
                yv = top + int(t / 10 * (h - 1))
                dr.line([(gut - 8, yv), (gut - 1, yv)], fill=(220, 220, 220), width=1)
                if t % 2 == 0:
                    dr.text((2, max(top, yv - 8)), f'{fy:.2f}', fill=(200, 200, 200), font=small)
            n += 1
            index.append({'tile': n, 'sheet': f'{prefix}_{s // per:02d}.jpg', 'photo': ref, 'box': [0, y[0], 1, y[1]],
                          'hint': label})
            yy = top + h
        sheet.save(os.path.join(out_dir, f'{prefix}_{s // per:02d}.jpg'), quality=88)
    json.dump(index, open(os.path.join(out_dir, prefix + '.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    return (len(items) + per - 1) // per


def _opt(argv, name, default=None, cast=str):
    return cast(argv[argv.index(name) + 1]) if name in argv else default


def main(argv):
    if len(argv) < 2 or argv[0] not in ('plates', 'boxes', 'band'):
        raise SystemExit(__doc__)
    mode, work = argv[0], arg_path(argv[1])
    lo, hi = _opt(argv, '--from', 0, int), _opt(argv, '--to', None, int)
    if mode == 'plates':
        n, t, nobox = plates(work, lo, hi)
        print(f'{t} plate hints on {n} sheet(s) in {os.path.join(work, "crops")} (plates_NN.jpg, plates.json)'
              + (f'; {nobox} hint(s) without a usable box (older app): use "band"' if nobox else ''))
    elif mode == 'boxes':
        specs = [a for k, a in enumerate(argv[2:], 2) if ':' in a and argv[k - 1] != '--name']
        name = _opt(argv, '--name', 'boxes')
        print(f'{boxes(work, specs, name)} sheet(s): {os.path.join(work, "crops", name + "_00.jpg")} ...')
    else:
        yy = tuple(float(v) for v in _opt(argv, '--y', '0.40,0.80').split(','))
        n = band(work, lo, hi, yy, _opt(argv, '--width', 1400, int))
        print(f'{n} sheet(s): {os.path.join(work, "crops", "band_00.jpg")} ...')


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    main(sys.argv[1:])
