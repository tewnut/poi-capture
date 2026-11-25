"""The result bundle the web app imports: <out>/result.json next to the place folders (docs/pipeline.md).

    python result.py <out>      (re)write <out>/result.json from the place folders; build.write_indexes calls it

result.json (schema "poi-result/1"):
  session   id, name, kind (sweep | poi), target (shops | addresses | signs; missing = shops), holding, startedAt, street
  track     [[lat, lon], ...] the ride (sweeps), shots [[photoIndex, lat, lon, cameraBearing], ...]
  places    one per place folder (docs/sweep-targets.md for the layers):
            layer (place | address | sign), placeRef (address: the shop it is on),
            sign {code, group, facingDeg, side} (sign),
            id, folder, name, category, status (open | closed | for_rent | under_construction | gone | unknown),
            confidence, fields {name|phone|houseNumber|street|openingHours|website|signText|other:
                                {value, photo, sure}}  (read from the photos; photo = sweep index or photo id;
                                 merged with the website / Google, source "website" / "google"; a street nobody
                                 gives is the road the photos were taken on, source "ride", for the reviewer to check),
            photos [{file, index, cover, why}],
            pins {algorithm | contributor: {lat, lon, method, uncertaintyM, sightings, unsnapped?, snap?},
                  google: {lat, lon, name, distanceM, url} | null,      (for review only: never published)
                  agent: {lat, lon, why} | null},
            google {the review block}, review {status: todo}
  tasks     [{type: visit | verify_removal | removal, place (id) | null, name, lat, lon, why, photo}]
            visit: new to Google (no result close enough to merge: google_mergeable), changed, or partly read ->
            Place capture; removal: closed / for rent / gone seen
            on the photos; verify_removal: gone but the facade was not clearly in view
"""
import json, os, sys
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from paths import arg_path, folder_no, layer_of, place_folders  # noqa: E402

STATUS_GONE = ('closed', 'for_rent', 'gone')


def _google_pin(g):
    if not g or g.get('lat') is None:
        return None
    return {'lat': g['lat'], 'lon': g['lon'], 'name': g.get('name'), 'distanceM': g.get('distanceM'),
            'nameMatch': g.get('nameMatch'), 'url': g.get('url')}


# ---- merge with Google Maps (the project's decision, 2026-11-15) ----
# A matched Google place (name match >= 0.5, within 100 m) is compared field by field with what the photos show:
#   same -> the photo value, google = "match";   different -> the photo value (newer), google = "differs" + its value;
#   missing on the photos -> Google's value, source = "google".
# Google-sourced values are marked and never go into the OpenStreetMap export (OSM does not accept Google data).
import re, unicodedata

def _fold(s):
    s = unicodedata.normalize('NFD', (s or '').lower().replace('đ', 'd'))
    return ''.join(c for c in s if unicodedata.category(c) != 'Mn')

# 11-digit mobile prefixes renumbered to 10 digits in 2018 (Bộ TT&TT, 15 Sep - 15 Nov 2018): signs still show the
# old ones ("0168 …"), Google the new ("038 …")
OLD_MOBILE = {'0162': '032', '0163': '033', '0164': '034', '0165': '035', '0166': '036', '0167': '037', '0168': '038',
              '0169': '039',                                                                  # Viettel
              '0120': '070', '0121': '079', '0122': '077', '0126': '076', '0128': '078',      # MobiFone
              '0123': '083', '0124': '084', '0125': '085', '0127': '081', '0129': '082',      # VinaPhone
              '0186': '056', '0188': '058',                                                   # Vietnamobile
              '0199': '059'}                                                                  # Gmobile


def _digits(p):
    """A phone as bare digits in today's national form: "+84 90 452 3417" -> "0904523417", the 2018 renumbering
    applied ("0168 123 4567" -> "0381234567")."""
    d = re.sub(r'\D', '', p or '')
    d = '0' + d[2:] if d.startswith('84') and len(d) >= 11 else d
    if len(d) == 11 and d[:4] in OLD_MOBILE:
        d = OLD_MOBILE[d[:4]] + d[4:]
    return d

