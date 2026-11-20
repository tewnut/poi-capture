// POI Capture cloud as a Cloudflare Worker: the same contract as ../server.mjs (tus 1.0 + index.json + download),
// with the bytes in R2 and the upload state in one SQLite-backed Durable Object. See ../README.md, "Cloudflare Worker".
//
//   OPTIONS/POST/HEAD/PATCH/DELETE <BASE>/...   tus core + creation + creation-with-upload + termination + expiration
//   GET <BASE>/<id>                             download a finished upload
//   GET <BASE>/index.json[?type=...]            {schema: "poi-cloud-index/1", uploads: [...]}, newest first
//
// Chunks: R2 multipart parts must all be the same size (>= 5 MiB) except the last, while tus PATCHes come in any size
// (the phone sends 2 MB, cloud.py 8 MiB). Each PATCH is streamed into PART_SIZE buffers; every full buffer becomes a
// part, what is left over (the "carry", < PART_SIZE) is kept as a temporary R2 object and put in front of the next
// PATCH's bytes. The last PATCH uploads the remainder as the last part and completes the multipart upload (an upload
// that never fills one part is written with one put). A PATCH holds a lease on its upload in the Durable Object, so
// two PATCHes to one upload never interleave (the second gets 423), and its result is committed at the end in one
// step: a PATCH that fails half way leaves the upload at its old offset, and the client resends from there.
import { DurableObject } from 'cloudflare:workers'
import { DEFAULT_MAX_SIZE, EXTENSIONS, PART_SIZE, TUS_VERSION, corsOrigin, encodeMetadata, parseMetadata } from './tus'

export interface Env {
  BUCKET: R2Bucket
  UPLOADS: DurableObjectNamespace<Uploads>
  TOKEN?: string
  CORS_ORIGINS?: string
  MAX_SIZE?: string
  BASE_PATH?: string
  EXPIRE_DAYS?: string
}

const LEASE_MS = 5 * 60 * 1000                  // a PATCH that has not finished in 5 min loses its lease
const MAX_METADATA = 8 * 1024                   // Upload-Metadata header, bytes

const TUS_HEADERS = ['Authorization', 'Content-Type', 'Location', 'Tus-Extension', 'Tus-Max-Size', 'Tus-Resumable',
  'Tus-Version', 'Upload-Concat', 'Upload-Defer-Length', 'Upload-Expires', 'Upload-Length', 'Upload-Metadata',
  'Upload-Offset', 'X-HTTP-Method-Override', 'X-Requested-With']

// ------------------------------------------------------------------------------------------------ state (DO)

export type Part = { partNumber: number; etag: string }
export type UploadState = {
  id: string
  size: number
  offset: number
  metadata: Record<string, string>
  createdAt: string
  updatedAt: number
  mpId: string | null       // R2 multipart upload id, once the first full part was written
  parts: Part[]
  carryKey: string | null   // R2 key of the bytes after the last full part
  carryLen: number
  complete: boolean
}
export type Begin = { ok: true; state: UploadState; lease: string } | { ok: false; status: 404 | 409 | 423 | 400; state?: UploadState }
export type Commit = Pick<UploadState, 'offset' | 'mpId' | 'parts' | 'carryKey' | 'carryLen' | 'complete'>

type Row = Record<string, SqlStorageValue>

function fromRow(r: Row): UploadState {
  return {
    id: String(r.id), size: Number(r.size), offset: Number(r.offset), metadata: JSON.parse(String(r.metadata)),
    createdAt: String(r.created_at), updatedAt: Number(r.updated_at), mpId: (r.mp_id as string | null) ?? null,
    parts: JSON.parse(String(r.parts)), carryKey: (r.carry_key as string | null) ?? null, carryLen: Number(r.carry_len),
    complete: Number(r.complete) === 1,
  }
}

