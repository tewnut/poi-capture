"""Merge the places.json parts of a long ride read by several agents (SKILL.md "Long rides").

usage: python merge_places.py <work>/places.json <part1.json> <part2.json> [...] [--dry-run]

Each reader read a range of photos (e.g. 0-87, 84-173, 170-254: ranges overlap by a few photos so that a sign at a
boundary is seen whole by one of them) and wrote a places.json part with the same format. This joins them:
- ride-level keys ("street", "defaultFacadeDistanceM", "bearingOffsetDeg", ...) from the first part that has them;
  "road" and "streets" stretches and "removals" are joined;
- places, addresses and signs are joined, and the same thing read by two readers in the overlap is merged into one
  (a sign's "post" gets its part's number in front, "2.p1", so two readers' "p1" stay apart; a post merged in the
  overlap takes one id for all its signs):
  a place with the same name (folded, or one name inside the other) whose photo ranges overlap or touch (within
  MAX_GAP photos); an address with the same house number on overlapping photos; a sign with the same code on
  overlapping photos. The merged entry spans both ranges, keeps every sighting (one per photo), every field (the
  surer reading wins), every best photo, the cover of the surer (else the first) reader, the lower confidence when
  they differ, and both notes. Removals with the same name and photo are kept once.
Prints every merge and every near miss (same name or number, ranges apart, or similar names on overlapping photos)
to check by hand; nothing else is guessed.
"""
import difflib, json, os, re, sys

sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from paths import arg_path  # noqa: E402
from result import _fold  # noqa: E402

MAX_GAP = 3                  # photo ranges this close count as touching (a sign half-seen at a part's edge)
SURE = {'high': 3, 'medium': 2, 'low': 1, None: 0}
LISTS = ('road', 'streets')


def _key(s):
    return ' '.join(re.findall(r'[a-z0-9]+', _fold(str(s or ''))))


def _range(x):
    p = x.get('photos') or [x.get('cover'), x.get('cover')]
    return int(p[0]), int(p[1])


def _near(a, b, gap=MAX_GAP):
    (a0, a1), (b0, b1) = _range(a), _range(b)
    return a0 <= b1 + gap and b0 <= a1 + gap


def same(kind, a, b):
    """The same place / address / sign read by two readers: same name / number / code, photos overlapping."""
    if not _near(a, b):
        return False
    if kind == 'places':
        ka, kb = _key(a.get('name')), _key(b.get('name'))
        return bool(ka and kb) and (ka == kb or f' {ka} ' in f' {kb} ' or f' {kb} ' in f' {ka} ')
    if kind == 'addresses':
        if not _key(a.get('houseNumber')) or _key(a.get('houseNumber')) != _key(b.get('houseNumber')):
            return False
        sa, sb = _key(a.get('street')), _key(b.get('street'))
        return not sa or not sb or sa == sb
    if kind == 'signs':
        return _key(a.get('code')) == _key(b.get('code')) and bool(_key(a.get('code')))
    return False


def merge_two(a, b):
    """One entry from two readings of the same thing (see the module doc)."""
    sa, sb = SURE.get(a.get('confidence'), 0), SURE.get(b.get('confidence'), 0)
    first, second = (b, a) if sb > sa else (a, b)
    out = dict(first)
    (a0, a1), (b0, b1) = _range(a), _range(b)
    out['photos'] = [min(a0, b0), max(a1, b1)]
    seen = {}
    for s in (first.get('sightings') or []) + (second.get('sightings') or []):
        seen.setdefault(s['photo'], s)
    out['sightings'] = [seen[k] for k in sorted(seen)]
    if a.get('confidence') and b.get('confidence') and a['confidence'] != b['confidence']:
        out['confidence'] = min(a['confidence'], b['confidence'], key=lambda c: SURE.get(c, 0))
    fields = dict(second.get('fields') or {})
    for k, v in (first.get('fields') or {}).items():
        w = fields.get(k)
        sv = SURE.get(v.get('sure'), 0) if isinstance(v, dict) else 3
        sw = SURE.get(w.get('sure'), 0) if isinstance(w, dict) else 3
        if w is None or sv >= sw:
            fields[k] = v
    if fields:
        out['fields'] = fields
    best, ids = [], set()
    for x in (first.get('best') or []) + (second.get('best') or []):
        i = x['photo'] if isinstance(x, dict) else x
        if i not in ids:
            ids.add(i)
            best.append(x)
    if best:
        out['best'] = best
    for k in ('altNumbers',):
        if a.get(k) or b.get(k):
            out[k] = list(dict.fromkeys((a.get(k) or []) + (b.get(k) or [])))
    for k in ('oldNumber', 'source', 'placeRef', 'street', 'unit', 'agentPin', 'signText', 'extra', 'side', 'lane',
              'post', 'order'):
        if not out.get(k) and second.get(k):
            out[k] = second[k]
    notes = [n for n in (first.get('note'), second.get('note')) if n]
    out['note'] = ' | '.join(dict.fromkeys(notes)) if notes else out.get('note', '')
    return out


