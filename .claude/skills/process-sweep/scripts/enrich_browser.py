"""Look each place up on Google Maps in a real browser (agent-browser), for review.

usage: python enrich_browser.py <outdir> [--force] [--only 06_Starbucks,16_Highlands_Coffee]

For each place folder: open Google Maps at the place's estimated position (zoom 18), search its name there, take the
results Google lists nearby, pick the one whose name matches best and is closest (<= 300 m), open it and read the
address, phone, website, plus code, opening hours (all days), rating and its exact position. Writes the "googleMaps"
block of place.json (with the other candidates, for review) and rewrites the indexes.

Needs the agent-browser CLI (npm i -g agent-browser). One search per place with a pause between them; no API key.
Kept for review only: see "Terms" in SKILL.md.
"""
import json, os, random, re, subprocess, sys, time, urllib.parse
from datetime import datetime, timezone

sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build  # noqa: E402
from enrich import similarity, dist_m  # noqa: E402

import shutil  # noqa: E402


def find_agent_browser():
    """The native binary: on Windows the npm shim is a .cmd, and cmd.exe would mangle the JavaScript passed to eval
    (it cuts at newlines and reads & | < > as its own), so call the .exe it wraps."""
    if os.environ.get('AGENT_BROWSER'):
        return os.environ['AGENT_BROWSER']
    shim = shutil.which('agent-browser') or shutil.which('agent-browser.cmd')
    if shim and os.name == 'nt':
        exe = os.path.join(os.path.dirname(shim), 'node_modules', 'agent-browser', 'bin', 'agent-browser-win32-x64.exe')
        if os.path.exists(exe):
            return exe
    return shim or 'agent-browser'


AB = find_agent_browser()

LIST_JS = r"""JSON.stringify([...document.querySelectorAll('a[href*="/maps/place/"]')].slice(0, 12).map(a => {
  const card = a.closest('[role=article]') || a.parentElement;
  const m = a.href.match(/!3d(-?[\d.]+)!4d(-?[\d.]+)/);
  return {name: a.getAttribute('aria-label'), lat: m && +m[1], lon: m && +m[2],
          text: (card.innerText || '').replace(/\s+/g, ' ').slice(0, 240)};
}))"""

CLICK_JS = r"""(() => { const a = [...document.querySelectorAll('a[href*="/maps/place/"]')]
  .find(a => a.getAttribute('aria-label') === %s); if (a) a.click(); return !!a; })()"""


DETAIL_JS = r"""(() => {
  const q = s => document.querySelector(s);
  const t = e => e ? (e.getAttribute('aria-label') || e.innerText || '').replace(/\s+/g, ' ').trim() : null;
  const strip = s => s && s.replace(/^[^:]{1,20}:\s*/, '');
  const hours = [...document.querySelectorAll('table tr')].map(r => [...r.cells].map(c => c.innerText.replace(/\s+/g, ' ').trim()))
    .filter(c => c.length >= 2 && c[0]).map(c => c[0] + ': ' + c[1]);
  const m = location.href.match(/!3d(-?[\d.]+)!4d(-?[\d.]+)/);
  const head = (document.querySelector('[role=main]') || document.body).innerText.slice(0, 1500);
  return JSON.stringify({
    category: t(q('button[jsaction*="category"]')),
    address: strip(t(q('[data-item-id="address"]'))), phone: strip(t(q('[data-item-id^="phone:tel:"]'))),
    website: q('a[data-item-id="authority"]')?.href || null, plusCode: strip(t(q('[data-item-id="oloc"]'))),
    hoursSummary: (q('[jsaction*="openhours"][jsaction*="dropdown"]')?.innerText || '').replace(/\s+/g, ' ').trim() || null,
    openingHours: [...new Set(hours)], businessStatus: /đóng cửa vĩnh viễn|permanently closed/i.test(head) ? 'CLOSED_PERMANENTLY'
      : /tạm thời đóng cửa|temporarily closed/i.test(head) ? 'CLOSED_TEMPORARILY' : 'OPERATIONAL',
    rating: t(q('div.F7nice span[aria-hidden]')) || t(q('div.F7nice')),
    lat: m && +m[1], lon: m && +m[2], url: location.href.split('?')[0]
  });
})()"""


def ab(*args, timeout=60):
    r = subprocess.run([AB, *args], capture_output=True, text=True, encoding='utf-8', timeout=timeout)
    if r.returncode != 0:
        raise RuntimeError((r.stderr or r.stdout).strip()[:300])
    return r.stdout.strip()


def ev(js):
    out = ab('eval', js)
    v = json.loads(out)               # agent-browser prints the value as JSON
    return json.loads(v) if isinstance(v, str) and v[:1] in '[{' else v


MAX_M = 300           # a match farther than this from the estimate is another branch
MIN_MATCH = 0.5       # share of the sign's words found in Google's name


