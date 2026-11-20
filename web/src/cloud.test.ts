import { afterEach, describe, expect, it, vi } from 'vitest'
import { DEFAULT_CLOUD_URL, loadCloud, saveCloud, downloadItem, endpoint, fetchIndex, importState, parseIndex, processedItems, INDEX_SCHEMA, type CloudItem } from './cloud'

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
