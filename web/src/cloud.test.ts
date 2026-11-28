import { afterEach, describe, expect, it, vi } from 'vitest'
import { DEFAULT_CLOUD_URL, loadCloud, saveCloud, downloadItem, endpoint, fetchIndex, importState, parseIndex, processedItems, INDEX_SCHEMA, type CloudItem,
  serverRoot, setupLink, me, listTokens, issueToken, revokeToken, deleteUpload, CloudError, loadAdminToken, saveAdminToken,
  adminTokens, updateToken, parseScopes, roleOf, SCOPES, PRESETS, decodePolyline, parseSeen, twoClients, browserSummary, clientName, listReviews, listProcessing, reviewHistory, restoreReview } from './cloud'

const index = {
  schema: INDEX_SCHEMA,
  uploads: [
    { id: 'aaa111', size: 100, offset: 100, complete: true, createdAt: '2026-11-17T10:00:00.000Z',
      metadata: { type: 'processed-result', sessionId: 's1', name: 'Nguyễn Thanh Sơn [261117]', kind: 'sweep', filename: 's1.zip' } },
    { id: 'bbb222', size: 900, offset: 300, complete: false, createdAt: '2026-11-18T08:00:00.000Z',
      metadata: { type: 'processed-result', sessionId: 's2', filename: 's2.zip' } },
    { id: 'ccc333', size: 50, offset: 50, createdAt: '2026-11-18T09:00:00.000Z',
      metadata: { type: 'raw-session', sessionId: 'raw1', target: 'signs', filename: 'poi-capture_place_2026.zip' } },
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
    // the ride's target (docs/sweep-targets.md); none sent = null (shops)
    expect(items.find((i) => i.id === 'ccc333')!.target).toBe('signs')
    expect(a.target).toBeNull()
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

  it('keeps only the key: the server comes from the build, an address saved by an older version is dropped', () => {
    const m = storage()
    m.set('cloudUrl', 'http://localhost:1080/files/')
    saveCloud({ token: ' t0k ' })
    expect(loadCloud()).toEqual({ url: DEFAULT_CLOUD_URL, token: 't0k' })
    expect(m.has('cloudUrl')).toBe(false)
    saveCloud({ token: '' })
    expect(m.has('cloudToken')).toBe(false)
  })

  it('still works when storage is blocked', () => {
    vi.stubGlobal('localStorage', { getItem: () => { throw new Error('blocked') } })
    expect(loadCloud()).toEqual({ url: DEFAULT_CLOUD_URL, token: '' })
  })
})

