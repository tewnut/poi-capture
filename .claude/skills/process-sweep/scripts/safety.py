"""Checks for data from the phone: an export zip or session folder is untrusted input (it can come from anyone).

- inside(root, rel): root/rel, refused when rel would leave root ('../', absolute paths)
- safe_id(x): photo and place ids are short tokens; anything else (slashes, '..') is refused
- extract(zip, dest): unzip, refusing entries that would land outside dest
"""
import os, re, zipfile

SAFE_ID = re.compile(r'^[A-Za-z0-9_-]{1,64}$')


def inside(root, rel):
    base = os.path.realpath(root)
    p = os.path.realpath(os.path.join(base, rel))
    if os.path.commonpath([base, p]) != base:
        raise ValueError(f'path outside the session: {rel!r}')
    return p


def safe_id(x):
    if not isinstance(x, str) or not SAFE_ID.match(x):
        raise ValueError(f'unexpected id in the session: {x!r}')
    return x


def extract(zip_path, dest):
    with zipfile.ZipFile(zip_path) as z:
        for n in z.namelist():
            inside(dest, n)
        z.extractall(dest)
