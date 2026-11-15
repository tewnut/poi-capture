"""Place capture -> one folder per place + result.json, like a sweep (docs/pipeline.md, stage 2b).

usage: python build_place.py <session folder | export zip> <readings.json> <outdir>

The places are already grouped by the contributor (one tap per place); their pin is the contributor's (GPS at the
door, or where they dragged it). The agent reads every photo of a place and writes <readings.json>, keyed P1, P2, ...
in capture order (print them with --list):

{"P1": {"name": "Kiốt — Bún chả & Bánh cuốn chấm", "category": "restaurant", "status": "open", "confidence": "high",
        "fields": {"phone": {"value": "0935555861", "photo": 1, "sure": "medium"}, ...},
        "best": [{"photo": 1, "why": "sign whole"}],          # photo = position in that place's photos, from 1
        "agentPin": {"lat": .., "lon": .., "why": ".."},     # optional; never from Google
        "note": ""}}

Places are not matched with sweeps (separate tasks); Google Maps is looked up afterwards with enrich_browser.py.
"""
import json, os, shutil, sys, tempfile, zipfile

sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build  # noqa: E402


def load_session(src):
    """(session, places [{id, position, photos: [item + 'path']}], track [[lat, lon]]) from a pulled folder or a zip."""
    tmp = None
    if src.lower().endswith('.zip'):
        tmp = tempfile.mkdtemp(prefix='place_')
        with zipfile.ZipFile(src) as z:
            z.extractall(tmp)
        src = tmp
    if os.path.isfile(os.path.join(src, 'manifest.json')):          # export zip
        m = json.load(open(os.path.join(src, 'manifest.json'), encoding='utf-8'))
        items = {i['id']: dict(i, path=os.path.join(src, i['file'])) for i in m['items'] if i.get('kind') == 'photo'}
        places = [{'id': p['id'], 'position': p['position'], 'fields': p.get('fields') or {},
                   'photos': [items[i] for i in p.get('photos') or [] if i in items]} for p in m.get('pois') or []]
        track = []
        tf = os.path.join(src, m.get('trackFile') or 'track.geojson')
        if os.path.isfile(tf):
            g = json.load(open(tf, encoding='utf-8'))
            for f in g.get('features') or [g]:
                geo = f.get('geometry') or f
                if geo.get('type') == 'LineString':
                    track += [[c[1], c[0]] for c in geo['coordinates']]
        return m['session'], places, track, tmp
    # folder pulled from the phone: session.json, pois.jsonl, items.jsonl (+ patches), photos/
    ses = json.load(open(os.path.join(src, 'session.json'), encoding='utf-8'))
    pois, order, items = {}, [], {}
    for line in open(os.path.join(src, 'pois.jsonl'), encoding='utf-8'):
        o = json.loads(line)
        if 'id' in o:
            if o['id'] not in pois:
                order.append(o['id'])
            pois[o['id']] = {**pois.get(o['id'], {}), **o}
    for line in open(os.path.join(src, 'items.jsonl'), encoding='utf-8'):
        o = json.loads(line)
        if '_patch' in o:
            if o['_patch'] in items:
                items[o['_patch']].update({k: v for k, v in o.items() if k != '_patch'})
        elif o.get('kind') == 'photo':
            items[o['id']] = dict(o, path=os.path.join(src, 'photos', o['id'] + '.jpg'))
    places = []
    for pid in order:
        ph = sorted((i for i in items.values() if i.get('poiId') == pid), key=lambda i: i.get('tMs') or 0)
        places.append({'id': pid, 'position': pois[pid]['position'], 'fields': pois[pid].get('fields') or {}, 'photos': ph})
    track = []
    tf = os.path.join(src, 'track.jsonl')
    if os.path.isfile(tf):
        track = [[o['lat'], o['lon']] for o in map(json.loads, open(tf, encoding='utf-8')) if o.get('lat') is not None]
    return ses, places, track, tmp


