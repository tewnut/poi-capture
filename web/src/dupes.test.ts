import { describe, expect, it } from 'vitest'
import { crossRideDupes, duplicateComment, likeNames, phones, sameWhy } from './dupes'
import type { Dataset, Place, Review } from './types'

const place = (id: string, name: string, lat: number, lon: number, extra: Partial<Place> = {}): Place => ({
  id, name, status: 'open', fields: { name: { value: name } }, photos: [], pins: { agent: { lat, lon } }, ...extra,
})
const ds = (id: string, places: Place[], extra: Partial<Dataset> = {}): Dataset => ({
  id, source: 'result', importedAt: '', session: { id, name: 'ride ' + id }, track: [], shots: [], places, tasks: [], files: [], ...extra,
})
const todo: Review = { decision: 'todo', fields: {} }
const none = () => todo
// 0.0001° of latitude is about 11 m
const LAT = 10.7769, LON = 106.7530

describe('like names and phones', () => {
  it('names: most words of the shorter one, without accents', () => {
    expect(likeNames('Gà Rán Street Food', 'GA RAN street food')).toBe(true)
    expect(likeNames('Mắt Kính', 'Mắt Kính 553')).toBe(true)
    expect(likeNames('Cà Phê An', 'Cà Phê Bình')).toBe(false)
    expect(likeNames('', 'x')).toBe(false)
  })
  it('phones: the last 9 digits, +84 and 0 alike, short runs left out', () => {
    expect(phones('0909 123 456, +84 28 3812 3456')).toEqual(['909123456', '838123456'])
    expect(phones('1555')).toEqual([])
  })
})

describe('same place on two rides', () => {
  it('a close pin with a like name, or the same phone', () => {
    const a = ds('A', [place('a1', 'Gà Rán Street Food', LAT, LON), place('a2', 'Tiệm Vàng Kim', LAT + 0.001, LON),
      place('a3', 'Shop X', LAT + 0.002, LON, { fields: { name: { value: 'Shop X' }, phone: { value: '0909 123 456' } } })])
    const b = ds('B', [place('b1', 'Gà rán street food', LAT + 0.0002, LON), place('b2', 'Phở Hòa', LAT + 0.001, LON),
      place('b3', 'Cửa hàng Y', LAT + 0.002, LON + 0.0001, { fields: { name: { value: 'Cửa hàng Y' }, phone: { value: '+84 909123456' } } })])
    const m = crossRideDupes(a, [a, b], none)
    expect([...m.keys()]).toEqual(['a1', 'a3'])
    expect(m.get('a1')![0]).toMatchObject({ place: { id: 'b1' }, why: 'name' })
    expect(m.get('a1')![0].distM).toBeGreaterThan(15)
    expect(m.get('a3')![0].why).toBe('phone')
  })
  it('too far apart is not the same place', () => {
    const a = ds('A', [place('a1', 'Mắt Kính', LAT, LON)])
    const b = ds('B', [place('b1', 'Mắt Kính', LAT + 0.0005, LON)])   // about 55 m
    expect(crossRideDupes(a, [b], none).size).toBe(0)
    expect(crossRideDupes(a, [b], none, 60).size).toBe(1)
  })
  it('the reviewer pin and edits count; a rejected name does not', () => {
    const a = ds('A', [place('a1', 'Quán A', LAT, LON)])
    const b = ds('B', [place('b1', 'Quán B', LAT + 0.001, LON)])
    const rv = (d: Dataset, p: Place): Review => (p.id === 'b1' ? { decision: 'todo', fields: { name: { state: 'edited', value: 'Quán A' } }, pin: { lat: LAT, lon: LON, source: 'manual' } } : todo)
    expect(crossRideDupes(a, [b], rv).get('a1')![0]).toMatchObject({ distM: 0, why: 'name' })
  })
  it('addresses: the same house number; places and addresses are not mixed', () => {
    const n = (id: string, v: string): Place => ({ ...place(id, v, LAT, LON), layer: 'address', fields: { houseNumber: { value: v } } })
    expect(sameWhy(n('x', '553'), todo, n('y', '553 '), todo)).toBe('number')
    expect(sameWhy(n('x', '553'), todo, n('y', '555'), todo)).toBeNull()
    expect(sameWhy(n('x', '55…'), todo, n('y', '55…'), todo)).toBeNull()
    expect(sameWhy(n('x', '553'), todo, place('y', '553', LAT, LON), todo)).toBeNull()
  })
  it('leaves out the same session (its raw copy) and raw phone exports', () => {
    const a = ds('A', [place('a1', 'Mắt Kính', LAT, LON)])
    const raw = ds('A-raw', [place('r1', 'Mắt Kính', LAT, LON)], { source: 'raw', session: { id: 'A' } })
    const other = ds('C', [place('c1', 'Mắt Kính', LAT, LON)], { source: 'raw' })
    expect(crossRideDupes(a, [a, raw, other], none).size).toBe(0)
  })
  it('the rejection comment names the other ride and keeps the reviewer\'s note', () => {
    const b = ds('B', [place('b1', 'Gà Rán', LAT, LON)])
    const d = { ds: b, place: b.places[0], distM: 3, why: 'name' as const }
    expect(duplicateComment(d)).toBe('Duplicate of "Gà Rán" on ride ride B (B / b1).')
    expect(duplicateComment(d, 'Duplicate of "x" on ride y (y / 1).\nsign faded')).toBe('Duplicate of "Gà Rán" on ride ride B (B / b1).\nsign faded')
  })
})
