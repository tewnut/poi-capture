import { describe, expect, it } from 'vitest'
import { cell, csv, geojson, osm, osmHours, record } from './publish'
import { _test } from './importer'
import type { Dataset, Place, Review } from './types'

const place: Place = {
  id: 's1-01', name: 'Bún Bò Huế Hương Cố Đô', category: 'restaurant', status: 'open', confidence: 'high',
  fields: {
    name: { value: 'Bún Bò Huế Hương Cố Đô', photo: 37 },
    phone: { value: '0886673868', photo: 37 },
    houseNumber: { value: '106', photo: 37, sure: 'high' },
    openingHours: { value: '07:00–22:00', photo: 20, sure: 'medium' },
  },
  photos: [{ file: '12_x/COVER.jpg', cover: true }],
  pins: {
    algorithm: { lat: 10.7769, lon: 106.7578, sightings: [] },
    agent: { lat: 10.77695, lon: 106.75775, why: 'shopfront' },
    google: { lat: 10.7771, lon: 106.7576, name: 'Google name' },
  },
  google: { name: 'Google name', phone: '+84 999', address: 'Google address' },
}
const dataset = { id: 's1', session: { id: 's1', name: 'NTS', startedAt: '2026-11-15T04:41:59Z' }, places: [place], tasks: [
  { type: 'removal', place: null, name: 'Old shop', lat: 10.77, lon: 106.75, why: 'for rent' },
] } as unknown as Dataset

describe('publish', () => {
  const review: Review = { decision: 'confirmed', fields: { name: { state: 'ok', value: 'Bún Bò Huế Hương Cố Đô' },
    phone: { state: 'ok', value: '0886673868' }, houseNumber: { state: 'rejected', value: '106' },
    openingHours: { state: 'ok', value: '07:00–22:00' } }, reviewer: 'A', at: '2026-11-15T10:00:00Z' }

  it('uses the confirmed fields and the agent pin, never the Google pin', () => {
    const r = record({ dataset, place, review })
    expect(r.phone).toBe('0886673868')
    expect(r.houseNumber).toBeNull()              // rejected by the reviewer
    expect(r.lat).toBeCloseTo(10.77695)           // agent pin, not Google's
    expect(r.pinSource).toBe('agent')
    const all = geojson([{ dataset, place, review }], []) + csv([{ dataset, place, review }], []) + osm([{ dataset, place, review }])
    expect(all).not.toContain('Google address')
    expect(all).not.toContain('10.7771')
  })

  it('a field never confirmed is not published', () => {
    const r = record({ dataset, place, review: { decision: 'confirmed', fields: {} } })
    expect(r.phone).toBeNull()
    expect(r.name).toBe(place.name)
  })

  it('a pin the reviewer dragged wins', () => {
    const r = record({ dataset, place, review: { ...review, pin: { lat: 10.1, lon: 106.1, source: 'manual' } } })
    expect([r.lat, r.lon, r.pinSource]).toEqual([10.1, 106.1, 'manual'])
  })

  it('writes OSM tags', () => {
    const x = osm([{ dataset, place, review }])
    expect(x).toContain('<tag k="amenity" v="restaurant"/>')
    expect(x).toContain('<tag k="phone" v="+84 886673868"/>')
    expect(x).toContain('<tag k="opening_hours" v="Mo-Su 07:00-22:00"/>')
    expect(x).toContain('id="-1"')
    expect(x).not.toContain('addr:housenumber')
  })

  it('reads opening hours only when they parse', () => {
    expect(osmHours('7h00 - 21h30')).toBe('Mo-Su 07:00-21:30')
    expect(osmHours('Mo-Fr 08:00-17:00')).toBe('Mo-Fr 08:00-17:00')
    expect(osmHours('sáng 7 giờ')).toBeNull()
  })

  it('escapes CSV and lists removals', () => {
    const c = csv([{ dataset, place: { ...place, name: 'A, "B"' }, review: { decision: 'confirmed', fields: {} } }],
      [{ dataset, index: 0, review: { decision: 'confirmed' } }])
    expect(c).toContain('"A, ""B"""')
    expect(c).toContain('remove,Old shop')
    expect(c.charCodeAt(0)).toBe(0xfeff)
  })
})

describe('Google-filled fields', () => {
  const p2: Place = { ...place, fields: { ...place.fields, website: { value: 'shop.vn', source: 'google', sure: 'medium' } } }
  const rv: Review = { decision: 'confirmed', fields: { website: { state: 'ok', value: 'shop.vn' } } }
  it('are marked in CSV / GeoJSON and left out of the .osm file', () => {
    expect(record({ dataset, place: p2, review: rv }).fromGoogle).toBe('website')
    expect(csv([{ dataset, place: p2, review: rv }], [])).toContain('shop.vn')
    expect(osm([{ dataset, place: p2, review: rv }])).not.toContain('shop.vn')
  })
  it('edited by the reviewer, they are the reviewer\'s', () => {
    const ed: Review = { decision: 'confirmed', fields: { website: { state: 'edited', value: 'shop2.vn' } } }
    expect(record({ dataset, place: p2, review: ed }).fromGoogle).toBe('')
    expect(osm([{ dataset, place: p2, review: ed }])).toContain('shop2.vn')
  })
})

describe('comparison labels stay metadata', () => {
  const p3: Place = { ...place, fields: {
    phone: { value: '0886673868', photo: 37, google: 'differs', googleValue: '02811112222' },
    houseNumber: { value: '106', photo: 37, google: 'match' },
    website: { value: 'shop.vn', source: 'google', sure: 'medium', note: 'Google lists only one day' } } }
  const rv: Review = { decision: 'confirmed', fields: { phone: { state: 'ok', value: '0886673868' },
    houseNumber: { state: 'ok', value: '106' }, website: { state: 'ok', value: 'shop.vn' } } }
  it('never appear in GeoJSON, CSV or OSM', () => {
    const out = geojson([{ dataset, place: p3, review: rv }], []) + csv([{ dataset, place: p3, review: rv }], []) + osm([{ dataset, place: p3, review: rv }])
    for (const s of ['match', 'differs', 'googleValue', '02811112222', 'only one day', 'khớp', 'khác Google'])
      expect(out).not.toContain(s)
    expect(out).toContain('0886673868')
  })
})

describe('csv cells', () => {
  it('defuses formulas but keeps numbers', () => {
    expect(cell('=HYPERLINK("http://x")')).toBe(`"'=HYPERLINK(""http://x"")"`)
    expect(cell('+84 886')).toBe("'+84 886")
    expect(cell('@x')).toBe("'@x")
    expect(cell(-10.5)).toBe('-10.5')
    expect(cell('Bún bò')).toBe('Bún bò')
  })
})

describe('import', () => {
  it('strips the session folder the user picked or zipped', () => {
    const s = _test.stripTop(['NTS/result.json', 'NTS/01_a/COVER.jpg'])
    expect(s('NTS/01_a/COVER.jpg')).toBe('01_a/COVER.jpg')
    const t = _test.stripTop(['result.json', '01_a/COVER.jpg'])
    expect(t('01_a/COVER.jpg')).toBe('01_a/COVER.jpg')
  })
})
