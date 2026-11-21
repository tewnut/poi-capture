import { SELF, env } from 'cloudflare:test'
import { describe, expect, it } from 'vitest'
import { PART_SIZE, corsOrigin, encodeMetadata, parseMetadata } from '../src/tus'

const BASE = 'https://cloud.test/files/'
const AUTH = { Authorization: 'Bearer test-token', 'Tus-Resumable': '1.0.0' }
const MiB = 1024 * 1024

function bytes(n: number, seed = 1): Uint8Array {
  const b = new Uint8Array(n)
  let x = seed
  for (let i = 0; i < n; i++) { x = (x * 1103515245 + 12345) >>> 0; b[i] = x >>> 24 }
  return b
}

async function create(size: number, meta: Record<string, string> = { filename: 't.zip', type: 'raw-session' }) {
  const r = await SELF.fetch(BASE, { method: 'POST', headers: { ...AUTH, 'Upload-Length': String(size), 'Upload-Metadata': encodeMetadata(meta) } })
  expect(r.status).toBe(201)
  expect(r.headers.get('Location')).toMatch(/^https:\/\/cloud\.test\/files\/[0-9a-f]{32}$/)
  return r.headers.get('Location')!
}

/** As the phone sends it: POST + X-HTTP-Method-Override: PATCH. */
function patch(url: string, offset: number, body: Uint8Array, viaOverride = true) {
  return SELF.fetch(url, {
    method: viaOverride ? 'POST' : 'PATCH',
    headers: { ...AUTH, 'Upload-Offset': String(offset), 'Content-Type': 'application/offset+octet-stream', ...(viaOverride ? { 'X-HTTP-Method-Override': 'PATCH' } : {}) },
    body,
  })
}

async function upload(data: Uint8Array, chunks: number[] | number, viaOverride = true) {
  const url = await create(data.length)
  let off = 0
  let i = 0
  while (off < data.length) {
    const n = Array.isArray(chunks) ? chunks[Math.min(i++, chunks.length - 1)] : chunks
    const r = await patch(url, off, data.subarray(off, Math.min(off + n, data.length)), viaOverride)
    expect(r.status).toBe(204)
    const next = Number(r.headers.get('Upload-Offset'))
    expect(next).toBe(Math.min(off + n, data.length))
    off = next
  }
  return url
}

async function download(url: string) {
  const r = await SELF.fetch(url, { headers: { Authorization: 'Bearer test-token' } })
  expect(r.status).toBe(200)
  expect(r.headers.get('Content-Type')).toBe('application/octet-stream')
  return new Uint8Array(await r.arrayBuffer())
}

const same = (a: Uint8Array, b: Uint8Array) => a.length === b.length && a.every((v, i) => v === b[i])

describe('auth and CORS', () => {
  it('needs the token, preflight excepted', async () => {
    expect((await SELF.fetch(BASE + 'index.json')).status).toBe(401)
    expect((await SELF.fetch(BASE, { method: 'OPTIONS' })).status).toBe(401)
    expect((await SELF.fetch(BASE + 'index.json', { headers: { Authorization: 'Bearer wrong' } })).status).toBe(401)
    const pre = await SELF.fetch(BASE, { method: 'OPTIONS', headers: { Origin: 'http://localhost:5173', 'Access-Control-Request-Method': 'PATCH' } })
    expect(pre.status).toBe(204)
    expect(pre.headers.get('Access-Control-Allow-Origin')).toBe('http://localhost:5173')
    expect(pre.headers.get('Access-Control-Allow-Methods')).toContain('PATCH')
    expect(pre.headers.get('Access-Control-Allow-Headers')).toContain('Upload-Offset')
    const res = await SELF.fetch(BASE + 'index.json', { headers: { Authorization: 'Bearer test-token', Origin: 'https://abc.poi-capture-reviewer.pages.dev' } })
    expect(res.headers.get('Access-Control-Allow-Origin')).toBe('https://abc.poi-capture-reviewer.pages.dev')
    expect(res.headers.get('Access-Control-Expose-Headers')).toContain('Upload-Offset')
    const other = await SELF.fetch(BASE + 'index.json', { headers: { Authorization: 'Bearer test-token', Origin: 'https://evil.example' } })
    expect(other.headers.get('Access-Control-Allow-Origin')).toBeNull()
  })

  it('matches origins', () => {
    expect(corsOrigin(undefined, 'http://x')).toBe('*')
    expect(corsOrigin('https://*.a.dev', 'https://b.a.dev')).toBe('https://b.a.dev')
    expect(corsOrigin('https://*.a.dev', 'https://a.dev')).toBeNull()
    expect(corsOrigin('https://*.a.dev', 'http://b.a.dev')).toBeNull()
    expect(corsOrigin('https://*.a.dev', 'https://b.evil.dev/.a.dev')).toBeNull()
  })

  it('answers OPTIONS with what it speaks', async () => {
    const r = await SELF.fetch(BASE, { method: 'OPTIONS', headers: AUTH })
    expect(r.status).toBe(204)
    expect(r.headers.get('Tus-Version')).toBe('1.0.0')
    expect(r.headers.get('Tus-Extension')!.split(',')).toContain('creation')
    expect(Number(r.headers.get('Tus-Max-Size'))).toBe(4 * 1024 ** 3)
  })
})

