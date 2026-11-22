// Cloud storage: the tus server the phone uploads to and the agent pushes processed results to (server/README.md).
// tus has no listing, so the server adds GET <endpoint>index.json; a finished upload downloads with GET <endpoint><id>.
// The reviewer pulls PROCESSED packages only (metadata type=processed-result); raw sessions are for the agent.
// The server address and token are kept in this browser's localStorage, nowhere else. Without a saved address the
// team's server is used (DEFAULT_CLOUD_URL, the Cloudflare Worker in server/worker/); the token is always typed in.
// Tokens and permissions (server/README.md, "Tokens and permissions"): a token has independent scopes (upload, read,
// process, delete). GET /me says who a token is; the admin password also reaches /admin/tokens (issue, list, change
// scopes, revoke personal tokens) and may DELETE any upload. /me and /admin/... sit at the server
// root (the origin of the tus endpoint). The admin password is kept in sessionStorage only: gone when the tab closes.
import type { Dataset } from './types'
import { version } from '../package.json'

/** The project's server: set when the app is built (VITE_CLOUD_URL, e.g. in .env or the Pages build settings). */
export const DEFAULT_CLOUD_URL: string = (import.meta.env?.VITE_CLOUD_URL as string | undefined)?.trim() || 'https://poi-uploader.4hum.ai/files/'

export const INDEX_SCHEMA = 'poi-cloud-index/1'
export const ME_SCHEMA = 'poi-cloud-me/1'
export const TOKENS_SCHEMA = 'poi-cloud-tokens/1'
export const PROCESSED = 'processed-result'

export type CloudConfig = { url: string; token: string }

export type CloudItem = {
  id: string
  size: number | null
  offset: number
  complete: boolean
  createdAt: string | null
  type: string
  sessionId: string | null
  name: string | null
  kind: string | null
  filename: string
  /** The token that uploaded it; null for uploads made before the server recorded it. */
  by: { id: string; name: string } | null
  /** The upload's metadata as the server sent it (tus metadata: mostly strings). */
  metadata: Record<string, unknown>
  /** A phone ride's trip metadata (server/README.md, "Trip metadata"); null when the upload carries none. */
  trip: Trip | null
}

/** A ride: `track` is a Google encoded polyline (precision 5); every field is optional on the server. */
export type Trip = { track: string | null; startedAt: string | null; endedAt: string | null; distanceM: number | null; photos: number | null }

/** A token's latest request (server/README.md, "Last seen"): the place is Cloudflare's IP lookup, city level at best. */
export type Seen = {
  at: string
  country: string | null
  region: string | null
  city: string | null
  network: string | null
  lat: number | null
  lon: number | null
  /** The X-Client header the client sent, else null; userAgent the User-Agent. */
  client: string | null
  userAgent: string | null
  /** The previous different client, and when it was last seen. */
  prevClient: string | null
  prevAt: string | null
}

/** A token's permissions (server/README.md): independent, ticked by the admin. */
export type Scope = 'upload' | 'read' | 'process' | 'delete'
/** Every scope, in display order. */
export const SCOPES: readonly Scope[] = ['upload', 'read', 'process', 'delete']
/** The presets of the issue form: Phone, Reviewer, Agent. */
export const PRESETS: Record<'phone' | 'reviewer' | 'agent', Scope[]> = { phone: ['upload'], reviewer: ['read'], agent: ['process'] }

/** The server's one-word summary of the scopes, for older clients (`custom` when none fits). */
export type Role = 'uploader' | 'reviewer' | 'agent' | 'custom' | 'admin'

/** A built-in identity (the admin password, `admin`): a server secret, not issued; its scopes cannot be changed. */
export type Builtin = { id: string; name: string; scopes: Scope[]; role: Role; seen: Seen | null }

export type Me = { id: string; name: string; scopes: Scope[]; admin: boolean; role: Role }
export type Token = {
  id: string
  name: string
  scopes: Scope[]
  role: Role
  /** The first 12 characters of the secret (pct_ + 8), to tell tokens apart. */
  prefix: string
  createdAt: string | null
  lastUsedAt: string | null
  revokedAt: string | null
  uploads: number
  /** Null when never seen, or from a server that does not record it. */
  seen: Seen | null
}

