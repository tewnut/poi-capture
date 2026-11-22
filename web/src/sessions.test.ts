import { describe, expect, it } from 'vitest'
import { rows } from './sessions'
import type { CloudItem } from './cloud'
import type { Dataset } from './types'

const item = (o: Partial<CloudItem>): CloudItem => ({
  id: 'u' + Math.random().toString(36).slice(2), size: 1000, offset: 1000, complete: true, createdAt: '2026-11-20T10:00:00Z',
  type: 'processed-result', sessionId: 's1', name: 'Ride 1', kind: 'sweep', filename: 'x.zip', by: null, metadata: {}, trip: null, ...o,
})
const ds = (id: string, importedAt: string, name = id) =>
  ({ id, source: 'result', importedAt, session: { id, name, startedAt: '2026-11-19T08:00:00Z', kind: 'sweep' }, places: [], tasks: [] }) as unknown as Dataset

describe('session rows', () => {
  it('a processed session is to download, here once downloaded, updated when the server has a newer one', () => {
    const p = item({ createdAt: '2026-11-20T10:00:00Z' })
    expect(rows([p], [])[0]).toMatchObject({ sessionId: 's1', state: 'remote', item: p })
    expect(rows([p], [ds('s1', '2026-11-21T00:00:00Z')])[0].state).toBe('here')
    expect(rows([p], [ds('s1', '2026-11-19T00:00:00Z')])[0].state).toBe('updated')
  })

  it('the newest processed result wins; its raw ride is attached', () => {
    const newer = item({ createdAt: '2026-11-21T10:00:00Z' })
    const older = item({ createdAt: '2026-11-20T10:00:00Z' })
    const raw = item({ type: 'raw-session', createdAt: '2026-11-19T10:00:00Z' })
    const r = rows([newer, older, raw], [])
    expect(r).toHaveLength(1)
    expect(r[0].item).toBe(newer)
    expect(r[0].raw).toBe(raw)
  })

  it('a ride processed in parts: one row per part, the raw ride no longer waiting', () => {
    const raw = item({ type: 'raw-session', sessionId: 'ride', name: 'Ride' })
    const p1 = item({ sessionId: 'ride-1', parentId: 'ride', name: 'Ngô Tất Tố 2026-11-19 14h32' })
    const p2 = item({ sessionId: 'ride-2', parentId: 'ride', name: 'Trường Sa 2026-11-19 14h51' })
    expect(rows([p1, p2, raw], []).map((x) => [x.sessionId, x.state])).toEqual([['ride-1', 'remote'], ['ride-2', 'remote']])
  })

  it('a raw ride alone is waiting for the agent; unfinished uploads are left out', () => {
    const r = rows([item({ type: 'raw-session', sessionId: 's2', name: 'Ride 2' }), item({ sessionId: 's3', complete: false })], [])
    expect(r.map((x) => [x.sessionId, x.state])).toEqual([['s2', 'waiting']])
  })

  it('a session only in this browser stays listed; newest first', () => {
    const r = rows([item({ sessionId: 's1', trip: { startedAt: '2026-11-20T06:00:00Z', track: null, endedAt: null, distanceM: null, photos: null } })],
      [ds('old', '2026-11-01T00:00:00Z')])
    expect(r.map((x) => [x.sessionId, x.state])).toEqual([['s1', 'remote'], ['old', 'local']])
  })

  it('no connection: the downloaded sessions only', () => {
    expect(rows(null, [ds('a', 'x')]).map((x) => x.state)).toEqual(['local'])
  })
})
