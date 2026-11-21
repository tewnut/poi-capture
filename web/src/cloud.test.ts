import { afterEach, describe, expect, it, vi } from 'vitest'
import { DEFAULT_CLOUD_URL, loadCloud, saveCloud, downloadItem, endpoint, fetchIndex, importState, parseIndex, processedItems, INDEX_SCHEMA, type CloudItem,
  serverRoot, setupLink, me, listTokens, issueToken, revokeToken, deleteUpload, CloudError, loadAdminToken, saveAdminToken,
  adminTokens, decodePolyline, parseSeen, twoClients, browserSummary, clientName } from './cloud'

const index = {
  schema: INDEX_SCHEMA,
  uploads: [
    { id: 'aaa111', size: 100, offset: 100, complete: true, createdAt: '2026-11-17T10:00:00.000Z',
      metadata: { type: 'processed-result', sessionId: 's1', name: 'Nguyễn Thanh Sơn [261117]', kind: 'sweep', filename: 's1.zip' } },
    { id: 'bbb222', size: 900, offset: 300, complete: false, createdAt: '2026-11-18T08:00:00.000Z',
      metadata: { type: 'processed-result', sessionId: 's2', filename: 's2.zip' } },
    { id: 'ccc333', size: 50, offset: 50, createdAt: '2026-11-18T09:00:00.000Z',
      metadata: { type: 'raw-session', sessionId: 'raw1', filename: 'poi-capture_place_2026.zip' } },
    { id: '../etc/passwd', size: 1, offset: 1, metadata: {} },
    { size: 1 },
    { id: 'ddd444', size: 10, offset: 10, complete: true, metadata: { type: 'processed-result' } },
  ],
}

describe('cloud index', () => {
  it('parses, drops malformed and unsafe ids, newest first', () => {
    const items = parseIndex(index)
    expect(items.map((i) => i.id)).toEqual(['ccc333', 'bbb222', 'aaa111', 'ddd444'])
    const a = items.find((i) => i.id === 'aaa111')!
    expect(a).toMatchObject({ type: 'processed-result', sessionId: 's1', name: 'Nguyễn Thanh Sơn [261117]', kind: 'sweep', complete: true, size: 100 })
    // complete is derived when the server leaves it out
    expect(items.find((i) => i.id === 'ccc333')!.complete).toBe(true)
    // no filename: one from the id
    expect(items.find((i) => i.id === 'ddd444')!.filename).toBe('ddd444.zip')
  })

  it('rejects what is not an index', () => {
    expect(() => parseIndex({})).toThrow(/no uploads/)
    expect(() => parseIndex(null)).toThrow()
    expect(() => parseIndex({ schema: 'other/9', uploads: [] })).toThrow(/schema/)
  })

  it('offers only finished processed packages', () => {
    expect(processedItems(parseIndex(index)).map((i) => i.id)).toEqual(['aaa111', 'ddd444'])
  })

  it('marks what is already imported, and a newer push of the same session', () => {
    const item = parseIndex(index).find((i) => i.id === 'aaa111')!
    expect(importState(item, [])).toBe('new')
    expect(importState(item, [{ id: 's1', source: 'raw', importedAt: '2026-11-17T12:00:00Z' }])).toBe('new')
    expect(importState(item, [{ id: 's1', source: 'result', importedAt: '2026-11-17T12:00:00.000Z' }])).toBe('imported')
    expect(importState(item, [{ id: 's1', source: 'result', importedAt: '2026-11-16T12:00:00.000Z' }])).toBe('updated')
    const noSession: CloudItem = { ...item, sessionId: null }
    expect(importState(noSession, [{ id: 's1', source: 'result', importedAt: '' }])).toBe('new')
  })

  it('normalises the endpoint', () => {
    expect(endpoint(' https://h.example/files ')).toBe('https://h.example/files/')
    expect(endpoint('http://localhost:1080/files//')).toBe('http://localhost:1080/files/')
    expect(endpoint('')).toBeNull()
    expect(endpoint('ftp://h/files')).toBeNull()
    expect(endpoint('not a url')).toBeNull()
  })
})