def main():
    if '--list' in sys.argv:
        _s, places, _t, _tmp = load_session(sys.argv[1])
        for k, p in enumerate(places, 1):
            print(f"P{k}: {len(p['photos'])} photos at {p['position']['lat']:.6f},{p['position']['lon']:.6f} "
                  f"({p['position'].get('source')}) -> " + ', '.join(os.path.basename(x['path']) for x in p['photos']))
        return
    src, readings_f, out = sys.argv[1:4]
    ses, places, track, tmp = load_session(src)
    readings = json.load(open(readings_f, encoding='utf-8'))
    previous = {}
    if os.path.isdir(out):
        for d in os.listdir(out):
            f = os.path.join(out, d, 'place.json')
            if os.path.isfile(f):
                m = json.load(open(f, encoding='utf-8'))
                if m.get('googleMaps'):
                    previous[m['place']['name']] = m['googleMaps']
        for d in os.listdir(out):
            q = os.path.join(out, d)
            shutil.rmtree(q) if os.path.isdir(q) else os.remove(q)
    os.makedirs(out, exist_ok=True)
    shots = []
    n = 0
    for k, p in enumerate(places, 1):
        rd = readings.get(f'P{k}')
        if not rd:
            print(f'P{k}: no reading, skipped')
            continue
        n += 1
        pos = p['position']
        d = os.path.join(out, f"{n:02d}_{build.safe(rd['name'])}")
        os.makedirs(d)
        best = [(b['photo'], b.get('why')) if isinstance(b, dict) else (b, None) for b in rd.get('best') or []] \
            or [(i, None) for i in range(1, len(p['photos']) + 1)]
        files = []
        for j, (i, why) in enumerate(best):
            it = p['photos'][i - 1]
            fn = f"{'COVER' if j == 0 else f'BEST{j + 1}'}_{i:02d}_{it['id']}.jpg"
            shutil.copy2(it['path'], os.path.join(d, fn))
            files.append({'file': fn, 'id': it['id'], 'sweepIndex': i, 'cover': j == 0, 't': it.get('t'), 'why': why,
                          'camera': {'lat': it.get('lat'), 'lon': it.get('lon'), 'accuracyM': it.get('accuracyM'),
                                     'bearing': it.get('cameraBearing'), 'bearingSource': it.get('cameraBearingSource')},
                          'size': f"{it.get('width')}x{it.get('height')}"})
        for it in p['photos']:
            if it.get('lat') is not None:
                shots.append([len(shots), round(it['lat'], 6), round(it['lon'], 6), it.get('cameraBearing')])
        fields = {}
        for key, v in (rd.get('fields') or {}).items():
            if v in (None, '', []):
                continue
            v = dict(v) if isinstance(v, dict) else {'value': v, 'photo': 1, 'sure': 'high'}
            if isinstance(v.get('photo'), int) and 1 <= v['photo'] <= len(p['photos']):
                v['photo'] = p['photos'][v['photo'] - 1]['id']
            fields[key] = v
        fields.setdefault('name', {'value': rd['name'], 'photo': p['photos'][best[0][0] - 1]['id'] if p['photos'] else None,
                                   'sure': rd.get('confidence') or 'high'})
        method = f"contributor ({pos.get('source') or 'gps'}, ±{round(pos.get('accuracyM') or 0, 1)} m)"
        meta = {
            'place': {'name': rd['name'], 'category': rd.get('category'), 'signText': (fields.get('signText') or {}).get('value'),
                      'confidence': rd.get('confidence') or 'high', 'note': rd.get('note') or None,
                      'status': rd.get('status') or 'open', 'readBy': 'vision model, from the photos in this folder',
                      'street': (fields.get('street') or {}).get('value'),
                      'address': None, 'phone': None, 'openingHours': None, 'website': None,
                      'position': {'lat': pos['lat'], 'lon': pos['lon'], 'method': method,
                                   'uncertaintyM': round(pos.get('accuracyM') or 5, 1), 'sightings': [],
                                   'googleMaps': build.gmaps(pos['lat'], pos['lon']),
                                   'coverCameraOnGoogleMaps': build.gmaps(pos['lat'], pos['lon'])}},
            'review': {'status': 'todo', 'comment': None},
            'fields': fields,
            'pins': {'contributor': {'lat': pos['lat'], 'lon': pos['lon'], 'method': method,
                                     'uncertaintyM': round(pos.get('accuracyM') or 5, 1)},
                     'agent': ({'lat': rd['agentPin']['lat'], 'lon': rd['agentPin']['lon'], 'why': rd['agentPin'].get('why')}
                               if rd.get('agentPin') else None)},
            'googleMaps': previous.get(rd['name']),
            'session': {'id': ses.get('id'), 'name': ses.get('name'), 'kind': 'poi'},
            'photos': files,
            'photoSelection': {'how': 'chosen while reading the photos' if rd.get('best') else 'all photos',
                               'seenOn': len(p['photos']), 'kept': len(files), 'whyBest': None},
            'otherPhotos': [{'id': it['id'], 'sweepIndex': i, 'file': None} for i, it in enumerate(p['photos'], 1)
                            if i not in [b[0] for b in best]],
        }
        json.dump(meta, open(os.path.join(d, 'place.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
    json.dump({'session': {k: ses.get(k) for k in ('id', 'name', 'kind', 'holding', 'startedAt', 'endedAt')},
               'street': None, 'track': track or [[s[1], s[2]] for s in shots], 'photos': shots},
              open(os.path.join(out, 'sweep.json'), 'w', encoding='utf-8'), ensure_ascii=False)
    json.dump([], open(os.path.join(out, 'removals.json'), 'w', encoding='utf-8'))
    build.write_indexes(out, {'id': ses.get('id'), 'name': ses.get('name'), 'kind': 'poi'})
    if tmp:
        shutil.rmtree(tmp, ignore_errors=True)
    print(f'{n} places -> {out}')


if __name__ == '__main__':
    main()
