"""Pull raw sessions from the POI Capture cloud (a tus server, see server/README.md) and push processed results back.

    python cloud.py list [--type raw-session|processed-result|all]
    python cloud.py pull <id> <dir> [--extract]       download one upload into <dir> (and unzip it next to it)
    python cloud.py push <zip-or-folder> [--type processed-result] [--session-id ID] [--name NAME] [--unblurred]
    python cloud.py delete <id> [<id> ...]           remove uploads, finished ones too (needs the admin token)
    python cloud.py processing                       who is processing which ride
    python cloud.py claim <session-id>               say you are processing it (pull does it), renew it
    python cloud.py release <session-id>             stop processing it without a result (push ends it too)

`pull` of a raw ride marks it as being processed by this key (the reviewers' list shows who, server/README.md
"Processing"); another agent's claim stops the pull unless --force. Uploading its processed result ends the claim.

The server is the tus endpoint the phone uploads to, e.g. https://host/files/ : --url, or the POI_CLOUD_URL environment
variable. The token (optional, sent as "Authorization: Bearer ...") comes only from POI_CLOUD_TOKEN or --token-file,
never from the command line, and is never printed.

A push is resumable: the upload URL is kept in "<zip>.tus.json" beside the zip; run the same push again after an
interruption and it continues from the server's offset (HEAD). A folder is zipped first (a processed place folder
with result.json; its session id, name, kind and target are read from it).

Standard library only.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import tempfile
import urllib.error
import urllib.parse
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from paths import arg_path  # noqa: E402
import zipfile
from datetime import datetime, timezone

sys.stdout.reconfigure(encoding="utf-8")

TUS = '1.0.0'
CHUNK = 8 * 1024 * 1024


# ----------------------------------------------------------------------------------------------- pure helpers

def encode_metadata(meta: dict) -> str:
    """tus Upload-Metadata: "key base64(value)" pairs joined by commas; empty values are sent as the bare key."""
    parts = []
    for k, v in meta.items():
        if v is None:
            continue
        if not k or any(c in k for c in ' ,'):
            raise ValueError(f'bad metadata key {k!r}')
        s = str(v)
        parts.append(k if s == '' else f"{k} {base64.b64encode(s.encode('utf-8')).decode('ascii')}")
    return ','.join(parts)


def decode_metadata(header: str) -> dict:
    out = {}
    for pair in (header or '').split(','):
        pair = pair.strip()
        if not pair:
            continue
        k, _, v = pair.partition(' ')
        out[k] = base64.b64decode(v).decode('utf-8') if v else ''
    return out


def endpoint(url: str) -> str:
    """The tus endpoint with exactly one trailing slash (so ids and index.json resolve under it)."""
    return url.rstrip('/') + '/'


def human(n) -> str:
    if n is None:
        return '?'
    for unit in ('B', 'KB', 'MB', 'GB'):
        if n < 1024 or unit == 'GB':
            return f'{n:.0f} {unit}' if unit == 'B' else f'{n:.1f} {unit}'
        n /= 1024


# ----------------------------------------------------------------------------------------------- http

class Cloud:
    def __init__(self, url: str, token: str | None):
        self.base = endpoint(url)
        self._token = token or None

    def _req(self, method: str, url: str, headers: dict | None = None, data: bytes | None = None):
        # own User-Agent: Cloudflare's edge (the Worker on workers.dev) refuses Python's default one (error 1010)
        h = {'Tus-Resumable': TUS, 'User-Agent': 'poi-capture-cloud.py/1', 'X-Client': 'cloud.py/1', **(headers or {})}
        if self._token:
            h['Authorization'] = 'Bearer ' + self._token
        req = urllib.request.Request(url, data=data, method=method, headers=h)
        try:
            return urllib.request.urlopen(req, timeout=120)
        except urllib.error.HTTPError as e:
            if e.code == 401:
                raise SystemExit(f'{method} {url}: 401 unauthorized (set POI_CLOUD_TOKEN or --token-file)')
            if e.code == 403:
                raise SystemExit(f'{method} {url}: 403 forbidden (not allowed for this token\'s role)')
            raise

    def delete(self, upload_id: str) -> bool:
        """DELETE an upload; True when removed, False when not on the server. Finished uploads: admin token only."""
        try:
            with self._req('DELETE', self.base + urllib.parse.quote(upload_id)):
                return True
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return False
            if e.code == 400:
                raise SystemExit(f'{upload_id}: finished uploads can only be deleted with the admin token')
            raise

    def _root(self) -> str:
        """The server's root (the tus endpoint lives under it, e.g. /files/): /processing is there."""
        u = urllib.parse.urlsplit(self.base)
        return f'{u.scheme}://{u.netloc}'

    def processing(self) -> list[dict]:
        with self._req('GET', self._root() + '/processing') as r:
            return json.load(r).get('processing', [])

    def claim(self, session_id: str) -> tuple[bool, dict]:
        """Mark [session_id] as being processed by this key: (True, claim), or (False, the other agent's claim)."""
        try:
            with self._req('POST', self._root() + '/processing/' + urllib.parse.quote(session_id)) as r:
                return True, json.load(r)
        except urllib.error.HTTPError as e:
            if e.code == 409:
                return False, json.load(e)
            raise

    def release(self, session_id: str) -> bool:
        try:
            with self._req('DELETE', self._root() + '/processing/' + urllib.parse.quote(session_id)):
                return True
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return False
            raise

    def index(self, type_: str | None = None) -> list[dict]:
        q = '?' + urllib.parse.urlencode({'type': type_}) if type_ else ''
        with self._req('GET', self.base + 'index.json' + q) as r:
            return json.load(r).get('uploads', [])

    def download(self, upload_id: str, out_path: str) -> int:
        n = 0
        with self._req('GET', self.base + urllib.parse.quote(upload_id)) as r, open(out_path + '.part', 'wb') as f:
            while True:
                b = r.read(1 << 20)
                if not b:
                    break
                f.write(b)
                n += len(b)
        os.replace(out_path + '.part', out_path)
        return n

    def create(self, size: int, meta: dict) -> str:
        with self._req('POST', self.base, {'Upload-Length': str(size), 'Upload-Metadata': encode_metadata(meta),
                                           'Content-Length': '0'}, b'') as r:
            loc = r.headers.get('Location')
        if not loc:
            raise SystemExit('the server gave no Location for the new upload')
        return urllib.parse.urljoin(self.base, loc)

    def offset(self, url: str) -> int | None:
        try:
            with self._req('HEAD', url) as r:
                return int(r.headers.get('Upload-Offset'))
        except urllib.error.HTTPError as e:
            if e.code in (404, 410):
                return None
            raise

    def patch(self, url: str, offset: int, chunk: bytes) -> int:
        with self._req('PATCH', url, {'Upload-Offset': str(offset), 'Content-Type': 'application/offset+octet-stream'},
                       chunk) as r:
            return int(r.headers.get('Upload-Offset'))


