// Pure tus helpers and constants (the Worker's main module may export only handlers and classes).

export const TUS_VERSION = '1.0.0'
export const EXTENSIONS = 'creation,creation-with-upload,termination,expiration,concatenation'
export const PART_SIZE = 8 * 1024 * 1024        // R2: >= 5 MiB, equal for every part but the last; = cloud.py's chunk
export const DEFAULT_MAX_SIZE = 4 * 1024 ** 3   // 4 GiB per upload unless MAX_SIZE says otherwise

export function parseMetadata(header: string | null): Record<string, string> | null {
  const out: Record<string, string> = {}
  for (const pair of (header || '').split(',')) {
    const p = pair.trim()
    if (!p) continue
    const [k, v, ...rest] = p.split(' ')
    if (!k || rest.length || k in out) return null
    if (!v) { out[k] = ''; continue }
    try {
      const bin = atob(v)
      out[k] = new TextDecoder('utf-8', { fatal: true, ignoreBOM: false }).decode(Uint8Array.from(bin, (c) => c.charCodeAt(0)))
    } catch { return null }
  }
  return out
}

export function encodeMetadata(m: Record<string, string>): string {
  return Object.entries(m).map(([k, v]) => {
    if (!v) return k
    const bytes = new TextEncoder().encode(v)
    let bin = ''
    for (const b of bytes) bin += String.fromCharCode(b)
    return `${k} ${btoa(bin)}`
  }).join(',')
}

/** CORS_ORIGINS: "*", or exact origins and "https://*.example.dev" patterns, comma-separated. */
export function corsOrigin(allowed: string | undefined, origin: string | null): string | null {
  const list = (allowed || '*').split(',').map((s) => s.trim()).filter(Boolean)
  if (list.includes('*')) return '*'
  if (!origin) return null
  for (const a of list) {
    if (a === origin) return origin
    const m = /^(https?:\/\/)\*\.(.+)$/.exec(a)
    if (m && origin.startsWith(m[1]) && origin.endsWith('.' + m[2]) && !origin.slice(m[1].length).includes('/')) return origin
  }
  return null
}
