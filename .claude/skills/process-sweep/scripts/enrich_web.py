"""Open each place's website and read what it says about the place, plus its images.

usage: python enrich_web.py <outdir> [--force] [--only 06_Starbucks,16_Highlands_Coffee] [--respect-robots]
                              [--no-jawb] [--fb-profile NAME]

The website comes from the photos (fields.website) or from the Google lookup. For each one the page is fetched (one
request at a time, a pause between sites; robots.txt is not consulted unless --respect-robots: project decision
2026-11-16, a business's own public contact page) and read:
- schema.org JSON-LD (LocalBusiness / Store / Restaurant / Organization): name, telephone, address, opening hours,
  images;
- the page title and description (og: / meta), tel: links;
- labelled text when there is no JSON-LD ("Địa chỉ:", "Điện thoại:", "Hotline:", "Giờ làm việc:" …, most small
  business sites put them in the footer), and the site's contact page ("Liên hệ") when phone or address is missing;
- up to 8 images: og:image, JSON-LD image, then the page's own photos (<img>, lazy-loaded and srcset too; this
  site's uploads only, not logos / icons / theme files; the full-size original rather than a thumbnail; >= 400 px),
  saved next to the photos as WEB_1.jpg … (images only, <= 4 MB).
Written to place.json as a "website" block; result.py then fills the fields the photos lack from it (the business's
own words come before Google's) and the review page shows the images.

jawb (the local browser, `jawb` CLI + jawbd; JAWB_DATA_DIR as the running daemon's) reads what a plain fetch cannot:
sites that refuse scripts or fail, pages built by JavaScript (little text, placeholder images), and Facebook pages
(only with a logged-in jawb profile, --fb-profile NAME; logged out, Facebook shows a login wall, recorded as such).
Other social and link-in-bio pages (instagram, tiktok, linkbio, zalo, shopee) are still skipped.
"""
import html, json, os, re, subprocess, sys, time, urllib.parse, urllib.request, urllib.robotparser
from datetime import datetime, timezone

sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build  # noqa: E402
from paths import arg_path, business_folders  # noqa: E402
from result import google_mergeable  # noqa: E402

UA = 'Mozilla/5.0 (compatible; poi-capture-reviewer/0.16; place details from the business website)'
SKIP = ('instagram.com', 'tiktok.com', 'linkbio', 'linktr.ee', 'zalo.me', 'shopee', 'lazada')
FACEBOOK = ('facebook.com', 'fb.com', 'fb.me')
RESPECT_ROBOTS = '--respect-robots' in sys.argv
USE_JAWB = '--no-jawb' not in sys.argv
FB_PROFILE = sys.argv[sys.argv.index('--fb-profile') + 1] if '--fb-profile' in sys.argv else None
MAX_IMG = 4 * 1024 * 1024
MAX_IMAGES = 8
MIN_SIDE = 400
SKIP_IMG = re.compile(r'logo|icon|favicon|avatar|sprite|dummy|placeholder|blank|loading|/plugins/|/themes/|'
                      r'emoji|flag|badge|qrcode|zalo|facebook|messenger|\.svg|\.gif', re.I)
LABELS = {
    'address': r'(?:địa chỉ|dia chi|address|trụ sở)',
    'phone': r'(?:số điện thoại|điện thoại|hotline|sđt|sdt|đt|tel|phone|di động)',
    'openingHours': r'(?:giờ làm việc|giờ mở cửa|thời gian làm việc|thời gian mở cửa|opening hours|giờ hoạt động)',
}
STOP = r'(?=\s*(?:%s|email|e-mail|website|fax)\s*[:：])|(?=\s*(?:bản quyền|copyright|©))' % '|'.join(LABELS.values())
TYPES = ('LocalBusiness', 'Store', 'Restaurant', 'FoodEstablishment', 'Organization', 'CafeOrCoffeeShop', 'Pharmacy',
         'HealthAndBeautyBusiness', 'AutoRepair', 'ClothingStore', 'BeautySalon', 'Hotel', 'MedicalBusiness', 'Dentist')


def get(url, limit=3_000_000, timeout=15):
    req = urllib.request.Request(url, headers={'User-Agent': UA, 'Accept-Language': 'vi,en;q=0.8'})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        ctype = r.headers.get('Content-Type', '')
        return r.geturl(), ctype, r.read(limit + 1)


def allowed(url):
    if not RESPECT_ROBOTS:
        return True
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


def visible_text(page):
    t = re.sub(r'<(script|style|noscript)[^>]*>.*?</\1>', ' ', page, flags=re.S | re.I)
    t = re.sub(r'<br\s*/?>|</(p|div|li|h\d|td|tr)>', ' | ', t, flags=re.I)
    return html.unescape(re.sub(r'\s+', ' ', re.sub(r'<[^>]+>', ' ', t))).strip()