# ----------------------------------------------------------------------------------------------- commands

def _is_street_photo(name: str) -> bool:
    base = name.replace(os.sep, '/').rsplit('/', 1)[-1]
    return base.lower().endswith(('.jpg', '.jpeg', '.png')) and not base.startswith('WEB_')


def _unblurred_places(places: dict[str, tuple[dict, list[str]]]) -> list[str]:
    """{place folder: (place.json, its street photos relative to the folder)} -> the folders with a photo blur.py
    did not blur: not marked blurred, or a photo that is not in privacy.files (added or rebuilt after the blur)."""
    bad = []
    for d, (m, photos) in sorted(places.items()):
        pv = m.get('privacy') or {}
        if not photos:
            continue
        if not pv.get('blurred'):
            bad.append(d)
        elif 'files' in pv:
            if set(photos) - set(pv['files']):
                bad.append(d)
        elif (pv.get('photos') or 0) < len(photos):      # older results: no list, compare the count
            bad.append(d)
    return bad


def unblurred(folder: str) -> list[str]:
    """Photos blur.py has not blurred (faces and plates must not leave the PC). With privacy.json (blur.py since
    25 Nov 2026) every street photo anywhere in the output must be in its list; older outputs are checked per place
    folder, and a photo outside the place folders (e.g. _removals/) is refused."""
    photos = [os.path.relpath(os.path.join(r, f), folder).replace(os.sep, '/')
              for r, _, fs in os.walk(folder) for f in fs if _is_street_photo(f)]
    pj = os.path.join(folder, 'privacy.json')
    if os.path.isfile(pj):
        return _not_listed(json.load(open(pj, encoding='utf-8')), photos)
    places = {}
    loose = [p for p in photos if '/' not in p or not os.path.isfile(os.path.join(folder, p.split('/')[0], 'place.json'))]
    for d in sorted(os.listdir(folder)):
        p = os.path.join(folder, d, 'place.json')
        if os.path.isfile(p):
            places[d] = (json.load(open(p, encoding='utf-8')), [x[len(d) + 1:] for x in photos if x.startswith(d + '/')])
    return _unblurred_places(places) + loose


