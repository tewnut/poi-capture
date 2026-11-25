import { describe, expect, it } from 'vitest'
import {
  dayGroups, dayKey, dayLabel, extraFilters, filterFacts, fold, fromQuery, inPeriod, listKind, listStage, matches, NO_FILTERS,
  riders, sortFacts, stageCounts, toQuery, type Facts, type ListFilters,
} from './sessionList'

// local times: the tests run in whatever time zone the machine has
const at = (y: number, m: number, d: number, h = 10) => new Date(y, m - 1, d, h).toISOString()
const NOW = new Date(2026, 10, 25, 15).getTime()   // 25/11/2026 15:00 local
const f = (o: Partial<Facts>): Facts => ({ key: o.name || 'x', name: 'x', date: at(2026, 11, 25), stage: 'toReview', kind: 'place', rider: '', text: o.name || 'x', ...o })
const F = (o: Partial<ListFilters> = {}): ListFilters => ({ ...NO_FILTERS, ...o })

describe('list stage', () => {
  it('raw rides are captured, or processing while an agent holds them', () => {
    expect(listStage({ raw: true, processing: false, progress: null, locked: false })).toBe('captured')
    expect(listStage({ raw: true, processing: true, progress: null, locked: false })).toBe('processing')
  })
  it('processed: to review, reviewing (checked out or partly decided), done', () => {
    expect(listStage({ raw: false, processing: false, progress: null, locked: false })).toBe('toReview')
    expect(listStage({ raw: false, processing: false, progress: { done: 0, total: 12 }, locked: false })).toBe('toReview')
    expect(listStage({ raw: false, processing: false, progress: { done: 0, total: 12 }, locked: true })).toBe('reviewing')
    expect(listStage({ raw: false, processing: false, progress: { done: 3, total: 12 }, locked: false })).toBe('reviewing')
    expect(listStage({ raw: false, processing: false, progress: { done: 12, total: 12 }, locked: true })).toBe('done')
    expect(listStage({ raw: false, processing: false, progress: { done: null, total: null }, locked: false })).toBe('toReview')
  })
})

describe('list kind', () => {
  it('Place capture and indoor walks by kind, sweeps by target', () => {
    expect(listKind('poi', 'signs')).toBe('poi')
    expect(listKind('indoor', null)).toBe('indoor')
    expect(listKind('sweep', 'addresses')).toBe('address')
    expect(listKind('sweep', 'signs')).toBe('sign')
    expect(listKind(null, null)).toBe('place')
    expect(listKind('sweep', 'shops')).toBe('place')
  })
})

describe('search', () => {
  it('ignores accents, đ and case, every word anywhere', () => {
    expect(fold('Đồng Văn Cống')).toBe('dong van cong')
    expect(matches('Đường Đồng Văn Cống, Q2', 'dong van cong')).toBe(true)
    expect(matches('Đồng Văn Cống', 'ĐỒNG cống')).toBe(true)
    expect(matches('Đồng Văn Cống', 'cong dong')).toBe(true)
    expect(matches('Đồng Văn Cống', 'mai chi tho')).toBe(false)
    expect(matches('anything', '   ')).toBe(true)
  })
})

describe('period', () => {
  it('today, 7 and 30 days count whole local days; no date is in no period', () => {
    expect(inPeriod(at(2026, 11, 25, 0), 'today', NOW)).toBe(true)
    expect(inPeriod(at(2026, 11, 24, 23), 'today', NOW)).toBe(false)
    expect(inPeriod(at(2026, 11, 19, 1), '7d', NOW)).toBe(true)
    expect(inPeriod(at(2026, 11, 18, 23), '7d', NOW)).toBe(false)
    expect(inPeriod(at(2026, 10, 27, 1), '30d', NOW)).toBe(true)
    expect(inPeriod(null, '30d', NOW)).toBe(false)
    expect(inPeriod(null, '', NOW)).toBe(true)
  })
})

