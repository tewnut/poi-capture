import { describe, expect, it } from 'vitest'
import { build, parse, collect, reviewFiles, progress, REVIEW_SCHEMA, type ReviewDoc } from './reviewpkg'
import type { Dataset, Review } from './types'

const doc = (over: Partial<ReviewDoc> = {}): ReviewDoc => ({
  schema: REVIEW_SCHEMA, sessionId: 's1', reviewer: 'Sơn', savedAt: '2026-11-22T03:00:00Z',
  reviews: { p1: { decision: 'confirmed', fields: {}, photos: { added: ['added/p1/a.jpg'], crops: { 'x.jpg': { rect: [0, 0, 0.5, 0.5], file: 'crop/p1/c.jpg' } } } } },
  removals: { '2': { decision: 'confirmed' } }, ...over,
})

describe('review package', () => {
  it('round-trips the review and the reviewer photos', () => {
    const files = new Map([['added/p1/a.jpg', new Uint8Array([1, 2, 3])], ['crop/p1/c.jpg', new Uint8Array([4])]])
    const back = parse(build(doc(), files), 's1')
    expect(back.doc).toEqual(doc())
    expect([...back.files.keys()].sort()).toEqual(['added/p1/a.jpg', 'crop/p1/c.jpg'])
    expect(back.files.get('added/p1/a.jpg')).toEqual(new Uint8Array([1, 2, 3]))
  })

  it('refuses another session, another schema, and paths outside added/ and crop/', () => {
    const z = build(doc(), new Map([['../evil.jpg', new Uint8Array([1])], ['photos/x.jpg', new Uint8Array([1])]]))
    expect(() => parse(z, 's2')).toThrow(/s1, not s2/)
    expect([...parse(z, 's1').files.keys()]).toEqual([])
    expect(() => parse(build(doc({ schema: 'x' as any }), new Map()), 's1')).toThrow(/schema/)
  })

  it('collects one session out of the app maps, and the files its reviews use', () => {
    const r: Review = { decision: 'todo', fields: {}, photos: { added: ['added/p/b.png', '../x'] } }
    const c = collect('s1', { 's1|p1': r, 's2|p9': r }, { 's1|0': { decision: 'verify' }, 's10|0': { decision: 'confirmed' } })
    expect(Object.keys(c.reviews)).toEqual(['p1'])
    expect(c.removals).toEqual({ '0': { decision: 'verify' } })
    expect(reviewFiles(c.reviews)).toEqual(['added/p/b.png'])
  })

  it('counts decided places and removal tasks', () => {
    const d = { places: [{ id: 'p1' }, { id: 'p2' }], tasks: [{ type: 'visit' }, { type: 'removal' }] } as unknown as Dataset
    expect(progress(d, { p1: { decision: 'confirmed', fields: {} } }, { '1': { decision: 'dismissed' } })).toEqual({ done: 2, total: 3 })
  })
})