describe('cloud requests', () => {
  function fake(responses: Record<string, Response>) {
    const calls: { url: string; init?: RequestInit }[] = []
    const f = (async (url: string, init?: RequestInit) => {
      calls.push({ url, init })
      return responses[url] ?? new Response('nope', { status: 404 })
    }) as unknown as typeof fetch
    return { f, calls }
  }

  it('lists processed results with the bearer token', async () => {
    const { f, calls } = fake({ 'https://h/files/index.json?type=processed-result': new Response(JSON.stringify(index)) })
    const items = await fetchIndex({ url: 'https://h/files', token: 'Bearer t0k' }, f)
    expect(items.length).toBe(4)
    expect((calls[0].init!.headers as Record<string, string>).Authorization).toBe('Bearer t0k')
  })

  it('sends no Authorization without a token, and explains a refused one', async () => {
    const { f, calls } = fake({ 'https://h/files/index.json?type=processed-result': new Response('', { status: 401 }) })
    await expect(fetchIndex({ url: 'https://h/files/', token: '' }, f)).rejects.toThrow(/refused the token/)
    expect((calls[0].init!.headers as Record<string, string>).Authorization).toBeUndefined()
    await expect(fetchIndex({ url: '', token: '' }, f)).rejects.toThrow(/no cloud server/)
  })

  it('downloads an item as a zip File for the importer', async () => {
    const item = parseIndex(index).find((i) => i.id === 'aaa111')!
    const { f } = fake({ 'https://h/files/aaa111': new Response(new Uint8Array([80, 75, 3, 4])) })
    const file = await downloadItem({ url: 'https://h/files/', token: '' }, item, f)
    expect(file.name).toBe('s1.zip')
    expect(file.size).toBe(4)
  })
})

describe('cloud settings', () => {
  function storage() {
    const m = new Map<string, string>()
    vi.stubGlobal('localStorage', {
      getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v), removeItem: (k: string) => void m.delete(k),
    })
    return m
  }
  afterEach(() => vi.unstubAllGlobals())

  it('defaults to the team server, without a token', () => {
    storage()
    expect(endpoint(DEFAULT_CLOUD_URL)).toBe(DEFAULT_CLOUD_URL)
    expect(loadCloud()).toEqual({ url: DEFAULT_CLOUD_URL, token: '' })
  })

  it('keeps another server and the token; an emptied address goes back to the default', () => {
    const m = storage()
    saveCloud({ url: ' http://localhost:1080/files/ ', token: ' t0k ' })
    expect(loadCloud()).toEqual({ url: 'http://localhost:1080/files/', token: 't0k' })
    saveCloud({ url: '', token: 't0k' })
    expect(loadCloud().url).toBe(DEFAULT_CLOUD_URL)
    saveCloud({ url: DEFAULT_CLOUD_URL, token: '' })
    expect(m.has('cloudUrl')).toBe(false)
    expect(m.has('cloudToken')).toBe(false)
  })

  it('still works when storage is blocked', () => {
    vi.stubGlobal('localStorage', { getItem: () => { throw new Error('blocked') } })
    expect(loadCloud()).toEqual({ url: DEFAULT_CLOUD_URL, token: '' })
  })
})

