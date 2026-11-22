import { SELF, env } from 'cloudflare:test'
import { describe, expect, it } from 'vitest'
import { PART_SIZE, corsOrigin, encodeMetadata, parseMetadata } from '../src/tus'

const BASE = 'https://cloud.test/files/'
const AUTH = { Authorization: 'Bearer admin-token', 'Tus-Resumable': '1.0.0' }
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
  const r = await SELF.fetch(url, { headers: { Authorization: 'Bearer admin-token' } })
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
    const res = await SELF.fetch(BASE + 'index.json', { headers: { Authorization: 'Bearer admin-token', Origin: 'https://abc.poi-capture-reviewer.pages.dev' } })
    expect(res.headers.get('Access-Control-Allow-Origin')).toBe('https://abc.poi-capture-reviewer.pages.dev')
    expect(res.headers.get('Access-Control-Expose-Headers')).toContain('Upload-Offset')
    const other = await SELF.fetch(BASE + 'index.json', { headers: { Authorization: 'Bearer admin-token', Origin: 'https://evil.example' } })
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
    expect((await SELF.fetch(BASE, { method: 'POST', headers: { Authorization: 'Bearer admin-token', 'Upload-Length': '1' } })).status).toBe(412)
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
    // a phone's own finished upload: tus termination refuses it (deleting finished uploads is the admin's)
    const k = await SELF.fetch('https://cloud.test/admin/tokens', { method: 'POST', headers: { Authorization: 'Bearer admin-token', 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'phone', scopes: ['upload'] }) })
    const PHONE = { Authorization: `Bearer ${(await k.json<{ secret: string }>()).secret}`, 'Tus-Resumable': '1.0.0' }
    const c = await SELF.fetch(BASE, { method: 'POST', headers: { ...PHONE, 'Upload-Length': '10', 'Content-Type': 'application/offset+octet-stream',
      'Upload-Metadata': encodeMetadata({ filename: 'p.zip', type: 'raw-session' }) }, body: bytes(10) })
    expect(c.status).toBe(201)
    const done = new URL(c.headers.get('Location')!, BASE).toString()
    expect((await SELF.fetch(done, { method: 'POST', headers: { ...PHONE, 'X-HTTP-Method-Override': 'DELETE' } })).status).toBe(400)
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
    expect((await SELF.fetch(ROOT + '/me', { headers: bearer('test-token') })).status).toBe(401)   // no shared token any more
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
    expect(l.builtins.map((b) => b.id)).toEqual(['admin'])
    expect(l.builtins.find((b) => b.id === 'admin')!.scopes).toEqual(['upload', 'read', 'process', 'delete'])
  })
})