describe('metadata', () => {
  it('round-trips UTF-8 and bare keys', () => {
    const m = { name: 'Nguyễn Thanh Sơn [261117]', empty: '', filename: 'a.zip' }
    expect(parseMetadata(encodeMetadata(m))).toEqual(m)
    expect(parseMetadata('a !!!')).toBeNull()
    expect(parseMetadata('a YQ==,a YQ==')).toBeNull()
  })
})

describe('uploads', () => {
  it('phone: 2 MiB PATCHes as POST + override, HEAD as override, then index and download', async () => {
    const data = bytes(19 * MiB + 12345)
    const url = await upload(data, 2 * MiB)
    const h = await SELF.fetch(url, { method: 'POST', headers: { ...AUTH, 'X-HTTP-Method-Override': 'HEAD' } })
    expect(h.status).toBe(200)
    expect(h.headers.get('Upload-Offset')).toBe(String(data.length))
    expect(same(await download(url), data)).toBe(true)
    const idx = await (await SELF.fetch(BASE + 'index.json?type=raw-session', { headers: AUTH })).json() as any
    expect(idx.schema).toBe('poi-cloud-index/1')
    const u = idx.uploads.find((x: any) => url.endsWith(x.id))
    expect(u).toMatchObject({ size: data.length, offset: data.length, complete: true, metadata: { filename: 't.zip', type: 'raw-session' } })
    expect((await (await SELF.fetch(BASE + 'index.json?type=processed-result', { headers: AUTH })).json() as any).uploads).toEqual([])
  })

  it('uneven chunks across part boundaries, real PATCH', async () => {
    const data = bytes(3 * PART_SIZE + 777, 7)
    const url = await upload(data, [3 * MiB, 7 * MiB, 1, PART_SIZE, PART_SIZE + 5, 13 * MiB], false)
    expect(same(await download(url), data)).toBe(true)
  })

  it('agent: 8 MiB chunks; small and empty uploads', async () => {
    const data = bytes(2 * PART_SIZE + 5, 3)
    expect(same(await download(await upload(data, 8 * MiB, false)), data)).toBe(true)
    const small = bytes(1000, 9)
    expect(same(await download(await upload(small, [10, 990])), small)).toBe(true)
    const empty = await create(0)
    expect((await SELF.fetch(empty, { method: 'HEAD', headers: AUTH })).headers.get('Upload-Offset')).toBe('0')
    expect((await download(empty)).length).toBe(0)
  })

  it('creation-with-upload', async () => {
    const data = bytes(3 * MiB, 4)
    const r = await SELF.fetch(BASE, { method: 'POST', body: data.subarray(0, MiB),
      headers: { ...AUTH, 'Upload-Length': String(data.length), 'Content-Type': 'application/offset+octet-stream' } })
    expect(r.status).toBe(201)
    expect(r.headers.get('Upload-Offset')).toBe(String(MiB))
    const url = r.headers.get('Location')!
    expect((await patch(url, MiB, data.subarray(MiB))).status).toBe(204)
    expect(same(await download(url), data)).toBe(true)
  })

  it('refuses wrong offsets, too much, unfinished download, too large, no tus header', async () => {
    const url = await create(4 * MiB)
    expect((await patch(url, 0, bytes(MiB))).status).toBe(204)
    expect((await patch(url, 0, bytes(10))).status).toBe(409)
    expect((await patch(url, MiB, bytes(4 * MiB))).status).toBe(413)
    expect((await SELF.fetch(url, { method: 'HEAD', headers: AUTH })).headers.get('Upload-Offset')).toBe(String(MiB))
    const dl = await SELF.fetch(url, { headers: AUTH })
    expect(dl.status).toBe(404)
    expect(await dl.text()).toMatch(/not finished/)
    const big = await SELF.fetch(BASE, { method: 'POST', headers: { ...AUTH, 'Upload-Length': String(5 * 1024 ** 3) } })
    expect(big.status).toBe(413)
    expect((await SELF.fetch(BASE, { method: 'POST', headers: { Authorization: 'Bearer test-token', 'Upload-Length': '1' } })).status).toBe(412)
    expect((await SELF.fetch(BASE + 'nope', { method: 'HEAD', headers: AUTH })).status).toBe(404)
  })

  it('one PATCH at a time per upload (lease)', async () => {
    const url = await create(100)
    const id = url.split('/').pop()!
    const s = env.UPLOADS.get(env.UPLOADS.idFromName('uploads'))
    const a = await s.begin(id, 0)
    expect(a.ok).toBe(true)
    expect((await patch(url, 0, bytes(10))).status).toBe(423)
    if (a.ok) await s.release(id, a.lease)
    expect((await patch(url, 0, bytes(10))).status).toBe(204)
  })

  it('deletes unfinished uploads only, and expires abandoned ones', async () => {
    const done = await upload(bytes(10), 10)
    expect((await SELF.fetch(done, { method: 'POST', headers: { ...AUTH, 'X-HTTP-Method-Override': 'DELETE' } })).status).toBe(400)
    expect((await download(done)).length).toBe(10)
    const part = await create(PART_SIZE * 2)
    expect((await patch(part, 0, bytes(PART_SIZE + 10))).status).toBe(204)
    expect((await SELF.fetch(part, { method: 'DELETE', headers: AUTH })).status).toBe(204)
    expect((await SELF.fetch(part, { method: 'HEAD', headers: AUTH })).status).toBe(404)
    const id = part.split('/').pop()!
    expect((await env.BUCKET.list({ prefix: `carry/${id}/` })).objects.length).toBe(0)

    const old = await create(PART_SIZE * 2)
    expect((await patch(old, 0, bytes(100))).status).toBe(204)
    const s = env.UPLOADS.get(env.UPLOADS.idFromName('uploads'))
    const gone = await s.expire(Date.now() + 1000)
    expect(gone.map((g) => g.id)).toContain(old.split('/').pop())
    expect(gone.map((g) => g.id)).not.toContain(done.split('/').pop())
  })
})

