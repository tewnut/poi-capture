// POI Capture cloud: a tus (resumable upload, tus.io v1.0) server plus one listing endpoint.
//
//   POST/HEAD/PATCH/DELETE/OPTIONS <BASE_PATH>/...   tus core + creation + termination (@tus/server, files on disk)
//   GET <BASE_PATH>/<id>                             download a finished upload
//   GET <BASE_PATH>/index.json[?type=...]            what is stored: id, size, offset, complete, createdAt, metadata
//
// The phone uploads raw sessions (metadata type=raw-session), the agent pulls them and pushes the processed zip back
// (type=processed-result), the reviewer's browser lists and pulls those. See README.md.
import http from 'node:http'
import crypto from 'node:crypto'
import path from 'node:path'
import fs from 'node:fs'
import { Server } from '@tus/server'
import { FileStore } from '@tus/file-store'

const env = process.env
const PORT = Number(env.PORT || 1080)
const HOST = env.HOST || '127.0.0.1'
const BASE = '/' + (env.BASE_PATH || '/files').replace(/^\/+|\/+$/g, '')   // no trailing slash
const DATA_DIR = path.resolve(env.DATA_DIR || './data')
const TOKEN = env.TOKEN || ''                       // optional bearer token; never logged
const ORIGINS = (env.CORS_ORIGINS || '*').split(',').map((s) => s.trim()).filter(Boolean)
const MAX_SIZE = env.MAX_SIZE ? Number(env.MAX_SIZE) : undefined
const TRUST_PROXY = env.TRUST_PROXY === '1'         // honour X-Forwarded-Proto / -Host in the Location header

const datastore = new FileStore({ directory: DATA_DIR })
const tus = new Server({
  path: BASE,
  datastore,
  maxSize: MAX_SIZE,
  respectForwardedHeaders: TRUST_PROXY,
  allowedOrigins: ORIGINS.includes('*') ? undefined : ORIGINS,
  // a finished upload is kept: the phone must not delete what the agent has not pulled yet
  disableTerminationForFinishedUploads: true,
})

const TUS_HEADERS = ['Authorization', 'Content-Type', 'Location', 'Tus-Extension', 'Tus-Max-Size', 'Tus-Resumable',
  'Tus-Version', 'Upload-Defer-Length', 'Upload-Length', 'Upload-Metadata', 'Upload-Offset',
  'X-Client', 'X-HTTP-Method-Override', 'X-Requested-With']

function corsOrigin(origin) {
  if (ORIGINS.includes('*')) return '*'
  return origin && ORIGINS.includes(origin) ? origin : null
}

function setCors(req, res) {
  const o = corsOrigin(req.headers.origin)
  if (!o) return
  res.setHeader('Access-Control-Allow-Origin', o)
  if (o !== '*') res.setHeader('Vary', 'Origin')
  res.setHeader('Access-Control-Expose-Headers', [...TUS_HEADERS, 'Content-Disposition', 'Content-Length'].join(', '))
}

function authorized(req) {
  if (!TOKEN) return true
  const h = req.headers.authorization || ''
  const given = Buffer.from(h.replace(/^Bearer\s+/i, ''))
  const want = Buffer.from(TOKEN)
  return given.length === want.length && crypto.timingSafeEqual(given, want)
}

function send(res, status, body, type = 'text/plain; charset=utf-8') {
  const b = typeof body === 'string' ? body : JSON.stringify(body)
  res.writeHead(status, { 'Content-Type': type, 'Content-Length': Buffer.byteLength(b), 'Cache-Control': 'no-store' })
  res.end(b)
}

/** Every stored upload with its info (offset read from the file, as tus HEAD would), newest first. */
async function index(type) {
  const ids = await datastore.configstore.list()
  const out = []
  for (const id of ids) {
    try {
      const u = await datastore.getUpload(id)
      const meta = u.metadata || {}
      if (type && meta.type !== type) continue
      out.push({ id: u.id, size: u.size ?? null, offset: u.offset, complete: u.size != null && u.offset === u.size,
        createdAt: u.creation_date || null, metadata: meta })
    } catch { /* half-written info file: skip */ }
  }
  out.sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)))
  return { schema: 'poi-cloud-index/1', uploads: out }
}

/**
 * GET of a finished upload, streamed from disk. Served here rather than by @tus/server (its GET goes through web
 * streams, which crashed Node 24 at the end of a download) so it also gets the CORS headers the browser needs.
 */
async function download(id, res) {
  if (!/^[A-Za-z0-9_-]+$/.test(id)) return send(res, 404, 'Not found\n')
  let u
  try { u = await datastore.getUpload(id) } catch { return send(res, 404, 'Not found\n') }
  if (u.size == null || u.offset !== u.size) return send(res, 404, 'Upload not finished\n')
  const name = String(u.metadata?.filename || id).replace(/[^\w.\-]+/g, '_')
  res.writeHead(200, {
    'Content-Type': 'application/octet-stream',   // never rendered by the browser, whatever was uploaded
    'Content-Length': String(u.size),
    'Content-Disposition': `attachment; filename="${name}"`,
    'X-Content-Type-Options': 'nosniff',
  })
  const stream = fs.createReadStream(path.join(DATA_DIR, id))
  stream.on('error', () => res.destroy())
  stream.pipe(res)
}

const httpServer = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://x')
    setCors(req, res)
    // CORS preflight from the reviewer's browser: answered here, no token needed (the real request carries it)
    if (req.method === 'OPTIONS' && req.headers['access-control-request-method']) {
      res.writeHead(204, {
        'Access-Control-Allow-Methods': 'POST, HEAD, PATCH, OPTIONS, DELETE, GET',
        'Access-Control-Allow-Headers': TUS_HEADERS.join(', '),
        'Access-Control-Max-Age': '86400',
      })
      return res.end()
    }
    if (url.pathname === '/' || url.pathname === '/health') return send(res, 200, 'POI Capture cloud: tus at ' + BASE + '/\n')
    if (!url.pathname.startsWith(BASE + '/') && url.pathname !== BASE) return send(res, 404, 'Not found\n')
    if (!authorized(req)) {
      res.setHeader('WWW-Authenticate', 'Bearer')
      return send(res, 401, 'Unauthorized\n')
    }
    if (req.method === 'GET' && url.pathname === BASE + '/index.json') {
      return send(res, 200, await index(url.searchParams.get('type') || ''), 'application/json')
    }
    // tus clients without PATCH (Android's HttpURLConnection) send POST + X-HTTP-Method-Override (tusd honours it,
    // @tus/server 2 no longer does)
    const override = String(req.headers['x-http-method-override'] || '').toUpperCase()
    if (req.method === 'POST' && ['PATCH', 'HEAD', 'DELETE'].includes(override)) req.method = override
    if (req.method === 'GET') return await download(url.pathname.slice(BASE.length + 1), res)
    return await tus.handle(req, res)
  } catch (e) {
    console.error('request failed:', req.method, req.url, e?.message || e)
    if (!res.headersSent) send(res, 500, 'Server error\n')
    else res.destroy()
  }
})

tus.on('POST_CREATE', (_req, upload) => console.log(`created ${upload.id} (${upload.size ?? '?'} B) ${upload.metadata?.type || ''} ${upload.metadata?.filename || ''}`))
tus.on('POST_FINISH', (_req, _res, upload) => console.log(`finished ${upload.id}`))

httpServer.listen(PORT, HOST, () => {
  console.log(`POI Capture cloud on http://${HOST}:${PORT}${BASE}/  data: ${DATA_DIR}  token: ${TOKEN ? 'required' : 'none (open!)'}`)
})
