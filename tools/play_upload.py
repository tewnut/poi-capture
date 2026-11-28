"""Upload a signed .aab to a Google Play track, with its release notes (the release workflow's last step).

usage: python play_upload.py <app.aab> --package vn.vmaps.quetpho --track internal --notes release-notes/vi/X.Y.Z.txt
                             [--name X.Y.Z] [--status completed|draft] [--dry-run]

Credentials: the service account's JSON key in the environment variable PLAY_SERVICE_ACCOUNT_JSON (never a file in
the repository, never printed). The account needs the Google Play Android Developer API enabled in its Cloud project
and, in the Play Console (Users and permissions), release rights on this app. Play's API flow: one "edit" holds the
changes: upload the bundle, put its version code on the track with the notes, commit.

--dry-run checks the inputs (bundle readable, notes <= 500 characters) and prints what it would do, without
credentials or network.
"""
import argparse, json, os, sys, zipfile

LANGUAGE = 'vi'             # the notes' language as Play names it ("<vi>" in the Play Console)


def notes_text(path):
    text = open(path, encoding='utf-8').read().strip()
    if not text:
        raise SystemExit(f'{path} is empty')
    if len(text) > 500:
        raise SystemExit(f'{path} has {len(text)} characters, Play takes at most 500')
    return text


def check_bundle(path):
    with zipfile.ZipFile(path) as z:
        if 'base/manifest/AndroidManifest.xml' not in z.namelist():
            raise SystemExit(f'{path} is not an Android App Bundle')


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('aab')
    ap.add_argument('--package', required=True)
    ap.add_argument('--track', default='internal', choices=['internal', 'alpha', 'beta', 'production'])
    ap.add_argument('--notes', required=True)
    ap.add_argument('--name', help='release name shown in the Play Console (default: Play uses the version name)')
    ap.add_argument('--status', default='completed', choices=['completed', 'draft'])
    ap.add_argument('--dry-run', action='store_true')
    a = ap.parse_args(argv)

    check_bundle(a.aab)
    notes = notes_text(a.notes)
    release = {'status': a.status, 'releaseNotes': [{'language': LANGUAGE, 'text': notes}]}
    if a.name:
        release['name'] = a.name
    print(f'{a.package}: {os.path.basename(a.aab)} -> track "{a.track}" ({a.status}), notes {len(notes)} characters')
    if a.dry_run:
        return 0

    raw = os.environ.get('PLAY_SERVICE_ACCOUNT_JSON', '').strip()
    if not raw:
        raise SystemExit('PLAY_SERVICE_ACCOUNT_JSON is not set')
    from google.oauth2 import service_account          # pinned in the workflow
    from googleapiclient.discovery import build
    from googleapiclient.http import MediaFileUpload
    creds = service_account.Credentials.from_service_account_info(
        json.loads(raw), scopes=['https://www.googleapis.com/auth/androidpublisher'])
    edits = build('androidpublisher', 'v3', credentials=creds, cache_discovery=False).edits()

    edit = edits.insert(packageName=a.package, body={}).execute()['id']
    media = MediaFileUpload(a.aab, mimetype='application/octet-stream', resumable=True)
    code = edits.bundles().upload(packageName=a.package, editId=edit, media_body=media).execute()['versionCode']
    release['versionCodes'] = [str(code)]
    edits.tracks().update(packageName=a.package, editId=edit, track=a.track,
                          body={'track': a.track, 'releases': [release]}).execute()
    edits.commit(packageName=a.package, editId=edit).execute()
    print(f'uploaded version code {code} to "{a.track}"')
    return 0


if __name__ == '__main__':
    sys.exit(main())