describe('tokens and roles', () => {
  const ADMIN = { Authorization: 'Bearer admin-token' }
  const ROOT = 'https://cloud.test'
  const bearer = (t: string) => ({ Authorization: `Bearer ${t}` })

  async function issue(name: string, role: string) {
    const r = await SELF.fetch(ROOT + '/admin/tokens', { method: 'POST', headers: { ...ADMIN, 'Content-Type': 'application/json' }, body: JSON.stringify({ name, role }) })
    expect(r.status).toBe(201)
    const j = await r.json<{ token: { id: string; prefix: string; role: string }; secret: string }>()
    expect(j.secret).toMatch(/^pct_[A-Za-z0-9_-]{43}$/)
    expect(j.token.prefix).toBe(j.secret.slice(0, 12))
    return j
  }

  it('says who you are', async () => {
    const me = async (h: Record<string, string>) => (await SELF.fetch(ROOT + '/me', { headers: h })).json<{ id: string; role: string }>()
    expect(await me(ADMIN)).toMatchObject({ id: 'admin', role: 'admin' })
    expect(await me(bearer('test-token'))).toMatchObject({ id: 'shared', role: 'reviewer' })
    expect((await SELF.fetch(ROOT + '/me')).status).toBe(401)
  })

  it('issues, lists, uses and revokes personal tokens', async () => {
    const phone = await issue('Sơn – phone', 'uploader')
    const web = await issue('Reviewer laptop', 'reviewer')
    const P = { ...bearer(phone.secret), 'Tus-Resumable': '1.0.0' }

    // uploader: create + patch, but no listing, download, or admin
    let r = await SELF.fetch(BASE, { method: 'POST', headers: { ...P, 'Upload-Length': '5', 'Upload-Metadata': encodeMetadata({ filename: 'p.zip', type: 'raw-session' }) } })
    expect(r.status).toBe(201)
    const url = r.headers.get('Location')!
    r = await SELF.fetch(url, { method: 'PATCH', headers: { ...P, 'Upload-Offset': '0', 'Content-Type': 'application/offset+octet-stream' }, body: bytes(5) })
    expect(r.status).toBe(204)
    expect((await SELF.fetch(BASE + 'index.json', { headers: P })).status).toBe(403)
    expect((await SELF.fetch(url, { headers: P })).status).toBe(403)
    expect((await SELF.fetch(ROOT + '/admin/tokens', { headers: P })).status).toBe(403)
    expect((await SELF.fetch(url, { method: 'DELETE', headers: P })).status).toBe(400)   // finished: admin only

    // reviewer sees who uploaded it
    const idx = await (await SELF.fetch(BASE + 'index.json', { headers: bearer(web.secret) })).json<{ uploads: { id: string; by: { id: string; name: string } | null }[] }>()
    const id = url.split('/').pop()!
    expect(idx.uploads.find((u) => u.id === id)!.by).toEqual({ id: phone.token.id, name: 'Sơn – phone' })
    expect((await SELF.fetch(ROOT + '/admin/tokens', { headers: bearer(web.secret) })).status).toBe(403)

    // another uploader cannot terminate someone else's unfinished upload
    const other = await issue('Other phone', 'uploader')
    r = await SELF.fetch(BASE, { method: 'POST', headers: { ...P, 'Upload-Length': '50' } })
    const mine = r.headers.get('Location')!
    expect((await SELF.fetch(mine, { method: 'DELETE', headers: { ...bearer(other.secret), 'Tus-Resumable': '1.0.0' } })).status).toBe(403)
    expect((await SELF.fetch(mine, { method: 'DELETE', headers: P })).status).toBe(204)

    // the list: newest first, upload counts, no secrets
    const list = await (await SELF.fetch(ROOT + '/admin/tokens', { headers: ADMIN })).json<{ tokens: Record<string, unknown>[] }>()
    const row = list.tokens.find((t) => t.id === phone.token.id)!
    expect(row).toMatchObject({ name: 'Sơn – phone', role: 'uploader', uploads: 1, revokedAt: null })
    expect(row.lastUsedAt).not.toBeNull()
    expect(JSON.stringify(list)).not.toContain(phone.secret)

    // revoke: 401 at once, kept in the list
    expect((await SELF.fetch(ROOT + '/admin/tokens/' + phone.token.id, { method: 'DELETE', headers: ADMIN })).status).toBe(204)
    expect((await SELF.fetch(ROOT + '/me', { headers: P })).status).toBe(401)
    const after = await (await SELF.fetch(ROOT + '/admin/tokens', { headers: ADMIN })).json<{ tokens: { id: string; revokedAt: string | null }[] }>()
    expect(after.tokens.find((t) => t.id === phone.token.id)!.revokedAt).not.toBeNull()
    expect((await SELF.fetch(ROOT + '/admin/tokens/nope', { method: 'DELETE', headers: ADMIN })).status).toBe(404)
  })

  it('refuses bad token requests', async () => {
    const post = (body: unknown) => SELF.fetch(ROOT + '/admin/tokens', { method: 'POST', headers: { ...ADMIN, 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
    expect((await post({ name: 'x', role: 'admin' })).status).toBe(400)
    expect((await post({ name: '', role: 'uploader' })).status).toBe(400)
    expect((await post({ name: 'x'.repeat(81), role: 'uploader' })).status).toBe(400)
    expect((await SELF.fetch(ROOT + '/admin/tokens', { method: 'POST', headers: ADMIN, body: 'nope' })).status).toBe(400)
    expect((await SELF.fetch(ROOT + '/me', { headers: bearer('pct_' + 'A'.repeat(43)) })).status).toBe(401)
  })

  it('admin deletes finished uploads with their R2 object', async () => {
    const done = await upload(bytes(PART_SIZE + 10), PART_SIZE)
    const id = done.split('/').pop()!
    expect(await env.BUCKET.head(`files/${id}`)).not.toBeNull()
    expect((await SELF.fetch(done, { method: 'DELETE', headers: { ...ADMIN, 'Tus-Resumable': '1.0.0' } })).status).toBe(204)
    expect(await env.BUCKET.head(`files/${id}`)).toBeNull()
    expect((await SELF.fetch(done, { method: 'HEAD', headers: AUTH })).status).toBe(404)
    expect((await SELF.fetch(done, { method: 'DELETE', headers: { ...ADMIN, 'Tus-Resumable': '1.0.0' } })).status).toBe(404)
  })
})

describe('last seen', () => {
  const ROOT = 'https://cloud.test'
  const ADMIN = { Authorization: 'Bearer admin-token' }
  type Seen = { at: string; client: string | null; prevClient: string | null; prevAt: string | null; userAgent: string | null }
  type List = { tokens: { id: string; seen: Seen | null }[]; builtins: { id: string; seen: Seen | null }[] }

  async function seenOf(id: string, want: (s: Seen | null) => boolean): Promise<Seen | null> {
    for (let i = 0; i < 50; i++) {   // the record is written after the response (waitUntil)
      const l = await (await SELF.fetch(ROOT + '/admin/tokens', { headers: ADMIN })).json<List>()
      const s = [...l.tokens, ...l.builtins].find((t) => t.id === id)?.seen ?? null
      if (want(s)) return s
      await new Promise((r) => setTimeout(r, 20))
    }
    throw new Error(`seen for ${id} never matched`)
  }

  it('keeps the latest client, flags a second one, lists built-ins, never the IP', async () => {
    const r = await SELF.fetch(ROOT + '/admin/tokens', { method: 'POST', headers: { ...ADMIN, 'Content-Type': 'application/json' }, body: JSON.stringify({ name: 'Seen phone', role: 'uploader' }) })
    const { token, secret } = await r.json<{ token: { id: string; seen: unknown }; secret: string }>()
    expect(token.seen).toBeNull()
    const phoneA = 'POI Capture/0.16.0 (Android 14; samsung SM-A546E)'
    await SELF.fetch(ROOT + '/me', { headers: { Authorization: `Bearer ${secret}`, 'X-Client': phoneA, 'User-Agent': 'Dalvik/2.1.0', 'CF-Connecting-IP': '203.0.113.7' } })
    const a = await seenOf(token.id, (s) => s?.client === phoneA)
    expect(a).toMatchObject({ client: phoneA, userAgent: 'Dalvik/2.1.0', prevClient: null })
    expect(JSON.stringify(a)).not.toContain('203.0.113.7')

    const phoneB = 'POI Capture/0.16.0 (Android 15; Google Pixel 8)'
    await SELF.fetch(ROOT + '/me', { headers: { Authorization: `Bearer ${secret}`, 'X-Client': phoneB } })
    const b = await seenOf(token.id, (s) => s?.client === phoneB)
    expect(b).toMatchObject({ client: phoneB, prevClient: phoneA })
    expect(b!.prevAt).not.toBeNull()

    await SELF.fetch(ROOT + '/me', { headers: { Authorization: 'Bearer test-token', 'X-Client': 'cloud.py/1' } })
    expect(await seenOf('shared', (s) => s?.client === 'cloud.py/1')).not.toBeNull()
    expect(await seenOf('admin', (s) => s != null)).not.toBeNull()
  })

  it('lets browsers send X-Client', async () => {
    const r = await SELF.fetch(BASE, { method: 'OPTIONS', headers: { Origin: 'http://localhost:5173', 'Access-Control-Request-Method': 'GET', 'Access-Control-Request-Headers': 'x-client' } })
    expect(r.headers.get('Access-Control-Allow-Headers')).toContain('X-Client')
  })
})

describe('permissions (scopes)', () => {
  const ROOT = 'https://cloud.test'
  const ADMIN = { Authorization: 'Bearer admin-token' }
  const T = (secret: string) => ({ Authorization: `Bearer ${secret}`, 'Tus-Resumable': '1.0.0' })
  async function issue(name: string, scopes: string[]) {
    const r = await SELF.fetch(ROOT + '/admin/tokens', { method: 'POST', headers: { ...ADMIN, 'Content-Type': 'application/json' }, body: JSON.stringify({ name, scopes }) })
    expect(r.status).toBe(201)
    return r.json<{ token: { id: string; scopes: string[]; role: string }; secret: string }>()
  }
  const createAs = (secret: string, type: string) => SELF.fetch(BASE, { method: 'POST', headers: { ...T(secret), 'Upload-Length': '4', 'Upload-Metadata': encodeMetadata({ filename: 'x.zip', type }) } })

  it('checks each scope on its own', async () => {
    const phone = await issue('Phone', ['upload'])
    const reader = await issue('Partner', ['read'])
    const agent = await issue('Agent', ['process'])
    const cleaner = await issue('Lead', ['delete', 'upload'])
    expect(phone.token).toMatchObject({ scopes: ['upload'], role: 'uploader' })
    expect(reader.token).toMatchObject({ scopes: ['read'], role: 'reviewer' })
    expect(agent.token).toMatchObject({ scopes: ['process'], role: 'agent' })
    expect(cleaner.token.scopes).toEqual(['upload', 'delete'])   // known order

    // send rides: raw sessions yes, processed results no, no listing
    const raw = await createAs(phone.secret, 'raw-session')
    expect(raw.status).toBe(201)
    expect((await createAs(phone.secret, 'processed-result')).status).toBe(403)
    expect((await SELF.fetch(BASE + 'index.json', { headers: T(phone.secret) })).status).toBe(403)
    // read only: lists and downloads, cannot upload or PATCH someone's upload
    expect((await SELF.fetch(BASE + 'index.json', { headers: T(reader.secret) })).status).toBe(200)
    expect((await createAs(reader.secret, 'raw-session')).status).toBe(403)
    const rawUrl = raw.headers.get('Location')!
    expect((await SELF.fetch(rawUrl, { method: 'PATCH', headers: { ...T(reader.secret), 'Upload-Offset': '0', 'Content-Type': 'application/offset+octet-stream' }, body: bytes(4) })).status).toBe(403)
    expect((await SELF.fetch(rawUrl, { method: 'PATCH', headers: { ...T(phone.secret), 'Upload-Offset': '0', 'Content-Type': 'application/offset+octet-stream' }, body: bytes(4) })).status).toBe(204)
    // process: reads, uploads processed results only
    expect((await SELF.fetch(BASE + 'index.json', { headers: T(agent.secret) })).status).toBe(200)
    expect((await SELF.fetch(rawUrl, { headers: T(agent.secret) })).status).toBe(200)
    expect((await createAs(agent.secret, 'processed-result')).status).toBe(201)
    expect((await createAs(agent.secret, 'raw-session')).status).toBe(403)
    // delete: anyone's finished upload; without it, not even a finished own one
    expect((await SELF.fetch(rawUrl, { method: 'DELETE', headers: T(phone.secret) })).status).toBe(400)
    expect((await SELF.fetch(rawUrl, { method: 'DELETE', headers: T(cleaner.secret) })).status).toBe(204)
    // /me
    const m = await (await SELF.fetch(ROOT + '/me', { headers: T(agent.secret) })).json()
    expect(m).toMatchObject({ scopes: ['process'], admin: false, role: 'agent' })
    expect(await (await SELF.fetch(ROOT + '/me', { headers: ADMIN })).json()).toMatchObject({ admin: true, scopes: ['upload', 'read', 'process', 'delete'] })
  })

  it('changes a token\'s scopes and name; refuses bad changes', async () => {
    const t = await issue('Changing', ['upload'])
    const patchTok = (body: unknown, id = t.token.id) => SELF.fetch(ROOT + '/admin/tokens/' + id, { method: 'PATCH', headers: { ...ADMIN, 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
    let r = await patchTok({ scopes: ['read'], name: 'Changed' })
    expect(r.status).toBe(200)
    expect((await r.json<{ token: unknown }>()).token).toMatchObject({ name: 'Changed', scopes: ['read'], role: 'reviewer' })
    expect((await createAs(t.secret, 'raw-session')).status).toBe(403)
    expect((await SELF.fetch(BASE + 'index.json', { headers: T(t.secret) })).status).toBe(200)
    expect((await patchTok({ scopes: [] })).status).toBe(400)
    expect((await patchTok({ scopes: ['upload', 'admin'] })).status).toBe(400)
    expect((await patchTok({})).status).toBe(400)
    expect((await patchTok({ scopes: ['read'] }, 'nope')).status).toBe(404)
    await SELF.fetch(ROOT + '/admin/tokens/' + t.token.id, { method: 'DELETE', headers: ADMIN })
    expect((await patchTok({ scopes: ['upload'] })).status).toBe(409)
    const nonAdmin = await issue('Not admin', ['read', 'process', 'delete', 'upload'])
    expect((await SELF.fetch(ROOT + '/admin/tokens/' + t.token.id, { method: 'PATCH', headers: { ...T(nonAdmin.secret), 'Content-Type': 'application/json' }, body: '{"scopes":["read"]}' })).status).toBe(403)
  })

  it('lists built-ins with their scopes', async () => {
    const l = await (await SELF.fetch(ROOT + '/admin/tokens', { headers: ADMIN })).json<{ builtins: { id: string; scopes: string[] }[] }>()
    expect(l.builtins.find((b) => b.id === 'shared')!.scopes).toEqual(['upload', 'read', 'process'])
    expect(l.builtins.find((b) => b.id === 'admin')!.scopes).toEqual(['upload', 'read', 'process', 'delete'])
  })
})

describe('parallel uploads (concatenation)', () => {
  const ROOT = 'https://cloud.test'
  const ADMIN = { Authorization: 'Bearer admin-token' }
  async function key(name: string, scopes: string[]) {
    const r = await SELF.fetch(ROOT + '/admin/tokens', { method: 'POST', headers: { ...ADMIN, 'Content-Type': 'application/json' }, body: JSON.stringify({ name, scopes }) })
    return { Authorization: `Bearer ${(await r.json<{ secret: string }>()).secret}`, 'Tus-Resumable': '1.0.0' }
  }
  async function partial(h: Record<string, string>, data: Uint8Array, chunk: number) {
    const r = await SELF.fetch(BASE, { method: 'POST', headers: { ...h, 'Upload-Concat': 'partial', 'Upload-Length': String(data.length) } })
    expect(r.status).toBe(201)
    const url = r.headers.get('Location')!
    for (let off = 0; off < data.length; off += chunk) {
      const p = await SELF.fetch(url, { method: 'POST', headers: { ...h, 'X-HTTP-Method-Override': 'PATCH', 'Upload-Offset': String(off), 'Content-Type': 'application/offset+octet-stream' }, body: data.subarray(off, Math.min(off + chunk, data.length)) })
      expect(p.status).toBe(204)
    }
    return url
  }
  const final = (h: Record<string, string>, urls: string[], meta = { filename: 's.zip', type: 'raw-session', sessionId: 's_cat' }) =>
    SELF.fetch(BASE, { method: 'POST', headers: { ...h, 'Upload-Concat': 'final;' + urls.join(' '), 'Upload-Metadata': encodeMetadata(meta) } })

  it('joins partials uploaded in parallel; lists and downloads the whole file; deletes the parts with it', async () => {
    const phone = await key('Phone cat', ['upload'])
    const data = bytes(3 * PART_SIZE + 12345, 7)
    const cuts = [0, PART_SIZE, 2 * PART_SIZE, data.length]
    const urls = await Promise.all([0, 1, 2].map((i) => partial(phone, data.subarray(cuts[i], cuts[i + 1]), 3 * MiB)))
    expect((await SELF.fetch(BASE + 'index.json', { headers: AUTH })).status).toBe(200)
    const head = await SELF.fetch(urls[0], { method: 'HEAD', headers: phone })
    expect(head.headers.get('Upload-Concat')).toBe('partial')
    expect((await SELF.fetch(urls[0], { headers: AUTH })).status).toBe(404)   // a partial alone is not downloadable

    const r = await final(phone, urls)
    expect(r.status).toBe(201)
    const url = r.headers.get('Location')!
    expect(r.headers.get('Upload-Offset')).toBe(String(data.length))
    const h = await SELF.fetch(url, { method: 'HEAD', headers: phone })
    expect(h.headers.get('Upload-Length')).toBe(String(data.length))
    expect(h.headers.get('Upload-Offset')).toBe(String(data.length))
    expect(h.headers.get('Upload-Concat')).toMatch(/^final;\/files\/[0-9a-f]{32} /)

    const idx = await (await SELF.fetch(BASE + 'index.json', { headers: AUTH })).json<{ uploads: { id: string; size: number; complete: boolean }[] }>()
    const ids = idx.uploads.map((u) => u.id)
    expect(ids).toContain(url.split('/').pop())
    for (const u of urls) expect(ids).not.toContain(u.split('/').pop())   // partials are not listed
    expect(idx.uploads.find((u) => u.id === url.split('/').pop())).toMatchObject({ size: data.length, complete: true })

    const got = await download(url)
    expect(same(got, data)).toBe(true)

    expect((await final(phone, urls)).status).toBe(409)                       // used once only
    expect((await SELF.fetch(urls[1], { method: 'DELETE', headers: { ...ADMIN, 'Tus-Resumable': '1.0.0' } })).status).toBe(409)   // part of a joined upload
    expect((await SELF.fetch(url, { method: 'DELETE', headers: { ...ADMIN, 'Tus-Resumable': '1.0.0' } })).status).toBe(204)
    for (const u of urls) {
      expect((await SELF.fetch(u, { method: 'HEAD', headers: AUTH })).status).toBe(404)
      expect(await env.BUCKET.head(`files/${u.split('/').pop()}`)).toBeNull()
    }
  })

  it('refuses bad joins', async () => {
    const a = await key('Cat A', ['upload'])
    const b = await key('Cat B', ['upload'])
    const reader = await key('Cat reader', ['read'])
    const done = await partial(a, bytes(100), 100)
    const r = await SELF.fetch(BASE, { method: 'POST', headers: { ...a, 'Upload-Concat': 'partial', 'Upload-Length': '100' } })
    const unfinished = r.headers.get('Location')!
    const normal = await upload(bytes(10), 10)
    expect((await final(a, [done, unfinished])).status).toBe(400)            // not finished
    expect((await final(a, [done, normal])).status).toBe(400)                // not a partial
    expect((await final(a, [done, BASE + 'f'.repeat(32)])).status).toBe(404)
    expect((await final(b, [done])).status).toBe(403)                         // someone else's partial
    expect((await final(a, [done], { filename: 'r.zip', type: 'processed-result', sessionId: 'x' })).status).toBe(403)   // scope for the type
    expect((await final(a, [done, done])).status).toBe(400)                  // duplicates
    expect((await final(a, [])).status).toBe(400)
    expect((await SELF.fetch(BASE, { method: 'POST', headers: { ...reader, 'Upload-Concat': 'partial', 'Upload-Length': '5' } })).status).toBe(403)
    expect((await SELF.fetch(BASE, { method: 'POST', headers: { ...a, 'Upload-Concat': 'nonsense', 'Upload-Length': '5' } })).status).toBe(400)
    expect((await final(a, [done])).status).toBe(201)                         // still usable after the refusals
  })

  it('expires partials nobody joined; lists concatenation', async () => {
    const a = await key('Cat exp', ['upload'])
    const lonely = await partial(a, bytes(50), 50)
    const s = env.UPLOADS.get(env.UPLOADS.idFromName('uploads'))
    const gone = await s.expire(Date.now() + 1000)
    expect(gone.map((g) => g.id)).toContain(lonely.split('/').pop())
    const o = await SELF.fetch(BASE, { method: 'OPTIONS', headers: AUTH })
    expect(o.headers.get('Tus-Extension')!.split(',')).toContain('concatenation')
  })
})
