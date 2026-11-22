"""Split a prepared ride into parts, one per street, and at long stops: each part is reviewed as its own session.

usage: python split.py <work> [--gap-min 3] [--min-photos 12]

Run after prepare.py. A part starts where the ride turns onto another street (the new street holds for at least
--min-photos photos: crossing an intersection or a photo or two snapped to a side street does not count) or after a
stop of more than --gap-min minutes between two photos. Parts shorter than --min-photos join their neighbour.

With one part, nothing is written ("no split"): process <work> as usual. Otherwise each part gets <work>/parts/<k>/
with its own photos.json (photos renumbered from 0, `rideIdx` = the index in the whole ride), session.json (id
"<ride id>-<k>", name "<street> <yyyy-MM-dd HHhmm>" like the phone's, parentId = the ride's id, part / parts) and
sheets/. Process each part folder like a work folder (places.json, build.py, ...): its result is a session of its own
on the server, and the reviewer's list counts the ride as processed once its parts are there (parentId).
"""
import argparse, json, os, sys
from datetime import datetime, timezone

sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from paths import arg_path  # noqa: E402

UNNAMED = 'Unnamed road'


def named(road):
    return bool(road) and road != 'null' and not str(road).startswith(UNNAMED)


def when(t):
    """A photo's time: ISO text or epoch milliseconds → datetime (UTC), or None."""
    if t is None:
        return None
    if isinstance(t, (int, float)):
        return datetime.fromtimestamp(t / 1000, timezone.utc)
    try:
        d = datetime.fromisoformat(str(t).replace('Z', '+00:00'))
        return d if d.tzinfo else d.replace(tzinfo=timezone.utc)
    except ValueError:
        return None


def streets(photos, min_photos):
    """Each photo's street, smoothed: unnamed photos take the street around them, and a street held for fewer than
    min_photos photos in a row takes the one before it (or after it, at the start)."""
    road = [p.get('roadName') if named(p.get('roadName')) else None for p in photos]
    last = None
    for i, r in enumerate(road):          # unnamed: the street before (forward fill), then backward at the start
        road[i] = r or last
        last = road[i]
    nxt = None
    for i in range(len(road) - 1, -1, -1):
        road[i] = road[i] or nxt
        nxt = road[i]
    changed = True
    while changed:                        # short runs join a neighbour until every run is long enough
        changed = False
        runs = []
        for i, r in enumerate(road):
            if runs and runs[-1][0] == r:
                runs[-1][2] = i
            else:
                runs.append([r, i, i])
        if len(runs) < 2:
            break
        for k, (r, a, b) in enumerate(runs):
            if b - a + 1 < min_photos:
                fill = runs[k - 1][0] if k > 0 else runs[k + 1][0]
                for i in range(a, b + 1):
                    road[i] = fill
                changed = True
                break
    return road


def split(photos, gap_min=3.0, min_photos=12):
    """[(first, last)] photo index ranges, one per part."""
    if not photos:
        return []
    road = streets(photos, min_photos)
    cuts = [0]
    for i in range(1, len(photos)):
        a, b = when(photos[i - 1].get('t')), when(photos[i].get('t'))
        gap = a is not None and b is not None and (b - a).total_seconds() > gap_min * 60
        if gap or road[i] != road[i - 1]:
            cuts.append(i)
    parts = [[c, (cuts[k + 1] - 1 if k + 1 < len(cuts) else len(photos) - 1)] for k, c in enumerate(cuts)]
    merged = []                           # a part too short (a stop cut it) joins the one before
    for p in parts:
        if merged and p[1] - p[0] + 1 < min_photos:
            merged[-1][1] = p[1]
        elif merged and merged[-1][1] - merged[-1][0] + 1 < min_photos:
            merged[-1][1] = p[1]
        else:
            merged.append(p)
    return [tuple(p) for p in merged]


def stamp(t):
    """The phone's naming: local time, "yyyy-MM-dd HHhmm" (no ':' so the name can be a folder name)."""
    d = when(t)
    return d.astimezone().strftime('%Y-%m-%d %Hh%M') if d else ''


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('work')
    ap.add_argument('--gap-min', type=float, default=3.0, help='a stop longer than this (minutes) starts a new part')
    ap.add_argument('--min-photos', type=int, default=12, help='a street must hold this many photos to start a part')
    a = ap.parse_args()
    work = arg_path(a.work)
    photos = json.load(open(os.path.join(work, 'photos.json'), encoding='utf-8'))
    session = json.load(open(os.path.join(work, 'session.json'), encoding='utf-8'))
    parts = split(photos, a.gap_min, a.min_photos)
    if len(parts) <= 1:
        print(f"no split: one part ({len(photos)} photos); process {work} as usual")
        return
    road = streets(photos, a.min_photos)
    from prepare import sheets
    sid = session.get('id') or 'ride'
    print(f"{len(parts)} parts of {sid} ({len(photos)} photos):")
    for k, (s, e) in enumerate(parts, 1):
        out = os.path.join(work, 'parts', str(k))
        os.makedirs(out, exist_ok=True)
        sub = []
        for n, p in enumerate(photos[s:e + 1]):
            q = dict(p)
            q['rideIdx'], q['idx'] = p['idx'], n
            sub.append(q)
        counts = {}
        for r in road[s:e + 1]:
            counts[r] = counts.get(r, 0) + 1
        street = max(counts, key=counts.get) if counts else None
        name = f"{street or session.get('name') or sid} {stamp(sub[0].get('t'))}".strip()
        ps = dict(session, id=f"{sid}-{k}", name=name, parentId=sid, part=k, parts=len(parts),
                  startedAt=sub[0].get('t'), endedAt=sub[-1].get('t'))
        json.dump(sub, open(os.path.join(out, 'photos.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
        json.dump(ps, open(os.path.join(out, 'session.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
        sheets(sub, os.path.join(out, 'sheets'))
        print(f"  {k}: {name} · photos #{s}-#{e} ({len(sub)}) -> {out}")


if __name__ == '__main__':
    main()
