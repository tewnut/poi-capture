// POI Capture cloud as a Cloudflare Worker: the same contract as ../server.mjs (tus 1.0 + index.json + download),
// with the bytes in R2 and the upload state in one SQLite-backed Durable Object. See ../README.md, "Cloudflare Worker".
//
//   OPTIONS/POST/HEAD/PATCH/DELETE <BASE>/...   tus core + creation + creation-with-upload + termination + expiration
//   GET <BASE>/<id>                             download a finished upload
//   GET <BASE>/index.json[?type=...]            {schema: "poi-cloud-index/1", uploads: [...]}, newest first
//   GET /me, GET|POST /admin/tokens, DELETE /admin/tokens/<id>   who am I; personal tokens (admin)
//
// Tokens and permissions (../README.md): ADMIN_TOKEN = the admin password, TOKEN = the old shared token, personal
// tokens issued by an admin with independent scopes (upload, read, process, delete), kept as SHA-256 hashes.
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
  ADMIN_TOKEN?: string
  CORS_ORIGINS?: string
  MAX_SIZE?: string
  BASE_PATH?: string
  EXPIRE_DAYS?: string
}

const LEASE_MS = 5 * 60 * 1000                  // a PATCH that has not finished in 5 min loses its lease
const MAX_METADATA = 8 * 1024                   // Upload-Metadata header, bytes
const TOUCH_MS = 10 * 60 * 1000                 // a token's lastUsedAt is written at most this often
const SEEN_MS = 60 * 1000                       // the last-seen record: at most once a minute unless client/city change

const TUS_HEADERS = ['Authorization', 'Content-Type', 'Location', 'Tus-Extension', 'Tus-Max-Size', 'Tus-Resumable',
  'Tus-Version', 'Upload-Concat', 'Upload-Defer-Length', 'Upload-Expires', 'Upload-Length', 'Upload-Metadata',
  'Upload-Offset', 'X-Client', 'X-HTTP-Method-Override', 'X-Requested-With']

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
  by?: By | null             // the token that created it
  concat?: Concat            // tus concatenation: a partial, or a final upload made of partials (ids, in order)
}
export type Concat = { partial: true } | { final: string[] } | null
export type By = { id: string; name: string }
export type Scope = 'upload' | 'read' | 'process' | 'delete'
const SCOPES: Scope[] = ['upload', 'read', 'process', 'delete']
/** A summary of the scopes for older clients (README, GET /me). */
export type Role = 'admin' | 'uploader' | 'reviewer' | 'agent' | 'custom'
export type Principal = { id: string; name: string; scopes: Scope[]; admin: boolean }
export type Token = { id: string; name: string; scopes: Scope[]; role: Role; prefix: string; createdAt: string; lastUsedAt: string | null; revokedAt: string | null; uploads: number; seen: Seen | null }
/** The latest request of a token (README "Last seen"): Cloudflare's approximate IP location, no IP, no history. */
export type Seen = { at: string; country: string | null; region: string | null; city: string | null; network: string | null;
  lat: number | null; lon: number | null; client: string | null; userAgent: string | null; prevClient: string | null; prevAt: string | null }
export type SeenInput = Omit<Seen, 'at' | 'prevClient' | 'prevAt'>
/** Tokens issued before scopes: their role column. */
const LEGACY: Record<string, Scope[]> = { uploader: ['upload'], reviewer: ['upload', 'read', 'process'] }

function roleOf(scopes: Scope[], admin = false): Role {
  if (admin) return 'admin'
  if (scopes.length === 1 && scopes[0] === 'upload') return 'uploader'
  if (scopes.includes('read')) return 'reviewer'
  if (scopes.includes('process')) return 'agent'
  return 'custom'
}

/** Known scopes, once each, in SCOPES order; null when none is valid or one is unknown. */
function cleanScopes(v: unknown): Scope[] | null {
  if (!Array.isArray(v) || !v.length || v.some((x) => !SCOPES.includes(x as Scope))) return null
  return SCOPES.filter((sc) => v.includes(sc))
}

