import { describe, expect, it } from 'vitest'
import { merge3, resolve, type ReviewSet } from './merge'
import type { Review } from './types'

const rv = (decision: Review['decision'], extra: Partial<Review> = {}): Review => ({ decision, fields: {}, ...extra })
const set = (reviews: Record<string, Review>, removals: ReviewSet['removals'] = {}): ReviewSet => ({ reviews, removals })

describe('three-way merge of a review', () => {
  it('takes each side’s own changes and keeps what both did the same', () => {
    const base = set({ a: rv('todo'), b: rv('confirmed'), c: rv('rejected') })
    const mine = set({ a: rv('confirmed', { reviewer: 'Sơn', at: '1' }), b: rv('confirmed'), c: rv('rejected'), d: rv('visit') })
    const theirs = set({ a: rv('todo'), b: rv('rejected', { reviewer: 'Lan' }), c: rv('rejected'), d: rv('visit', { reviewer: 'Lan', at: '2' }) },
      { 0: { decision: 'confirmed' } })
    const { merged, conflicts } = merge3(base, mine, theirs)
    expect(conflicts).toEqual([])
    expect(merged.reviews.a.decision).toBe('confirmed')   // only mine changed it
    expect(merged.reviews.b.decision).toBe('rejected')    // only theirs changed it
    expect(merged.reviews.d.decision).toBe('visit')       // both the same (who / when do not count)
    expect(merged.removals[0].decision).toBe('confirmed')
  })

  it('a place both changed differently is a conflict, mine until decided', () => {
    const base = set({ a: rv('todo') })
    const mine = set({ a: rv('confirmed', { fields: { phone: { state: 'edited', value: '0901' } } }) })
    const theirs = set({ a: rv('rejected', { comment: 'closed' }) })
    const { merged, conflicts } = merge3(base, mine, theirs)
    expect(conflicts).toHaveLength(1)
    expect(conflicts[0]).toMatchObject({ kind: 'place', key: 'a' })
    expect(merged.reviews.a.decision).toBe('confirmed')
    conflicts[0].choice = 'theirs'
    expect(resolve(merged, conflicts).reviews.a).toMatchObject({ decision: 'rejected', comment: 'closed' })
  })

  it('without a known base nothing is overwritten silently', () => {
    const { conflicts } = merge3(null, set({ a: rv('confirmed') }), set({ a: rv('rejected') }))
    expect(conflicts.map((c) => c.key)).toEqual(['a'])
  })

  it('an untouched review counts as none; a cleared one can be chosen', () => {
    const base = set({ a: rv('confirmed') })
    const { merged, conflicts } = merge3(base, set({ a: rv('todo') }), set({}))
    expect(conflicts).toHaveLength(0)   // both "cleared" it
    expect(merged.reviews.a).toEqual(rv('todo'))
    const r = merge3(set({ a: rv('todo') }), set({ a: rv('visit') }), set({}))
    expect(r.conflicts).toHaveLength(0)   // they did nothing (an empty review = todo): mine stays
    expect(r.merged.reviews.a.decision).toBe('visit')
  })
})