_HOUSE = r'([A-OR-Za-or-z]?\d+[A-Za-z]?(?:\s*[-/]\s*\d+[A-Za-z]?)*)'
_STREET_WORD = re.compile(r'^(?:(?:đường|duong|phố)\s+|[đd]\.\s*)', re.I)
_ADMIN = re.compile(r'^(?:phường|p\.|quận|q\.|tp\b|thành phố|huyện|xã|thị xã|khu phố|kp\b|ấp|tỉnh|việt nam|hồ chí minh)', re.I)
# where a street name ends inside one comma part: "Số 3/50 Lô A", "Tạ Hiện/2 Trần Quý Kiên", "X (quận 2 cũ)",
# "Vành Đai Tây. phường An Khánh", "Đường 3 - P. An Khánh", "đường số 3 phường An Khánh quận 2"
_STREET_END = re.compile(r'\s*(?:/|\(|\.\s|\s-\s)|\s+(?=(?:phường|p\.|quận|q\.|tp\.?|thành phố|khu phố|kp\.?)(?:\s|$))', re.I)


def street_name(s):
    """A street as written in an address, tidied: "Đ. Tạ Hiện" / "đường Tạ Hiện" -> "Tạ Hiện" (the name the phone's
    OSM road carries); the word stays when it is part of the name: "Đ. Số 3" -> "Đường Số 3", "Đường 59", "Đ. D2";
    "Số 9" -> "Đường Số 9". Cut where the name ends ("Đ. Số 3/48 P. Bình An" -> "Đường Số 3")."""
    s = re.sub(r'\s+', ' ', s or '').strip(' ,.')
    s = re.sub(r'^[ĐđDd]\.\s*', 'Đường ', s)
    s = _STREET_END.split(s, 1)[0].strip(' ,.-')
    m = re.match(r'^(đường|duong|phố)\s+(.+)$', s, re.I)
    if m:
        rest = m.group(2)
        if re.match(r'(?:số\s*)?[A-Za-z]{0,2}\d', rest, re.I):
            s = ('Phố ' if m.group(1).lower() == 'phố' else 'Đường ') + rest
        else:
            s = rest
    elif re.match(r'số\s*\d', s, re.I):
        s = 'Đường ' + s
    return s or None


def parse_address(addr):
    """(house number, street) from a Google / website address. The first comma part that is "<number> <street>"
    ("2 Bát Nàn", "133 -135 đường Trương Văn Bang", "số 12A Tạ Hiện"), or that starts with Đường / Đ. / Phố; parts
    before it (a building, a shop unit: "tòa nhà One Verandah", "SH 06 One Verandah", a plus code) are skipped.
    (None, None) when no part looks like a street ("Shop House, Chung cư One Verandah, Tạ Hiện, Thủ Đức")."""
    for part in (addr or '').split(','):
        part = part.strip()
        m = re.match(r'^(?:số\s+)?' + _HOUSE + r'\s+(.+)$', part, re.I)
        if m and re.match(r'[^\W\d_]', m.group(2)):
            street = street_name(m.group(2))
            if street and not _ADMIN.match(street):
                return re.sub(r'\s+', '', m.group(1)), street
        if _STREET_WORD.match(part) and not re.match(r'^[đd]\.\s*$', part, re.I):
            street = street_name(part)
            if street:
                return None, street
    return None, None


def _google_fields(g):
    out = {}
    if g.get('phone'):
        out['phone'] = _digits(g['phone'])
    house, street = parse_address(g.get('address'))
    if house:
        out['houseNumber'] = house
    if street:
        out['street'] = street
    if g.get('openingHours'):
        out['openingHours'] = '; '.join(g['openingHours'])
    if g.get('website'):
        out['website'] = re.sub(r'^https?://(www\.)?', '', g['website']).rstrip('/')
    return out


