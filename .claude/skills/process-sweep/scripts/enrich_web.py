"""Open each place's website and read what it says about the place, plus its images.

usage: python enrich_web.py <outdir> [--force] [--only 06_Starbucks,16_Highlands_Coffee]

The website comes from the photos (fields.website) or from the Google lookup. For each one: robots.txt is honoured,
the page is fetched (one request at a time, a pause between sites), and read:
- schema.org JSON-LD (LocalBusiness / Store / Restaurant / Organization): name, telephone, address, opening hours,
  images;
- the page title and description (og: / meta), tel: links;
- up to 3 images (og:image, JSON-LD image), saved next to the photos as WEB_1.jpg … (images only, <= 4 MB).
Written to place.json as a "website" block; result.py then fills the fields the photos lack from it (the business's
own words come before Google's) and the review page shows the images. Social and link-in-bio pages (facebook,
instagram, tiktok, linkbio) are skipped: they need a login or carry nothing structured.
"""
import html, json, os, re, sys, time, urllib.parse, urllib.request, urllib.robotparser
from datetime import datetime, timezone

sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build  # noqa: E402

UA = 'Mozilla/5.0 (compatible; poi-capture-reviewer/0.16; place details from the business website)'
SKIP = ('facebook.com', 'fb.com', 'instagram.com', 'tiktok.com', 'linkbio', 'linktr.ee', 'zalo.me', 'shopee', 'lazada')
MAX_IMG = 4 * 1024 * 1024
TYPES = ('LocalBusiness', 'Store', 'Restaurant', 'FoodEstablishment', 'Organization', 'CafeOrCoffeeShop', 'Pharmacy',
         'HealthAndBeautyBusiness', 'AutoRepair', 'ClothingStore', 'BeautySalon', 'Hotel', 'MedicalBusiness', 'Dentist')


def get(url, limit=3_000_000, timeout=15):
    req = urllib.request.Request(url, headers={'User-Agent': UA, 'Accept-Language': 'vi,en;q=0.8'})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        ctype = r.headers.get('Content-Type', '')
        return r.geturl(), ctype, r.read(limit + 1)


def allowed(url):
    p = urllib.parse.urlsplit(url)
    rp = urllib.robotparser.RobotFileParser()
    try:
        rp.set_url(f'{p.scheme}://{p.netloc}/robots.txt'); rp.read()
        return rp.can_fetch(UA, url)
    except Exception:
        return True


def jsonld(page):
    out = []
    for m in re.finditer(r'<script[^>]+application/ld\+json[^>]*>(.*?)</script>', page, re.S | re.I):
        try:
            d = json.loads(html.unescape(m.group(1).strip()))
        except Exception:
            continue
        stack = d if isinstance(d, list) else [d]
        while stack:
            x = stack.pop()
            if isinstance(x, dict):
                if '@graph' in x: stack.extend(x['@graph'] if isinstance(x['@graph'], list) else [x['@graph']])
                t = x.get('@type'); t = t if isinstance(t, list) else [t]
                if any(str(tt) in TYPES for tt in t): out.append(x)
            elif isinstance(x, list):
                stack.extend(x)
    return out


def meta(page, name):
    m = re.search(r'<meta[^>]+(?:property|name)=["\']%s["\'][^>]+content=["\']([^"\']+)' % re.escape(name), page, re.I) or \
        re.search(r'<meta[^>]+content=["\']([^"\']+)["\'][^>]+(?:property|name)=["\']%s["\']' % re.escape(name), page, re.I)
    return html.unescape(m.group(1)).strip() if m else None


def hours(spec):
    """schema.org openingHours (text or OpeningHoursSpecification) -> 'Mo-Su 07:00-22:00; …'."""
    if isinstance(spec, str): return spec
    if isinstance(spec, list) and spec and isinstance(spec[0], str): return '; '.join(spec)
    out = []
    for s in spec if isinstance(spec, list) else [spec]:
        if not isinstance(s, dict): continue
        days = s.get('dayOfWeek'); days = days if isinstance(days, list) else [days]
        d = ','.join(str(x).split('/')[-1][:2] for x in days if x)
        if s.get('opens') and s.get('closes'): out.append(f"{d} {s['opens'][:5]}-{s['closes'][:5]}".strip())
    return '; '.join(out) or None