describe('tokens and permissions', () => {
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
    const { f, calls } = fake(() => json({ schema: 'poi-cloud-me/1', id: 'admin', name: 'Admin', scopes: ['delete', 'upload', 'process', 'read'], admin: true, role: 'admin' }))
    expect(await me(cfg, f)).toEqual({ id: 'admin', name: 'Admin', scopes: ['upload', 'read', 'process', 'delete'], admin: true, role: 'admin' })
    expect(calls[0].url).toBe('https://h.example/me')
    expect((calls[0].init!.headers as Record<string, string>).Authorization).toBe('Bearer adm1n')
  })

  it('reads /me with scopes, and from an older server that sends only a role', async () => {
    const ans = async (v: unknown) => me(cfg, fake(() => json(v)).f)
    expect(await ans({ id: 't1', name: 'Sơn', scopes: ['upload', 'bogus', 'upload'], admin: false, role: 'uploader' }))
      .toEqual({ id: 't1', name: 'Sơn', scopes: ['upload'], admin: false, role: 'uploader' })
    // a role the client does not know yet: summarised from the scopes
    expect((await ans({ id: 't2', scopes: ['read', 'delete'], role: 'auditor' })).role).toBe('reviewer')
    expect(await ans({ id: 't3', name: 'Old', role: 'reviewer' }))
      .toEqual({ id: 't3', name: 'Old', scopes: ['upload', 'read', 'process'], admin: false, role: 'reviewer' })
    expect(await ans({ id: 'admin', role: 'admin' })).toMatchObject({ scopes: SCOPES, admin: true, role: 'admin' })
  })

  it('maps scopes and roles both ways', () => {
    expect(parseScopes(['process', 'read', 'x'], 'reviewer')).toEqual(['read', 'process'])   // scopes win over the role
    expect(parseScopes(undefined, 'uploader')).toEqual(['upload'])
    expect(parseScopes(undefined, 'reviewer')).toEqual(['upload', 'read', 'process'])
    expect(parseScopes(undefined, 'root')).toBeNull()
    expect(roleOf(['upload'])).toBe('uploader')
    expect(roleOf(['upload', 'read'])).toBe('reviewer')
    expect(roleOf(['process'])).toBe('agent')
    expect(roleOf(['upload', 'delete'])).toBe('custom')
    expect(roleOf(['upload'], true)).toBe('admin')
    expect(PRESETS).toEqual({ phone: ['upload'], reviewer: ['read'], agent: ['process'] })
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
    const { f, calls } = fake(() => json({ schema: 'poi-cloud-tokens/1', tokens: [tok, { id: 'bad' }, { ...tok, id: 't0', revokedAt: '2026-11-19T12:00:00Z' },
      { ...tok, id: 't2', scopes: ['read', 'delete'], role: 'reviewer' }, { ...tok, id: 't3', role: 'reviewer' }, { ...tok, id: 't4', scopes: [], role: undefined }] }))
    const list = await listTokens(cfg, f)
    expect(calls[0].url).toBe('https://h.example/admin/tokens')
    expect(list.map((t) => t.id)).toEqual(['t1', 't0', 't2', 't3', 't4'])
    // an older server: the role only
    expect(list[0]).toEqual({ ...tok, scopes: ['upload'], seen: null })
    expect(list[1].revokedAt).toBe('2026-11-19T12:00:00Z')
    expect(list[2].scopes).toEqual(['read', 'delete'])
    expect(list[3].scopes).toEqual(['upload', 'read', 'process'])
    expect(list[4]).toMatchObject({ scopes: [], role: 'custom' })
  })

  it('issues a token: POST JSON, returns the secret once', async () => {
    const { f, calls } = fake(() => json({ token: tok, secret: 'pct_s3cr3t' }, 201))
    const r = await issueToken(cfg, '  Sơn – phone ', ['upload'], f)
    expect(r.secret).toBe('pct_s3cr3t')
    expect(r.token.name).toBe('Sơn – phone')
    expect(calls[0].url).toBe('https://h.example/admin/tokens')
    expect(calls[0].init!.method).toBe('POST')
    expect((calls[0].init!.headers as Record<string, string>)['Content-Type']).toBe('application/json')
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ name: 'Sơn – phone', scopes: ['upload'] })
    expect(r.token.scopes).toEqual(['upload'])
    // scopes go in display order, once each
    await issueToken(cfg, 'Agent', ['delete', 'process', 'delete'], f)
    expect(JSON.parse(calls[1].init!.body as string)).toEqual({ name: 'Agent', scopes: ['process', 'delete'] })
    await expect(issueToken(cfg, '  ', ['read'], f)).rejects.toThrow(/name/)
    await expect(issueToken(cfg, 'x'.repeat(81), ['read'], f)).rejects.toThrow(/name/)
    await expect(issueToken(cfg, 'Sơn', [], f)).rejects.toThrow(/at least one/)
    await expect(issueToken(cfg, 'Sơn', ['admin' as any], f)).rejects.toThrow(/unknown permission/)
    expect(calls.length).toBe(2)
  })

  it('changes a token: PATCH JSON with only what changes', async () => {
    const changed = { ...tok, scopes: ['upload', 'delete'], role: 'custom' }
    const { f, calls } = fake(() => json({ token: changed }))
    const t = await updateToken(cfg, 't 1', { scopes: ['delete', 'upload'] }, f)
    expect(t.scopes).toEqual(['upload', 'delete'])
    expect(t.role).toBe('custom')
    expect(calls[0].url).toBe('https://h.example/admin/tokens/t%201')
    expect(calls[0].init!.method).toBe('PATCH')
    expect((calls[0].init!.headers as Record<string, string>)['Content-Type']).toBe('application/json')
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ scopes: ['upload', 'delete'] })
    await updateToken(cfg, 't1', { name: ' Sơn – new phone ' }, f)
    expect(JSON.parse(calls[1].init!.body as string)).toEqual({ name: 'Sơn – new phone' })
    await updateToken(cfg, 't1', { name: 'A', scopes: ['read'] }, f)
    expect(JSON.parse(calls[2].init!.body as string)).toEqual({ name: 'A', scopes: ['read'] })
    await expect(updateToken(cfg, 't1', { scopes: [] }, f)).rejects.toThrow(/at least one/)
    await expect(updateToken(cfg, 't1', { name: '' }, f)).rejects.toThrow(/name/)
    await expect(updateToken(cfg, 't1', {}, f)).rejects.toThrow(/nothing/)
    expect(calls.length).toBe(3)
    // a revoked token: 409
    const e = await updateToken(cfg, 't1', { scopes: ['read'] }, fake(() => new Response('', { status: 409 })).f).catch((x) => x)
    expect(e).toBeInstanceOf(CloudError)
    expect(e.status).toBe(409)
    await expect(updateToken(cfg, 't1', { scopes: ['read'] }, fake(() => json({})).f)).rejects.toThrow(/no token/)
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
    const f = (async () => json({ tokens: [{ ...tok, seen }, tok], builtins: [{ id: 'admin', name: 'Admin', scopes: ['upload', 'read', 'process', 'delete'], role: 'admin', seen: null },
      { id: 'shared', name: 'Shared token', role: 'reviewer', seen: { ...seen, lat: 'x' } }, { name: 'no id' }] })) as unknown as typeof fetch
    const r = await adminTokens({ url: 'https://h/files/', token: 'a' }, f)
    expect(r.tokens[0].seen).toEqual(seen)
    expect(r.tokens[1].seen).toBeNull()
    expect(r.builtins.map((b) => b.id)).toEqual(['admin', 'shared'])
    expect(r.builtins[1].seen!.lat).toBeNull()
    expect(r.builtins[1].seen!.city).toBe('Thu Duc')
    expect(r.builtins[0].scopes).toEqual(['upload', 'read', 'process', 'delete'])
    expect(r.builtins[1].scopes).toEqual(['upload', 'read', 'process'])   // role only: mapped
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