/** A refused or failed request; status is the HTTP status (401: wrong or revoked token, 403: no permission
 *  for this, 409: the token was revoked). */
export class CloudError extends Error {
  constructor(message: string, readonly status: number) { super(message) }
}

/** How an item on the server stands against the sessions in this browser. */
export type ImportState = 'new' | 'imported' | 'updated'

/** The tus endpoint with one trailing slash, or null when it is not an http(s) URL. */
export function endpoint(url: string): string | null {
  const t = (url || '').trim()
  if (!t) return null
  try {
    const u = new URL(t)
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return null
  } catch {
    return null
  }
  return t.replace(/\/+$/, '') + '/'
}

/** The server root (the tus endpoint's origin, no trailing slash) where /me and /admin/... are; null for a bad URL. */
export function serverRoot(url: string): string | null {
  const base = endpoint(url)
  return base ? new URL(base).origin : null
}

/** The setup string handed to a phone (shown as a QR code): `<endpoint>#token=<secret>&name=<name>`. The fragment
 *  is never sent to a server; the phone splits it. */
export function setupLink(url: string, secret: string, name: string): string {
  const base = (url || '').trim().replace(/\/+$/, '') + '/'
  return `${base}#token=${encodeURIComponent(secret.trim())}&name=${encodeURIComponent(name)}`
}

/**
 * What a reviewer pastes in Settings: the bare access key, or the admin's whole setup link (setupLink) → the key and,
 * from a link, the server address. null for nothing usable.
 */
export function parseKeyInput(text: string): { token: string; url: string | null } | null {
  const t = (text || '').trim().replace(/^Bearer\s+/i, '')
  if (!t) return null
  const hash = t.indexOf('#')
  if (/^https?:\/\//i.test(t) && hash > 0) {
    const params = new URLSearchParams(t.slice(hash + 1))
    const token = (params.get('token') || '').trim()
    if (!token) return null
    return { token, url: endpoint(t.slice(0, hash)) }
  }
  if (/\s/.test(t) || /^https?:/i.test(t)) return null
  return { token: t, url: null }
}

const str = (v: unknown) => (typeof v === 'string' && v ? v : null)
/** A number, also when sent as a string (tus metadata values are strings). */
const num = (v: unknown): number | null => {
  const n = typeof v === 'number' ? v : typeof v === 'string' && v.trim() ? Number(v) : NaN
  return Number.isFinite(n) ? n : null
}

function parseTrip(m: Record<string, unknown>): Trip | null {
  const trip: Trip = { track: str(m.track), startedAt: str(m.startedAt), endedAt: str(m.endedAt), distanceM: num(m.distanceM), photos: num(m.photos) }
  return Object.values(trip).some((v) => v != null) ? trip : null
}

/** A `seen` record, or null when absent or malformed (an older server sends none). */
export function parseSeen(v: unknown): Seen | null {
  const j = v as any
  if (!j || typeof j !== 'object' || !str(j.at)) return null
  const lat = num(j.lat), lon = num(j.lon)
  const ok = lat != null && lon != null && Math.abs(lat) <= 90 && Math.abs(lon) <= 180
  return {
    at: j.at, country: str(j.country), region: str(j.region), city: str(j.city), network: str(j.network),
    lat: ok ? lat : null, lon: ok ? lon : null,
    client: str(j.client), userAgent: str(j.userAgent), prevClient: str(j.prevClient), prevAt: str(j.prevAt),
  }
}

/** The same token from two different clients within `days` (a copied token, or a new phone). */
export function twoClients(s: Seen | null, days = 7): boolean {
  if (!s || !s.prevClient || !s.prevAt || s.prevClient === s.client) return false
  const at = Date.parse(s.at), prev = Date.parse(s.prevAt)
  return Number.isFinite(at) && Number.isFinite(prev) && at - prev <= days * 86400e3
}

