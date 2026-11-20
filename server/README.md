# POI Capture cloud (tus server)

A small self-hosted server the three stages share instead of passing zips around by hand:

```
phone ── raw session zip (type=raw-session) ──►  server  ◄── agent pulls it, pushes the processed zip back
                                                  │          (type=processed-result, cloud.py)
                                                  └──► reviewer web app lists and pulls processed zips
```

Two implementations of the same contract:

- **Cloudflare Worker** (`worker/`, below): bytes in R2, state in a Durable Object. This is the team's server,
  deployed at **`https://poi-capture-cloud.4hum-ai.workers.dev/files/`** and preset in the web app.
- **Node** (`server.mjs`, this folder): [`@tus/server`](https://github.com/tus/tus-node-server) with files on disk
  (`@tus/file-store`), for running it yourself.

Both speak [tus](https://tus.io/protocols/resumable-upload) 1.0 (core, creation, termination, expiration), plus:

| Request | What |
|---|---|
| `GET /files/index.json[?type=raw-session]` | every stored upload: `{schema: "poi-cloud-index/1", uploads: [{id, size, offset, complete, createdAt, metadata}]}`, newest first |
| `GET /files/<id>` | download a finished upload (always `application/octet-stream`, attachment) |
| `POST` + `X-HTTP-Method-Override: PATCH` | accepted as a PATCH (Android's HTTP client has no PATCH; tusd does this too) |

Upload metadata the clients send: `filename`, `filetype`, `type` (`raw-session` | `processed-result`), `sessionId`,
`name`, `kind` (`sweep` | `poi`), `createdAt`, and from the phone `photoCount`.

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

### Deploy

From `server/worker/` (wrangler is a devDependency; log in once with `npx wrangler login`):

```sh
npm install
npx wrangler r2 bucket create poi-capture-uploads
npx wrangler deploy
npx wrangler secret put TOKEN          # paste a long random string; every request then needs "Authorization: Bearer <it>"
```

Without `TOKEN` the server is open to anyone who knows the URL: always set it. (A first deploy can also take it
from a file: `npx wrangler deploy --secrets-file secrets.json`, with the file kept outside the repo.) The team's
token is not in the repository; whoever runs the server hands it out, and it is typed into the phone, the web app
and `POI_CLOUD_TOKEN` for the agent. Rotate it with `secret put TOKEN` again.

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
- Finished uploads cannot be deleted through tus. To clean up, delete in the Cloudflare dashboard (R2 →
  `poi-capture-uploads`, `files/<id>`); the index still lists the id until its row is removed (it then downloads
  as 404).