def phone_of(s):
    """First Vietnamese phone number in s, as 0xxxxxxxxx (from 0…, +84…, (+84) …, 84…)."""
    for m in re.finditer(r'(?:\(?\+?84\)?|0)[\s.\-]?\d(?:[\s.\-]?\d){7,9}', s):
        d = re.sub(r'\D', '', m.group(0))
        d = '0' + d[2:] if d.startswith('84') else d
        if 10 <= len(d) <= 11 and d.startswith('0'):
            return d
    return None


def hours_of(s):
    """'7h30 – 11h30 … 13h – 17h00' -> '07:30-11:30, 13:00-17:00' (None when it has no time ranges)."""
    t = r'(\d{1,2})\s*(?:h|:|giờ)\s*(\d{2})?'
    rs = [f'{int(a):02d}:{b or "00"}-{int(c):02d}:{d or "00"}'
          for a, b, c, d in re.findall(t + r'\s*(?:-|–|—|đến|to)\s*' + t, s)]
    return ', '.join(dict.fromkeys(rs)) or None


def text_fields(text):
    """Fields from labelled text ('Địa chỉ: 163 Trương Văn Bang, …'): {address, phone, openingHours}."""
    out = {}
    for k, lab in LABELS.items():
        # hours often run over several lines (morning | afternoon): only a new label ends them
        end = STOP + r'|$' if k == 'openingHours' else STOP + r'|\s\|\s|$'
        for m in re.finditer(lab + r'\s*[:：]\s*(.{4,200}?)(?:' + end + r')', text, re.I):
            v = m.group(1).strip(' -–|,.')
            if k == 'phone':
                v = phone_of(v)
            elif k == 'openingHours':
                v = hours_of(v)
            elif not re.search(r'\d', v):
                v = None          # an address has a number
            if v:
                out[k] = v
                break
    return out


def page_images(page, base):
    """The page's own photos, in page order: <img> src / lazy src / largest srcset entry, this site's only."""
    host = urllib.parse.urlsplit(base).netloc.lower().removeprefix('www.')
    out = []
    for tag in re.findall(r'<img\b[^>]*>', page, re.I):
        cands = re.findall(r'\b(?:data-lazy-src|data-src|data-original|src)=["\']([^"\']+)', tag, re.I)
        ss = re.search(r'\b(?:data-srcset|srcset)=["\']([^"\']+)', tag, re.I)
        if ss:
            parts = [x.strip().split() for x in ss.group(1).split(',') if x.strip()]
            best = max(parts, key=lambda x: int(re.sub(r'\D', '', x[1]) or 0) if len(x) > 1 else 0, default=None)
            if best:
                cands.insert(0, best[0])
        for c in cands:
            u = urllib.parse.urljoin(base, html.unescape(c))
            h = urllib.parse.urlsplit(u).netloc.lower().removeprefix('www.')
            if u.startswith('data:') or SKIP_IMG.search(u) or not (h == host or h.endswith('.' + host)):
                continue
            out.append(u)
            break
    return out


def originals(urls):
    """WordPress-style thumbnails (-370x280, -scaled) -> (full-size url, url as shown); one per photo."""
    seen, out = set(), []
    for u in urls:
        full = re.sub(r'(-scaled)?(-\d{2,4}x\d{2,4}x?)?(?=\.(jpe?g|png|webp)$)', '', u, flags=re.I)
        key = re.sub(r'\.(jpe?g|png|webp)$', '', urllib.parse.urlsplit(full).path.rsplit('/', 1)[-1].lower())
        key = re.sub(r'-\d{2,4}$', '', key)
        if key in seen:
            continue
        seen.add(key)
        out.append((full, u))
    return out


def contact_page(page, base):
    host = urllib.parse.urlsplit(base).netloc.lower()
    for href, label in re.findall(r'<a\b[^>]*href=["\']([^"\'#]+)["\'][^>]*>(.*?)</a>', page, re.I | re.S):
        u = urllib.parse.urljoin(base, href)
        if urllib.parse.urlsplit(u).netloc.lower() != host:
            continue
        if re.search(r'lien-?he|contact', u, re.I) or re.search(r'liên hệ|contact', re.sub(r'<[^>]+>', '', label), re.I):
            return u
    return None


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
        t0 = urllib.parse.unquote(tels[0])
        data['fields']['phone'] = phone_of(t0) or re.sub(r'[^\d+]', '', t0)
    # no structured data (most small business sites): the labelled text, then the contact page
    tf = text_fields(visible_text(page)) if not biz else {}
    if not biz and ('phone' not in tf or 'address' not in tf):
        cu = contact_page(page, final)
        if cu and cu.rstrip('/') != final.rstrip('/') and allowed(cu):
            try:
                time.sleep(1.0)
                _, ct, b = get(cu)
                if 'html' in ct.lower():
                    tf = {**text_fields(visible_text(b.decode('utf-8', 'replace'))), **tf}
                    data['contactUrl'] = cu
            except Exception:
                pass
    f = data['fields']
    if tf.get('phone') and 'phone' not in f:
        f['phone'] = tf['phone']
    if tf.get('openingHours') and 'openingHours' not in f:
        f['openingHours'] = tf['openingHours']
    if tf.get('address') and not data.get('address'):
        data['address'] = tf['address'][:200]
        m = re.match(r'\s*(?:số\s*)?(\d+[A-Za-z]?(?:[-/]\d+[A-Za-z]?)*)\s+([^,]+)', tf['address'], re.I)
        if m and 'houseNumber' not in f:
            f['houseNumber'], f['street'] = m.group(1), m.group(2).strip()
    found = [urllib.parse.urljoin(final, i) for i in dict.fromkeys(i for i in imgs if i)] + page_images(page, final)
    data['_images'] = originals(list(dict.fromkeys(found)))
    return data


