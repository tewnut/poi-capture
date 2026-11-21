"""Small helpers shared by the scripts: path arguments and the place folders.

- arg_path(p): a path from the command line. Git Bash on Windows leaves some arguments unconverted (a path with
  "[261117]" in it reaches Python as "/c/Users/..."), which Windows Python reads as C:\\c\\Users\\... and writes
  there. "/c/..." becomes "C:/..."; any other "/..." path is refused with a message (it would land on the current
  drive's root). Elsewhere (Linux, macOS, WSL) the path is returned as is.
- folder_no(d): "07_Ph\u1edf_V\u1ee5" -> "07", "104_Highlands" -> "104" (the full number, not 2 characters).
- place_folders(out): the place folders (those with a place.json) in numeric order: 9, 10, 99, 100, not 10, 100, 11.
"""
import os, re

_MSYS = re.compile(r'^/([A-Za-z])(?:/(.*))?$')


def arg_path(p, windows=None):
    if p is None:
        return None
    windows = os.name == 'nt' if windows is None else windows
    if not windows or not p.startswith('/') or p.startswith('//'):
        return p
    m = _MSYS.match(p)
    if m:
        return f"{m.group(1).upper()}:/{m.group(2) or ''}"
    raise SystemExit(f"path {p!r} looks like a Git Bash / MSYS path that was not converted; give it as a Windows path "
                     f"(C:/Users/...) or with the drive letter (/c/Users/...)")


def folder_no(d):
    m = re.match(r'(\d+)', os.path.basename(d))
    return m.group(1) if m else os.path.basename(d)[:2]


def _key(d):
    m = re.match(r'(\d+)', d)
    return (int(m.group(1)) if m else float('inf'), d)


def place_folders(out):
    return sorted((d for d in os.listdir(out) if os.path.isfile(os.path.join(out, d, 'place.json'))), key=_key)
