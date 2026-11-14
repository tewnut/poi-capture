"""Load a street sweep (exported zip, unzipped folder, or a raw session folder) and make contact sheets to read.

usage: python prepare.py <session.zip | folder> <workdir>

Writes into <workdir>:
  photos.json   every photo in order: idx, id, file (absolute path), time, camera position / bearing / FOV, size, blur
  session.json  the session record
  sheets/       sheet_NNN.jpg: 4 photos per sheet (2 x 2, 1000 px wide each), each labelled "#idx"
"""
import json, os, sys, zipfile

sys.stdout.reconfigure(encoding='utf-8')


def load(src, work):
    """(session, items, photo_dir) from a zip, an unzipped export (manifest.json) or a raw session dir."""
    if zipfile.is_zipfile(src):
        raw = os.path.join(work, 'raw')
        with zipfile.ZipFile(src) as z:
            z.extractall(raw)
        src = raw
    if os.path.exists(os.path.join(src, 'manifest.json')):
        m = json.load(open(os.path.join(src, 'manifest.json'), encoding='utf-8'))
        items = [i for i in m['items'] if i.get('kind') == 'photo']
        for i in items:
            i['_path'] = os.path.abspath(os.path.join(src, i.get('file') or f"photos/{i['id']}.jpg"))
        return m['session'], items
    # raw session dir (pulled from the phone): items.jsonl, later lines {"_patch": id, ...} update an item
    session = json.load(open(os.path.join(src, 'session.json'), encoding='utf-8'))
    by_id, order = {}, []
    for line in open(os.path.join(src, 'items.jsonl'), encoding='utf-8'):
        if not line.strip():
            continue
        o = json.loads(line)
        pid = o.pop('_patch', None)
        if pid is not None:
            if pid in by_id:
                by_id[pid].update(o)
            continue
        by_id[o['id']] = o
        order.append(o['id'])
    items = [by_id[i] for i in order if by_id[i].get('kind') == 'photo']
    for i in items:
        i['_path'] = os.path.abspath(os.path.join(src, 'photos', i['id'] + '.jpg'))
    return session, items


def sheets(photos, out, per=4, width=1000):
    from PIL import Image, ImageDraw, ImageFont, ImageOps
    os.makedirs(out, exist_ok=True)
    try:
        font = ImageFont.load_default(size=36)
    except TypeError:
        font = ImageFont.load_default()
    for s in range(0, len(photos), per):
        tiles = []
        for p in photos[s:s + per]:
            im = ImageOps.exif_transpose(Image.open(p['file'])).convert('RGB')
            im = im.resize((width, int(im.height * width / im.width)))
            d = ImageDraw.Draw(im)
            d.rectangle([0, 0, 200, 50], fill='black')
            d.text((8, 4), f"#{p['idx']}", fill='yellow', font=font)
            tiles.append(im)
        h = max(t.height for t in tiles)
        sheet = Image.new('RGB', (width * 2, h * 2), 'white')
        for k, t in enumerate(tiles):
            sheet.paste(t, ((k % 2) * width, (k // 2) * h))
        sheet.save(os.path.join(out, f"sheet_{s:03d}.jpg"), quality=85)


def main():
    src, work = sys.argv[1], sys.argv[2]
    os.makedirs(work, exist_ok=True)
    session, items = load(src, work)
    photos = []
    for n, i in enumerate(items):
        photos.append({
            'idx': n, 'id': i['id'], 'file': i['_path'], 't': i.get('t'),
            'lat': i.get('lat'), 'lon': i.get('lon'), 'accuracyM': i.get('accuracyM'),
            'cameraBearing': i.get('cameraBearing'), 'cameraBearingSource': i.get('cameraBearingSource'),
            'hFovDeg': i.get('hFovDeg'), 'width': i.get('width'), 'height': i.get('height'),
            'orientation': i.get('orientation'), 'speedMps': i.get('speedMps'), 'roadName': i.get('roadName'),
            'facadeDistanceM': i.get('facadeDistanceM'), 'blurry': (i.get('blur') or {}).get('blurry'),
            'sharpness': (i.get('blur') or {}).get('sharpness'),
            'compassDeg': (i.get('motion') or {}).get('cameraAzimuthDeg'), 'travelBearing': i.get('travelBearing'),
            'ocrText': (i.get('ocr') or {}).get('text'),
        })
    json.dump(photos, open(os.path.join(work, 'photos.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    json.dump(session, open(os.path.join(work, 'session.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    sheets(photos, os.path.join(work, 'sheets'))
    roads = {}
    for p in photos:
        roads[p['roadName']] = roads.get(p['roadName'], 0) + 1
    print(f"session {session.get('id')} | {session.get('kind')} | {session.get('name')}")
    print(f"{len(photos)} photos -> {(len(photos) + 3) // 4} sheets in {os.path.join(work, 'sheets')}")
    print("roads:", sorted(roads.items(), key=lambda kv: -kv[1])[:5])


if __name__ == '__main__':
    main()