def image_size(body):
    try:
        import io
        from PIL import Image
        return Image.open(io.BytesIO(body)).size
    except Exception:
        return None          # Pillow missing or an unreadable file: the byte-size check alone


def save_images(cands, folder):
    """cands: (full-size url, url on the page). The full size when it exists, else the page's; >= MIN_SIDE px."""
    for old in os.listdir(folder):
        if old.startswith('WEB_'):
            os.remove(os.path.join(folder, old))
    saved = []
    for full, shown in cands:
        if len(saved) >= MAX_IMAGES:
            break
        got = None
        for u in dict.fromkeys((full, shown)):
            try:
                final, ctype, body = get(u, limit=MAX_IMG, timeout=20)
            except Exception:
                continue
            ext = {'image/jpeg': '.jpg', 'image/png': '.png', 'image/webp': '.webp'}.get(ctype.split(';')[0].strip().lower())
            if ext and 2000 <= len(body) <= MAX_IMG:
                got = (final, ext, body)
                break
        if not got:
            continue
        final, ext, body = got
        wh = image_size(body)
        if wh and min(wh) < MIN_SIDE:
            continue
        fn = f'WEB_{len(saved) + 1}{ext}'
        open(os.path.join(folder, fn), 'wb').write(body)
        saved.append({'file': fn, 'url': final, **({'size': list(wh)} if wh else {})})
    return saved


def jawb(*args, timeout=90):
    r = subprocess.run(['jawb', *args, '--json'], capture_output=True, timeout=timeout, encoding='utf-8', errors='replace')
    try:
        d = json.loads(r.stdout)
    except Exception:
        raise RuntimeError(f'jawb {args[0]}: {(r.stderr or r.stdout)[:200]}')
    if not d.get('ok'):
        raise RuntimeError(f"jawb {args[0]}: {(d.get('error') or {}).get('code')}")
    return d['result']


def jawb_page(url, profile=None):
    """The page as Chrome shows it (JavaScript run): (final url, title, markdown)."""
    tab = jawb('open', url, *(['--profile', profile] if profile else ['--incognito']), '--wait', 'idle', '--timeout', '60s')['tab']
    try:
        r = jawb('read', '--tab', tab, '--js', '--format', 'md', '--full', '--timeout', '60s')
        c = r.get('content')
        return r.get('url') or url, r.get('title'), c if isinstance(c, str) else json.dumps(c, ensure_ascii=False)
    finally:
        try:
            jawb('close', '--tab', tab, timeout=20)
        except Exception:
            pass


def md_images(md, base, any_host=False):
    """Image addresses in markdown; Next.js /_next/image?url=… -> the original; this site's (or any, for Facebook)."""
    host = urllib.parse.urlsplit(base).netloc.lower().removeprefix('www.')
    root = '.'.join(host.split('.')[-2:]) if not host.endswith('.vn') or host.count('.') < 2 else '.'.join(host.split('.')[-3:])
    out = []
    for u in re.findall(r'!\[[^\]]*\]\((\S+?)(?:\s+"[^"]*")?\)', md):
        u = urllib.parse.urljoin(base, html.unescape(u))
        q = urllib.parse.parse_qs(urllib.parse.urlsplit(u).query)
        if '/_next/image' in u and q.get('url'):
            u = urllib.parse.urljoin(base, q['url'][0])
        h = urllib.parse.urlsplit(u).netloc.lower().removeprefix('www.')
        if u.startswith('data:') or SKIP_IMG.search(u.split('?')[0]):
            continue
        if any_host or h == host or h.endswith('.' + root) or h == root:
            out.append(u)
    return list(dict.fromkeys(out))


LOGIN_WALL = re.compile(r"Log in to Facebook|This content isn.t available|See more from .{1,80}\s*/?\s*Email address or phone", re.I)


