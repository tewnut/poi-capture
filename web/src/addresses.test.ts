import { describe, expect, it } from 'vitest'
import { addressExtras, alongRide, isPartial, parseHouseNumber, seqEntries, sequenceFlags, streetKey, type SeqEntry } from './addresses'
import type { Place, Review } from './types'

/** Numbers along one street, one photo apart (same "along" = seen at the same spot). */
const ride = (nums: (string | [string, number])[], street = 'Đường số 18'): SeqEntry[] =>
  nums.map((x, i) => (Array.isArray(x) ? { id: x[0] + '@' + i, number: x[0], street, along: x[1] } : { id: x + '@' + i, number: x, street, along: i * 10 }))
const flagged = (es: SeqEntry[]) => [...sequenceFlags(es).keys()].map((k) => k.split('@')[0])

describe('house numbers', () => {
  it('reads the forms seen on the plates', () => {
    expect(parseHouseNumber('12')).toEqual({ lo: 12, hi: 12, partial: false })
    expect(parseHouseNumber('12A')!.lo).toBe(12)
    expect(parseHouseNumber('12/3')!.lo).toBe(12)
    expect(parseHouseNumber('38/1')!.lo).toBe(38)
    expect(parseHouseNumber('04/9')!.lo).toBe(4)
    expect(parseHouseNumber('170-172')).toEqual({ lo: 170, hi: 172, partial: false })
    expect(parseHouseNumber('92 - 94')).toEqual({ lo: 92, hi: 94, partial: false })
    expect(parseHouseNumber('K12')!.lo).toBe(12)
    expect(parseHouseNumber('Số 558B')!.lo).toBe(558)
    expect(parseHouseNumber('58…')!.partial).toBe(true)
    expect(parseHouseNumber('…')!.partial).toBe(true)
    expect(parseHouseNumber('')).toBeNull()
    expect(parseHouseNumber('không số')).toBeNull()
    expect(isPartial('1…')).toBe(true)
    expect(isPartial('12')).toBe(false)
  })
  it('compares streets without case or accents', () => {
    expect(streetKey('Đường số  16')).toBe(streetKey('duong So 16'))
    expect(streetKey('Đường 16')).not.toBe(streetKey('Đường số 16'))
  })
})

describe('order along the ride', () => {
  it('a clean run gives no hint, rising or falling', () => {
    expect(flagged(ride(['309', '313', '317', '317A', '325', '329', '343', '345', '347']))).toEqual([])
    expect(flagged(ride(['266', '264', '262', '254', '254/1', '248', '204']))).toEqual([])
  })
  it('the same number twice (558 / 558B, two shops at 638) is not out of order', () => {
    expect(flagged(ride(['546', '558B', '558', '558B', '558', '566', '638', '638', '644']))).toEqual([])
  })
  it('stacked plates seen at one spot may come in any order', () => {
    expect(flagged(ride(['339', '341', ['347', 50], ['343', 50], ['345', 50], '349', '355']))).toEqual([])
  })
  it('a range counts as any of its numbers', () => {
    expect(flagged(ride(['176', '172', '170-172', '168', '160']))).toEqual([])
  })
  it('flags a number that breaks the run, with the numbers it sits between', () => {
    // Đồng Văn Cống: old and new numbering mixed
    const f = sequenceFlags(ride(['170-172', '188', '226', '92', '1400'], 'Đồng Văn Cống'))
    expect([...f.keys()].map((k) => k.split('@')[0])).toEqual(['92'])
    expect([...f.values()][0]).toEqual({ prev: '226', next: '1400' })
  })
  it('34 and 18 on one gate in a falling run: 18 is out, and one of 32 / 34', () => {
    const es = ride(['46', '42-44', '38A', '38/1', '38', '32', ['34', 55], ['18', 55], '22', '20', '16', '14', '12'])
    const f = sequenceFlags(es)
    expect(flagged(es).sort()).toEqual(['18', '32', '34'])
    const of = (n: string) => [...f.entries()].find(([k]) => k.startsWith(n + '@'))![1]
    expect(of('18')).toEqual({ prev: '38', next: '22' })
    expect(of('34')).toEqual({ prev: '38', next: '22', or: ['32'] })
    expect(of('32').or).toEqual(['34'])
  })
  it('odd and even sides are separate runs', () => {
    expect(flagged(ride(['59', '30', '65', '32', '69', '36', '77']))).toEqual([])
  })
  it('numbers on another street are not compared', () => {
    const es = [...ride(['59', '65', '69', '71', '77'], 'Đường Số 7'), ...ride(['30', '32'], 'Thích Mật Thể').map((e) => ({ ...e, along: e.along + 100 }))]
    expect(flagged(es)).toEqual([])
    // the same, but all on one street: 30 and 32 after 77 are out
    expect(flagged(ride(['59', '65', '69', '71', '77', '31', '33']))).toEqual(['31', '33'])
  })
  it('partly read numbers are left out of the order', () => {
    expect(flagged(ride(['170', '188', '19…', '226', '…', '230']))).toEqual([])
  })
  it('says when its other (old) number fits', () => {
    const es = ride(['46', '38', '32', '18', '30', '28'])
    es[3].alts = ['34']   // 34 does not fit between 32 and 30 either
    expect(sequenceFlags(es).get(es[3].id)).toEqual({ prev: '32', next: '30' })
    es[3].alts = ['34', '31']
    expect(sequenceFlags(es).get(es[3].id)).toEqual({ prev: '32', next: '30', altFits: '31' })
  })
  it('too few numbers, or no order at all, gives no hint', () => {
    expect(flagged(ride(['10', '2']))).toEqual([])
    expect(flagged(ride(['10', '2', '12']))).toEqual([])
  })
})

