import { describe, expect, it } from 'vitest'
import { cell, csv, geojson, osm, osmHours, record, tasksGeojson } from './publish'
import { _test } from './importer'
import { keptPhotos, removedPhotos, exportNames } from './photos'
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

describe('tasks for the phone', () => {
  const ds = { ...dataset, tasks: [
    { type: 'visit', place: 's1-01', name: 'Visit me', lat: 10.1, lon: 106.1, why: 'sign unreadable' },
    { type: 'removal', place: null, name: 'Gone shop', lat: 10.2, lon: 106.2, why: 'for rent' },
    { type: 'removal', place: null, name: 'Check shop', lat: 10.3, lon: 106.3, why: 'shutter down' },
    { type: 'verify_removal', place: null, name: 'Maybe gone', lat: 10.4, lon: 106.4, why: 'not seen' },
  ] } as unknown as Dataset

  it('sends only the work left on site, as poi-tasks/1', () => {
    const j = JSON.parse(tasksGeojson([ds], {}, { 's1|1': { decision: 'confirmed' }, 's1|2': { decision: 'verify' } }, 'A'))
    expect(j.schema).toBe('poi-tasks/1')
    expect(j.features.map((f: any) => f.properties.name)).toEqual(['Visit me', 'Check shop', 'Maybe gone'])
    expect(j.features[1].properties.type).toBe('verify_removal')
    expect(j.features[0].geometry.coordinates).toEqual([106.1, 10.1])
  })

  it('adds places the reviewer marked "visit", once', () => {
    const p2 = { ...place, id: 's1-02', name: 'Second' }
    const d2 = { ...dataset, places: [place, p2], tasks: [] } as unknown as Dataset
    const reviews = { 's1|s1-02': { decision: 'visit', fields: {}, comment: 'hours missing' } } as Record<string, Review>
    const j = JSON.parse(tasksGeojson([d2], reviews, {}))
    expect(j.features).toHaveLength(1)
    expect(j.features[0].properties).toMatchObject({ type: 'visit', name: 'Second', why: 'hours missing', place: 's1-02' })
    expect(j.features[0].geometry.coordinates).toEqual([106.75775, 10.77695])   // agent pin
  })
})

describe('photo edits', () => {
  const p: Place = { ...place, photos: [{ file: 'a/1.jpg', index: 1 }, { file: 'a/COVER.jpg', cover: true }, { file: 'a/WEB_1.png', source: 'website' }] }
  it('keeps the photos, cover first, when nothing was edited', () => {
    expect(keptPhotos(p).map((x) => x.file)).toEqual(['a/COVER.jpg', 'a/1.jpg', 'a/WEB_1.png'])
  })
  it('removes, adds, crops and picks the cover', () => {
    const r: Review = { decision: 'confirmed', fields: {}, photos: {
      removed: ['a/COVER.jpg'], added: ['added/x/1.jpg'], cover: 'added/x/1.jpg',
      crops: { 'a/1.jpg': { rect: [0.1, 0.1, 0.5, 0.5], file: 'crop/x/c.jpg' } } } }
    const k = keptPhotos(p, r)
    expect(k.map((x) => x.show)).toEqual(['added/x/1.jpg', 'crop/x/c.jpg', 'a/WEB_1.png'])
    expect(k[0].cover).toBe(true)
    expect(removedPhotos(p, r).map((x) => x.file)).toEqual(['a/COVER.jpg'])
    expect(exportNames(p, r)).toEqual(['s1-01/1.jpg', 's1-01/2.jpg', 's1-01/3.png'])
    expect(record({ dataset, place: p, review: r }).photos).toBe('s1-01/1.jpg s1-01/2.jpg s1-01/3.png')
  })
  it('falls back to the first photo when the cover was removed', () => {
    const k = keptPhotos(p, { decision: 'todo', fields: {}, photos: { removed: ['a/COVER.jpg'] } })
    expect(k[0]).toMatchObject({ file: 'a/1.jpg', cover: true })
  })
})