/** A Google encoded polyline (precision 5) → [lat, lon] pairs; stops at the first malformed point. */
export function decodePolyline(str: string): [number, number][] {
  const out: [number, number][] = []
  let i = 0, lat = 0, lon = 0
  const next = (): number | null => {
    let result = 0, shift = 0, b: number
    do {
      if (i >= str.length || shift > 30) return null
      b = str.charCodeAt(i++) - 63
      if (b < 0 || b > 63) return null
      result |= (b & 0x1f) << shift
      shift += 5
    } while (b >= 0x20)
    return result & 1 ? ~(result >> 1) : result >> 1
  }
  while (i < str.length) {
    const dlat = next(), dlon = next()
    if (dlat == null || dlon == null) break
    lat += dlat; lon += dlon
    out.push([lat / 1e5, lon / 1e5])
  }
  return out
}

/** A short summary of the browser from its User-Agent: "Chrome 131; Windows". */
export function browserSummary(ua: string): string {
  const pick = (re: RegExp) => ua.match(re)?.[1]?.split('.')[0]
  let b = 'browser'
  let v: string | undefined
  if ((v = pick(/Edg(?:e|A|iOS)?\/([\d.]+)/))) b = 'Edge'
  else if ((v = pick(/OPR\/([\d.]+)/))) b = 'Opera'
  else if ((v = pick(/(?:Firefox|FxiOS)\/([\d.]+)/))) b = 'Firefox'
  else if ((v = pick(/(?:Chrome|CriOS)\/([\d.]+)/))) b = 'Chrome'
  else if (/Safari\//.test(ua) && (v = pick(/Version\/([\d.]+)/))) b = 'Safari'
  const os = /Android/.test(ua) ? 'Android' : /iPhone|iPad|iPod/.test(ua) ? 'iOS' : /Windows/.test(ua) ? 'Windows'
    : /Mac OS X|Macintosh/.test(ua) ? 'macOS' : /CrOS/.test(ua) ? 'ChromeOS' : /Linux/.test(ua) ? 'Linux' : ''
  return [v ? `${b} ${v}` : b, os].filter(Boolean).join('; ')
}

/** The X-Client header this app sends (server/README.md, "Last seen"); ASCII only, at most 120 characters. */
export function clientName(ua: string = typeof navigator !== 'undefined' ? navigator.userAgent || '' : ''): string {
  return `POI Capture Reviewer/${version} (${browserSummary(ua)})`.replace(/[^\x20-\x7e]/g, '').slice(0, 120)
}

/** The server's index.json → items, newest first; anything malformed is left out (ids must be plain tokens). */
export function parseIndex(json: unknown): CloudItem[] {
  const j = json as any
  if (!j || typeof j !== 'object' || !Array.isArray(j.uploads)) throw new Error('not a cloud index (no uploads list)')
  if (j.schema && j.schema !== INDEX_SCHEMA) throw new Error(`unknown cloud index schema ${j.schema}`)
  const out: CloudItem[] = []
  for (const u of j.uploads) {
    if (!u || typeof u.id !== 'string' || !/^[A-Za-z0-9_-]+$/.test(u.id)) continue
    const m = u.metadata && typeof u.metadata === 'object' ? u.metadata : {}
    const size = typeof u.size === 'number' ? u.size : null
    const offset = typeof u.offset === 'number' ? u.offset : 0
    const by = u.by && typeof u.by === 'object' && typeof u.by.id === 'string' ? { id: u.by.id, name: str(u.by.name) || u.by.id } : null
    const metadata: Record<string, unknown> = { ...m }
    out.push({
      id: u.id, size, offset,
      complete: u.complete === true || (size != null && offset === size),
      createdAt: str(u.createdAt) || str(m.createdAt),
      type: str(m.type) || 'unknown', sessionId: str(m.sessionId), name: str(m.name), kind: str(m.kind),
      filename: str(m.filename) || `${u.id}.zip`, by, metadata, trip: parseTrip(metadata),
    })
  }
  return out.sort((a, b) => (b.createdAt || '').localeCompare(a.createdAt || ''))
}

/** What the reviewer can import: finished processed packages. */
export function processedItems(items: CloudItem[]): CloudItem[] {
  return items.filter((i) => i.type === PROCESSED && i.complete)
}

/** imported: the session is here (a processed import of the same session id); updated: the server has a newer one. */
export function importState(item: CloudItem, datasets: Pick<Dataset, 'id' | 'source' | 'importedAt'>[]): ImportState {
  const d = item.sessionId ? datasets.find((x) => x.id === item.sessionId && x.source === 'result') : undefined
  if (!d) return 'new'
  return item.createdAt && d.importedAt && item.createdAt > d.importedAt ? 'updated' : 'imported'
}

function headers(cfg: CloudConfig): Record<string, string> {
  const h: Record<string, string> = { 'X-Client': clientName() }
  const t = cfg.token.trim().replace(/^Bearer\s+/i, '')
  if (t) h.Authorization = `Bearer ${t}`
  return h
}

async function check(r: Response, what: string) {
  if (r.ok) return
  if (r.status === 401) throw new CloudError(`${what}: the server refused the token (HTTP 401)`, 401)
  if (r.status === 403) throw new CloudError(`${what}: the server refused the token, it lacks the permission (HTTP 403)`, 403)
  if (r.status === 409) throw new CloudError(`${what}: the token has been revoked (HTTP 409)`, 409)
  throw new CloudError(`${what}: HTTP ${r.status}`, r.status)
}

function base(cfg: CloudConfig): string {
  const b = endpoint(cfg.url)
  if (!b) throw new Error('no cloud server set (Settings)')
  return b
}

function root(cfg: CloudConfig): string {
  const r = serverRoot(cfg.url)
  if (!r) throw new Error('no cloud server set (Settings)')
  return r
}

/** The server's index: by default the processed packages; `type = ''` for every upload (admin list). */
export async function fetchIndex(cfg: CloudConfig, f: typeof fetch = fetch, type: string = PROCESSED): Promise<CloudItem[]> {
  const q = type ? `?type=${encodeURIComponent(type)}` : ''
  const r = await f(`${base(cfg)}index.json${q}`, { headers: headers(cfg), cache: 'no-store' })
  await check(r, 'list')
  return parseIndex(await r.json())
}

/** The finished upload as a File, ready for importFiles(). */
export async function downloadItem(cfg: CloudConfig, item: CloudItem, f: typeof fetch = fetch): Promise<File> {
  const r = await f(base(cfg) + encodeURIComponent(item.id), { headers: headers(cfg), cache: 'no-store' })
  await check(r, item.filename)
  const name = /\.zip$/i.test(item.filename) ? item.filename : item.filename + '.zip'
  return new File([await r.blob()], name, { type: 'application/zip' })
}

const ROLES: Role[] = ['uploader', 'reviewer', 'agent', 'custom', 'admin']
const isRole = (v: unknown): v is Role => ROLES.includes(v as Role)
export const isScope = (v: unknown): v is Scope => SCOPES.includes(v as Scope)

/** Known scopes, deduplicated, in display order. */
export function sortScopes(list: readonly unknown[]): Scope[] {
  return SCOPES.filter((s) => list.includes(s))
}

/** The scopes an answer carries; an older server sends only a role: uploader → upload; reviewer → upload, read,
 *  process; admin → every scope. Null when there is neither a scopes list nor a known role. */
export function parseScopes(scopes: unknown, role: unknown): Scope[] | null {
  if (Array.isArray(scopes)) return sortScopes(scopes)
  if (role === 'uploader') return ['upload']
  if (role === 'reviewer') return ['upload', 'read', 'process']
  if (role === 'agent') return ['process']
  if (role === 'admin') return [...SCOPES]
  return null
}

/** The summary role of a set of scopes, as the server makes it: exactly upload → uploader, has read → reviewer, has
 *  process → agent, else custom. */
export function roleOf(scopes: readonly Scope[], admin = false): Role {
  if (admin) return 'admin'
  if (scopes.length === 1 && scopes[0] === 'upload') return 'uploader'
  if (scopes.includes('read')) return 'reviewer'
  if (scopes.includes('process')) return 'agent'
  return 'custom'
}

/** Who the token is: GET /me (any valid token). */
export async function me(cfg: CloudConfig, f: typeof fetch = fetch): Promise<Me> {
  const r = await f(`${root(cfg)}/me`, { headers: headers(cfg), cache: 'no-store' })
  await check(r, 'me')
  const j = (await r.json()) as any
  const scopes = j && typeof j === 'object' ? parseScopes(j.scopes, j.role) : null
  if (!j || (j.schema && j.schema !== ME_SCHEMA) || typeof j.id !== 'string' || !scopes) throw new Error('not a /me answer from the server')
  const admin = j.admin === true || j.role === 'admin'
  return { id: j.id, name: str(j.name) || j.id, scopes, admin, role: isRole(j.role) ? j.role : roleOf(scopes, admin) }
}

function parseToken(t: any): Token | null {
  if (!t || typeof t !== 'object' || typeof t.id !== 'string') return null
  const scopes = parseScopes(t.scopes, t.role)
  if (!scopes) return null
  return {
    id: t.id, name: str(t.name) || t.id, scopes, role: isRole(t.role) ? t.role : roleOf(scopes), prefix: str(t.prefix) || '',
    createdAt: str(t.createdAt), lastUsedAt: str(t.lastUsedAt), revokedAt: str(t.revokedAt),
    uploads: typeof t.uploads === 'number' ? t.uploads : 0,
    seen: parseSeen(t.seen),
  }
}

function parseBuiltin(b: any): Builtin | null {
  if (!b || typeof b !== 'object' || typeof b.id !== 'string' || !b.id) return null
  // neither scopes nor a known role (a server from before roles were listed): only the admin password gets every scope
  const scopes = parseScopes(b.scopes, b.role) ?? (b.id === 'admin' ? [...SCOPES] : [])
  const role = isRole(b.role) ? b.role : b.id === 'admin' ? 'admin' : roleOf(scopes)
  return { id: b.id, name: str(b.name) || b.id, scopes, role, seen: parseSeen(b.seen) }
}

/** Every personal token, newest first, revoked ones included, and the built-in identities (none from an older
 *  server) (admin). */
export async function adminTokens(cfg: CloudConfig, f: typeof fetch = fetch): Promise<{ tokens: Token[]; builtins: Builtin[] }> {
  const r = await f(`${root(cfg)}/admin/tokens`, { headers: headers(cfg), cache: 'no-store' })
  await check(r, 'tokens')
  const j = (await r.json()) as any
  if (!j || !Array.isArray(j.tokens) || (j.schema && j.schema !== TOKENS_SCHEMA)) throw new Error('not a token list from the server')
  return {
    tokens: j.tokens.map(parseToken).filter((t: Token | null): t is Token => t != null),
    builtins: Array.isArray(j.builtins) ? j.builtins.map(parseBuiltin).filter((b: Builtin | null): b is Builtin => b != null) : [],
  }
}

/** Every personal token, newest first, revoked ones included (admin). */
export async function listTokens(cfg: CloudConfig, f: typeof fetch = fetch): Promise<Token[]> {
  return (await adminTokens(cfg, f)).tokens
}

function checkName(name: string): string {
  const n = name.trim()
  if (!n || [...n].length > 80) throw new Error('the name must be 1 to 80 characters')
  return n
}

function checkScopes(scopes: readonly unknown[]): Scope[] {
  const unknown = scopes.filter((s) => !isScope(s))
  if (unknown.length) throw new Error(`unknown permission: ${unknown.join(', ')}`)
  const s = sortScopes(scopes)
  if (!s.length) throw new Error('tick at least one permission')
  return s
}

/** A new personal token with these scopes (admin). The secret comes back this once only. */
export async function issueToken(cfg: CloudConfig, name: string, scopes: readonly Scope[], f: typeof fetch = fetch): Promise<{ token: Token; secret: string }> {
  const body = { name: checkName(name), scopes: checkScopes(scopes) }
  const r = await f(`${root(cfg)}/admin/tokens`, {
    method: 'POST', headers: { ...headers(cfg), 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  })
  await check(r, 'issue')
  const j = (await r.json()) as any
  const token = parseToken(j?.token)
  if (!token || typeof j.secret !== 'string' || !j.secret) throw new Error('the server sent no token')
  return { token, secret: j.secret }
}

/** Changes a personal token's scopes and/or name (admin); takes effect on its next request. 409: it was revoked. */
export async function updateToken(cfg: CloudConfig, id: string, change: { scopes?: readonly Scope[]; name?: string },
  f: typeof fetch = fetch): Promise<Token> {
  const body: { scopes?: Scope[]; name?: string } = {}
  if (change.scopes !== undefined) body.scopes = checkScopes(change.scopes)
  if (change.name !== undefined) body.name = checkName(change.name)
  if (!Object.keys(body).length) throw new Error('nothing to change')
  const r = await f(`${root(cfg)}/admin/tokens/${encodeURIComponent(id)}`, {
    method: 'PATCH', headers: { ...headers(cfg), 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  })
  await check(r, 'update')
  const token = parseToken(((await r.json()) as any)?.token)
  if (!token) throw new Error('the server sent no token')
  return token
}

/** Revokes a personal token at once (admin); it stays in the list with revokedAt, for attribution. */
export async function revokeToken(cfg: CloudConfig, id: string, f: typeof fetch = fetch): Promise<void> {
  const r = await f(`${root(cfg)}/admin/tokens/${encodeURIComponent(id)}`, { method: 'DELETE', headers: headers(cfg) })
  await check(r, 'revoke')
}

/** Removes an upload, finished or not (admin; tus termination). */
export async function deleteUpload(cfg: CloudConfig, id: string, f: typeof fetch = fetch): Promise<void> {
  const r = await f(base(cfg) + encodeURIComponent(id), { method: 'DELETE', headers: { ...headers(cfg), 'Tus-Resumable': '1.0.0' } })
  await check(r, 'delete')
}

const KEY_URL = 'cloudUrl'
const KEY_TOKEN = 'cloudToken'
const KEY_ADMIN = 'cloudAdminToken'

/** The server is the build's (DEFAULT_CLOUD_URL); only the access key is kept in this browser. */
export function loadCloud(): CloudConfig {
  try {
    return { url: DEFAULT_CLOUD_URL, token: localStorage.getItem(KEY_TOKEN) || '' }
  } catch {
    return { url: DEFAULT_CLOUD_URL, token: '' }
  }
}

export function saveCloud(c: Pick<CloudConfig, 'token'>) {
  try {
    localStorage.removeItem(KEY_URL)   // an address saved by an older version
    if (c.token.trim()) localStorage.setItem(KEY_TOKEN, c.token.trim())
    else localStorage.removeItem(KEY_TOKEN)
  } catch { /* storage blocked: kept for this page only */ }
}

/** The admin password: sessionStorage only (this tab; forgotten when it closes), never localStorage. */
export function loadAdminToken(): string {
  try { return sessionStorage.getItem(KEY_ADMIN) || '' } catch { return '' }
}

export function saveAdminToken(token: string) {
  try {
    const t = token.trim()
    if (t) sessionStorage.setItem(KEY_ADMIN, t)
    else sessionStorage.removeItem(KEY_ADMIN)
  } catch { /* storage blocked: kept for this page only */ }
}

export function humanSize(n: number | null): string {
  if (n == null) return '?'
  if (n < 1024 * 1024) return `${Math.max(1, Math.round(n / 1024))} KB`
  if (n < 1024 ** 3) return `${(n / 1024 / 1024).toFixed(1)} MB`
  return `${(n / 1024 ** 3).toFixed(2)} GB`
}

/** Where a token was seen: "Thu Duc, VN" (city, else region, and the country); '' when unknown. */
export function seenPlace(s: Seen | null): string {
  if (!s) return ''
  return [s.city || s.region, s.country].filter(Boolean).join(', ')
}

/** A client name made short for a table: "App 0.16.0 (samsung SM-A546E)", "Web 0.1.0 (Chrome 131; Windows)". */
export function shortClient(client: string | null, userAgent: string | null = null): string {
  if (!client) return userAgent ? (userAgent.length > 32 ? userAgent.slice(0, 31) + '…' : userAgent) : ''
  const m = client.match(/^(.+?)\/(\S+)(?: \((.*)\))?$/)
  if (!m) return client.length > 40 ? client.slice(0, 39) + '…' : client
  const [, name, ver, paren] = m
  const label = name === 'POI Capture' ? 'App' : name === 'POI Capture Reviewer' ? 'Web' : name
  // the phone's "(Android 14; samsung SM-A546E)": the device says more than the OS
  const detail = paren ? (label === 'App' ? paren.split(';').pop()!.trim() : paren) : ''
  return detail ? `${label} ${ver} (${detail})` : `${label} ${ver}`
}

// ---- reviews (server/README.md "Reviews"): one reviewer at a time checks a session out, saves, checks in

export const REVIEWS_SCHEMA = 'poi-cloud-reviews/1'
export type ReviewInfo = {
  sessionId: string
  lock: { by: { id: string; name: string }; at: string; until: string } | null
  saved: { by: { id: string; name: string }; at: string; size: number; done: number | null; total: number | null; version?: string | null } | null
  /** How the last checkout ended (null while checked out, or never): checked in, given up, released, revoked, expired. */
  ended?: { of: { id: string; name: string }; by: string | null; at: string; reason: EndReason } | null
}
export type EndReason = 'checkin' | 'gaveup' | 'released' | 'revoked' | 'expired'
const END_REASONS: EndReason[] = ['checkin', 'gaveup', 'released', 'revoked', 'expired']

export type ReviewAction = 'checkout' | 'save' | 'checkin' | 'gaveup' | 'released' | 'revoked' | 'expired' | 'restored'
/** One line of a session's review history; `version`: a save still kept on the server (an admin can restore it). */
export type ReviewEvent = {
  at: string; action: ReviewAction; by: { id: string; name: string } | null; of: string | null; version: string | null
  done: number | null; total: number | null; note: string | null
}

function parseReviewInfo(v: any): ReviewInfo | null {
  if (!v || typeof v.sessionId !== 'string') return null
  const by = (b: any) => (b && typeof b.id === 'string' ? { id: b.id, name: str(b.name) || b.id } : null)
  const lb = by(v.lock?.by), sb = by(v.saved?.by)
  return {
    sessionId: v.sessionId,
    lock: lb && str(v.lock.at) && str(v.lock.until) ? { by: lb, at: v.lock.at, until: v.lock.until } : null,
    saved: sb && str(v.saved.at) ? { by: sb, at: v.saved.at, size: num(v.saved.size) ?? 0, done: num(v.saved.done), total: num(v.saved.total), version: str(v.saved.version) } : null,
    ended: by(v.ended?.of) && str(v.ended.at) && END_REASONS.includes(v.ended.reason)
      ? { of: by(v.ended.of)!, by: str(v.ended.by), at: v.ended.at, reason: v.ended.reason } : null,
  }
}

/** Someone else has the session checked out (409): who, from the server's answer. */
export class CheckedOut extends CloudError {
  constructor(public info: ReviewInfo) { super(`checked out by ${info.lock?.by.name || 'someone else'}`, 409) }
}

export async function listReviews(cfg: CloudConfig, f: typeof fetch = fetch): Promise<ReviewInfo[]> {
  const r = await f(`${root(cfg)}/reviews`, { headers: headers(cfg), cache: 'no-store' })
  await check(r, 'reviews')
  const j = (await r.json()) as any
  if (j?.schema !== REVIEWS_SCHEMA || !Array.isArray(j.reviews)) throw new Error('not a reviews list')
  return j.reviews.map(parseReviewInfo).filter((x: ReviewInfo | null): x is ReviewInfo => !!x)
}

async function reviewAnswer(r: Response, what: string): Promise<ReviewInfo> {
  if (r.status === 409) {
    const info = parseReviewInfo(await r.json().catch(() => null))
    throw new CheckedOut(info || { sessionId: '', lock: null, saved: null })
  }
  await check(r, what)
  const info = parseReviewInfo(await r.json())
  if (!info) throw new Error(`${what}: unexpected answer`)
  return info
}

export async function checkout(cfg: CloudConfig, sessionId: string, f: typeof fetch = fetch): Promise<ReviewInfo> {
  return reviewAnswer(await f(`${root(cfg)}/reviews/${encodeURIComponent(sessionId)}/checkout`, { method: 'POST', headers: headers(cfg) }), 'check out')
}

/** Give up the checkout (one's own; an admin's: anybody's) without saving. */
export async function releaseCheckout(cfg: CloudConfig, sessionId: string, f: typeof fetch = fetch): Promise<void> {
  const r = await f(`${root(cfg)}/reviews/${encodeURIComponent(sessionId)}/checkout`, { method: 'DELETE', headers: headers(cfg) })
  if (r.status === 404) return   // not checked out (any more)
  await check(r, 'release')
}

/** What happened to a session's review, newest first. */
export async function reviewHistory(cfg: CloudConfig, sessionId: string, f: typeof fetch = fetch): Promise<ReviewEvent[]> {
  const r = await f(`${root(cfg)}/reviews/${encodeURIComponent(sessionId)}/history`, { headers: headers(cfg), cache: 'no-store' })
  await check(r, 'history')
  const j = (await r.json()) as any
  if (j?.schema !== 'poi-cloud-review-history/1' || !Array.isArray(j.events)) throw new Error('not a review history')
  return j.events.filter((e: any) => e && str(e.at) && typeof e.action === 'string').map((e: any) => ({
    at: e.at, action: e.action, by: e.by && typeof e.by.id === 'string' ? { id: e.by.id, name: str(e.by.name) || e.by.id } : null,
    of: str(e.of), version: str(e.version), done: num(e.done), total: num(e.total), note: str(e.note),
  }))
}

/** An admin makes an earlier saved version the session's review (409 while someone has it checked out). */
export async function restoreReview(cfg: CloudConfig, sessionId: string, version: string, f: typeof fetch = fetch): Promise<ReviewInfo> {
  return reviewAnswer(await f(`${root(cfg)}/reviews/${encodeURIComponent(sessionId)}/restore`, {
    method: 'POST', headers: { ...headers(cfg), 'Content-Type': 'application/json' }, body: JSON.stringify({ version }),
  }), 'restore')
}

/** The last saved review package; null when nothing was saved yet. */
export async function getReview(cfg: CloudConfig, sessionId: string, f: typeof fetch = fetch): Promise<Uint8Array | null> {
  const r = await f(`${root(cfg)}/reviews/${encodeURIComponent(sessionId)}`, { headers: headers(cfg), cache: 'no-store' })
  if (r.status === 404) return null
  await check(r, 'review')
  return new Uint8Array(await r.arrayBuffer())
}

/** Save the review package (needs the checkout); [checkin] also releases it. */
export async function putReviewPackage(cfg: CloudConfig, sessionId: string, zip: Uint8Array, p: { done: number; total: number },
  checkin: boolean, f: typeof fetch = fetch): Promise<ReviewInfo> {
  const r = await f(`${root(cfg)}/reviews/${encodeURIComponent(sessionId)}${checkin ? '?checkin=1' : ''}`, {
    method: 'PUT', body: zip,
    headers: { ...headers(cfg), 'Content-Type': 'application/zip', 'X-Review-Done': String(p.done), 'X-Review-Total': String(p.total) },
  })
  return reviewAnswer(r, checkin ? 'check in' : 'save')
}