def near_misses(kind, items):
    """Pairs worth a look: same name / number on photos apart, or similar names on overlapping photos."""
    out = []
    for i, a in enumerate(items):
        for b in items[i + 1:]:
            if kind == 'places':
                ka, kb = _key(a.get('name')), _key(b.get('name'))
                r = difflib.SequenceMatcher(None, ka, kb).ratio()
                if ka == kb and not _near(a, b):
                    out.append((a, b, 'same name, photos apart (a chain twice, or a pass-by?)'))
                elif ka != kb and r >= 0.75 and _near(a, b, 0):
                    out.append((a, b, f'similar names on overlapping photos ({r:.2f})'))
            elif kind == 'addresses' and _key(a.get('houseNumber')) == _key(b.get('houseNumber')) and not _near(a, b):
                out.append((a, b, 'same number, photos apart (two plates, or two streets?)'))
    return out


def label(kind, x):
    lo, hi = _range(x)
    what = x.get('name') if kind == 'places' else x.get('houseNumber') if kind == 'addresses' else x.get('code') or x.get('label')
    return f'{what} [{lo}-{hi}]'


def merge(parts):
    """places.json parts -> (merged places.json, report lines)."""
    out, report = {}, []
    for p in parts:
        for k, v in p.items():
            if k in ('places', 'addresses', 'signs', 'removals') + LISTS:
                continue
            out.setdefault(k, v)
    for k in LISTS:
        segs = [s for p in parts for s in p.get(k) or []]
        if segs:
            out[k] = sorted(segs, key=lambda s: s.get('fromPhoto', 0))
    alias = {}                     # sign posts: each reader's ids made distinct ("2.p1"); joined where merged

    def root(pid):
        while alias.get(pid, pid) != pid:
            pid = alias[pid]
        return pid
    for kind in ('places', 'addresses', 'signs'):
        merged = []
        for n, p in enumerate(parts, 1):
            for x in p.get(kind) or []:
                if kind == 'signs' and x.get('post') not in (None, '') and len(parts) > 1:
                    x = dict(x, post=f"{n}.{x['post']}")
                hit = next((k for k, y in enumerate(merged) if same(kind, y, x)), None)
                if hit is None:
                    merged.append(dict(x))
                else:
                    report.append(f'{kind}: merged {label(kind, merged[hit])} + {label(kind, x)}')
                    a, b = merged[hit].get('post'), x.get('post')
                    if a and b and root(a) != root(b):
                        alias[root(b)] = root(a)
                    merged[hit] = merge_two(merged[hit], x)
        if kind == 'signs':
            for x in merged:
                if x.get('post'):
                    x['post'] = root(x['post'])
        merged.sort(key=lambda x: (_range(x)[0], x.get('cover') or 0))
        for a, b, why in near_misses(kind, merged):
            report.append(f'{kind}: CHECK {label(kind, a)} / {label(kind, b)}: {why}')
        if merged or kind == 'places':
            out[kind] = merged
    rem, keys = [], set()
    for p in parts:
        for r in p.get('removals') or []:
            k = (_key(r.get('name')), r.get('photo'))
            if k not in keys:
                keys.add(k)
                rem.append(r)
    out['removals'] = rem
    return out, report


def main(argv):
    args = [a for a in argv if not a.startswith('--')]
    if len(args) < 3:
        raise SystemExit(__doc__)
    dest, parts = arg_path(args[0]), [arg_path(a) for a in args[1:]]
    if os.path.abspath(dest) in [os.path.abspath(p) for p in parts]:
        raise SystemExit('write the merged file somewhere else than its parts')
    data = [json.load(open(p, encoding='utf-8')) for p in parts]
    out, report = merge(data)
    for line in report:
        print(line)
    print(f"{len(out.get('places') or [])} places, {len(out.get('addresses') or [])} addresses, "
          f"{len(out.get('signs') or [])} signs, {len(out['removals'])} removals from {len(parts)} parts")
    if '--dry-run' not in argv:
        json.dump(out, open(dest, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
        print(dest)


if __name__ == '__main__':
    main(sys.argv[1:])