/** Every upload's state, in one Durable Object (one instance, "uploads"): small rows, no bytes. */
export class Uploads extends DurableObject<Env> {
  private sql: SqlStorage

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env)
    this.sql = ctx.storage.sql
    this.sql.exec(`CREATE TABLE IF NOT EXISTS uploads (
      id TEXT PRIMARY KEY, size INTEGER NOT NULL, offset INTEGER NOT NULL, metadata TEXT NOT NULL,
      created_at TEXT NOT NULL, updated_at INTEGER NOT NULL, mp_id TEXT, parts TEXT NOT NULL DEFAULT '[]',
      carry_key TEXT, carry_len INTEGER NOT NULL DEFAULT 0, complete INTEGER NOT NULL DEFAULT 0,
      lease TEXT, lease_until INTEGER NOT NULL DEFAULT 0)`)
  }

  private row(id: string): Row | null {
    return this.sql.exec('SELECT * FROM uploads WHERE id = ?', id).toArray()[0] ?? null
  }

  create(s: UploadState): void {
    this.sql.exec(`INSERT INTO uploads (id, size, offset, metadata, created_at, updated_at, parts, complete)
      VALUES (?, ?, ?, ?, ?, ?, '[]', ?)`, s.id, s.size, s.offset, JSON.stringify(s.metadata), s.createdAt,
      s.updatedAt, s.complete ? 1 : 0)
  }

  get(id: string): UploadState | null {
    const r = this.row(id)
    return r ? fromRow(r) : null
  }

  /** Lease the upload for one PATCH at [offset]: 404 unknown, 400 finished, 409 wrong offset, 423 another PATCH runs. */
  begin(id: string, offset: number): Begin {
    const r = this.row(id)
    if (!r) return { ok: false, status: 404 }
    const s = fromRow(r)
    if (s.complete) return { ok: false, status: 400, state: s }
    const now = Date.now()
    if (r.lease && Number(r.lease_until) > now) return { ok: false, status: 423, state: s }
    if (s.offset !== offset) return { ok: false, status: 409, state: s }
    const lease = crypto.randomUUID()
    this.sql.exec('UPDATE uploads SET lease = ?, lease_until = ? WHERE id = ?', lease, now + LEASE_MS, id)
    return { ok: true, state: s, lease }
  }

  /** The PATCH's result, all at once; false when its lease was lost (expired and taken by another PATCH). */
  commit(id: string, lease: string, c: Commit): boolean {
    const r = this.row(id)
    if (!r || r.lease !== lease) return false
    this.sql.exec(`UPDATE uploads SET offset = ?, mp_id = ?, parts = ?, carry_key = ?, carry_len = ?, complete = ?,
      updated_at = ?, lease = NULL, lease_until = 0 WHERE id = ?`, c.offset, c.mpId, JSON.stringify(c.parts),
      c.carryKey, c.carryLen, c.complete ? 1 : 0, Date.now(), id)
    return true
  }

  release(id: string, lease: string): void {
    this.sql.exec('UPDATE uploads SET lease = NULL, lease_until = 0 WHERE id = ? AND lease = ?', id, lease)
  }

  /** Termination: forget an unfinished upload no PATCH is writing; the caller frees its R2 objects. */
  terminate(id: string): { status: 204 | 404 | 400 | 423; state?: UploadState } {
    const r = this.row(id)
    if (!r) return { status: 404 }
    const s = fromRow(r)
    if (s.complete) return { status: 400 }
    if (r.lease && Number(r.lease_until) > Date.now()) return { status: 423 }
    this.sql.exec('DELETE FROM uploads WHERE id = ?', id)
    return { status: 204, state: s }
  }

  list(type: string): UploadState[] {
    return this.sql.exec('SELECT * FROM uploads ORDER BY created_at DESC').toArray().map(fromRow)
      .filter((s) => !type || s.metadata.type === type)
  }

  /** Unfinished uploads untouched since [before] (ms) and not being written: removed, returned for R2 cleanup. */
  expire(before: number): UploadState[] {
    const rows = this.sql.exec('SELECT * FROM uploads WHERE complete = 0 AND updated_at < ? AND lease_until < ?',
      before, Date.now()).toArray().map(fromRow)
    for (const s of rows) this.sql.exec('DELETE FROM uploads WHERE id = ?', s.id)
    return rows
  }
}