describe('filter and stage counts', () => {
  const list = [
    f({ name: 'Đồng Văn Cống', stage: 'toReview', kind: 'place', rider: 'Sơn' }),
    f({ name: 'Mai Chí Thọ', stage: 'reviewing', kind: 'sign', rider: 'An' }),
    f({ name: 'Vincom', stage: 'done', kind: 'indoor', rider: 'Sơn', date: at(2026, 11, 1) }),
    f({ name: 'Raw', stage: 'captured', kind: 'place', rider: '', date: null }),
  ]
  it('every filter narrows the list', () => {
    expect(filterFacts(list, F({ q: 'dong van' }), NOW).map((x) => x.name)).toEqual(['Đồng Văn Cống'])
    expect(filterFacts(list, F({ stage: 'done' }), NOW).map((x) => x.name)).toEqual(['Vincom'])
    expect(filterFacts(list, F({ kind: 'sign' }), NOW).map((x) => x.name)).toEqual(['Mai Chí Thọ'])
    expect(filterFacts(list, F({ rider: 'Sơn' }), NOW).map((x) => x.name)).toEqual(['Đồng Văn Cống', 'Vincom'])
    expect(filterFacts(list, F({ period: '7d' }), NOW).map((x) => x.name)).toEqual(['Đồng Văn Cống', 'Mai Chí Thọ'])
    expect(filterFacts(list, F(), NOW)).toHaveLength(4)
  })
  it('the stage counts follow the other filters, not the stage itself', () => {
    expect(stageCounts(list, F(), NOW)).toEqual({ all: 4, captured: 1, processing: 0, toReview: 1, reviewing: 1, done: 1 })
    expect(stageCounts(list, F({ rider: 'Sơn', stage: 'done' }), NOW)).toEqual({ all: 2, captured: 0, processing: 0, toReview: 1, reviewing: 0, done: 1 })
  })
  it('riders: unique, sorted, no blanks', () => {
    expect(riders(list)).toEqual(['An', 'Sơn'])
  })
})

describe('sort and day groups', () => {
  const a = f({ name: 'b ride', date: at(2026, 11, 25, 9), stage: 'done' })
  const b = f({ name: 'A ride', date: at(2026, 11, 25, 8), stage: 'captured' })
  const c = f({ name: 'Đ ride', date: at(2026, 11, 24), stage: 'reviewing' })
  const d = f({ name: 'c ride', date: null, stage: 'captured' })
  it('newest, oldest (no date last), by stage, by name', () => {
    expect(sortFacts([b, d, a, c], 'new').map((x) => x.name)).toEqual(['b ride', 'A ride', 'Đ ride', 'c ride'])
    expect(sortFacts([b, d, a, c], 'old').map((x) => x.name)).toEqual(['Đ ride', 'A ride', 'b ride', 'c ride'])
    expect(sortFacts([a, b, c, d], 'stage').map((x) => x.name)).toEqual(['A ride', 'c ride', 'Đ ride', 'b ride'])
    expect(sortFacts([a, b, c, d], 'name').map((x) => x.name)).toEqual(['A ride', 'b ride', 'c ride', 'Đ ride'])
  })
  it('groups by local day when sorted by date, one group otherwise', () => {
    const g = dayGroups(sortFacts([a, b, c, d], 'new'), 'new')
    expect(g.map((x) => [x.key, x.items.length])).toEqual([['2026-11-25', 2], ['2026-11-24', 1], ['', 1]])
    expect(dayGroups([a, b], 'name')).toEqual([{ key: null, items: [a, b] }])
    expect(dayGroups([], 'name')).toEqual([])
  })
  it('day labels: today, yesterday, dd/mm/yyyy', () => {
    expect(dayKey(at(2026, 11, 25, 0))).toBe('2026-11-25')
    expect(dayLabel('2026-11-25', NOW).day).toBe('today')
    expect(dayLabel('2026-11-24', NOW).day).toBe('yesterday')
    expect(dayLabel('2026-11-23', NOW)).toEqual({ day: 'date', text: '23/11/2026' })
    expect(dayLabel('', NOW).day).toBe('none')
  })
})

describe('address query', () => {
  it('only what is set; read back the same', () => {
    expect(toQuery(F())).toEqual({})
    const set = F({ q: 'dong van', stage: 'reviewing', kind: 'sign', period: '7d', rider: 'Sơn', sort: 'name' })
    expect(fromQuery(toQuery(set))).toEqual(set)
    expect(extraFilters(set)).toBe(3)
  })
  it('unknown values fall back to no filter', () => {
    expect(fromQuery({ stage: 'nope', kind: 3, period: ['30d', 'x'], sort: 'sideways', q: null })).toEqual(F({ period: '30d' }))
  })
})