describe('tokens and roles', () => {
  function fake(handler: (url: string, init?: RequestInit) => Response) {
    const calls: { url: string; init?: RequestInit }[] = []
    const f = (async (url: string, init?: RequestInit) => { calls.push({ url, init }); return handler(url, init) }) as unknown as typeof fetch
    return { f, calls }
  }
  const cfg = { url: 'https://h.example/files', token: 'adm1n' }
  const json = (v: unknown, status = 200) => new Response(JSON.stringify(v), { status, headers: { 'Content-Type': 'application/json' } })
  const tok = { id: 't1', name: 'Sơn – phone', role: 'uploader', prefix: 'pct_abcdefgh', createdAt: '2026-11-19T10:00:00.000Z',
    lastUsedAt: null, revokedAt: null, uploads: 3 }

  it('finds the server root from the tus endpoint', () => {
    expect(serverRoot('https://h.example/files/')).toBe('https://h.example')
    expect(serverRoot('http://localhost:8787/files')).toBe('http://localhost:8787')
    expect(serverRoot('nope')).toBeNull()
  })

  it('builds the phone setup link', () => {
    expect(setupLink('https://h.example/files', 'pct_s3cr3t', 'Sơn – phone'))
      .toBe('https://h.example/files/#token=pct_s3cr3t&name=S%C6%A1n%20%E2%80%93%20phone')
    expect(setupLink(' https://h.example/files// ', 'x', 'a&b=c')).toBe('https://h.example/files/#token=x&name=a%26b%3Dc')
  })

  it('reads the uploader of each index entry', () => {
    const items = parseIndex({ uploads: [
      { id: 'a1', size: 1, offset: 1, createdAt: '2026-11-19', metadata: {}, by: { id: 't1', name: 'Sơn' } },
      { id: 'a2', size: 1, offset: 1, createdAt: '2026-11-18', metadata: {}, by: null },
      { id: 'a3', size: 1, offset: 1, createdAt: '2026-11-17', metadata: {} },
    ] })
    expect(items.map((i) => i.by)).toEqual([{ id: 't1', name: 'Sơn' }, null, null])
  })

  it('asks /me at the server root', async () => {
    const { f, calls } = fake(() => json({ schema: 'poi-cloud-me/1', id: 'admin', name: 'Admin', role: 'admin' }))
    expect(await me(cfg, f)).toEqual({ id: 'admin', name: 'Admin', role: 'admin' })
    expect(calls[0].url).toBe('https://h.example/me')
    expect((calls[0].init!.headers as Record<string, string>).Authorization).toBe('Bearer adm1n')
  })

  it('tells a wrong token (401) from a role too low (403)', async () => {
    const e401 = await me(cfg, fake(() => new Response('', { status: 401 })).f).catch((e) => e)
    expect(e401).toBeInstanceOf(CloudError)
    expect(e401.status).toBe(401)
    const e403 = await listTokens(cfg, fake(() => new Response('', { status: 403 })).f).catch((e) => e)
    expect(e403.status).toBe(403)
    await expect(me(cfg, fake(() => json({ id: 'x', role: 'root' })).f)).rejects.toThrow(/me/)
  })

  it('lists tokens, dropping malformed ones', async () => {
    const { f, calls } = fake(() => json({ schema: 'poi-cloud-tokens/1', tokens: [tok, { id: 'bad' }, { ...tok, id: 't0', revokedAt: '2026-11-19T12:00:00Z' }] }))
    const list = await listTokens(cfg, f)
    expect(calls[0].url).toBe('https://h.example/admin/tokens')
    expect(list.map((t) => t.id)).toEqual(['t1', 't0'])
    expect(list[0]).toEqual({ ...tok, seen: null })
    expect(list[1].revokedAt).toBe('2026-11-19T12:00:00Z')
  })

  it('issues a token: POST JSON, returns the secret once', async () => {
    const { f, calls } = fake(() => json({ token: tok, secret: 'pct_s3cr3t' }, 201))
    const r = await issueToken(cfg, '  Sơn – phone ', 'uploader', f)
    expect(r.secret).toBe('pct_s3cr3t')
    expect(r.token.name).toBe('Sơn – phone')
    expect(calls[0].url).toBe('https://h.example/admin/tokens')
    expect(calls[0].init!.method).toBe('POST')
    expect((calls[0].init!.headers as Record<string, string>)['Content-Type']).toBe('application/json')
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ name: 'Sơn – phone', role: 'uploader' })
    await expect(issueToken(cfg, '  ', 'reviewer', f)).rejects.toThrow(/name/)
    await expect(issueToken(cfg, 'x'.repeat(81), 'reviewer', f)).rejects.toThrow(/name/)
    expect(calls.length).toBe(1)
  })

  it('revokes a token and deletes an upload', async () => {
    const { f, calls } = fake(() => new Response(null, { status: 204 }))
    await revokeToken(cfg, 't 1', f)
    expect(calls[0].url).toBe('https://h.example/admin/tokens/t%201')
    expect(calls[0].init!.method).toBe('DELETE')
    await deleteUpload(cfg, 'aaa111', f)
    expect(calls[1].url).toBe('https://h.example/files/aaa111')
    expect(calls[1].init!.method).toBe('DELETE')
    expect((calls[1].init!.headers as Record<string, string>)['Tus-Resumable']).toBe('1.0.0')
    expect((calls[1].init!.headers as Record<string, string>).Authorization).toBe('Bearer adm1n')
    const e = await revokeToken(cfg, 'zz', fake(() => new Response('', { status: 404 })).f).catch((x) => x)
    expect(e.status).toBe(404)
  })

  it('lists every upload when no type is asked', async () => {
    const { f, calls } = fake(() => json(index))
    await fetchIndex(cfg, f, '')
    expect(calls[0].url).toBe('https://h.example/files/index.json')
  })

  describe('admin password storage', () => {
    afterEach(() => vi.unstubAllGlobals())
    it('lives in sessionStorage only', () => {
      const s = new Map<string, string>()
      const l = new Map<string, string>()
      const mk = (m: Map<string, string>) => ({ getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v), removeItem: (k: string) => void m.delete(k) })
      vi.stubGlobal('sessionStorage', mk(s))
      vi.stubGlobal('localStorage', mk(l))
      saveAdminToken(' secret ')
      expect(loadAdminToken()).toBe('secret')
      expect(l.size).toBe(0)
      saveAdminToken('')
      expect(s.size).toBe(0)
      vi.stubGlobal('sessionStorage', { getItem: () => { throw new Error('blocked') }, setItem: () => { throw new Error('blocked') } })
      expect(loadAdminToken()).toBe('')
      expect(() => saveAdminToken('x')).not.toThrow()
    })
  })
})

