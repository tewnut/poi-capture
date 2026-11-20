import { describe, expect, it } from 'vitest'
import { confirmFields, confirmRule, googleMergeable, missingStreet, pendingSuggestions, suggestionKind } from './fields'
import { csv, osm, record } from './publish'
import type { Dataset, Place, Review } from './types'

const place = (fields: Place['fields']): Place => ({
  id: 's1-07', name: 'Phở Vụ', category: 'restaurant', status: 'open', confidence: 'high', fields,
  photos: [], pins: { algorithm: { lat: 10.78, lon: 106.77 } },
})
const todo: Review = { decision: 'todo', fields: {} }
const dataset = { id: 's1', session: { id: 's1', name: 'Tạ Hiện' }, places: [], tasks: [] } as unknown as Dataset

describe('Confirm', () => {
  const p = place({
    name: { value: 'Phở Vụ', photo: 3, sure: 'high' },
    phone: { value: '0901234567', photo: 4, sure: 'medium' },
    houseNumber: { value: '145', source: 'google', sure: 'medium' },
    website: { value: 'phovu.vn', source: 'website', sure: 'medium' },
    street: { value: 'Tạ Hiện, Phường Cát Lái', photo: 5, sure: 'low' },
    openingHours: { value: '06:00–22:00', source: 'google', sure: 'low', note: 'Google lists only one day' },
  })

  it('accepts clear readings and Google / website values, never unsure readings', () => {
    const f = confirmFields(p, todo)
    expect(Object.keys(f).sort()).toEqual(['houseNumber', 'name', 'phone', 'website'])
    expect(f.houseNumber).toEqual({ state: 'ok', value: '145' })
    expect(f.street).toBeUndefined()
    expect(f.openingHours).toBeUndefined()
  })

  it('lists the unsure readings as suggestions instead of dropping them silently', () => {
    expect(pendingSuggestions(p, { decision: 'confirmed', fields: confirmFields(p, todo) })).toEqual(['street', 'openingHours'])
    expect(suggestionKind(p.fields.street)).toBe('low')
  })

  it('a suggestion the reviewer accepts is published', () => {
    const r: Review = { decision: 'confirmed', fields: { ...confirmFields(p, todo), street: { state: 'ok', value: 'Tạ Hiện, Phường Cát Lái' } } }
    expect(pendingSuggestions(p, r)).toEqual(['openingHours'])
    expect(record({ dataset, place: p, review: r }).street).toBe('Tạ Hiện, Phường Cát Lái')
    expect(record({ dataset, place: p, review: { decision: 'confirmed', fields: confirmFields(p, todo) } }).street).toBeNull()
  })

  it('keeps what the reviewer already decided', () => {
    const r: Review = { decision: 'todo', fields: { phone: { state: 'rejected', value: '0901234567' } } }
    expect(confirmFields(p, r).phone).toEqual({ state: 'rejected', value: '0901234567' })
  })
})

describe('the ride road as a street suggestion', () => {
  const p = place({ name: { value: 'Phở Vụ', sure: 'high' }, street: { value: 'Tạ Hiện', source: 'ride', sure: 'medium' } })

  it('is a suggestion, not accepted by Confirm', () => {
    expect(confirmRule(p.fields.street!)).toBe('suggest')
    expect(suggestionKind(p.fields.street)).toBe('ride')
    expect(confirmFields(p, todo).street).toBeUndefined()
    expect(pendingSuggestions(p, todo)).toEqual(['street'])
  })

  it('once accepted it is our own data: in the .osm file, not marked fromGoogle', () => {
    const r: Review = { decision: 'confirmed', fields: { street: { state: 'ok', value: 'Tạ Hiện' } } }
    expect(record({ dataset, place: p, review: r }).fromGoogle).toBe('')
    expect(osm([{ dataset, place: p, review: r }])).toContain('<tag k="addr:street" v="Tạ Hiện"/>')
    expect(csv([{ dataset, place: p, review: r }], [])).toContain('Tạ Hiện')
  })
})

describe('missing street', () => {
  it('flags a place with no street read, suggested or typed', () => {
    expect(missingStreet(place({ name: { value: 'X' } }), todo)).toBe(true)
    expect(missingStreet(place({ street: { value: 'Tạ Hiện', source: 'ride', sure: 'medium' } }), todo)).toBe(false)
    expect(missingStreet(place({ name: { value: 'X' } }), { decision: 'todo', fields: { street: { state: 'edited', value: 'Bát Nàn' } } })).toBe(false)
    expect(missingStreet(place({ street: { value: 'Tạ Hiện', sure: 'high' } }), { decision: 'todo', fields: { street: { state: 'rejected', value: 'Tạ Hiện' } } })).toBe(true)
  })
})

describe('on Google Maps', () => {
  it('only a result close and alike enough to merge counts', () => {
    expect(googleMergeable({ name: 'Phở Vụ', nameMatch: 1, distanceM: 6 })).toBe(true)
    expect(googleMergeable({ name: 'Phở Vụ', nameMatch: 1, distanceM: 0 })).toBe(true)
    expect(googleMergeable({ name: 'Phở Vụ Hà Nội', nameMatch: 1, distanceM: 1134000 })).toBe(false)
    expect(googleMergeable({ name: 'Trà sữa The Alley', nameMatch: 0.4, distanceM: 7 })).toBe(false)
    expect(googleMergeable({ status: 'not found nearby' })).toBe(false)
    expect(googleMergeable(null)).toBe(false)
  })
})