describe('direct uploads (presigned R2 parts)', () => {
  const ROOT = 'https://cloud.test'
  const KEY = { Authorization: 'Bearer admin-token', 'Content-Type': 'application/json' }
  const post = (path: string, body: unknown, h: Record<string, string> = KEY) =>
    SELF.fetch(ROOT + path, { method: 'POST', headers: h, body: JSON.stringify(body) })

  it('signs like the reference SigV4 (Python, checked against R2)', async () => {
    const { presign } = await import('../src/presign')
    const url = await presign({ accountId: 'acc123', bucket: 'bkt', accessKeyId: 'AKIDEXAMPLE', secretAccessKey: 'wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY' },
      'PUT', 'files/abc', { partNumber: '3', uploadId: 'up+id/=x' }, 3600, new Date('2026-11-22T01:02:03.456Z'))
    expect(url).toBe('https://acc123.r2.cloudflarestorage.com/bkt/files/abc?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=AKIDEXAMPLE%2F20261122%2Fauto%2Fs3%2Faws4_request&X-Amz-Date=20261122T010203Z&X-Amz-Expires=3600&X-Amz-SignedHeaders=host&partNumber=3&uploadId=up%2Bid%2F%3Dx&X-Amz-Signature=28b1e54ab7696614d48012eda8564d308b0f6c185a7c60c3bd83f1df67f4fa7b')
  })

  it('creates, takes the parts (written here through the binding) and completes into an ordinary upload', async () => {
    const size = 8 * MiB + 1000
    const r = await post('/direct', { size, metadata: { type: 'raw-session', filename: 'd.zip' } })
    expect(r.status).toBe(201)
    const d = await r.json<{ id: string; location: string; partSize: number; parts: number; urls: { part: number; url: string }[] }>()
    expect(d.partSize).toBe(8 * MiB)
    expect(d.parts).toBe(2)
    expect(d.urls.map((u) => u.part)).toEqual([1, 2])
    expect(d.urls[0].url).toContain('partNumber=1')
    expect(d.location).toBe(`${ROOT}/files/${d.id}`)
    // listed as unfinished, and not a tus PATCH target
    expect((await (await SELF.fetch(BASE + 'index.json', { headers: AUTH })).json<{ uploads: { id: string; complete: boolean }[] }>()).uploads.find((u) => u.id === d.id)?.complete).toBe(false)
    expect((await patch(d.location, 0, bytes(10))).status).toBe(400)
    // fresh URLs for one part
    const again = await post(`/direct/${d.id}/urls`, { parts: [2] })
    expect(again.status).toBe(200)
    expect((await again.json<{ urls: { part: number }[] }>()).urls.map((u) => u.part)).toEqual([2])
    expect((await post(`/direct/${d.id}/urls`, { parts: [3] })).status).toBe(400)
    // the parts, as R2 would take them from the presigned PUTs
    const data = bytes(size, 7)
    const row = await env.UPLOADS.get(env.UPLOADS.idFromName('uploads')).get(d.id)
    const mp = env.BUCKET.resumeMultipartUpload(`files/${d.id}`, row!.mpId!)
    const p1 = await mp.uploadPart(1, data.subarray(0, 8 * MiB))
    const p2 = await mp.uploadPart(2, data.subarray(8 * MiB))
    // wrong list: refused, nothing changes
    expect((await post(`/direct/${d.id}/complete`, { parts: [{ part: 1, etag: p1.etag }] })).status).toBe(400)
    // another key may not complete it
    const other = await SELF.fetch(ROOT + '/admin/tokens', { method: 'POST', headers: { Authorization: 'Bearer admin-token', 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'other', scopes: ['upload'] }) })
    const secret = (await other.json<{ secret: string }>()).secret
    expect((await post(`/direct/${d.id}/complete`, { parts: [] }, { Authorization: `Bearer ${secret}`, 'Content-Type': 'application/json' })).status).toBe(403)
    const done = await post(`/direct/${d.id}/complete`, { parts: [{ part: 1, etag: p1.etag }, { part: 2, etag: p2.etag }] })
    expect(done.status).toBe(200)
    expect(await done.json()).toMatchObject({ id: d.id, size, complete: true })
    // a repeat is fine (the first answer may have been lost)
    expect((await post(`/direct/${d.id}/complete`, { parts: [] })).status).toBe(200)
    const h = await SELF.fetch(d.location, { method: 'HEAD', headers: AUTH })
    expect(h.headers.get('Upload-Offset')).toBe(String(size))
    const dl = await SELF.fetch(d.location, { headers: AUTH })
    expect(new Uint8Array(await dl.arrayBuffer())).toEqual(data)
    const idx = await (await SELF.fetch(BASE + 'index.json', { headers: AUTH })).json<{ uploads: { id: string; complete: boolean }[] }>()
    expect(idx.uploads.find((u) => u.id === d.id)?.complete).toBe(true)
    expect((await SELF.fetch(d.location, { method: 'DELETE', headers: { Authorization: 'Bearer admin-token', 'Tus-Resumable': '1.0.0' } })).status).toBe(204)
  }, 120_000)

  it('checks permissions and input', async () => {
    const reader = await SELF.fetch(ROOT + '/admin/tokens', { method: 'POST', headers: { Authorization: 'Bearer admin-token', 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'reader', scopes: ['read'] }) })
    const secret = (await reader.json<{ secret: string }>()).secret
    expect((await post('/direct', { size: 10, metadata: { type: 'raw-session' } }, { Authorization: `Bearer ${secret}`, 'Content-Type': 'application/json' })).status).toBe(403)
    expect((await post('/direct', { size: 0 })).status).toBe(400)
    expect((await post('/direct', { size: 10, metadata: { type: 5 } })).status).toBe(400)
    expect((await post('/direct', { size: 10 }, { 'Content-Type': 'application/json' })).status).toBe(401)
    expect((await post('/direct/abc/complete', { parts: [] })).status).toBe(404)
  })
})