describe('last seen and trips', () => {
  const seen = { at: '2026-11-20T10:00:00.000Z', country: 'VN', region: 'Ho Chi Minh', city: 'Thu Duc', network: 'Viettel Group',
    lat: 10.85, lon: 106.77, client: 'POI Capture/0.16.0 (Android 14; samsung SM-A546E)', userAgent: 'okhttp', prevClient: null, prevAt: null }
  const json = (v: unknown) => new Response(JSON.stringify(v), { headers: { 'Content-Type': 'application/json' } })
  const tok = { id: 't1', name: 'Sơn', role: 'uploader', prefix: 'pct_abcdefgh', createdAt: null, lastUsedAt: null, revokedAt: null, uploads: 0 }

  it('decodes a Google polyline', () => {
    expect(decodePolyline('_p~iF~ps|U_ulLnnqC_mqNvxq`@')).toEqual([[38.5, -120.2], [40.7, -120.95], [43.252, -126.453]])
    expect(decodePolyline('')).toEqual([])
    // a truncated string keeps the whole points before it
    expect(decodePolyline('_p~iF~ps|U_ulL')).toEqual([[38.5, -120.2]])
  })

  it('reads seen and the built-in identities; an older server sends neither', async () => {
    const f = (async () => json({ tokens: [{ ...tok, seen }, tok], builtins: [{ id: 'admin', name: 'Admin', role: 'admin', seen: null },
      { id: 'shared', name: 'Shared token', role: 'reviewer', seen: { ...seen, lat: 'x' } }, { name: 'no id' }] })) as unknown as typeof fetch
    const r = await adminTokens({ url: 'https://h/files/', token: 'a' }, f)
    expect(r.tokens[0].seen).toEqual(seen)
    expect(r.tokens[1].seen).toBeNull()
    expect(r.builtins.map((b) => b.id)).toEqual(['admin', 'shared'])
    expect(r.builtins[1].seen!.lat).toBeNull()
    expect(r.builtins[1].seen!.city).toBe('Thu Duc')
    const old = (async () => json({ tokens: [tok] })) as unknown as typeof fetch
    expect((await adminTokens({ url: 'https://h/files/', token: 'a' }, old)).builtins).toEqual([])
    expect(parseSeen({ city: 'x' })).toBeNull()
  })

  it('flags two clients within 7 days', () => {
    const s = parseSeen({ ...seen, prevClient: 'POI Capture/0.15.0 (Android 13; Xiaomi 2201117TG)', prevAt: '2026-11-15T10:00:00.000Z' })
    expect(twoClients(s)).toBe(true)
    expect(twoClients({ ...s!, prevAt: '2026-11-01T10:00:00.000Z' })).toBe(false)
    expect(twoClients({ ...s!, prevClient: s!.client })).toBe(false)
    expect(twoClients(parseSeen(seen))).toBe(false)
    expect(twoClients(null)).toBe(false)
  })

  it('reads trip metadata, numbers sent as strings too', () => {
    const items = parseIndex({ uploads: [
      { id: 'r1', size: 1, offset: 1, createdAt: '2026-11-19', metadata: { type: 'raw-session', track: '_p~iF~ps|U', startedAt: '2026-11-19T01:00:00Z', distanceM: '1234.5', photos: 12 } },
      { id: 'r2', size: 1, offset: 1, createdAt: '2026-11-18', metadata: { type: 'raw-session' } },
    ] })
    expect(items[0].trip).toEqual({ track: '_p~iF~ps|U', startedAt: '2026-11-19T01:00:00Z', endedAt: null, distanceM: 1234.5, photos: 12 })
    expect(items[0].metadata.type).toBe('raw-session')
    expect(items[1].trip).toBeNull()
  })

  it('names itself in X-Client', async () => {
    expect(browserSummary('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36')).toBe('Chrome 131; Windows')
    expect(browserSummary('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0')).toBe('Edge 131; Windows')
    expect(browserSummary('Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1')).toBe('Safari 17; iOS')
    expect(browserSummary('Mozilla/5.0 (X11; Linux x86_64; rv:133.0) Gecko/20100101 Firefox/133.0')).toBe('Firefox 133; Linux')
    expect(clientName('')).toMatch(/^POI Capture Reviewer\/\d+\.\d+\.\d+ \(browser\)$/)
    const calls: RequestInit[] = []
    const f = (async (_u: string, init: RequestInit) => { calls.push(init); return json({ uploads: [] }) }) as unknown as typeof fetch
    await fetchIndex({ url: 'https://h/files/', token: '' }, f)
    expect((calls[0].headers as Record<string, string>)['X-Client']).toMatch(/^POI Capture Reviewer\//)
  })
})

describe('seen, shown short', () => {
  it('shortens clients and places', async () => {
    const { shortClient, seenPlace } = await import('./cloud')
    expect(shortClient('POI Capture/0.16.0 (Android 14; samsung SM-A546E)')).toBe('App 0.16.0 (samsung SM-A546E)')
    expect(shortClient('POI Capture Reviewer/0.1.0 (Chrome 131; Windows)')).toBe('Web 0.1.0 (Chrome 131; Windows)')
    expect(shortClient('cloud.py/1')).toBe('cloud.py 1')
    expect(shortClient(null, 'curl/8.4.0')).toBe('curl/8.4.0')
    expect(shortClient(null, null)).toBe('')
    expect(seenPlace(null)).toBe('')
    expect(seenPlace(parseSeen({ at: 'x', city: 'Thu Duc', region: 'HCM', country: 'VN' }))).toBe('Thu Duc, VN')
    expect(seenPlace(parseSeen({ at: 'x', region: 'HCM' }))).toBe('HCM')
  })
})