def _not_listed(privacy: dict, photos: list[str]) -> list[str]:
    if not privacy.get('blurred'):
        return sorted(photos)
    return sorted(set(photos) - set(privacy.get('files') or []))


def unblurred_zip(path: str) -> list[str]:
    """The same check on a processed-result zip (place folders at its top level or one folder down)."""
    with zipfile.ZipFile(path) as z:
        names = [n for n in z.namelist() if not n.endswith('/')]
        pj = sorted((n for n in names if n == 'privacy.json' or n.endswith('/privacy.json')), key=len)
        if pj:   # the output's privacy.json: every street photo in the zip must be listed
            pre = pj[0][:-len('privacy.json')]
            photos = [n[len(pre):] if n.startswith(pre) else n for n in names if _is_street_photo(n)]
            return _not_listed(json.loads(z.read(pj[0]).decode('utf-8')), photos)
        dirs = {n.rsplit('/', 1)[0] for n in names if n.endswith('/place.json') or n == 'place.json'}
        places = {}
        for d in dirs:
            pre = d + '/' if d != 'place.json' else ''
            m = json.loads(z.read(pre + 'place.json').decode('utf-8'))
            photos = [n[len(pre):] for n in names if n.startswith(pre) and _is_street_photo(n)]
            places[d] = (m, photos)
        # a photo in no place folder (e.g. _removals/) has no record: refused
        loose = [n for n in names if _is_street_photo(n) and 'place.json' not in dirs
                 and not any(n.startswith(d + '/') for d in dirs)]
    return _unblurred_places(places) + loose


def zip_folder(folder: str) -> str:
    fd, out = tempfile.mkstemp(suffix='.zip', prefix=os.path.basename(os.path.abspath(folder)) + '-')
    os.close(fd)
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
        for root, _, files in os.walk(folder):
            for name in files:
                p = os.path.join(root, name)
                z.write(p, os.path.relpath(p, folder).replace(os.sep, '/'))
    return out


def read_session(zip_path: str) -> dict:
    """session id / name / kind / target from result.json (processed) or manifest.json (raw), wherever it sits in
    the zip."""
    try:
        with zipfile.ZipFile(zip_path) as z:
            for n in sorted(z.namelist(), key=len):
                base = n.rsplit('/', 1)[-1]
                if base in ('result.json', 'manifest.json'):
                    s = json.loads(z.read(n)).get('session') or {}
                    return {'sessionId': s.get('id'), 'name': s.get('name'), 'kind': s.get('kind'),
                            'target': s.get('target')}
    except (zipfile.BadZipFile, ValueError, KeyError):
        pass
    return {}


def push_metadata(info: dict, filename: str, type_: str, session_id: str | None, name: str | None) -> dict:
    """The upload's metadata; `target` only when the result names one (the web list shows it; None is not sent)."""
    return {'filename': filename, 'filetype': 'application/zip', 'type': type_,
            'sessionId': session_id or info.get('sessionId'), 'name': name or info.get('name'),
            'kind': info.get('kind'), 'target': info.get('target'),
            'createdAt': datetime.now(timezone.utc).isoformat(timespec='seconds')}