describe('reviews (checkout / check-in)', () => {
  const ROOT = 'https://cloud.test'
  async function key(name: string, scopes: string[]) {
    const r = await SELF.fetch(ROOT + '/admin/tokens', { method: 'POST', headers: { Authorization: 'Bearer admin-token', 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, scopes }) })
    return { Authorization: `Bearer ${(await r.json<{ secret: string }>()).secret}` }
  }
  const put = (h: Record<string, string>, sid: string, body: Uint8Array, checkin = false, done = '3', total = '10') =>
    SELF.fetch(`${ROOT}/reviews/${sid}${checkin ? '?checkin=1' : ''}`, { method: 'PUT', body,
      headers: { ...h, 'Content-Type': 'application/zip', 'X-Review-Done': done, 'X-Review-Total': total } })

  it('one reviewer at a time: check out, save, check in, then the next one', async () => {
    const son = await key('Sơn', ['read'])
    const lan = await key('Lan', ['read'])
    const sid = 's_review1'
    const a = await SELF.fetch(`${ROOT}/reviews/${sid}/checkout`, { method: 'POST', headers: son })
    expect(a.status).toBe(200)
    expect((await a.json<any>()).lock.by.name).toBe('Sơn')
    // Lan cannot check it out, nor save over it
    const b = await SELF.fetch(`${ROOT}/reviews/${sid}/checkout`, { method: 'POST', headers: lan })
    expect(b.status).toBe(409)
    expect((await b.json<any>()).lock.by.name).toBe('Sơn')
    expect((await put(lan, sid, bytes(10))).status).toBe(409)
    // Sơn saves (keeps the lock), then checks in
    const s1 = await put(son, sid, bytes(100, 3))
    expect(s1.status).toBe(200)
    expect(await s1.json<any>()).toMatchObject({ lock: { by: { name: 'Sơn' } }, saved: { by: { name: 'Sơn' }, size: 100, done: 3, total: 10 } })
    const s2 = await put(son, sid, bytes(120, 4), true, '10', '10')
    expect((await s2.json<any>())).toMatchObject({ lock: null, saved: { size: 120, done: 10 } })
    expect(new Uint8Array(await (await SELF.fetch(`${ROOT}/reviews/${sid}`, { headers: lan })).arrayBuffer())).toEqual(bytes(120, 4))
    // after check-in, saving needs a new checkout; Lan can take it now
    expect((await put(son, sid, bytes(10))).status).toBe(409)
    expect((await SELF.fetch(`${ROOT}/reviews/${sid}/checkout`, { method: 'POST', headers: lan })).status).toBe(200)
    const list = await (await SELF.fetch(`${ROOT}/reviews`, { headers: lan })).json<any>()
    expect(list.reviews.find((r: any) => r.sessionId === sid)).toMatchObject({ lock: { by: { name: 'Lan' } }, saved: { by: { name: 'Sơn' } } })
  })

  it('giving up a checkout: one\'s own, or any as admin; a phone key cannot review', async () => {
    const son = await key('Sơn', ['read'])
    const lan = await key('Lan', ['read'])
    const phone = await key('Phone', ['upload'])
    const sid = 's_review2'
    expect((await SELF.fetch(`${ROOT}/reviews/${sid}/checkout`, { method: 'POST', headers: phone })).status).toBe(403)
    expect((await SELF.fetch(`${ROOT}/reviews/${sid}/checkout`, { method: 'POST', headers: son })).status).toBe(200)
    expect((await SELF.fetch(`${ROOT}/reviews/${sid}/checkout`, { method: 'DELETE', headers: lan })).status).toBe(403)
    expect((await SELF.fetch(`${ROOT}/reviews/${sid}/checkout`, { method: 'DELETE', headers: { Authorization: 'Bearer admin-token' } })).status).toBe(204)
    expect((await SELF.fetch(`${ROOT}/reviews/${sid}/checkout`, { method: 'POST', headers: lan })).status).toBe(200)
    expect((await SELF.fetch(`${ROOT}/reviews/${sid}/checkout`, { method: 'DELETE', headers: lan })).status).toBe(204)
    expect((await SELF.fetch(`${ROOT}/reviews/${sid}`, { headers: lan })).status).toBe(404)
  })
})
