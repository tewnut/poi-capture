# POI Capture cloud (tus server)

A small self-hosted server the three stages share instead of passing zips around by hand:

```
phone ── raw session zip (type=raw-session) ──►  server  ◄── agent pulls it, pushes the processed zip back
                                                  │          (type=processed-result, cloud.py)
                                                  └──► reviewer web app lists and pulls processed zips
```

Two implementations of the same contract:

- **Cloudflare Worker** (`worker/`, below): bytes in R2, state in a Durable Object. This is the team's server,
  deployed at **`https://poi-uploader.4hum.ai/files/`** and preset in the web app.
- **Node** (`server.mjs`, this folder): [`@tus/server`](https://github.com/tus/tus-node-server) with files on disk
  (`@tus/file-store`), for running it yourself.

Both speak [tus](https://tus.io/protocols/resumable-upload) 1.0 (core, creation, termination, expiration), plus:

| Request | What |
|---|---|
| `GET /files/index.json[?type=raw-session]` | every stored upload: `{schema: "poi-cloud-index/1", uploads: [{id, size, offset, complete, createdAt, metadata}]}`, newest first |
| `GET /files/<id>` | download a finished upload (always `application/octet-stream`, attachment) |
| `POST` + `X-HTTP-Method-Override: PATCH` | accepted as a PATCH (Android's HTTP client has no PATCH; tusd does this too) |

Upload metadata the clients send: `filename`, `filetype`, `type` (`raw-session` | `processed-result`), `sessionId`,
`name`, `kind` (`sweep` | `poi` | `indoor`), `target` (a sweep's purpose: `shops` | `addresses` | `signs`,
docs/sweep-targets.md; missing = `shops`), `createdAt`, and from the phone `photoCount`; `street` (the ride's main
street names, at most 3, ", "-joined, ≤ 120 chars) and `building` (an indoor walk's building name), from the phone
(app 0.21.1+) and from processed results. The server stores metadata as given; the web list reads `target`,
`street` and `building` from it (filter and search before a ride is downloaded).

Finished uploads cannot be deleted through tus (the phone must not remove what the agent has not pulled yet);
clean up `DATA_DIR` by hand (each upload is `<id>` + `<id>.json`).

## Node: run it locally

Node 20.19 or newer.

```sh
cd server
npm install
TOKEN=some-long-random-string HOST=0.0.0.0 npm start      # PowerShell: $env:TOKEN="..."; $env:HOST="0.0.0.0"; npm start
```

Then use `http://localhost:1080/files/` in the web app's Settings → Cloud storage and for the agent
(`POI_CLOUD_URL`). Without `HOST` the server only listens on 127.0.0.1; `HOST=0.0.0.0` lets other devices in.

The phone: Android allows plain http only in **debug** builds and only to `localhost` / `10.0.2.2`
(`app/src/debug/res/xml/network_security_config.xml`). So for a local test, connect the phone by USB and run
`adb reverse tcp:1080 tcp:1080`, then enter `http://localhost:1080/files/` on the phone (emulator:
`http://10.0.2.2:1080/files/`). Release builds need https (below). Plain http is for testing only: the token and the
photos travel in clear.

| Variable | Default | |
|---|---|---|
| `PORT` | `1080` | |
| `HOST` | `127.0.0.1` | `0.0.0.0` to accept other devices |
| `DATA_DIR` | `./data` | where uploads are stored |
| `TOKEN` | none | if set, every request needs `Authorization: Bearer <TOKEN>` (CORS preflights excepted). **Set it** on anything reachable from the internet. Never logged |
| `CORS_ORIGINS` | `*` | comma-separated origins allowed to call from a browser, e.g. `https://poi-capture-reviewer.pages.dev` |
| `BASE_PATH` | `/files` | |
| `MAX_SIZE` | none | largest upload in bytes |
| `TRUST_PROXY` | off | `1` behind a reverse proxy: the `Location` of new uploads uses `X-Forwarded-Proto` / `-Host` |

## Behind a reverse proxy (https)

Run it on 127.0.0.1 with `TRUST_PROXY=1` and let the proxy do TLS. Uploads are large: no body size limit, no request
buffering, long timeouts.

Caddy:

```
cloud.example.org {
    reverse_proxy 127.0.0.1:1080
}
```

nginx:

```nginx
location /files/ {
    proxy_pass http://127.0.0.1:1080;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    client_max_body_size 0;
    proxy_request_buffering off;
    proxy_buffering off;
    proxy_read_timeout 600s;
    proxy_send_timeout 600s;
}
```

The server URL is then `https://cloud.example.org/files/`. Keep it running with systemd, pm2 or a container
(`node server.mjs` with the variables above).

## Other tus servers

The phone and `cloud.py push` work with any tus 1.0 server that accepts `X-HTTP-Method-Override` (tusd does).
Listing (`index.json`) is this server's own addition: the web app and `cloud.py list / pull` need it.

## Cloudflare Worker (`worker/`)

`worker/src/index.ts` (+ `tus.ts`), `worker/wrangler.jsonc`. Same requests, headers, index and download as the Node
server, so the phone, `cloud.py` and the web app work against either. Extensions: creation, creation-with-upload,
termination (unfinished uploads only), expiration (unfinished uploads untouched for `EXPIRE_DAYS` are removed by a
daily cron, with their R2 parts).

### Design: chunks and state

- **Bytes in R2** (bucket `poi-capture-uploads`, binding `BUCKET`). A finished upload is one object `files/<id>`.
  R2 multipart uploads need every part but the last to be the same size and at least 5 MiB, while tus PATCHes come
  in any size (the phone sends 2 MB, `cloud.py` 8 MiB). So each PATCH body is streamed into 8 MiB buffers
  (`PART_SIZE`); every full buffer is uploaded as the next part, and what is left (< 8 MiB, the *carry*) is written
  to a temporary object `carry/<id>/<lease>` and put in front of the next PATCH's bytes. The PATCH that reaches
  `Upload-Length` uploads the rest as the last part and completes the multipart upload (an upload smaller than one
  part is written with a single put). `cloud.py`'s 8 MiB chunks line up with the parts, so they never need a carry;
  four phone chunks make one part. Memory per PATCH: about two parts (16 MiB).
- **State in one SQLite-backed Durable Object** (`Uploads`, one instance): a row per upload with size, offset,
  metadata, createdAt, the R2 multipart id, the parts list (number + etag), the carry key and length, complete, and
  a *lease*. A PATCH first takes the upload's lease at its `Upload-Offset` (409 if the offset is wrong, 423 if
  another PATCH holds it, 5 min expiry), writes its parts and carry to R2, then commits the new offset, parts and
  carry in one step and drops the lease. Nothing is committed when the body breaks off (dropped connection): the
  offset stays where it was, the client HEADs and resends from there; a re-sent part simply replaces the part with
  the same number. Only small rows go through the Durable Object; the bytes go straight from the Worker to R2.
  `index.json` is a query on that table.

Why not the alternatives: storing every PATCH as its own object and concatenating on download makes many small
objects and slow downloads; per-upload state in R2 object metadata cannot serialise two PATCHes to the same upload.

### Reviews (checkout / check-in)

A processed session is reviewed by one person at a time, in the reviewer web app. The review (every decision, field
edit, pin and the photos the reviewer added or cropped) travels as one zip, `review.json` + those photos
(web/src/reviewpkg.ts). Every save is its own R2 object, `reviews/<session id>/<version>.zip` (a version); the
session's row points at the current one. Every call needs `read` (View & download) or `process`.

Lifecycle: **free** → *check out* → **checked out** (save any number of times; the web app autosaves every 2 min)
→ *check in* → **free**, the next reviewer going on from the checked-in review. A checkout also ends by *giving up*
(no save), an admin *releasing* it, its key being *revoked*, or *expiring* after 24 h without a save. However it
ends, changes the reviewer had not saved stay in their browser: the web app shows why the checkout ended and lets
them check out again keeping their changes (they replace the server's on the next save; asked first when someone
saved in between) or discard them. It never overwrites unsaved changes by itself.

- `GET /reviews` → `{schema: "poi-cloud-reviews/1", reviews: [{sessionId, lock, saved, ended}]}`: `lock` = `{by:
  {id, name}, at, until}` or null (free); `saved` = `{by, at, size, done, total, version}` or null (nothing saved yet);
  `ended` = how the last checkout ended, `{of: {id, name}, by, at, reason}` with reason `checkin`, `gaveup`,
  `released` (by an admin or a key with `release`), `revoked` (its key) or `expired`; null while checked out.
- `POST /reviews/<session>/checkout`: take the lock. `200` with the review's state when it was free, expired, or
  already this key's (renewed); `409` with who has it otherwise.
- `GET /reviews/<session>`: the current review zip (`404` none).
- `PUT /reviews/<session>[?checkin=1]`, body the zip (at most 100 MB), headers `X-Review-Done` / `X-Review-Total`
  (decided / to decide, for the list): only by the key holding the checkout (`409` otherwise). It renews the lock;
  `checkin=1` ends it. Versions kept per session: the current one, the last 10 check-ins and the last 5 saves.
- `DELETE /reviews/<session>/checkout`: give up the checkout without saving (one's own; an admin or a key with `release`: anybody's).
- `GET /reviews/<session>/history` → `{schema: "poi-cloud-review-history/1", events: [{at, action, by, of, version,
  size, done, total, note}]}`, newest first: `checkout`, `save`, `checkin`, `gaveup`, `released`, `revoked`,
  `expired`, `restored`. `version` is set on saves still kept.
- `POST /reviews/<session>/restore` `{version}` (admin only): make a kept version the current review again (undo a
  check-in). `409` while checked out. Nothing is deleted, so a restore can be undone the same way.
- Revoking a key ends its checkouts at once.

### Conflicts (two reviewers changed the same session)

A reviewer whose checkout ended (released, expired) keeps their unsaved changes in the browser. If somebody else
saved the session meanwhile, "Check out again, keep my changes" does not overwrite that save: the web app (web/src/
merge.ts) merges place by place against the review the reviewer's work started from (the version loaded or last
saved, kept in the browser): a place changed on one side only takes that change, a place changed the same way on both
is kept, and a place both changed differently is a **conflict** shown side by side ("Yours" / "Theirs", with
decision, edited fields, comment and who). The reviewer keeps one per place (or all mine / all theirs); Save and
Check in wait until every conflict is decided. The server is unchanged: it stores whole reviews and their versions,
so either side stays restorable from the history.

### Processing (who is working on a raw ride)

So the reviewers see that a waiting ride is being worked on, and two agents do not process the same one:

- `GET /processing` (read or process) → `{schema: "poi-cloud-processing/1", processing: [{sessionId, by: {id, name},
  at, until}]}`: the rides being processed now.
- `POST /processing/<sessionId>` (process): claim it, or renew one's own claim (`until` = now + 12 h); `409` with the
  holder's claim when another key has it. `cloud.py pull` claims a raw ride before downloading it.
- `DELETE /processing/<sessionId>`: release (one's own, or any with `release` / as an admin); `404` when nobody has it.
- A claim ends by itself when a complete `processed-result` with that `sessionId` (or `parentId`) is uploaded after
  it, or 12 h after its last renewal. The web list shows "⚙ <name> is processing · since …" on the waiting ride.

### Direct uploads (presigned R2 parts)

From Vietnam, uploads to Cloudflare's customer addresses (our Worker, on workers.dev or our own domain, and Pages)
crawl, before the Worker even runs: measured 2026-11-22, 0.01–0.08 MB/s per connection, while R2's own S3 endpoint
takes 1.1–1.5 MB/s per connection and 3.3 MB/s with four. So the phone sends its session zip straight to R2, and the
Worker only says yes and joins the parts. The app uploads only this way; tus stays for the web app, `cloud.py`
and `server.mjs` (small processed results).

- `POST /direct` (scope `upload`, or `process` for a processed-result), JSON `{size, metadata: {name: string}}` →
  `201 {schema: "poi-cloud-direct/1", id, location, size, partSize, parts, urls: [{part, url}], expiresAt}`. The Worker
  opens an R2 multipart upload; every part is `partSize` (8 MiB) except the last. `location` is the upload at the tus
  endpoint: HEAD (Upload-Offset = size once complete), DELETE and download work there as for any upload, and it is in
  `index.json` (unfinished until complete).
- `PUT <url>` with the part's bytes, straight to R2 (no Authorization header: the presigned URL is the permission;
  valid 6 h). R2 answers with the part's `ETag`. Parts may go in any order and several at once.
- `POST /direct/<id>/urls` `{parts: [n, …]}` → fresh URLs (up to 1000 at once), e.g. after a restart.
- `POST /direct/<id>/complete` `{parts: [{part, etag}, …]}`: every part, in order. `200 {id, location, size, complete:
  true}`, also when repeated after completing. `409` when R2 lacks a part or the sizes do not add up (start again).
- Only the token that opened it (or an admin) may continue or complete it. A tus `PATCH` to it is refused (400). An
  unfinished one is removed by the daily cleanup like any unfinished upload (its R2 parts too).
- `501` when the server has no R2 keys: clients fall back to tus. Setup: create an R2 API token with **Object Read &
  Write** on the bucket, then in `server/worker`: `npx wrangler secret put R2_ACCESS_KEY_ID` and
  `npx wrangler secret put R2_SECRET_ACCESS_KEY` (`R2_ACCOUNT_ID` and `R2_BUCKET` are vars in `wrangler.jsonc`).

### Tokens and permissions

Every request carries `Authorization: Bearer <token>` (CORS preflights excepted). A token has a set of independent
**permissions** (scopes), ticked by the admin when issuing it and changeable later:

| Scope | Label | Allows |
|---|---|---|
| `upload` | Send rides | create (`POST`), `HEAD`, `PATCH` uploads of any type except `processed-result` (phones: `raw-session`) |
| `read` | View & download | `GET <BASE>/index.json`, `GET <BASE>/<id>` (every upload) |
| `process` | Process rides | list and download (as `read`), and create / `HEAD` / `PATCH` `processed-result` uploads (the agent pipeline, `cloud.py`) |
| `delete` | Delete uploads | `DELETE <BASE>/<id>` of any upload, finished ones included (R2 object and row) |
| `release` | Release others' checkouts | end another key's review checkout (`DELETE /reviews/<s>/checkout`) or processing claim (`DELETE /processing/<s>`): a lead reviewer, without the admin token |

Any valid token may `GET /me`, send `OPTIONS`, and `DELETE` (tus termination) its **own unfinished** uploads. `HEAD` /
`PATCH` need the scope that allows creating that upload's type. Managing tokens (`/admin/...`) is the admin password's
alone and is never a scope. Presets in the admin page: Phone = `upload`; Reviewer = `read`; Agent = `process`.

Where the token comes from:
- `ADMIN_TOKEN` (secret, `npx wrangler secret put ADMIN_TOKEN`): id `admin`, name `Admin`, every scope plus `/admin/...`.
  Never on a phone.
- Personal tokens issued by an admin (below), stored in the Durable Object as a SHA-256 hash only: the secret is shown
  once, when issued. A revoked token gets 401 at once. Tokens issued before scopes existed: role `uploader` → `upload`;
  `reviewer` → `upload`, `read`, `process`.
- With no `ADMIN_TOKEN` and no personal token issued, the server is open (local tests only).

A wrong or revoked token gets `401`; a valid token without the scope gets `403 Forbidden`.

**Attribution.** Each upload records the token that created it; `index.json` entries get
`"by": {"id": "<token id>", "name": "<token name>"}` (`null` for uploads made before this).

**Last seen.** For every token (personal, `admin`) the Worker keeps **one** record of its latest request,
overwritten each time (at most once a minute, or at once when the client or the city changes). No IP address and no
history is kept:

`Seen = {"at": ISO, "country": "VN", "region": "Ho Chi Minh", "city": "Thu Duc", "network": "Viettel Group",
"lat": 10.85, "lon": 106.77, "client": "POI Capture/0.16.0 (Android 14; samsung SM-A546E)", "userAgent": "...",
"prevClient": "..." | null, "prevAt": ISO | null}`

- `country` … `lon` come from Cloudflare's IP lookup (`request.cf`): approximate, city level at best, often the
  carrier's hub on mobile data. `network` is the ISP / carrier (`asOrganization`).
- `client` is the `X-Client` request header (each client names itself; at most 120 characters), else null;
  `userAgent` the `User-Agent` (at most 200). Clients send `X-Client`:
  phone `POI Capture/<versionName> (Android <release>; <manufacturer> <model>)`, web
  `POI Capture Reviewer/<version> (<browser summary>)`, cloud.py `cloud.py/1`.
- `prevClient` / `prevAt`: the previous different client and when it was last seen. The admin page flags a token used
  from two different clients within 7 days (a copied token, or a new phone).

`GET /admin/tokens` adds `"seen": Seen | null` to each Token, and a list of the built-in identities:
`"builtins": [{"id": "admin", "name", "scopes", "role", "seen": Seen | null}]`.

**Trip metadata.** A phone's `raw-session` upload carries, besides `filename`, `type`, `sessionId`, `name`, …:
`track` (the ride as a Google encoded polyline, precision 5, simplified so it stays under 2,000 characters),
`startedAt`, `endedAt` (ISO), `distanceM`, `photos` (count). All optional: older uploads have none. `index.json`
returns them in `metadata`, so the admin map draws trips without downloading the zips.

**`GET /me`** (any valid token): `{"schema": "poi-cloud-me/1", "id", "name", "scopes": ["upload", ...], "admin": bool,
"role": "admin" | "uploader" | "reviewer" | "agent" | "custom"}` (`role`: a summary of the scopes for older clients:
exactly `upload` → uploader, has `read` → reviewer, has `process` → agent). Used by the admin page and the phone's
connection test.

**Admin requests** (admin password only, JSON bodies, `Content-Type: application/json`):

- `GET /admin/tokens` → `{"schema": "poi-cloud-tokens/1", "tokens": [Token...], "builtins": [...]}`, newest first,
  revoked ones included.
  `Token = {"id", "name", "scopes": [...], "role": <summary as in /me>, "prefix", "createdAt", "lastUsedAt": ISO | null,
  "revokedAt": ISO | null, "uploads": n, "seen": Seen | null}`. `prefix`: the first 12 characters of the secret
  (`pct_` + 8), to tell tokens apart; `lastUsedAt` is updated at most every 10 minutes.
- `POST /admin/tokens` with `{"name": "Sơn – phone", "scopes": ["upload"]}` (name 1–80 characters; at least one known
  scope; `{"role": "uploader" | "reviewer"}` is still accepted and mapped as above) → `201 {"token": Token, "secret":
  "pct_<43 base64url chars>"}`. The only time the secret is returned.
- `PATCH /admin/tokens/<id>` with `{"scopes": [...]}` (at least one) and/or `{"name": "..."}` → `200 {"token": Token}`;
  takes effect on the token's next request. `404` unknown, `409` revoked.
- `DELETE /admin/tokens/<id>` → `204` (revoked: kept in the list with `revokedAt`, for attribution), `404` unknown.
- `DELETE <BASE>/<upload id>` with `Tus-Resumable: 1.0.0` → `204`: removes any upload, finished or not (admin, or a
  token with `delete`).

**Setup QR / link.** A token is handed to a phone as one string, shown as a QR code by the web app:
`<tus endpoint>#token=<secret>&name=<url-encoded token name>`, e.g.
`https://poi-uploader.4hum.ai/files/#token=pct_…&name=S%C6%A1n%20%E2%80%93%20phone`. The fragment is
never sent to a server. The phone splits it: the part before `#` is the server address, `token` the access token.

### Deploy

From `server/worker/` (wrangler is a devDependency; log in once with `npx wrangler login`):

```sh
npm install
npx wrangler r2 bucket create poi-capture-uploads
npx wrangler deploy
npx wrangler secret put ADMIN_TOKEN    # paste a long random string: the admin password (Admin tab, deletes)
```

Without `ADMIN_TOKEN` or any issued token the server is open to anyone who knows the URL: always set
`ADMIN_TOKEN`. (A first deploy can also take secrets from a file: `npx wrangler deploy --secrets-file secrets.json`,
kept outside the repo.) No secret is in the repository. The admin password stays with whoever runs the server (a
password manager); everyone else gets a personal token from the reviewer's Admin tab (phones scan its QR code), and a
lost phone is cut off by revoking its token. Rotate the admin password with `secret put ADMIN_TOKEN` again.

`vars` in `wrangler.jsonc`:

| Variable | Default | |
|---|---|---|
| `CORS_ORIGINS` | the reviewer's origins | comma-separated; `*` for any; `https://*.example.dev` matches subdomains. Set to `http://localhost:5173`, `http://localhost:5199`, `https://poi-capture-reviewer.pages.dev` and its preview subdomains |
| `MAX_SIZE` | 4 GiB | largest upload (`Tus-Max-Size`); bigger creations get 413 |
| `EXPIRE_DAYS` | 7 | unfinished uploads untouched this long are removed by the daily cron |
| `BASE_PATH` | `/files` | |

Local: `npx wrangler dev` (local R2 and Durable Object under `.wrangler/`; put `TOKEN=...` in a git-ignored
`.dev.vars`). Tests: `npm test` (vitest inside workerd: auth, CORS, phone-style override PATCHes, uneven chunks
across part boundaries, creation-with-upload, offsets, leases, termination, expiry), `npm run typecheck`.

### Limits and cost

- One PATCH body is at most 100 MB (Cloudflare's request limit on the free and pro plans); the clients send 2 and
  8 MiB. Uploads are limited by `MAX_SIZE`, and by R2's 10,000 parts (80 GB at 8 MiB).
- `Python-urllib`'s default User-Agent is refused at the workers.dev edge (error 1010), so `cloud.py` sends its own.
- Free plan: Workers 100,000 requests a day; R2 10 GB stored, 1 M writes and 10 M reads a month, no egress fees;
  SQLite Durable Objects are included. A 100 MB session from the phone is about 50 PATCH requests, ~13 R2 parts and
  ~50 small carry writes. Beyond the free tier R2 is about $0.015 per GB-month.
- Finished uploads are deleted by an admin: `DELETE <BASE>/<id>` with the admin token (the web app's Admin tab,
  or `cloud.py delete <id>`).