// ------------------------------------------------------------------------------------------------ helpers

async function authorized(env: Env, req: Request): Promise<boolean> {
  if (!env.TOKEN) return true
  const given = (req.headers.get('Authorization') || '').replace(/^Bearer\s+/i, '')
  const enc = new TextEncoder()
  // digests first: equal lengths for timingSafeEqual, and no early exit on the length of the token
  const [a, b] = await Promise.all([crypto.subtle.digest('SHA-256', enc.encode(given)), crypto.subtle.digest('SHA-256', enc.encode(env.TOKEN))])
  return crypto.subtle.timingSafeEqual(a, b)
}

const dataKey = (id: string) => `files/${id}`
const isId = (s: string) => /^[A-Za-z0-9_-]{1,64}$/.test(s)
const maxSize = (env: Env) => (env.MAX_SIZE ? Number(env.MAX_SIZE) : DEFAULT_MAX_SIZE)
const expireMs = (env: Env) => Number(env.EXPIRE_DAYS || 7) * 86400_000

function stub(env: Env) {
  return env.UPLOADS.get(env.UPLOADS.idFromName('uploads'))
}

function text(status: number, body: string, extra: Record<string, string> = {}): Response {
  return new Response(status === 204 ? null : body, {
    status, headers: { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store', ...extra },
  })
}

function tusHeaders(env: Env, s: UploadState): Record<string, string> {
  const h: Record<string, string> = {
    'Tus-Resumable': TUS_VERSION, 'Upload-Offset': String(s.offset), 'Upload-Length': String(s.size),
    'Cache-Control': 'no-store',
  }
  if (Object.keys(s.metadata).length) h['Upload-Metadata'] = encodeMetadata(s.metadata)
  if (!s.complete) h['Upload-Expires'] = new Date(s.updatedAt + expireMs(env)).toUTCString()
  return h
}

// ------------------------------------------------------------------------------------------------ PATCH

class TusError extends Error {
  constructor(public status: number, message: string) { super(message) }
}

/**
 * Append [body] at [offset] (already checked against the state by begin()); returns the new offset. All R2 writes
 * happen before the commit; nothing is committed when the body breaks off, so the offset stays where it was.
 */
async function append(env: Env, ctx: ExecutionContext, id: string, offset: number, body: ReadableStream<Uint8Array> | null): Promise<UploadState> {
  const uploads = stub(env)
  const b = await uploads.begin(id, offset)
  if (!b.ok) {
    if (b.status === 404) throw new TusError(404, 'Not found')
    if (b.status === 400) throw new TusError(400, 'Upload already finished')
    if (b.status === 423) throw new TusError(423, 'Another PATCH is writing this upload')
    throw new TusError(409, `Upload-Offset is ${b.state!.offset}`)
  }
  const s = b.state
  const lease = b.lease
  let newCarryKey: string | null = null
  try {
    let buf = new Uint8Array(PART_SIZE)
    let filled = 0
    if (s.carryLen > 0 && s.carryKey) {
      const obj = await env.BUCKET.get(s.carryKey)
      if (!obj) throw new Error('carry object missing')
      const carry = new Uint8Array(await obj.arrayBuffer())
      if (carry.length !== s.carryLen) throw new Error('carry object has the wrong size')
      buf.set(carry)
      filled = carry.length
    }
    let mpId = s.mpId
    let mp: R2MultipartUpload | null = mpId ? env.BUCKET.resumeMultipartUpload(dataKey(id), mpId) : null
    const parts = [...s.parts]
    const customMetadata = { id, filename: s.metadata.filename || id }
    const flush = async (bytes: Uint8Array) => {
      if (!mp) {
        mp = await env.BUCKET.createMultipartUpload(dataKey(id), { customMetadata, httpMetadata: { contentType: 'application/octet-stream' } })
        mpId = mp.uploadId
      }
      const p = await mp.uploadPart(parts.length + 1, bytes)
      parts.push({ partNumber: p.partNumber, etag: p.etag })
    }

    let received = 0
    if (body) {
      const reader = body.getReader()
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        if (offset + received + value.length > s.size) {
          await reader.cancel().catch(() => {})
          throw new TusError(413, 'Body goes past Upload-Length')
        }
        received += value.length
        let at = 0
        while (at < value.length) {
          const n = Math.min(PART_SIZE - filled, value.length - at)
          buf.set(value.subarray(at, at + n), filled)
          filled += n
          at += n
          if (filled === PART_SIZE) {
            await flush(buf)
            buf = new Uint8Array(PART_SIZE)
            filled = 0
          }
        }
      }
    }

    const newOffset = offset + received
    const complete = newOffset === s.size
    if (complete) {
      if (mp) {
        const m: R2MultipartUpload = mp
        if (filled > 0) await flush(buf.subarray(0, filled))
        try {
          await m.complete(parts)
        } catch (e) {
          // completed by an earlier try whose commit did not land: the object is there in full
          const head = await env.BUCKET.head(dataKey(id))
          if (!head || head.size !== s.size) throw e
        }
      } else {
        await env.BUCKET.put(dataKey(id), buf.subarray(0, filled), { customMetadata, httpMetadata: { contentType: 'application/octet-stream' } })
      }
    } else if (filled > 0) {
      newCarryKey = `carry/${id}/${lease}`
      await env.BUCKET.put(newCarryKey, buf.subarray(0, filled))
    }
    const c: Commit = { offset: newOffset, mpId, parts, carryKey: complete ? null : newCarryKey, carryLen: complete ? 0 : filled, complete }
    if (!(await uploads.commit(id, lease, c))) {
      if (newCarryKey) ctx.waitUntil(env.BUCKET.delete(newCarryKey))
      throw new TusError(409, 'Lost the lease on this upload (PATCH too slow); HEAD and resend')
    }
    if (s.carryKey && s.carryKey !== c.carryKey) ctx.waitUntil(env.BUCKET.delete(s.carryKey))
    return { ...s, ...c, updatedAt: Date.now() }
  } catch (e) {
    ctx.waitUntil(uploads.release(id, lease))
    if (newCarryKey) ctx.waitUntil(env.BUCKET.delete(newCarryKey))
    throw e
  }
}

