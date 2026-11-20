// Cloud storage: the tus server the phone uploads to and the agent pushes processed results to (server/README.md).
// tus has no listing, so the server adds GET <endpoint>index.json; a finished upload downloads with GET <endpoint><id>.
// The reviewer pulls PROCESSED packages only (metadata type=processed-result); raw sessions are for the agent.
// The server address and token are kept in this browser's localStorage, nowhere else. Without a saved address the
// team's server is used (DEFAULT_CLOUD_URL, the Cloudflare Worker in server/worker/); the token is always typed in.
import type { Dataset } from './types'

export const DEFAULT_CLOUD_URL = 'https://poi-capture-cloud.4hum-ai.workers.dev/files/'

export const INDEX_SCHEMA = 'poi-cloud-index/1'
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

const str = (v: unknown) => (typeof v === 'string' && v ? v : null)

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
    out.push({
      id: u.id, size, offset,
      complete: u.complete === true || (size != null && offset === size),
      createdAt: str(u.createdAt) || str(m.createdAt),
      type: str(m.type) || 'unknown', sessionId: str(m.sessionId), name: str(m.name), kind: str(m.kind),
      filename: str(m.filename) || `${u.id}.zip`,
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

function headers(cfg: CloudConfig): HeadersInit {
  const h: Record<string, string> = {}
  const t = cfg.token.trim().replace(/^Bearer\s+/i, '')
  if (t) h.Authorization = `Bearer ${t}`
  return h
}

async function check(r: Response, what: string) {
  if (r.ok) return
  if (r.status === 401 || r.status === 403) throw new Error(`${what}: the server refused the token (HTTP ${r.status})`)
  throw new Error(`${what}: HTTP ${r.status}`)
}

export async function fetchIndex(cfg: CloudConfig, f: typeof fetch = fetch): Promise<CloudItem[]> {
  const base = endpoint(cfg.url)
  if (!base) throw new Error('no cloud server set (Settings)')
  const r = await f(`${base}index.json?type=${PROCESSED}`, { headers: headers(cfg), cache: 'no-store' })
  await check(r, 'list')
  return parseIndex(await r.json())
}

/** The finished upload as a File, ready for importFiles(). */
export async function downloadItem(cfg: CloudConfig, item: CloudItem, f: typeof fetch = fetch): Promise<File> {
  const base = endpoint(cfg.url)
  if (!base) throw new Error('no cloud server set (Settings)')
  const r = await f(base + encodeURIComponent(item.id), { headers: headers(cfg), cache: 'no-store' })
  await check(r, item.filename)
  const name = /\.zip$/i.test(item.filename) ? item.filename : item.filename + '.zip'
  return new File([await r.blob()], name, { type: 'application/zip' })
}

const KEY_URL = 'cloudUrl'
const KEY_TOKEN = 'cloudToken'

/** The saved address, else the default one; an emptied field goes back to the default. */
export function loadCloud(): CloudConfig {
  try {
    return { url: localStorage.getItem(KEY_URL) || DEFAULT_CLOUD_URL, token: localStorage.getItem(KEY_TOKEN) || '' }
  } catch {
    return { url: DEFAULT_CLOUD_URL, token: '' }
  }
}

export function saveCloud(c: CloudConfig) {
  try {
    const url = c.url.trim()
    if (url && url !== DEFAULT_CLOUD_URL) localStorage.setItem(KEY_URL, url)
    else localStorage.removeItem(KEY_URL)   // the default is not pinned: a later default change reaches this browser
    if (c.token.trim()) localStorage.setItem(KEY_TOKEN, c.token.trim())
    else localStorage.removeItem(KEY_TOKEN)
  } catch { /* storage blocked: kept for this page only */ }
}

export function humanSize(n: number | null): string {
  if (n == null) return '?'
  if (n < 1024 * 1024) return `${Math.max(1, Math.round(n / 1024))} KB`
  if (n < 1024 ** 3) return `${(n / 1024 / 1024).toFixed(1)} MB`
  return `${(n / 1024 ** 3).toFixed(2)} GB`
}