const addr = (id: string, num: string, extra: Partial<Place> & Record<string, any> = {}): Place => ({
  id, name: num, status: 'open', layer: 'address', fields: { houseNumber: { value: num } }, photos: [], pins: {}, ...extra,
} as Place)
const todo: Review = { decision: 'todo', fields: {} }

describe('addresses as the reviewer has them', () => {
  it('uses the reviewer edit, leaves rejected ones out, keeps ride order', () => {
    const ps = [addr('a', '10', { photos: [{ file: 'x', index: 4, cover: true }] }), addr('b', '13', { photos: [{ file: 'y', index: 9 }, { file: 'z', index: 7 }] }),
      addr('c', '99'), { ...addr('d', 'shop'), layer: 'place' } as Place]
    const rv: Record<string, Review> = { b: { decision: 'todo', fields: { houseNumber: { state: 'edited', value: '12' } } }, c: { decision: 'rejected', fields: {} } }
    const es = seqEntries(ps, (p) => rv[p.id] || todo)
    expect(es.map((e) => [e.id, e.number, e.along])).toEqual([['a', '10', 4], ['b', '12', 7]])
  })
  it('alongRide falls back to the first sighting, then the list position', () => {
    expect(alongRide(addr('a', '1', { pins: { algorithm: { lat: 0, lon: 0, sightings: [{ i: 33, lat: 0, lon: 0 }] } } }), 5)).toBe(33)
    expect(alongRide(addr('a', '1'), 5)).toBe(5)
  })
  it('reads the optional numbering block, or the same at the top level / on the field', () => {
    const none = { source: null, old: null, others: [], partial: false }
    expect(addressExtras(addr('a', '1'))).toEqual(none)
    expect(addressExtras(addr('a', '14', { numbering: { source: 'plate', oldNumber: '53C', altNumbers: [], partial: false } })))
      .toEqual({ ...none, source: 'plate', old: '53C' })
    expect(addressExtras(addr('a', '18', { numbering: { source: null, oldNumber: null, altNumbers: ['34'], partial: false } })))
      .toEqual({ ...none, others: ['34'] })
    expect(addressExtras(addr('a', '1…', { numbering: { partial: true } })).partial).toBe(true)
    expect(addressExtras(addr('a', '18', { source: 'painted', old: 34, alt: ['36'] }))).toEqual({ ...none, source: 'painted', old: '34', others: ['36'] })
    expect(addressExtras(addr('a', '18', { fields: { houseNumber: { value: '18', source: 'sign' as any } } })).source).toBe('sign')
    expect(addressExtras(addr('a', '18', { source: 'google', numbering: 'x' as any }))).toEqual(none)
  })
  it('the order check tries the old and other numbers', () => {
    const ps = [addr('a', '46'), addr('b', '38'), addr('c', '32'), addr('d', '18', { numbering: { altNumbers: ['31'] } }), addr('e', '30'), addr('f', '28')]
      .map((p, i) => ({ ...p, photos: [{ file: String(i), index: i }] }))
    expect(sequenceFlags(seqEntries(ps, () => todo)).get('d')).toEqual({ prev: '32', next: '30', altFits: '31' })
  })
})