// ------------------------------------------------------------------------------------------------ requests

async function create(env: Env, ctx: ExecutionContext, req: Request, base: string): Promise<Response> {
  if (req.headers.get('Upload-Defer-Length') || req.headers.get('Upload-Concat')) return text(501, 'Not supported\n')
  const lenH = req.headers.get('Upload-Length')
  if (!lenH || !/^\d+$/.test(lenH)) return text(400, 'Upload-Length required\n')
  const size = Number(lenH)
  if (size > maxSize(env)) return text(413, 'Upload too large\n')
  const metaH = req.headers.get('Upload-Metadata')
  if (metaH && metaH.length > MAX_METADATA) return text(400, 'Upload-Metadata too long\n')
  const metadata = parseMetadata(metaH)
  if (!metadata) return text(400, 'Bad Upload-Metadata\n')
  const id = crypto.randomUUID().replace(/-/g, '')
  const now = Date.now()
  const s: UploadState = { id, size, offset: 0, metadata, createdAt: new Date(now).toISOString(), updatedAt: now,
    mpId: null, parts: [], carryKey: null, carryLen: 0, complete: size === 0 }
  if (size === 0) {
    await env.BUCKET.put(dataKey(id), new Uint8Array(0), { customMetadata: { id, filename: metadata.filename || id } })
  }
  await stub(env).create(s)
  console.log(`created ${id} (${size} B) ${metadata.type || ''} ${metadata.filename || ''}`)
  const location = `${new URL(req.url).origin}${base}/${id}`
  const h: Record<string, string> = { Location: location, 'Tus-Resumable': TUS_VERSION, 'Cache-Control': 'no-store' }
  // creation-with-upload: the first bytes in the POST itself
  if (size > 0 && req.body && (req.headers.get('Content-Type') || '').startsWith('application/offset+octet-stream')) {
    try {
      const after = await append(env, ctx, id, 0, req.body)
      h['Upload-Offset'] = String(after.offset)
    } catch (e) {
      if (!(e instanceof TusError)) console.error('creation-with-upload failed:', id, (e as Error)?.message)
      h['Upload-Offset'] = '0'
    }
  } else {
    h['Upload-Offset'] = '0'
  }
  return new Response(null, { status: 201, headers: h })
}