def push(cloud: Cloud, path: str, type_: str, session_id: str | None, name: str | None) -> str:
    temp = None
    if os.path.isdir(path):
        temp = zip_folder(path)
        zip_path, filename = temp, os.path.basename(os.path.abspath(path)) + '.zip'
        state_file = os.path.join(os.path.dirname(os.path.abspath(path)), filename + '.tus.json')
    else:
        zip_path, filename = path, os.path.basename(path)
        state_file = path + '.tus.json'
    try:
        size = os.path.getsize(zip_path)
        meta = push_metadata(read_session(zip_path), filename, type_, session_id, name)
        url, offset = None, 0
        if os.path.exists(state_file):
            st = json.load(open(state_file, encoding='utf-8'))
            # a folder is zipped again each time, so its bytes may differ: only a zip file resumes
            if temp is None and st.get('size') == size and st.get('mtime') == os.path.getmtime(zip_path):
                url = st.get('url')
                offset = cloud.offset(url) if url else None
                if offset is None:
                    url, offset = None, 0
        if url is None:
            url = cloud.create(size, meta)
            json.dump({'url': url, 'size': size, 'mtime': os.path.getmtime(zip_path)}, open(state_file, 'w', encoding='utf-8'))
        elif offset:
            print(f'resuming at {human(offset)} of {human(size)}')
        with open(zip_path, 'rb') as f:
            f.seek(offset)
            while offset < size:
                chunk = f.read(CHUNK)
                for attempt in range(5):   # a slow link times a chunk out now and then: ask where the server is, go on
                    try:
                        new = cloud.patch(url, offset, chunk)
                        break
                    except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
                        if isinstance(e, urllib.error.HTTPError) and e.code < 500 and e.code != 409:
                            raise
                        if attempt == 4:
                            raise
                        time.sleep(5 * (attempt + 1))
                        now = cloud.offset(url)
                        print(f'  retrying after {getattr(e, "reason", e)} (server at {human(now or 0)})', file=sys.stderr)
                        if now is not None and now != offset:
                            offset = now
                            f.seek(offset)
                            chunk = f.read(CHUNK)
                if new <= offset:
                    raise SystemExit(f'the server did not advance the offset ({offset})')
                offset = new
                f.seek(offset)
                print(f'  {human(offset)} / {human(size)}', file=sys.stderr)
        os.remove(state_file)
        return url
    finally:
        if temp:
            os.remove(temp)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--url', default=os.environ.get('POI_CLOUD_URL'), help='tus endpoint, e.g. https://host/files/')
    ap.add_argument('--token-file', help='file holding the bearer token (else POI_CLOUD_TOKEN)')
    sub = ap.add_subparsers(dest='cmd', required=True)
    p = sub.add_parser('list')
    p.add_argument('--type', default='raw-session', help='raw-session (default), processed-result, or all')
    p.add_argument('--json', action='store_true')
    p = sub.add_parser('pull')
    p.add_argument('id')
    p.add_argument('dir')
    p.add_argument('--extract', action='store_true', help='also unzip into <dir>/<file name without .zip>/')
    p.add_argument('--force', action='store_true', help='pull even when another agent is processing it')
    p.add_argument('--no-claim', action='store_true', help='only download (a look, not processing it)')
    sub.add_parser('processing')
    p = sub.add_parser('claim')
    p.add_argument('session_id')
    p = sub.add_parser('release')
    p.add_argument('session_id')
    p = sub.add_parser('push')
    p.add_argument('path', help='a zip, or a folder to zip (processed place folder with result.json)')
    p.add_argument('--type', default='processed-result')
    p.add_argument('--session-id')
    p.add_argument('--name')
    p.add_argument('--unblurred', action='store_true', help='send place photos that blur.py has not blurred')
    p = sub.add_parser('delete')
    p.add_argument('ids', nargs='+')
    a = ap.parse_args(argv)
    for k in ('dir', 'path', 'token_file'):   # Git Bash on Windows may pass "/c/..." unconverted
        if getattr(a, k, None):
            setattr(a, k, arg_path(getattr(a, k)))
    if not a.url:
        ap.error('no server: pass --url or set POI_CLOUD_URL')
    token = open(a.token_file, encoding='utf-8').read().strip() if a.token_file else os.environ.get('POI_CLOUD_TOKEN')
    cloud = Cloud(a.url, token)

    if a.cmd == 'list':
        ups = cloud.index(None if a.type == 'all' else a.type)
        if a.json:
            print(json.dumps(ups, ensure_ascii=False, indent=1))
            return 0
        for u in ups:
            m = u.get('metadata') or {}
            state = 'complete' if u.get('complete') else f"partial {human(u.get('offset'))}"
            print(f"{u['id']}  {m.get('type', '?'):16} {(u.get('createdAt') or '')[:16]:16}  {human(u.get('size')):>9}  "
                  f"{state:9}  {m.get('sessionId') or '-'}  {m.get('name') or m.get('filename') or ''}"
                  + (f"  [by {u['by']['name']}]" if u.get('by') else ''))
        if not ups:
            print('nothing stored' + ('' if a.type == 'all' else f' of type {a.type}'))
    elif a.cmd == 'pull':
        ups = {u['id']: u for u in cloud.index()}
        u = ups.get(a.id)
        if not u:
            raise SystemExit(f'{a.id}: not on the server')
        if not u.get('complete'):
            raise SystemExit(f'{a.id}: upload not finished yet ({human(u.get("offset"))} of {human(u.get("size"))})')
        meta = u.get('metadata') or {}
        sid = meta.get('sessionId')
        if sid and meta.get('type') == 'raw-session' and not a.no_claim:
            ok, c = cloud.claim(sid)
            if not ok and not a.force:
                raise SystemExit(f"{sid}: {c['by']['name']} is processing it since {c['at'][:16]} (--force to pull anyway)")
            print(f"{sid}: processing by {c['by']['name']} (until {c['until'][:16]} UTC, renewed by claim)" if ok
                  else f"{sid}: pulled anyway, {c['by']['name']} is processing it")
        # named by the session: the pieces of a ride split per street share the phone's file name
        name = (f'{sid}.zip' if sid and str(meta.get('filename', '')).endswith('.zip') else
                os.path.basename(meta.get('filename') or a.id) or a.id)
        os.makedirs(a.dir, exist_ok=True)
        out = os.path.join(a.dir, name)
        n = cloud.download(a.id, out)
        print(f'{out}  ({human(n)})')
        if a.extract and zipfile.is_zipfile(out):
            dest = os.path.join(a.dir, os.path.splitext(name)[0])
            with zipfile.ZipFile(out) as z:
                root = os.path.realpath(dest)
                for m in z.namelist():   # no paths outside dest
                    t = os.path.realpath(os.path.join(dest, m))
                    if t != root and not t.startswith(root + os.sep):
                        raise SystemExit(f'unsafe path in zip: {m}')
                z.extractall(dest)
            print(dest)
    elif a.cmd == 'processing':
        rows = cloud.processing()
        for c in rows:
            print(f"{c['sessionId']}  {c['by']['name']}  since {c['at'][:16]}  until {c['until'][:16]} UTC")
        if not rows:
            print('nobody is processing a ride')
    elif a.cmd == 'claim':
        ok, c = cloud.claim(a.session_id)
        print(f"{a.session_id}: " + (f"processing by {c['by']['name']} until {c['until'][:16]} UTC" if ok
              else f"{c['by']['name']} is processing it since {c['at'][:16]}"))
        return 0 if ok else 1
    elif a.cmd == 'release':
        print(f"{a.session_id}: " + ('released' if cloud.release(a.session_id) else 'nobody was processing it'))
    elif a.cmd == 'delete':
        for i in a.ids:
            print(f"{i}  {'deleted' if cloud.delete(i) else 'not on the server'}")
    elif a.cmd == 'push':
        if a.type == 'processed-result' and not a.unblurred:
            bad = unblurred(a.path) if os.path.isdir(a.path) else unblurred_zip(a.path)
            if bad:
                raise SystemExit(f"{len(bad)} place folder(s) not blurred yet (faces, number plates), e.g. {bad[0]}: run "
                                 f"blur.py detect / apply first (SKILL.md 'Privacy'), or --unblurred to send them as they are")
        url = push(cloud, a.path, a.type, a.session_id, a.name)
        print(url)
    return 0


if __name__ == '__main__':
    sys.exit(main())