def street_key(s):
    """A street reduced for comparing: the first comma part, without "Phường ...", the Đường / Đ. / Phố word, a
    house number or "Số": "Đường Số 3, P. An Khánh" and "Đ. Số 3" -> "3", "Tạ Hiện, Phường Cát Lái" -> "ta hien"."""
    a = _fold(s).split(',')[0]
    a = re.split(r'\s(?:phuong|p\.|quan|q\.|tp\.?|thanh pho|kp\.?|khu pho)\s|/|\(|\s-\s', ' ' + a + ' ')[0]
    a = re.sub(r'[^\w\s/-]', ' ', re.sub(r'^\s*(?:(?:duong|pho)\s+|d\.\s*)', '', a.strip()))
    a = re.sub(r'^\s*\d+[a-z]?(?:[-/]\d+[a-z]?)*\s+(?=[a-z])', '', a)
    a = re.sub(r'^\s*(?:duong|pho|d)\s+', '', a)
    a = re.sub(r'^so\s+(?=\d)', '', a.strip())
    return ' '.join(a.split())


def _same(k, photo, google):
    if k == 'phone':
        ps = {_digits(x) for x in re.split(r'[,;/]', photo) if _digits(x)}
        return google in ps
    if k == 'houseNumber':
        first = re.match(r'\s*([\dA-Za-z/-]+)', photo or '')
        return bool(first) and _fold(first.group(1)) == _fold(google)
    if k == 'street':
        a, b = street_key(photo), street_key(google)
        return bool(a) and bool(b) and (a == b or f' {a} ' in f' {b} ' or f' {b} ' in f' {a} ')
    if k == 'website':
        dom = lambda x: re.sub(r'^https?://(www\.)?', '', _fold(x)).split('/')[0]
        return dom(photo) == dom(google)
    if k == 'openingHours':
        times = lambda x: set(re.findall(r'\d{1,2}[:h]\d{2}', x.replace('h', ':')))
        t1 = {t.zfill(5) for t in times(photo)}; t2 = {t.zfill(5) for t in times(google)}
        return bool(t1 & t2)
    return _fold(photo) == _fold(google)

def _unclear(v):
    """A reading the agent was not sure of: low sureness, or parts it could not read ("…", "?")."""
    val = str(v.get('value') or '')
    return v.get('sure') == 'low' or '…' in val or '...' in val or '?' in val


def _compatible(k, photo, other):
    """An unclear photo reading that another source can clarify: what was read agrees with it."""
    if k == 'phone':
        o = _digits(other)
        # read in full but unsure: the same number with one or two digits misread (0904521417 / 0904523417)
        for x in re.split(r'[,;/]', photo):
            d = _digits(x)
            if len(d) >= 8 and len(d) == len(o) and sum(a != b for a, b in zip(d, o)) <= 2:
                return True
        # read in parts ("0901 457 …"): a part of 3+ digits found in the other number
        chunks = [c for c in re.split(r'\D+', photo) if len(c) >= 3]
        return any(len(c) < len(_digits(photo)) and c in o for c in chunks)
    a = re.sub(r'(…|\.\.\.|\?)', ' ', _fold(photo)).split()
    b = _fold(other)
    if not a:
        return False
    import difflib
    return sum(1 for w in a if w in b) / len(a) >= 0.5 or difflib.SequenceMatcher(None, ' '.join(a), b).ratio() >= 0.6


def merge_source(fields, values, source):
    """Compare / clarify / fill fields with another source's values ({field: value}), each field marked:
    match / differs (the photo wins: newer); an unclear photo reading that agrees with the source is clarified by it
    (source = <source>, photoValue kept); a field the photos lack is filled (source = <source>)."""
    out = {k: dict(v) for k, v in fields.items()}
    for k, gv in values.items():
        if not gv:
            continue
        cur = out.get(k)
        if cur and cur.get('value') and cur.get('source') in (None, 'photo'):
            val = str(cur['value'])
            if _same(k, val, gv):
                cur[source] = 'match'
            elif _unclear(cur) and _compatible(k, val, gv):
                out[k] = {'value': gv, 'source': source, 'sure': 'medium', 'clarified': True, 'photoValue': val,
                          'photo': cur.get('photo')}
            else:
                cur[source] = 'differs'
                cur[source + 'Value'] = gv
        elif not cur or not cur.get('value'):
            out[k] = {'value': gv, 'source': source, 'sure': 'medium'}
    return out