describe('addresses and signs (docs/sweep-targets.md)', () => {
  const addr: Place = { id: 's1-a1', name: '106 Nguyễn Thanh Sơn', category: 'address', status: 'open', layer: 'address', placeRef: 'Bún Bò',
    fields: { houseNumber: { value: '106', sure: 'high' }, street: { value: 'Nguyễn Thanh Sơn, Thảo Điền', sure: 'high' } },
    photos: [], pins: { agent: { lat: 10.1, lon: 106.1 } } }
  const sign: Place = { id: 's1-s1', name: 'Cấm đỗ xe', category: 'traffic_sign', status: 'open', layer: 'sign',
    sign: { code: 'P.131a', group: 'prohibitory', facingDeg: 271.6, side: 'right' },
    fields: { signCode: { value: 'P.131a', sure: 'high' }, name: { value: 'Cấm đỗ xe' }, other: { value: '6h-9h' } },
    photos: [], pins: { agent: { lat: 10.2, lon: 106.2 } } }
  const ok = (p: Place): Review => ({ decision: 'confirmed', fields: Object.fromEntries(Object.entries(p.fields).map(([k, f]) => [k, { state: 'ok', value: f!.value }])) })

  it('an address is a node with addr:housenumber and addr:street only', () => {
    const x = osm([{ dataset, place: addr, review: ok(addr) }])
    expect(x).toContain('<tag k="addr:housenumber" v="106"/>')
    expect(x).toContain('<tag k="addr:street" v="Nguyễn Thanh Sơn"/>')
    expect(x).not.toContain('k="name"')
  })

  it('a sign is a node with traffic_sign=VN:<code> and direction, only with a confirmed code', () => {
    const x = osm([{ dataset, place: sign, review: ok(sign) }])
    expect(x).toContain('<tag k="traffic_sign" v="VN:P.131a"/>')
    expect(x).toContain('<tag k="direction" v="272"/>')
    expect(x).not.toContain('k="name"')
    const noCode: Review = { decision: 'confirmed', fields: { signCode: { state: 'rejected', value: 'P.131a' } } }
    expect(osm([{ dataset, place: sign, review: noCode }])).not.toContain('<node')
    const edited: Review = { decision: 'confirmed', fields: { signCode: { state: 'edited', value: 'P.130' } } }
    expect(osm([{ dataset, place: { ...sign, sign: { code: 'P.131a' } }, review: edited }])).toContain('v="VN:P.130"')
    expect(osm([{ dataset, place: { ...sign, sign: { code: 'P.131a' } }, review: edited }])).not.toContain('direction')
  })

  it('numbers the nodes one by one when some are left out', () => {
    const x = osm([{ dataset, place: sign, review: { decision: 'confirmed', fields: {} } }, { dataset, place: addr, review: ok(addr) }])
    expect(x).toContain('id="-1"')
    expect(x).not.toContain('id="-2"')
  })

  it('GeoJSON and CSV carry the layer, and the sign code for signs', () => {
    const all = [{ dataset, place: addr, review: ok(addr) }, { dataset, place: sign, review: ok(sign) }, { dataset, place, review: { decision: 'confirmed', fields: {} } as Review }]
    const props = JSON.parse(geojson(all, [])).features.map((f: any) => f.properties)
    expect(props.map((p: any) => p.layer)).toEqual(['address', 'sign', 'place'])
    expect(props[1].signCode).toBe('P.131a')
    expect('signCode' in props[0]).toBe(false)
    const c = csv(all, []).split('\r\n')
    const cols = c[0].replace('﻿', '').split(',')
    expect(c[2].split(',')[cols.indexOf('layer')]).toBe('sign')
    expect(c[2].split(',')[cols.indexOf('signCode')]).toBe('P.131a')
    expect(c[3].split(',')[cols.indexOf('layer')]).toBe('place')
  })
})
