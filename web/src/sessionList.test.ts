import { describe, expect, it } from 'vitest'
import {
  dayGroups, dayKey, dayLabel, extraFilters, filterFacts, fold, fromQuery, inPeriod, listKind, listStage, matches, NO_FILTERS,
  riders, sortFacts, stageCounts, toQuery, hiddenKeys, metaWords, lastChange, latestExport, isExported, mergeExports, shortDay,
  type Facts, type ListFilters,
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
    expect(stageCounts(list, F(), NOW)).toEqual({ all: 4, captured: 1, processing: 0, toReview: 1, reviewing: 1, done: 1, exported: 0 })
    expect(stageCounts(list, F({ rider: 'Sơn', stage: 'done' }), NOW)).toEqual({ all: 2, captured: 0, processing: 0, toReview: 1, reviewing: 0, done: 1, exported: 0 })
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

describe('reviewed and exported', () => {
  const all = { raw: false, processing: false, progress: { done: 12, total: 12 }, locked: false }
  it('every decision made: reviewed, or exported when an export is newer than the last decision', () => {
    expect(listStage(all)).toBe('done')
    expect(listStage({ ...all, exported: true })).toBe('exported')
    // not every decision made: an export does not count
    expect(listStage({ ...all, progress: { done: 11, total: 12 }, exported: true })).toBe('reviewing')
  })
  it('an export counts only when newer than every review and removal decision', () => {
    const rv = [{ at: '2026-11-24T08:00:00Z' }, { at: '2026-11-24T09:00:00Z' }, {}]
    const rm = [{ at: '2026-11-24T09:30:00Z' }]
    expect(lastChange(rv, rm)).toBe('2026-11-24T09:30:00Z')
    expect(lastChange([], [])).toBe('')
    const ex = [{ at: '2026-11-24T09:00:00Z', by: 'An', format: 'csv' }, { at: '2026-11-24T10:00:00Z', by: 'An', format: 'osm' }]
    expect(latestExport(ex)?.format).toBe('osm')
    expect(isExported(ex, lastChange(rv, rm))).toBe(true)
    // a decision changed after the export: reviewed again, not exported
    expect(isExported(ex, lastChange([...rv, { at: '2026-11-24T11:00:00Z' }], rm))).toBe(false)
    expect(isExported([], '')).toBe(false)
    expect(isExported(null, '')).toBe(false)
  })
  it('exports from this browser and the server merge without repeats, oldest first', () => {
    const a = [{ at: '2026-11-24T10:00:00Z', by: 'An', format: 'osm' }]
    const b = [{ at: '2026-11-24T10:00:00Z', by: 'An', format: 'osm' }, { at: '2026-11-23T10:00:00Z', by: null, format: 'csv' }]
    expect(mergeExports(a, b)).toEqual([{ at: '2026-11-23T10:00:00Z', by: null, format: 'csv' }, a[0]])
    expect(mergeExports(a, [{ bad: 1 } as any, null as any])).toEqual(a)
    expect(mergeExports(b, a, 1)).toEqual(a)
  })
  it('the row writes the day as dd/mm', () => {
    expect(shortDay(at(2026, 11, 24))).toBe('24/11')
    expect(shortDay('nope')).toBe('')
  })
  it('the stage counts and filter know exported', () => {
    const list = [f({ name: 'A', stage: 'done' }), f({ name: 'B', stage: 'exported' })]
    expect(stageCounts(list, F(), NOW)).toMatchObject({ done: 1, exported: 1 })
    expect(filterFacts(list, F({ stage: 'exported' }), NOW).map((x) => x.name)).toEqual(['B'])
    expect(fromQuery({ stage: 'exported' }).stage).toBe('exported')
  })
})

describe('hidden by the filters', () => {
  it('the rides the search and filters leave out (a duplicate there still opens, marked hidden)', () => {
    const list = [f({ key: 'a', name: 'Đồng Văn Cống', rider: 'Sơn' }), f({ key: 'b', name: 'Mai Chí Thọ', rider: 'An' })]
    expect([...hiddenKeys(list, F({ q: 'dong van' }), NOW)]).toEqual(['b'])
    expect([...hiddenKeys(list, F({ rider: 'An' }), NOW)]).toEqual(['a'])
    expect(hiddenKeys(list, F(), NOW).size).toBe(0)
  })
})

describe('search before download', () => {
  it('the upload metadata: name, street(s), building, from the raw ride and the processed result', () => {
    const raw = { name: 'Chuyến 1', street: 'Đồng Văn Cống, Mai Chí Thọ', building: '', photos: '120' }
    const result = { name: 'Chuyến 1', street: 'Đồng Văn Cống', building: 'Vincom Thảo Điền' }
    expect(metaWords(result, raw)).toEqual(['Chuyến 1', 'Đồng Văn Cống', 'Vincom Thảo Điền', 'Đồng Văn Cống, Mai Chí Thọ'])
    expect(metaWords(null, undefined, { street: 5 })).toEqual([])
    const text = metaWords(result, raw).join(' · ')
    expect(matches(text, 'mai chi tho')).toBe(true)
    expect(matches(text, 'vincom thao dien')).toBe(true)
  })
})