def read_site(url):
    final, ctype, body = get(url)
    if 'html' not in ctype.lower():
        return {'status': 'not a web page', 'url': final}
    page = body.decode('utf-8', 'replace')
    title = re.search(r'<title[^>]*>(.*?)</title>', page, re.S | re.I)
    data = {'status': 'ok', 'url': final, 'title': html.unescape(title.group(1)).strip()[:200] if title else None,
            'description': (meta(page, 'og:description') or meta(page, 'description') or '')[:400] or None, 'fields': {}}
    imgs = [meta(page, 'og:image')]
    biz = jsonld(page)
    if biz:
        b = biz[0]
        data['schemaName'] = b.get('name')
        f = data['fields']
        if b.get('telephone'): f['phone'] = re.sub(r'[^\d+]', '', str(b['telephone']))
        a = b.get('address')
        if isinstance(a, dict):
            st = a.get('streetAddress') or ''
            m = re.match(r'\s*(\d+[A-Za-z]?(?:[-/]\d+[A-Za-z]?)*)\s+(.+)', st)
            if m: f['houseNumber'], f['street'] = m.group(1), m.group(2).split(',')[0].strip()
            data['address'] = ', '.join(str(a.get(k)) for k in ('streetAddress', 'addressLocality', 'addressRegion') if a.get(k))
        elif isinstance(a, str):
            data['address'] = a
        h = hours(b.get('openingHoursSpecification') or b.get('openingHours'))
        if h: f['openingHours'] = h
        im = b.get('image'); im = im if isinstance(im, list) else [im]
        imgs += [x.get('url') if isinstance(x, dict) else x for x in im]
    tels = re.findall(r'href=["\']tel:([^"\']+)', page, re.I)
    if tels and 'phone' not in data['fields']:
        data['fields']['phone'] = re.sub(r'[^\d+]', '', urllib.parse.unquote(tels[0]))
    data['_images'] = [urllib.parse.urljoin(final, i) for i in dict.fromkeys(i for i in imgs if i)][:3]
    return data


def save_images(urls, folder):
    saved = []
    for k, u in enumerate(urls, 1):
        try:
            final, ctype, body = get(u, limit=MAX_IMG, timeout=20)
        except Exception:
            continue
        ext = {'image/jpeg': '.jpg', 'image/png': '.png', 'image/webp': '.webp'}.get(ctype.split(';')[0].strip().lower())
        if not ext or len(body) > MAX_IMG or len(body) < 2000:
            continue
        fn = f'WEB_{k}{ext}'
        open(os.path.join(folder, fn), 'wb').write(body)
        saved.append({'file': fn, 'url': final})
    return saved


def main():
    out = sys.argv[1]
    force = '--force' in sys.argv
    only = set(sys.argv[sys.argv.index('--only') + 1].split(',')) if '--only' in sys.argv else None
    n = 0
    for d in sorted(os.listdir(out)):
        f = os.path.join(out, d, 'place.json')
        if not os.path.isfile(f) or (only and d not in only):
            continue
        m = json.load(open(f, encoding='utf-8'))
        if m.get('website') and not force:
            continue
        site = ((m.get('fields') or {}).get('website') or {}).get('value') or (m.get('googleMaps') or {}).get('website')
        if not site:
            continue
        url = site if site.startswith('http') else 'https://' + site.strip().strip('/')
        host = urllib.parse.urlsplit(url).netloc.lower()
        if any(s in host or s in url for s in SKIP):
            m['website'] = {'status': 'skipped (social / link page)', 'url': url}
        elif not allowed(url):
            m['website'] = {'status': 'not allowed by robots.txt', 'url': url}
        else:
            try:
                w = read_site(url)
                w['images'] = save_images(w.pop('_images', []), os.path.join(out, d)) if w.get('status') == 'ok' else []
            except Exception as e:
                w = {'status': f'error: {type(e).__name__}', 'url': url}
            m['website'] = w
            time.sleep(1.5)
        m['website']['fetchedAt'] = datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
        json.dump(m, open(f, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
        w = m['website']
        print(f"{d}: {w['status']} | {w.get('url')} | fields {list((w.get('fields') or {}).keys())} | images {len(w.get('images') or [])}")
        n += 1
    build.write_indexes(out)
    print(f'{n} websites read')


if __name__ == '__main__':
    main()