GOOGLE_MIN_MATCH, GOOGLE_MAX_M = 0.5, 100


# Words that name a kind of shop, not a shop: a sign made only of them ("Mắt Kính", "Hớt Tóc Nam", "Phở - Bò Kho",
# "Cơm Tấm") matches any neighbour of that kind. Folded (no accents, đ -> d).
GENERIC_WORDS = set('''
mat kinh hot toc nam nu pho com tam bun bo banh mi cafe coffee ca phe tap hoa sua xe rua quan an vat tiem shop spa
nail nha thuoc giat say ui photo copy may vang karaoke tra chay hu tieu xeo kho ga ran dien thoai nhom inox sat han
nuoc da kem che oc lau nuong chao canh xao nghi khach san gas son thoi trang my pham salon cat goi dau massage khoa
phong kham bac cam do game internet net bida sim the cua dong ho giay dep tui qua trai cay rau thit hai gao ngot bia
ruou dai ly cau va moto oto lop vo nhot thay dich vu ban le si cho thue sieu thi mini mart store hang in uong gia
re ngon dac mon cay xang dau may tinh laptop sach van phong pham thiet bi ve sinh noi that dieu hoa lanh dien tu
hop kim cuon rem ga chien vit heo luoc hap nem cuon goi chua sieu ky banh trang tiem tra sua sinh to nuoc ep
'''.split())


def generic_name(name):
    """True when every word of the name is a kind of shop ("Mắt Kính", "Hớt Tóc Nam", "Phở"): nothing in it says
    which shop. A digit or any other word (a person's or brand name) makes it specific."""
    words = re.findall(r'[a-z0-9]+', _fold(name))
    return bool(words) and all(w in GENERIC_WORDS for w in words)


def _photo_values(fields, k):
    """What the photos show for field k (not a value filled from Google / the website)."""
    v = (fields or {}).get(k) or {}
    return str(v['value']) if v.get('value') and v.get('source') in (None, 'photo') else None


def photo_phones(fields):
    """The phones the photos show (field "phone"; several separated by , ; /), in today's form (_digits)."""
    return {d for x in re.split(r'[,;/]', _photo_values(fields, 'phone') or '') for d in [_digits(x)] if len(d) >= 10}


def _base_number(h):
    """The leading number of a house number: "553" -> "553", "4/9" -> "4", "558B" -> "558", "…01" -> None."""
    m = re.match(r'\s*(?:số\s*)?(\d+)', str(h or ''), re.I)
    return m.group(1).lstrip('0') or '0' if m else None


def google_check(g, fields=None):
    """(merged, why): is this Google result this place? Found, within 100 m, not marked `notThisPlace`, and
    - the house number the photos show (read sure) is not another one than Google's ("553" on the sign, Google
      "644 Nguyễn Thị Định": another shop of the same kind);
    - the name matches (>= 0.5), or the phone the photos show is Google's (long names like "Rửa Xe ĐT MotorBike -
      Rửa Xe, Thay Nhớt" fall under 0.5 against the same shop 7 m away);
    - a generic name ("Mắt Kính", "Phở") needs the same phone or house number too.
    `"thisPlace": true` on the googleMaps block (set by the agent after looking) passes the name, house number and
    generic checks; the distance still counts. Without fields (older callers) only name and distance are checked."""
    if not g or not g.get('name'):
        return False, 'no Google result'
    if g.get('notThisPlace'):
        return False, 'marked not this place' + (f": {g['note']}" if g.get('note') else '')
    d = g.get('distanceM')
    if d is None or d > GOOGLE_MAX_M:
        return False, f'{d} m away (over {GOOGLE_MAX_M} m)'
    nm = g.get('nameMatch') or 0
    if g.get('thisPlace'):
        return True, 'confirmed by the agent (thisPlace)'
    gf = _google_fields(g)
    phone_ok = bool(gf.get('phone')) and gf['phone'] in photo_phones(fields)
    h_photo = (fields or {}).get('houseNumber') or {}
    a = _base_number(_photo_values(fields, 'houseNumber')) if h_photo and not _unclear(h_photo) else None
    b = _base_number(gf.get('houseNumber'))
    if a and b and a != b:
        return False, f"house number differs: photos {h_photo.get('value')}, Google {gf.get('houseNumber')}"
    house_ok = bool(a and b and a == b)
    if nm < GOOGLE_MIN_MATCH and not phone_ok:
        return False, f'name match {nm} (under {GOOGLE_MIN_MATCH})'
    name = _photo_values(fields, 'name')
    if name and generic_name(name) and not (phone_ok or house_ok):
        return False, f'generic name "{name}": needs the same phone or house number'
    return True, ('same phone (name match only %s)' % nm) if nm < GOOGLE_MIN_MATCH else \
        'same phone' if phone_ok else 'same house number' if house_ok else 'name and distance'


