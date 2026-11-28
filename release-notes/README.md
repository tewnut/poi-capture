# Release notes

One file per version, `vi/<versionName>.txt` (e.g. `vi/0.22.2.txt`): what changed for the riders, in Vietnamese,
plain text, one change per line starting with "- ", **at most 500 characters** (the Play Console's limit per
language). Write it from `git log <previous tag>..HEAD` before tagging: what a rider notices, not how it was done.

The release workflow (`.github/workflows/release.yml`) refuses a tag without its file or with a longer one, and
attaches `play-notes-vX.Y.Z.txt` to the GitHub release, ready to paste into the Play Console's release notes box:

```
<vi>
- …
</vi>
```