def read_with_jawb(url, profile=None, facebook=False):
    final, title, md = jawb_page(url, profile)
    flat = re.sub(r'\s*\n\s*', ' | ', re.sub(r'!\[[^\]]*\]\([^)]*\)', ' ', re.sub(r'\[([^\]]*)\]\([^)]*\)', r'\1', md)))
    if facebook and (LOGIN_WALL.search(flat) or len(flat) < 600):
        return {'status': 'facebook: login needed' if not profile else 'facebook: nothing readable', 'url': final, 'via': 'jawb'}
    data = {'status': 'ok', 'url': final, 'via': 'jawb', 'title': (title or (re.search(r'^#\s+(.+)$', md, re.M) or [None, None])[1] or '')[:200] or None,
            'description': None, 'fields': {}}
    tf = text_fields(flat)
    if facebook and 'phone' not in tf:
        ph = phone_of(flat)
        if ph:
            tf['phone'] = ph
    f = data['fields']
    for k in ('phone', 'openingHours'):
        if tf.get(k):
            f[k] = tf[k]
    if tf.get('address'):
        data['address'] = tf['address'][:200]
        m = re.match(r'\s*(?:số\s*)?(\d+[A-Za-z]?(?:[-/]\d+[A-Za-z]?)*)\s+([^,|]+)', tf['address'], re.I)
        if m:
            f['houseNumber'], f['street'] = m.group(1), m.group(2).strip()
    elif not facebook:   # an address line without a label (hero / footer): "97 Trương Văn Bang, Phường …"
        m = re.search(r'(?:^|\|)\s*((\d+[A-Za-z]?(?:[-/]\d+[A-Za-z]?)*)\s+([^,|]{3,40}),\s*(?:phường|p\.|quận|q\.|xã|tp|thành phố)[^|]{0,80})', flat, re.I)
        if m:
            data['address'], f['houseNumber'], f['street'] = m.group(1).strip()[:200], m.group(2), m.group(3).strip()
    data['_images'] = originals(md_images(md, final, any_host=facebook))
    return data


def website_of(m):
    """The site to read: the one on the photos, else Google's, but only from a Google result close enough to be
    this place (result.google_mergeable: name match >= 0.5, within 100 m); a farther one is another branch or shop."""
    site = ((m.get('fields') or {}).get('website') or {}).get('value')
    if site:
        return site
    g = m.get('googleMaps') or {}
    return g.get('website') if google_mergeable(g) else None


def main():
    out = arg_path(sys.argv[1])
    force = '--force' in sys.argv
    only = set(sys.argv[sys.argv.index('--only') + 1].split(',')) if '--only' in sys.argv else None
    n = 0
    for d in business_folders(out):   # no Google / website for addresses and signs
        f = os.path.join(out, d, 'place.json')
        if only and d not in only:
            continue
        m = json.load(open(f, encoding='utf-8'))
        if m.get('website') and not force:
            continue
        site = website_of(m)
        if not site:
            continue
        url = site if site.startswith('http') else 'https://' + site.strip().strip('/')
        host = urllib.parse.urlsplit(url).netloc.lower()
        if any(s in host or s in url for s in SKIP):
            m['website'] = {'status': 'skipped (social / link page)', 'url': url}
        elif any(host == s or host.endswith('.' + s) for s in FACEBOOK):
            try:
                w = read_with_jawb(url, FB_PROFILE, facebook=True) if USE_JAWB else {'status': 'facebook: jawb off', 'url': url}
            except Exception as e:
                w = {'status': f'error: {e}'[:200], 'url': url}
            w['images'] = save_images(w.pop('_images', []), os.path.join(out, d)) if w.get('status') == 'ok' else []
            m['website'] = w
            time.sleep(3)
        elif not allowed(url):
            m['website'] = {'status': 'not allowed by robots.txt', 'url': url}
        else:
            try:
                w = read_site(url)
            except Exception as e:
                w = {'status': f'error: {type(e).__name__}', 'url': url}
            # a plain fetch that failed or got next to nothing (blocked, JavaScript-built page): read it in Chrome
            thin = w.get('status') == 'ok' and not w.get('fields') and len(w.get('_images') or []) < 2
            if USE_JAWB and (w.get('status') != 'ok' or thin):
                try:
                    j = read_with_jawb(url)
                    if j.get('status') == 'ok' and (j.get('fields') or len(j.get('_images') or []) > len(w.get('_images') or [])):
                        if w.get('status') == 'ok':
                            j['title'] = w.get('title') or j.get('title'); j['description'] = w.get('description')
                        w = j
                except Exception as e:
                    w.setdefault('jawbError', str(e)[:200])
            w['images'] = save_images(w.pop('_images', []), os.path.join(out, d)) if w.get('status') == 'ok' else []
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