function rowScopes(r: Row): Scope[] {
  if (r.scopes) { try { return cleanScopes(JSON.parse(String(r.scopes))) ?? [] } catch { /* fall through */ } }
  return LEGACY[String(r.role)] ?? []
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
    by: r.by_id ? { id: String(r.by_id), name: String(r.by_name ?? '') } : null,
    concat: r.concat === 'partial' ? { partial: true } : r.concat ? { final: JSON.parse(String(r.concat)) } : null,
  }
}
const isPartial = (s: UploadState) => !!s.concat && 'partial' in s.concat
const finalParts = (s: UploadState) => (s.concat && 'final' in s.concat ? s.concat.final : null)

const iso = (ms: unknown) => (ms ? new Date(Number(ms)).toISOString() : null)
const strOrNull = (v: unknown) => (v == null ? null : String(v))
const numOrNull = (v: unknown) => (v == null ? null : Number(v))

function seenRow(r: Row): Seen {
  return {
    at: iso(r.at)!, country: strOrNull(r.country), region: strOrNull(r.region), city: strOrNull(r.city),
    network: strOrNull(r.network), lat: numOrNull(r.lat), lon: numOrNull(r.lon), client: strOrNull(r.client),
    userAgent: strOrNull(r.user_agent), prevClient: strOrNull(r.prev_client), prevAt: iso(r.prev_at),
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
    const cols = this.sql.exec('PRAGMA table_info(uploads)').toArray().map((c) => String(c.name))
    if (!cols.includes('by_id')) this.sql.exec('ALTER TABLE uploads ADD COLUMN by_id TEXT')
    if (!cols.includes('by_name')) this.sql.exec('ALTER TABLE uploads ADD COLUMN by_name TEXT')
    // concatenation: 'partial' | JSON list of partial ids (final); final_id: the final upload a partial belongs to
    if (!cols.includes('concat')) this.sql.exec('ALTER TABLE uploads ADD COLUMN concat TEXT')
    if (!cols.includes('final_id')) this.sql.exec('ALTER TABLE uploads ADD COLUMN final_id TEXT')
    this.sql.exec(`CREATE TABLE IF NOT EXISTS tokens (
      id TEXT PRIMARY KEY, name TEXT NOT NULL, role TEXT NOT NULL, hash TEXT NOT NULL UNIQUE, prefix TEXT NOT NULL,
      created_at INTEGER NOT NULL, last_used_at INTEGER, revoked_at INTEGER)`)
    const tcols = this.sql.exec('PRAGMA table_info(tokens)').toArray().map((c) => String(c.name))
    if (!tcols.includes('scopes')) this.sql.exec('ALTER TABLE tokens ADD COLUMN scopes TEXT')   // null: from role (LEGACY)
    this.sql.exec(`CREATE TABLE IF NOT EXISTS seen (
      id TEXT PRIMARY KEY, at INTEGER NOT NULL, country TEXT, region TEXT, city TEXT, network TEXT, lat REAL, lon REAL,
      client TEXT, user_agent TEXT, prev_client TEXT, prev_at INTEGER)`)
  }

  // ---- last seen (one row per token id, overwritten)

  touchSeen(id: string, v: SeenInput): void {
    const r = this.sql.exec('SELECT * FROM seen WHERE id = ?', id).toArray()[0]
    const now = Date.now()
    if (r && now - Number(r.at) < SEEN_MS && (r.client ?? null) === v.client && (r.city ?? null) === v.city) return
    let prevClient = (r?.prev_client as string | null) ?? null
    let prevAt = r?.prev_at != null ? Number(r.prev_at) : null
    if (r && r.client && r.client !== v.client) { prevClient = String(r.client); prevAt = Number(r.at) }
    this.sql.exec(`INSERT OR REPLACE INTO seen (id, at, country, region, city, network, lat, lon, client, user_agent, prev_client, prev_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, id, now, v.country, v.region, v.city, v.network, v.lat, v.lon, v.client,
      v.userAgent, prevClient, prevAt)
  }

  seenOf(id: string): Seen | null {
    const r = this.sql.exec('SELECT * FROM seen WHERE id = ?', id).toArray()[0]
    return r ? seenRow(r) : null
  }

  // ---- personal tokens

  /** The active token with this SHA-256 hash; its lastUsedAt is refreshed at most every TOUCH_MS. */
  findToken(hash: string): Principal | null {
    const r = this.sql.exec('SELECT * FROM tokens WHERE hash = ? AND revoked_at IS NULL', hash).toArray()[0]
    if (!r) return null
    const now = Date.now()
    if (!r.last_used_at || now - Number(r.last_used_at) > TOUCH_MS) this.sql.exec('UPDATE tokens SET last_used_at = ? WHERE id = ?', now, r.id)
    return { id: String(r.id), name: String(r.name), scopes: rowScopes(r), admin: false }
  }

  hasTokens(): boolean {
    return this.sql.exec('SELECT 1 FROM tokens LIMIT 1').toArray().length > 0
  }

  issueToken(id: string, name: string, scopes: Scope[], hash: string, prefix: string): Token {
    this.sql.exec('INSERT INTO tokens (id, name, role, scopes, hash, prefix, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
      id, name, roleOf(scopes), JSON.stringify(scopes), hash, prefix, Date.now())
    return this.listTokens().find((t) => t.id === id)!
  }

  /** New scopes and/or name; 404 unknown, 409 revoked. */
  updateToken(id: string, scopes: Scope[] | null, name: string | null): { status: 200 | 404 | 409; token?: Token } {
    const r = this.sql.exec('SELECT * FROM tokens WHERE id = ?', id).toArray()[0]
    if (!r) return { status: 404 }
    if (r.revoked_at) return { status: 409 }
    if (scopes) this.sql.exec('UPDATE tokens SET scopes = ?, role = ? WHERE id = ?', JSON.stringify(scopes), roleOf(scopes), id)
    if (name) this.sql.exec('UPDATE tokens SET name = ? WHERE id = ?', name, id)
    return { status: 200, token: this.listTokens().find((t) => t.id === id)! }
  }

  listTokens(): Token[] {
    return this.sql.exec(`SELECT t.*, (SELECT COUNT(*) FROM uploads u WHERE u.by_id = t.id) AS n FROM tokens t
      ORDER BY created_at DESC`).toArray().map((r) => ({
      id: String(r.id), name: String(r.name), scopes: rowScopes(r), role: roleOf(rowScopes(r)), prefix: String(r.prefix),
      createdAt: iso(r.created_at)!, lastUsedAt: iso(r.last_used_at), revokedAt: iso(r.revoked_at), uploads: Number(r.n),
      seen: this.seenOf(String(r.id)),
    }))
  }

  /** false: no such token. Revoking twice keeps the first date. */
  revokeToken(id: string): boolean {
    if (!this.sql.exec('SELECT 1 FROM tokens WHERE id = ?', id).toArray().length) return false
    this.sql.exec('UPDATE tokens SET revoked_at = COALESCE(revoked_at, ?) WHERE id = ?', Date.now(), id)
    return true
  }

  private row(id: string): Row | null {
    return this.sql.exec('SELECT * FROM uploads WHERE id = ?', id).toArray()[0] ?? null
  }

  create(s: UploadState): void {
    this.sql.exec(`INSERT INTO uploads (id, size, offset, metadata, created_at, updated_at, parts, complete, by_id, by_name, concat)
      VALUES (?, ?, ?, ?, ?, ?, '[]', ?, ?, ?, ?)`, s.id, s.size, s.offset, JSON.stringify(s.metadata), s.createdAt,
      s.updatedAt, s.complete ? 1 : 0, s.by?.id ?? null, s.by?.name ?? null,
      s.concat ? ('partial' in s.concat ? 'partial' : JSON.stringify(s.concat.final)) : null)
  }

  /**
   * The final upload of a concatenation, in one step: every partial must exist (404), be a partial (400), be complete
   * (400), not belong to another final (409) and be [by]'s (403; null = admin). Returns the new state.
   */
  concatFinal(f: Omit<UploadState, 'size' | 'offset'>, ids: string[], by: string | null): { status: 201 | 400 | 403 | 404 | 409; state?: UploadState; why?: string } {
    let size = 0
    for (const id of ids) {
      const r = this.row(id)
      if (!r) return { status: 404, why: `partial ${id} not found` }
      const ps = fromRow(r)
      if (!isPartial(ps)) return { status: 400, why: `${id} is not a partial upload` }
      if (!ps.complete) return { status: 400, why: `partial ${id} is not finished` }
      if (r.final_id) return { status: 409, why: `partial ${id} is already part of an upload` }
      if (by !== null && ps.by?.id !== by) return { status: 403, why: `partial ${id} is not yours` }
      size += ps.size
    }
    const s: UploadState = { ...f, size, offset: size, complete: true, concat: { final: ids } }
    this.create(s)
    for (const id of ids) this.sql.exec('UPDATE uploads SET final_id = ? WHERE id = ?', s.id, id)
    return { status: 201, state: s }
  }

  /** The partials of a final upload, removed with it; returned for R2 cleanup. */
  dropParts(ids: string[]): UploadState[] {
    const out: UploadState[] = []
    for (const id of ids) {
      const r = this.row(id)
      if (!r) continue
      out.push(fromRow(r))
      this.sql.exec('DELETE FROM uploads WHERE id = ?', id)
    }
    return out
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

  /**
   * Termination: forget an unfinished upload no PATCH is writing; the caller frees its R2 objects. [by]: only an
   * upload this token created (403 otherwise); null: an admin, who may also remove finished uploads and leased ones.
   */
  terminate(id: string, by: string | null): { status: 204 | 404 | 400 | 403 | 409 | 423; state?: UploadState } {
    const r = this.row(id)
    if (!r) return { status: 404 }
    const s = fromRow(r)
    if (r.final_id) return { status: 409 }   // part of a final upload: delete that one instead
    if (by === null) {
      this.sql.exec('DELETE FROM uploads WHERE id = ?', id)
      return { status: 204, state: s }
    }
    if (s.by?.id !== by) return { status: 403 }
    if (s.complete) return { status: 400 }
    if (r.lease && Number(r.lease_until) > Date.now()) return { status: 423 }
    this.sql.exec('DELETE FROM uploads WHERE id = ?', id)
    return { status: 204, state: s }
  }

  list(type: string): UploadState[] {
    return this.sql.exec("SELECT * FROM uploads WHERE concat IS NULL OR concat != 'partial' ORDER BY created_at DESC").toArray()
      .map(fromRow).filter((s) => !type || s.metadata.type === type)
  }

  /** Unfinished uploads untouched since [before] (ms) and not being written: removed, returned for R2 cleanup. */
  expire(before: number): UploadState[] {
    const rows = this.sql.exec(`SELECT * FROM uploads WHERE updated_at < ? AND lease_until < ? AND (complete = 0
      OR (concat = 'partial' AND final_id IS NULL))`, before, Date.now()).toArray().map(fromRow)
    for (const s of rows) this.sql.exec('DELETE FROM uploads WHERE id = ?', s.id)
    return rows
  }
}

// ------------------------------------------------------------------------------------------------ helpers

const enc = new TextEncoder()

async function sha256hex(t: string): Promise<string> {
  const d = new Uint8Array(await crypto.subtle.digest('SHA-256', enc.encode(t)))
  return [...d].map((b) => b.toString(16).padStart(2, '0')).join('')
}

/** Constant-time comparison (digests first: equal lengths, no early exit on the token's length). */
async function same(given: string, secret: string): Promise<boolean> {
  const [a, b] = await Promise.all([crypto.subtle.digest('SHA-256', enc.encode(given)), crypto.subtle.digest('SHA-256', enc.encode(secret))])
  return crypto.subtle.timingSafeEqual(a, b)
}

const ADMIN: Principal = { id: 'admin', name: 'Admin', scopes: [...SCOPES], admin: true }
const SHARED: Principal = { id: 'shared', name: 'Shared token', scopes: ['upload', 'read', 'process'], admin: false }

/** Who sent the request, or null (401). Open (admin) only while no secret is set and no token was issued. */
async function principal(env: Env, req: Request): Promise<Principal | null> {
  const given = (req.headers.get('Authorization') || '').replace(/^Bearer\s+/i, '').trim()
  if (given) {
    if (env.ADMIN_TOKEN && (await same(given, env.ADMIN_TOKEN))) return ADMIN
    if (env.TOKEN && (await same(given, env.TOKEN))) return SHARED
    if (given.startsWith('pct_')) {
      const p = await stub(env).findToken(await sha256hex(given))
      if (p) return p
    }
  }
  if (!env.ADMIN_TOKEN && !env.TOKEN && !(await stub(env).hasTokens())) return { ...ADMIN, id: 'open', name: 'Open server' }
  return null
}

/** README "Last seen": Cloudflare's IP lookup (approximate) and the client's own name; never the IP address. */
function seenInput(req: Request): SeenInput {
  const cf = (req as Request & { cf?: Record<string, unknown> }).cf || {}
  const s = (v: unknown, max = 80) => (typeof v === 'string' && v.trim() ? v.trim().slice(0, max) : null)
  const n = (v: unknown) => { const x = Number(v); return v != null && v !== '' && Number.isFinite(x) ? x : null }
  return {
    country: s(cf.country, 8), region: s(cf.region), city: s(cf.city), network: s(cf.asOrganization),
    lat: n(cf.latitude), lon: n(cf.longitude),
    client: s(req.headers.get('X-Client'), 120), userAgent: s(req.headers.get('User-Agent'), 200),
  }
}

const has = (p: Principal, sc: Scope) => p.admin || p.scopes.includes(sc)
const canRead = (p: Principal) => has(p, 'read') || has(p, 'process')
/** May this token create (and so HEAD / PATCH) an upload of this type? */
const canWrite = (p: Principal, type: string | undefined) => (type === 'processed-result' ? has(p, 'process') : has(p, 'upload'))
/** PATCH / HEAD of an existing upload: a partial is anybody's who may upload anything (its type is on the final). */
const canWriteState = (p: Principal, s: UploadState) => (isPartial(s) ? has(p, 'upload') || has(p, 'process') : canWrite(p, s.metadata.type))
const me = (p: Principal) => ({ id: p.id, name: p.name, scopes: p.scopes, admin: p.admin, role: roleOf(p.scopes, p.admin) })

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' } })
}

function newSecret(): string {
  const b = crypto.getRandomValues(new Uint8Array(32))
  return 'pct_' + btoa(String.fromCharCode(...b)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

async function admin(env: Env, req: Request, rest: string): Promise<Response> {
  const tokens = stub(env)
  if (rest === 'tokens' && req.method === 'GET') {
    const builtins = []
    if (env.ADMIN_TOKEN) builtins.push({ ...me(ADMIN), seen: await tokens.seenOf('admin') })
    if (env.TOKEN) builtins.push({ ...me(SHARED), seen: await tokens.seenOf('shared') })
    return json(200, { schema: 'poi-cloud-tokens/1', tokens: await tokens.listTokens(), builtins })
  }
  if (rest === 'tokens' && req.method === 'POST') {
    let body: { name?: unknown; role?: unknown; scopes?: unknown }
    try { body = await req.json() } catch { return text(400, 'JSON body required\n') }
    const name = typeof body?.name === 'string' ? body.name.trim() : ''
    if (!name || name.length > 80) return text(400, 'name: 1 to 80 characters\n')
    const scopes = body.scopes !== undefined ? cleanScopes(body.scopes) : (LEGACY[String(body.role)] ?? null)
    if (!scopes) return text(400, `scopes: one or more of ${SCOPES.join(', ')}\n`)
    const secret = newSecret()
    const token = await tokens.issueToken(crypto.randomUUID().replace(/-/g, '').slice(0, 16), name, scopes, await sha256hex(secret), secret.slice(0, 12))
    console.log(`token issued: ${token.id} ${token.scopes.join(',')}`)
    return json(201, { token, secret })
  }
  const m = /^tokens\/([A-Za-z0-9_-]{1,64})$/.exec(rest)
  if (m && req.method === 'PATCH') {
    let body: { name?: unknown; scopes?: unknown }
    try { body = await req.json() } catch { return text(400, 'JSON body required\n') }
    const scopes = body?.scopes === undefined ? null : cleanScopes(body.scopes)
    if (body?.scopes !== undefined && !scopes) return text(400, `scopes: one or more of ${SCOPES.join(', ')}\n`)
    const name = body?.name === undefined ? null : typeof body.name === 'string' ? body.name.trim() : ''
    if (name !== null && (!name || name.length > 80)) return text(400, 'name: 1 to 80 characters\n')
    if (!scopes && !name) return text(400, 'nothing to change\n')
    const r = await tokens.updateToken(m[1], scopes, name)
    if (r.status === 404) return text(404, 'Not found\n')
    if (r.status === 409) return text(409, 'Token revoked\n')
    console.log(`token changed: ${m[1]} ${r.token!.scopes.join(',')}`)
    return json(200, { token: r.token })
  }
  if (m && req.method === 'DELETE') {
    if (!(await tokens.revokeToken(m[1]))) return text(404, 'Not found\n')
    console.log(`token revoked: ${m[1]}`)
    return new Response(null, { status: 204 })
  }
  return text(404, 'Not found\n')
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
  if (s.concat) h['Upload-Concat'] = 'partial' in s.concat ? 'partial' : `final;${s.concat.final.map((id) => `${base(env)}/${id}`).join(' ')}`
  return h
}
const base = (env: Env) => '/' + (env.BASE_PATH || '/files').replace(/^\/+|\/+$/g, '')

// ------------------------------------------------------------------------------------------------ PATCH

class TusError extends Error {
  constructor(public status: number, message: string) { super(message) }
}

/**
 * Append [body] at [offset] (already checked against the state by begin()); returns the new offset. All R2 writes
 * happen before the commit; nothing is committed when the body breaks off, so the offset stays where it was.
 */
async function append(env: Env, ctx: ExecutionContext, id: string, offset: number, body: ReadableStream<Uint8Array> | null,
  allowed: (s: UploadState) => boolean = () => true): Promise<UploadState> {
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
  if (!allowed(s)) {
    await uploads.release(id, lease)
    await body?.cancel().catch(() => {})
    throw new TusError(403, 'Forbidden')
  }
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

async function create(env: Env, ctx: ExecutionContext, req: Request, base: string, by: Principal): Promise<Response> {
  if (req.headers.get('Upload-Defer-Length')) return text(501, 'Not supported\n')
  const concatH = (req.headers.get('Upload-Concat') || '').trim()
  if (concatH.startsWith('final;')) return createFinal(env, req, concatH.slice(6), by)
  if (concatH && concatH !== 'partial') return text(400, 'Upload-Concat: partial or final;<urls>\n')
  const partial = concatH === 'partial'
  if (partial && !has(by, 'upload') && !has(by, 'process')) return text(403, 'Forbidden\n')
  const lenH = req.headers.get('Upload-Length')
  if (!lenH || !/^\d+$/.test(lenH)) return text(400, 'Upload-Length required\n')
  const size = Number(lenH)
  if (size > maxSize(env)) return text(413, 'Upload too large\n')
  const metaH = req.headers.get('Upload-Metadata')
  if (metaH && metaH.length > MAX_METADATA) return text(400, 'Upload-Metadata too long\n')
  const metadata = parseMetadata(metaH)
  if (!metadata) return text(400, 'Bad Upload-Metadata\n')
  if (!partial && !canWrite(by, metadata.type)) return text(403, 'Forbidden\n')
  const id = crypto.randomUUID().replace(/-/g, '')
  const now = Date.now()
  const s: UploadState = { id, size, offset: 0, metadata, createdAt: new Date(now).toISOString(), updatedAt: now,
    mpId: null, parts: [], carryKey: null, carryLen: 0, complete: size === 0, by: { id: by.id, name: by.name },
    concat: partial ? { partial: true } : null }
  if (size === 0) {
    await env.BUCKET.put(dataKey(id), new Uint8Array(0), { customMetadata: { id, filename: metadata.filename || id } })
  }
  await stub(env).create(s)
  console.log(`created ${id} (${size} B) ${partial ? 'partial' : `${metadata.type || ''} ${metadata.filename || ''}`}`)
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

async function patch(env: Env, ctx: ExecutionContext, req: Request, id: string, p: Principal): Promise<Response> {
  if (!(req.headers.get('Content-Type') || '').startsWith('application/offset+octet-stream')) return text(415, 'Content-Type must be application/offset+octet-stream\n')
  const offH = req.headers.get('Upload-Offset')
  if (!offH || !/^\d+$/.test(offH)) return text(400, 'Upload-Offset required\n')
  try {
    const s = await append(env, ctx, id, Number(offH), req.body, (st) => canWriteState(p, st))
    if (s.complete) console.log(`finished ${id}`)
    return new Response(null, { status: 204, headers: tusHeaders(env, s) })
  } catch (e) {
    if (e instanceof TusError) return text(e.status, e.message + '\n', { 'Tus-Resumable': TUS_VERSION })
    throw e
  }
}

/** Upload-Concat: final;<url> <url> ...: join finished partials into one complete upload (README, Parallel uploads). */
async function createFinal(env: Env, req: Request, list: string, by: Principal): Promise<Response> {
  const ids = list.trim().split(/\s+/).filter(Boolean).map((u) => {
    try { return new URL(u, req.url).pathname.split('/').pop() || '' } catch { return '' }
  })
  if (!ids.length || ids.length > 16 || ids.some((i) => !isId(i)) || new Set(ids).size !== ids.length) {
    return text(400, 'Upload-Concat final: 1 to 16 distinct partial upload URLs\n')
  }
  const metaH = req.headers.get('Upload-Metadata')
  if (metaH && metaH.length > MAX_METADATA) return text(400, 'Upload-Metadata too long\n')
  const metadata = parseMetadata(metaH)
  if (!metadata) return text(400, 'Bad Upload-Metadata\n')
  if (!canWrite(by, metadata.type)) return text(403, 'Forbidden\n')
  const id = crypto.randomUUID().replace(/-/g, '')
  const now = Date.now()
  const r = await stub(env).concatFinal({ id, metadata, createdAt: new Date(now).toISOString(), updatedAt: now, mpId: null,
    parts: [], carryKey: null, carryLen: 0, complete: true, by: { id: by.id, name: by.name } }, ids, by.admin ? null : by.id)
  if (r.status !== 201) return text(r.status, `${r.why}\n`, { 'Tus-Resumable': TUS_VERSION })
  if (r.state!.size > maxSize(env)) console.log(`final ${id} is ${r.state!.size} B, over MAX_SIZE`)
  console.log(`created ${id} (${r.state!.size} B) ${metadata.type || ''} ${metadata.filename || ''} from ${ids.length} partials`)
  return new Response(null, { status: 201, headers: { Location: `${new URL(req.url).origin}${base(env)}/${id}`,
    'Tus-Resumable': TUS_VERSION, 'Upload-Offset': String(r.state!.size), 'Cache-Control': 'no-store' } })
}

async function head(env: Env, id: string, p: Principal): Promise<Response> {
  const s = await stub(env).get(id)
  if (s && !canWriteState(p, s) && !canRead(p)) return text(403, 'Forbidden\n', { 'Tus-Resumable': TUS_VERSION })
  if (!s) return new Response(null, { status: 404, headers: { 'Tus-Resumable': TUS_VERSION, 'Cache-Control': 'no-store' } })
  return new Response(null, { status: 200, headers: tusHeaders(env, s) })
}

async function terminate(env: Env, id: string, p: Principal): Promise<Response> {
  const r = await stub(env).terminate(id, has(p, 'delete') ? null : p.id)
  const h = { 'Tus-Resumable': TUS_VERSION }
  if (r.status === 400) return text(400, 'A finished upload can only be deleted with the Delete uploads permission\n', h)
  if (r.status === 403) return text(403, 'Not your upload\n', h)
  if (r.status === 423) return text(423, 'Another PATCH is writing this upload\n', h)
  if (r.status === 409) return text(409, 'Part of a joined upload: delete that upload instead\n', h)
  if (r.status !== 204) return text(404, 'Not found\n', h)
  await freeR2(env, r.state!)
  if (r.state!.complete) await env.BUCKET.delete(dataKey(id))
  const ids = finalParts(r.state!)
  if (ids) {
    for (const ps of await stub(env).dropParts(ids)) { await freeR2(env, ps); await env.BUCKET.delete(dataKey(ps.id)) }
  }
  console.log(`deleted ${id}${r.state!.complete ? ' (finished)' : ''} by ${p.id}`)
  return new Response(null, { status: 204, headers: h })
}

async function freeR2(env: Env, s: UploadState) {
  if (s.mpId) await env.BUCKET.resumeMultipartUpload(dataKey(s.id), s.mpId).abort().catch(() => {})
  if (s.carryKey) await env.BUCKET.delete(s.carryKey)
}

async function index(env: Env, type: string): Promise<Response> {
  const list = await stub(env).list(type)
  const uploads = list.map((s) => ({ id: s.id, size: s.size, offset: s.offset, complete: s.complete, createdAt: s.createdAt, metadata: s.metadata, by: s.by ?? null }))
  return new Response(JSON.stringify({ schema: 'poi-cloud-index/1', uploads }), {
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' },
  })
}

async function download(env: Env, ctx: ExecutionContext, id: string): Promise<Response> {
  if (!isId(id)) return text(404, 'Not found\n')
  const s = await stub(env).get(id)
  if (!s || isPartial(s)) return text(404, 'Not found\n')
  let body: ReadableStream
  let size: number
  let filename = s.metadata.filename || id
  const ids = finalParts(s)
  if (ids) {
    // a joined upload: its partials one after the other, streamed from R2 (no copy is ever stored)
    const objs: R2ObjectBody[] = []
    for (const pid of ids) {
      const o = await env.BUCKET.get(dataKey(pid))
      if (!o) return text(500, `part ${pid} missing\n`)
      objs.push(o)
    }
    size = objs.reduce((n, o) => n + o.size, 0)
    const { readable, writable } = new FixedLengthStream(size)
    ctx.waitUntil((async () => {
      for (const o of objs) await o.body.pipeTo(writable, { preventClose: true })
      await writable.close()
    })().catch((e) => { console.error('concat download failed:', id, (e as Error)?.message); return writable.abort(e) }))
    body = readable
  } else {
    const obj = await env.BUCKET.get(dataKey(id))
    if (!obj) return text(404, 'Upload not finished\n')
    body = obj.body
    size = obj.size
    filename = String(obj.customMetadata?.filename || filename)
  }
  const name = String(filename).replace(/[^\w.\-]+/g, '_')
  return new Response(body, {
    headers: {
      'Content-Type': 'application/octet-stream',   // never rendered by the browser, whatever was uploaded
      'Content-Length': String(size),
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
  const isMe = url.pathname === '/me'
  const isAdmin = url.pathname.startsWith('/admin/')
  if (!isMe && !isAdmin && url.pathname !== base && !url.pathname.startsWith(base + '/')) return text(404, 'Not found\n')
  const p = await principal(env, req)
  if (!p) return text(401, 'Unauthorized\n', { 'WWW-Authenticate': 'Bearer' })
  if (p.id !== 'open') ctx.waitUntil(stub(env).touchSeen(p.id, seenInput(req)).catch(() => {}))
  const forbidden = () => text(403, 'Forbidden\n')
  if (isMe) return req.method === 'GET' ? json(200, { schema: 'poi-cloud-me/1', ...me(p) }) : text(405, 'Method not allowed\n')
  if (isAdmin) return p.admin ? admin(env, req, url.pathname.slice('/admin/'.length)) : forbidden()

  const rest = url.pathname.slice(base.length).replace(/^\/+/, '')
  let method = req.method
  // tus clients without PATCH (Android's HttpURLConnection) send POST + X-HTTP-Method-Override, as tusd accepts
  const override = (req.headers.get('X-HTTP-Method-Override') || '').toUpperCase()
  if (method === 'POST' && ['PATCH', 'HEAD', 'DELETE'].includes(override)) method = override

  if (method === 'GET' && rest) {
    if (!canRead(p)) return forbidden()
    return rest === 'index.json' ? index(env, url.searchParams.get('type') || '') : download(env, ctx, rest)
  }
  if (method === 'OPTIONS') {
    return new Response(null, {
      status: 204,
      headers: { 'Tus-Resumable': TUS_VERSION, 'Tus-Version': TUS_VERSION, 'Tus-Extension': EXTENSIONS, 'Tus-Max-Size': String(maxSize(env)) },
    })
  }
  const tr = req.headers.get('Tus-Resumable')
  if (tr !== TUS_VERSION) return text(412, 'Tus-Resumable 1.0.0 required\n', { 'Tus-Version': TUS_VERSION })
  if (method === 'POST' && !rest) return create(env, ctx, req, base, p)
  if (!rest || !isId(rest)) return text(404, 'Not found\n')
  if (method === 'HEAD') return head(env, rest, p)
  if (method === 'PATCH') return patch(env, ctx, req, rest, p)
  if (method === 'DELETE') return terminate(env, rest, p)
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
    for (const s of gone) {
      await freeR2(env, s)
      if (s.complete) await env.BUCKET.delete(dataKey(s.id))   // a finished partial no final upload used
    }
    if (gone.length) console.log(`expired ${gone.length} unfinished upload(s) or unused partial(s)`)
  },
} satisfies ExportedHandler<Env>