def google_mergeable(g, fields=None):
    """A Google result that is this place (google_check). Only such a result is merged, counts as "on Google Maps"
    and lends its website (a farther or weaker one is probably another branch / shop). `notThisPlace` (set by the
    agent: another shop, e.g. a generic sign name matched a neighbour) is never merged."""
    return google_check(g, fields)[0]


def merge_google(fields, g):
    """fields (from the photos) merged with a matched Google place, each field marked as above."""
    if not google_mergeable(g, fields):
        return fields
    gv = _google_fields(g)
    out = merge_source(fields, gv, 'google')
    if out.get('openingHours', {}).get('source') == 'google' and len(g.get('openingHours') or []) <= 1:
        out['openingHours'].update({'sure': 'low', 'note': 'Google lists only one day'})
    return out


def _host(u):
    import urllib.parse
    u = str(u or '')
    return urllib.parse.urlsplit(u if '//' in u else '//' + u).netloc.lower().removeprefix('www.')


def _site_from_far_google(w, fields, g):
    """The site was taken from a Google result too far or too unlike the sign to be this place (before enrich_web.py
    checked that): neither its fields nor its images are used."""
    if (fields.get('website') or {}).get('value') or not g or google_mergeable(g, fields) or not g.get('website'):
        return False
    return _host(w.get('url')) == _host(g.get('website'))


def _web_fields(w, fields, g):
    """Fields from the place's own website (enrich_web.py), only when its address is this place's: a chain's site
    gives the head office's address and hotline, not this branch's (then only its description and images are kept)."""
    if not w or w.get('status') != 'ok' or w.get('notThisPlace'):   # the agent saw it is another business's site
        return {}
    if _site_from_far_google(w, fields, g):
        return {}
    f = w.get('fields') or {}
    addr = _fold(w.get('address') or '')
    known = _google_fields(g) if google_mergeable(g, fields) else {}
    street = (fields.get('street') or {}).get('value') or known.get('street') or ''
    house = (fields.get('houseNumber') or {}).get('value') or known.get('houseNumber') or ''
    house = re.match(r'\s*([\dA-Za-z/-]+)', house).group(1) if re.match(r'\s*([\dA-Za-z/-]+)', house) else ''
    here = bool(addr) and ((street and _fold(street).split(',')[0].strip() in addr) or (house and f.get('houseNumber') == house))
    if not here:
        return {}
    out = {k: f.get(k) for k in ('phone', 'houseNumber', 'street', 'openingHours') if f.get(k)}
    if out.get('phone'):   # "+84 70 528 7577" -> "0705287577", like Google's
        out['phone'] = ', '.join(dict.fromkeys(_digits(x) for x in re.split(r'[,;/]', str(out['phone'])) if _digits(x)))
    return out


def _site_is_this_place(m):
    """The website is this place's (its images are shown): its address is this place's, or its domain carries the
    place's name (Google sometimes lists an unrelated site)."""
    w = m.get('website') or {}
    if w.get('status') != 'ok' or w.get('notThisPlace') or _site_from_far_google(w, m.get('fields') or {}, m.get('googleMaps')):
        return False
    if _web_fields(w, m.get('fields') or {}, m.get('googleMaps')):
        return True
    import urllib.parse
    host = re.sub(r'[^a-z0-9]', '', _fold(urllib.parse.urlsplit(w.get('url') or '').netloc).replace('www.', ''))
    words = [x for x in re.findall(r'[a-z0-9]+', _fold(m['place']['name'])) if len(x) >= 3]
    return bool(words) and any(x in host for x in words)