async function patch(env: Env, ctx: ExecutionContext, req: Request, id: string): Promise<Response> {
  if (!(req.headers.get('Content-Type') || '').startsWith('application/offset+octet-stream')) return text(415, 'Content-Type must be application/offset+octet-stream\n')
  const offH = req.headers.get('Upload-Offset')
  if (!offH || !/^\d+$/.test(offH)) return text(400, 'Upload-Offset required\n')
  try {
    const s = await append(env, ctx, id, Number(offH), req.body)
    if (s.complete) console.log(`finished ${id}`)
    return new Response(null, { status: 204, headers: tusHeaders(env, s) })
  } catch (e) {
    if (e instanceof TusError) return text(e.status, e.message + '\n', { 'Tus-Resumable': TUS_VERSION })
    throw e
  }
}

async function head(env: Env, id: string): Promise<Response> {
  const s = await stub(env).get(id)
  if (!s) return new Response(null, { status: 404, headers: { 'Tus-Resumable': TUS_VERSION, 'Cache-Control': 'no-store' } })
  return new Response(null, { status: 200, headers: tusHeaders(env, s) })
}

async function terminate(env: Env, id: string): Promise<Response> {
  const r = await stub(env).terminate(id)
  if (r.status !== 204) return text(r.status, r.status === 400 ? 'A finished upload cannot be deleted\n' : 'Not found\n', { 'Tus-Resumable': TUS_VERSION })
  await freeR2(env, r.state!)
  return new Response(null, { status: 204, headers: { 'Tus-Resumable': TUS_VERSION } })
}

async function freeR2(env: Env, s: UploadState) {
  if (s.mpId) await env.BUCKET.resumeMultipartUpload(dataKey(s.id), s.mpId).abort().catch(() => {})
  if (s.carryKey) await env.BUCKET.delete(s.carryKey)
}

async function index(env: Env, type: string): Promise<Response> {
  const list = await stub(env).list(type)
  const uploads = list.map((s) => ({ id: s.id, size: s.size, offset: s.offset, complete: s.complete, createdAt: s.createdAt, metadata: s.metadata }))
  return new Response(JSON.stringify({ schema: 'poi-cloud-index/1', uploads }), {
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' },
  })
}

async function download(env: Env, id: string): Promise<Response> {
  if (!isId(id)) return text(404, 'Not found\n')
  const obj = await env.BUCKET.get(dataKey(id))
  if (!obj) {
    const s = await stub(env).get(id)
    return text(404, s ? 'Upload not finished\n' : 'Not found\n')
  }
  const name = String(obj.customMetadata?.filename || id).replace(/[^\w.\-]+/g, '_')
  return new Response(obj.body, {
    headers: {
      'Content-Type': 'application/octet-stream',   // never rendered by the browser, whatever was uploaded
      'Content-Length': String(obj.size),
      'Content-Disposition': `attachment; filename="${name}"`,
      'X-Content-Type-Options': 'nosniff',
      'Cache-Control': 'no-store',
    },
  })
}