describe('parseKeyInput', () => {
  it('takes a bare key, a Bearer key or the admin setup link', async () => {
    const { parseKeyInput, setupLink } = await import('./cloud')
    expect(parseKeyInput('  pct_abc-DEF_1  ')).toEqual({ token: 'pct_abc-DEF_1', url: null })
    expect(parseKeyInput('Bearer pct_x')).toEqual({ token: 'pct_x', url: null })
    const link = setupLink('https://poi-uploader.4hum.ai/files/', 'pct_secret', 'Sơn – laptop')
    expect(parseKeyInput(link)).toEqual({ token: 'pct_secret', url: 'https://poi-uploader.4hum.ai/files/' })
  })
  it('refuses what is not a key', async () => {
    const { parseKeyInput } = await import('./cloud')
    expect(parseKeyInput('')).toBeNull()
    expect(parseKeyInput('two words')).toBeNull()
    expect(parseKeyInput('https://poi-uploader.4hum.ai/files/')).toBeNull()
    expect(parseKeyInput('https://poi-uploader.4hum.ai/files/#name=x')).toBeNull()
  })
})

describe('review lifecycle', () => {
  const cfg = { url: 'https://h/files/', token: 'k' }
  const json = (v: unknown, status = 200) => new Response(JSON.stringify(v), { status, headers: { 'Content-Type': 'application/json' } })

  it('reads how the last checkout ended and the current version', async () => {
    const f = (async () => json({ schema: 'poi-cloud-reviews/1', reviews: [
      { sessionId: 's1', lock: null, saved: { by: { id: 'a', name: 'Admin' }, at: '2026-11-22T05:00:00Z', size: 5, done: 3, total: 9, version: 'v1-abcd1234' },
        ended: { of: { id: 't1', name: 'Lan' }, by: 'Admin', at: '2026-11-22T04:00:00Z', reason: 'released' } },
      { sessionId: 's2', lock: null, saved: null, ended: { of: { id: 't1', name: 'Lan' }, by: null, at: 'x', reason: 'stolen' } },
    ] })) as unknown as typeof fetch
    const [a, b] = await listReviews(cfg, f)
    expect(a.ended).toEqual({ of: { id: 't1', name: 'Lan' }, by: 'Admin', at: '2026-11-22T04:00:00Z', reason: 'released' })
    expect(a.saved!.version).toBe('v1-abcd1234')
    expect(b.ended).toBeNull()   // an unknown reason is ignored
  })

  it('reads who is processing which ride', async () => {
    const urls: string[] = []
    const f = (async (u: string) => { urls.push(u); return json({ schema: 'poi-cloud-processing/1', processing: [
      { sessionId: 's_ride', by: { id: 't9', name: 'Agent Phong' }, at: '2026-11-22T09:00:00Z', until: '2026-11-22T21:00:00Z' },
      { sessionId: 's_bad' },
    ] }) }) as unknown as typeof fetch
    expect(await listProcessing(cfg, f)).toEqual([{ sessionId: 's_ride', by: { id: 't9', name: 'Agent Phong' },
      at: '2026-11-22T09:00:00Z', until: '2026-11-22T21:00:00Z' }])
    expect(urls[0]).toMatch(/\/processing$/)
  })

  it('reads the history and restores a version', async () => {
    const calls: [string, RequestInit | undefined][] = []
    const f = (async (u: string, init?: RequestInit) => {
      calls.push([u, init])
      if (u.endsWith('/history')) return json({ schema: 'poi-cloud-review-history/1', sessionId: 's1', events: [
        { at: '2026-11-22T05:00:00Z', action: 'checkin', by: { id: 't1', name: 'Lan' }, of: null, version: 'v2-00000000', done: 2, total: 9, note: null },
        { at: '2026-11-22T04:00:00Z', action: 'released', by: { id: 'admin', name: 'Admin' }, of: 'Sơn', version: null },
        { action: 'broken' },
      ] })
      return json({ schema: 'poi-cloud-review/1', sessionId: 's1', lock: null, saved: { by: { id: 'admin', name: 'Admin' }, at: '2026-11-22T06:00:00Z', size: 5 } })
    }) as unknown as typeof fetch
    const h = await reviewHistory(cfg, 's1', f)
    expect(h.map((e) => e.action)).toEqual(['checkin', 'released'])
    expect(h[0]).toMatchObject({ by: { name: 'Lan' }, version: 'v2-00000000', done: 2, total: 9 })
    expect(h[1]).toMatchObject({ of: 'Sơn', version: null, done: null })
    const info = await restoreReview(cfg, 's1', 'v1-abcd1234', f)
    expect(info.saved!.by.name).toBe('Admin')
    expect(calls[1][0]).toBe('https://h/reviews/s1/restore')
    expect(calls[1][1]).toMatchObject({ method: 'POST', body: '{"version":"v1-abcd1234"}' })
  })
})