def name_match(sign, google):
    """How much of the name read on the sign is in Google's name ("Ông Bầu" in "Cà Phê Ông Bầu - The Sun Avenue" =
    1.0): Google names add the branch, the kind of shop and the building, the sign rarely does. The better of that
    and the plain word overlap."""
    a, b = set(fold_words(sign)), set(fold_words(google))
    if not a or not b:
        return 0.0
    return round(max(len(a & b) / len(a), similarity(sign, google)), 2)


def fold_words(s):
    from enrich import fold
    return [w for w in fold(s).split() if len(w) > 1 or w.isdigit()]


def place_h1():
    return ev("(() => { const h = document.querySelector('h1.DUwDvf') || [...document.querySelectorAll('h1')].reverse()"
              ".find(x => !/^(Kết quả|Results)$/.test(x.innerText.trim())); return h ? h.innerText.trim() : null })()")


def wait_coords(tries=5):
    """Google writes the place's own position (!3d…!4d…) into the URL a moment after showing it."""
    for _ in range(tries):
        if re.search(r'!3d-?[\d.]+!4d-?[\d.]+', ab('get', 'url')):
            return True
        ab('wait', '1200')
    return False


def lookup(name, lat, lon):
    url = f"https://www.google.com/maps/search/{urllib.parse.quote_plus(name)}/@{lat:.6f},{lon:.6f},18z?hl=vi"
    ab('open', url, timeout=90)
    ab('wait', '3500')
    found = ev(LIST_JS)
    cands = []
    for c in found:
        if c.get('lat') is None or not c.get('name'):
            continue
        d = dist_m(lat, lon, c['lat'], c['lon'])
        s = name_match(name, c['name'])
        cands.append({'name': c['name'], 'distanceM': round(d), 'nameMatch': s, 'summary': c['text'], '_score': s - d / 500})
    cands.sort(key=lambda c: -c['_score'])
    pick = next((c for c in cands if c['nameMatch'] >= MIN_MATCH and c['distanceM'] <= MAX_M), None)
    if not found:
        # one obvious match: Google shows that place straight away, without a list
        h1 = place_h1()
        if not h1 or not wait_coords():
            return None, cands
        m = re.search(r'!3d(-?[\d.]+)!4d(-?[\d.]+)', ab('get', 'url'))
        d = dist_m(lat, lon, float(m.group(1)), float(m.group(2)))
        only = {'name': h1, 'distanceM': round(d), 'nameMatch': name_match(name, h1), 'summary': 'the only result'}
        cands = [only]
        if only['nameMatch'] < MIN_MATCH or only['distanceM'] > MAX_M:
            return None, cands
        pick = dict(only)
    elif pick is None:
        return None, cands
    else:
        if not ev(CLICK_JS % json.dumps(pick['name'])):
            return None, cands
        ab('wait', '2500')
        wait_coords()
    try:   # Google ignores a scripted click on the hours expander: a real click (absent when it has no hours)
        ab('click', '[jsaction*="openhours"][jsaction*="dropdown"]', timeout=20)
        ab('wait', '1000')
    except RuntimeError:
        pass
    det = ev(DETAIL_JS)
    if det.get('lat') is not None:
        pick['distanceM'] = round(dist_m(lat, lon, det['lat'], det['lon']))
    return {'name': pick['name'], **det, 'distanceM': pick['distanceM'], 'nameMatch': pick['nameMatch']}, cands


def main():
    out = sys.argv[1]
    force = '--force' in sys.argv
    only = set(sys.argv[sys.argv.index('--only') + 1].split(',')) if '--only' in sys.argv else None
    now = lambda: datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
    try:
        for d in sorted(os.listdir(out)):
            f = os.path.join(out, d, 'place.json')
            if not os.path.isfile(f) or (only and d not in only):
                continue
            m = json.load(open(f, encoding='utf-8'))
            if (m.get('googleMaps') or {}).get('name') and not force:
                continue
            p = m['place']; pos = p['position']
            name = re.sub(r'\(.*?\)|…', ' ', p['name'])
            name = re.sub(r'\s+', ' ', name).strip()
            try:
                g, cands = lookup(name, pos['lat'], pos['lon'])
                others = [{k: c[k] for k in ('name', 'distanceM', 'nameMatch', 'summary')} for c in cands[:5]]
                g = ({**g, 'status': 'found', 'candidates': others} if g else {'status': 'not found nearby', 'candidates': others})
            except Exception as e:  # noqa: BLE001  (page changed, timeout, blocked: keep going, say so)
                g = {'status': f'error: {e}'}
            g.update({'query': name, 'source': 'Google Maps search near the estimated position (agent-browser)', 'fetchedAt': now()})
            m['googleMaps'] = g
            json.dump(m, open(f, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
            print(f"{d}: {g['status']} | {g.get('name') or ''} | {g.get('address') or ''} | {g.get('phone') or ''}"
                  + (f" | {g['distanceM']} m" if g.get('distanceM') is not None else ''))
            time.sleep(random.uniform(2, 4))          # one search at a time, at a human pace
    finally:
        build.write_indexes(out)


if __name__ == '__main__':
    main()