def usable_road(r):
    """A road name from the phone (OSM road within 30 m of the photo), or None for "Unnamed road (...)"."""
    return r if isinstance(r, str) and r.strip() and not r.lower().startswith('unnamed') else None


def pick_road(cover_road, roads, fallback=None):
    """The road a place was photographed from: its cover photo's road, else the most common road over its photos,
    else (last) the ride's street from places.json, unless that names several ("Trương Văn Bang / Tạ Hiện").
    -> (road, how) or (None, None)."""
    if usable_road(cover_road):
        return cover_road, 'cover photo'
    counts = {}
    for r in roads:
        if usable_road(r):
            counts[r] = counts.get(r, 0) + 1
    if counts:
        return max(counts, key=lambda r: counts[r]), 'photos'
    if usable_road(fallback) and not re.search(r'[/,;&]| - ', fallback):
        return fallback, 'ride'
    return None, None


def place_road(m):
    """The road for a place.json: what build.py chose (place.street + streetFrom), or, for a folder built before it
    did, from the kept photos' roadName."""
    p = m.get('place') or {}
    if p.get('streetFrom'):
        return usable_road(p.get('street'))
    ph = m.get('photos') or []
    return pick_road(next((x.get('roadName') for x in ph if x.get('cover')), None),
                     [x.get('roadName') for x in ph], p.get('street'))[0]


def add_ride_street(fields, road):
    """The road the place was photographed from, as a street suggestion: only when neither the photos, the website
    nor Google give a street (source "ride", sure "medium": the reviewer checks it; a corner shop or a place inside a
    building may be on a side street). When another source gives a different street, it is marked ride = "differs"."""
    if not road:
        return fields
    out = {k: dict(v) for k, v in fields.items()}
    cur = out.get('street')
    if not cur or not cur.get('value'):
        out['street'] = {'value': road, 'source': 'ride', 'sure': 'medium'}
    elif not _same('street', str(cur['value']), road):
        cur['ride'], cur['rideValue'] = 'differs', road
    else:
        cur['ride'] = 'match'
    return out


def place_entry(folder, m, session_id):
    p, pos = m['place'], m['place']['position']
    pins = dict(m.get('pins') or {})
    first = 'contributor' if (m.get('session') or {}).get('kind') == 'poi' else 'algorithm'
    pins.setdefault(first, {'lat': pos['lat'], 'lon': pos['lon'], 'method': pos.get('method'),
                            'uncertaintyM': pos.get('uncertaintyM'), 'sightings': pos.get('sightings') or [],
                            # a sign moved onto the road layout: the estimate before, and the move
                            **{k: pos[k] for k in ('unsnapped', 'snap') if pos.get(k)}})
    pins['google'] = _google_pin(m.get('googleMaps'))
    pins.setdefault('agent', None)
    layer = layer_of(m)
    extra = ({'placeRef': p.get('placeRef'), 'numbering': p.get('numbering')} if layer == 'address'
             else {'sign': p.get('sign')} if layer == 'sign' else {})
    g = m.get('googleMaps')
    if g and g.get('name') and layer == 'place':
        ok, why = google_check(g, m.get('fields') or {})
        g = {**g, 'merge': {'merged': ok, 'why': why}}   # why it was (not) merged, for the reviewer
    return {
        'id': f'{session_id}-{folder_no(folder)}', 'folder': folder, 'layer': layer, **extra,
        'name': p['name'], 'category': p.get('category'),
        'status': p.get('status') or 'open', 'confidence': p.get('confidence'),
        'fields': add_ride_street(
            merge_google(merge_source(m.get('fields') or {}, _web_fields(m.get('website'), m.get('fields') or {},
                                                                         m.get('googleMaps')), 'website'),
                         m.get('googleMaps')), place_road(m) if layer != 'sign' else None), 'note': p.get('note'),
        'website': {k: (m.get('website') or {}).get(k) for k in ('status', 'url', 'title', 'description', 'address')}
                   if m.get('website') else None,
        'photos': [{'file': f"{folder}/{x['file']}", 'index': x.get('sweepIndex'), 'cover': x.get('cover'),
                    'why': x.get('why')} for x in m.get('photos') or []]
                  + [{'file': f"{folder}/{x['file']}", 'index': None, 'cover': False, 'why': 'website',
                      'source': 'website', 'url': x.get('url')} for x in (m.get('website') or {}).get('images') or []
                     if _site_is_this_place(m) and not x.get('drop')],   # drop: the agent saw it is not this place
        'pins': pins, 'google': g, 'review': m.get('review') or {'status': 'todo'},
        'groundTruth': m.get('groundTruth'),
    }