async function route(req: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const url = new URL(req.url)
  const base = '/' + (env.BASE_PATH || '/files').replace(/^\/+|\/+$/g, '')
  // CORS preflight from the reviewer's browser: answered without the token (the real request carries it)
  if (req.method === 'OPTIONS' && req.headers.get('Access-Control-Request-Method')) {
    return new Response(null, {
      status: 204,
      headers: {
        'Access-Control-Allow-Methods': 'POST, HEAD, PATCH, OPTIONS, DELETE, GET',
        'Access-Control-Allow-Headers': TUS_HEADERS.join(', '),
        'Access-Control-Max-Age': '86400',
      },
    })
  }
  if (url.pathname === '/' || url.pathname === '/health') return text(200, `POI Capture cloud: tus at ${base}/\n`)
  if (url.pathname !== base && !url.pathname.startsWith(base + '/')) return text(404, 'Not found\n')
  if (!(await authorized(env, req))) return text(401, 'Unauthorized\n', { 'WWW-Authenticate': 'Bearer' })

  const rest = url.pathname.slice(base.length).replace(/^\/+/, '')
  let method = req.method
  // tus clients without PATCH (Android's HttpURLConnection) send POST + X-HTTP-Method-Override, as tusd accepts
  const override = (req.headers.get('X-HTTP-Method-Override') || '').toUpperCase()
  if (method === 'POST' && ['PATCH', 'HEAD', 'DELETE'].includes(override)) method = override

  if (method === 'GET' && rest === 'index.json') return index(env, url.searchParams.get('type') || '')
  if (method === 'GET' && rest) return download(env, rest)
  if (method === 'OPTIONS') {
    return new Response(null, {
      status: 204,
      headers: { 'Tus-Resumable': TUS_VERSION, 'Tus-Version': TUS_VERSION, 'Tus-Extension': EXTENSIONS, 'Tus-Max-Size': String(maxSize(env)) },
    })
  }
  const tr = req.headers.get('Tus-Resumable')
  if (tr !== TUS_VERSION) return text(412, 'Tus-Resumable 1.0.0 required\n', { 'Tus-Version': TUS_VERSION })
  if (method === 'POST' && !rest) return create(env, ctx, req, base)
  if (!rest || !isId(rest)) return text(404, 'Not found\n')
  if (method === 'HEAD') return head(env, rest)
  if (method === 'PATCH') return patch(env, ctx, req, rest)
  if (method === 'DELETE') return terminate(env, rest)
  return text(405, 'Method not allowed\n')
}

export default {
  async fetch(req: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    let res: Response
    try {
      res = await route(req, env, ctx)
    } catch (e) {
      console.error('request failed:', req.method, new URL(req.url).pathname, (e as Error)?.message || e)
      res = text(500, 'Server error\n')
    }
    const o = corsOrigin(env.CORS_ORIGINS, req.headers.get('Origin'))
    if (o) {
      res = new Response(res.body, res)
      res.headers.set('Access-Control-Allow-Origin', o)
      if (o !== '*') res.headers.append('Vary', 'Origin')
      res.headers.set('Access-Control-Expose-Headers', [...TUS_HEADERS, 'Content-Disposition', 'Content-Length'].join(', '))
    }
    return res
  },

  /** Daily: unfinished uploads untouched for EXPIRE_DAYS are removed with their R2 parts (tus expiration). */
  async scheduled(_c: ScheduledController, env: Env): Promise<void> {
    const gone = await stub(env).expire(Date.now() - expireMs(env))
    for (const s of gone) await freeR2(env, s)
    if (gone.length) console.log(`expired ${gone.length} unfinished upload(s)`)
  },
} satisfies ExportedHandler<Env>