def tasks_for(places, removals):
    out = []
    for p in places:
        if (p.get('layer') or 'place') != 'place':
            continue   # visits and removals are for businesses (Place capture)
        g = p.get('google') or {}
        pin = p['pins'].get('agent') or p['pins'].get('algorithm') or p['pins'].get('contributor')
        cover = next((x['file'] for x in p['photos'] if x.get('cover')), None)
        why = []
        if p['status'] in STATUS_GONE:
            out.append({'type': 'removal', 'place': p['id'], 'name': p['name'], 'lat': pin['lat'], 'lon': pin['lon'],
                        'why': f"seen {p['status'].replace('_', ' ')} on the photos", 'photo': cover})
            continue
        looked_up = bool(g.get('status')) and not str(g.get('status')).startswith('error')
        merged = (g['merge']['merged'] if isinstance(g.get('merge'), dict) else google_mergeable(g))
        if looked_up and not merged:   # only a match close enough to merge counts as on Google
            why.append('not on Google Maps nearby' + (
                f" (nearest: {g['name']}, {g.get('distanceM')} m, name match {g.get('nameMatch')}"
                + (f"; {g['merge']['why']}" if isinstance(g.get('merge'), dict) else '') + ')' if g.get('name') else ''))
        if merged and g.get('businessStatus') and g['businessStatus'] != 'OPERATIONAL':
            why.append(f"Google says {g['businessStatus'].lower().replace('_', ' ')}")
        if p.get('confidence') == 'low':
            why.append('name only partly read')
        if why:
            out.append({'type': 'visit', 'place': p['id'], 'name': p['name'], 'lat': pin['lat'], 'lon': pin['lon'],
                        'why': '; '.join(why), 'photo': cover})
    for r in removals:
        t = 'removal' if r.get('inView', True) and r.get('status') in STATUS_GONE else 'verify_removal'
        out.append({'type': t, 'place': None, 'name': r.get('name'), 'lat': r.get('lat'), 'lon': r.get('lon'),
                    'why': r.get('why') or r.get('status'), 'photo': r.get('photo')})
    return out


def write(out):
    folders = place_folders(out)
    if not folders:
        return None
    metas = [(d, json.load(open(os.path.join(out, d, 'place.json'), encoding='utf-8'))) for d in folders]
    sw = json.load(open(os.path.join(out, 'sweep.json'), encoding='utf-8')) if os.path.isfile(os.path.join(out, 'sweep.json')) else {}
    ses = dict(sw.get('session') or metas[0][1].get('session') or {})
    ses['street'] = sw.get('street')
    ses['target'] = ses.get('target') or 'shops'   # older rides and Place capture
    removals = json.load(open(os.path.join(out, 'removals.json'), encoding='utf-8')) if os.path.isfile(os.path.join(out, 'removals.json')) else []
    places = [place_entry(d, m, ses.get('id')) for d, m in metas]
    res = {'schema': 'poi-result/1', 'generatedAt': datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
           'session': ses, 'track': sw.get('track') or [], 'shots': sw.get('photos') or [],
           'places': places, 'tasks': tasks_for(places, removals)}
    f = os.path.join(out, 'result.json')
    json.dump(res, open(f, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    return f


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    print(write(arg_path(sys.argv[1])))
