import { describe, expect, it } from 'vitest'
import { buildingAddr, defaultLevel, latLonToPlan, levelOrder, levelsOf, normLevel, onLevel, overlayMatrix, placeLevel, placeOfUnit, planCorners, plansOn, planToLatLon, planUnitsOn, planXY, shotsOn, trackOn } from './indoor'
import { CSV_COLS, csv, geojson, osm, record } from './publish'
import { DBF_FIELDS, kml, rows } from './exports'
import { missingStreet, confirmFields } from './fields'
import { _test } from './importer'
import type { Dataset, Place, Review } from './types'

const shop = (id: string, name: string, level: string | null, unit: string | null, extra: Partial<Place> = {}): Place => ({
  id, name, category: 'cafe', status: 'open', confidence: 'high', indoor: true, level, unit,
  fields: {
    name: { value: name, photo: 1 },
    ...(level ? { level: { value: level, photo: 1, sure: 'medium' as const, source: 'walk' as const } } : {}),
    ...(unit ? { unit: { value: unit, photo: 1, sure: 'high' as const } } : {}),
  },
  photos: [{ file: `${id}/COVER.jpg`, cover: true }],
  pins: { algorithm: { lat: 10.8, lon: 106.74 } },
  building: { osm: 'way/1', name: 'Test Mall', address: '161 Xa Lộ Hà Nội' },
  ...extra,
})

const places = [shop('m-1', 'Highlands Coffee', '2', 'L2-15'), shop('m-2', 'Uniqlo', '2', null),
  shop('m-3', 'Gucci', '3', 'L3-01'), shop('m-4', 'Kiosk', null, null)]
const ds = {
  id: 'm', source: 'result', importedAt: '', session: { id: 'm', name: 'Mall', kind: 'indoor', startedAt: '2026-11-25T03:00:00Z' },
  track: [[10.8, 106.74], [10.8001, 106.74], [10.8002, 106.74], [10.8003, 106.74], [10.8004, 106.74]],
  trackLevels: ['2', '2', '3', '3', '2'],
  shots: [[0, 10.8, 106.74, 90, '2'], [1, 10.8003, 106.74, 90, '3']],
  levels: ['3', '2'],
  building: { osm: 'way/1', name: 'Test Mall', footprint: [[10.79, 106.73], [10.79, 106.75], [10.81, 106.75]],
    addr: { 'addr:housenumber': '161', 'addr:street': 'Xa Lộ Hà Nội', 'addr:city': 'Thủ Đức' } },
  places, tasks: [], files: [],
} as unknown as Dataset
const todo = (): Review => ({ decision: 'todo', fields: {} })

describe('levels', () => {
  it('names and order', () => {
    expect(['Tầng 2', 'L3', 'Hầm 1', 'Trệt', 'GF', '4', 'Rooftop'].map(normLevel)).toEqual(['2', '3', 'B1', 'G', 'G', '4', 'Rooftop'])
    expect(['2', 'G', 'B1', 'Rooftop', '1', 'B2'].sort(levelOrder)).toEqual(['B2', 'B1', 'G', '1', '2', 'Rooftop'])
  })

  it('opens on the level with the most places', () => {
    expect(levelsOf(ds)).toEqual(['2', '3'])
    expect(defaultLevel(ds)).toBe('2')
    expect(defaultLevel({ ...ds, session: { id: 'x', kind: 'sweep' } } as Dataset)).toBeNull()
  })

  it('the reviewer’s level wins', () => {
    const r: Review = { decision: 'todo', fields: { level: { state: 'edited', value: 'Tầng 3' } } }
    expect(placeLevel(places[0], r)).toBe('3')
    expect(onLevel(places[0], '3', r)).toBe(true)
    expect(onLevel(places[0], '2', r)).toBe(false)
    expect(defaultLevel(ds, (p) => (p.id === 'm-1' || p.id === 'm-2' ? r : todo()))).toBe('3')
  })

  it('filters places, track and photos to one level', () => {
    expect(places.filter((p) => onLevel(p, '3')).map((p) => p.name)).toEqual(['Gucci', 'Kiosk'])   // no level: every level
    expect(places.filter((p) => onLevel(p, '')).length).toBe(4)
    expect(trackOn(ds, '2')).toEqual([[[10.8, 106.74], [10.8001, 106.74]], [[10.8004, 106.74]]])
    expect(trackOn(ds, '3')).toEqual([[[10.8002, 106.74], [10.8003, 106.74]]])
    expect(trackOn(ds, '')).toEqual([ds.track])
    expect(shotsOn(ds, '3').map((s) => s[0])).toEqual([1])
  })
})

describe('indoor review and export', () => {
  const review = (p: Place): Review => ({ decision: 'confirmed', fields: confirmFields(p, todo()), reviewer: 'A', at: '2026-11-25T10:00:00Z' })

  it('no street is missing inside a building; the walk’s level is accepted on Confirm', () => {
    expect(missingStreet(places[0], todo())).toBe(false)
    expect(review(places[0]).fields.level).toEqual({ state: 'ok', value: '2' })
  })

  it('OSM: level and addr:unit, the building’s address, never a street from the phone', () => {
    const p = shop('m-9', 'Pharmacity', '2', 'L2-20', { fields: { name: { value: 'Pharmacity' },
      level: { value: '2', source: 'walk', sure: 'medium' }, unit: { value: 'L2-20' },
      street: { value: 'Đường Sai', source: 'ride', sure: 'medium' }, houseNumber: { value: '99' } } })
    const r: Review = { decision: 'confirmed', fields: { name: { state: 'ok', value: 'Pharmacity' }, level: { state: 'ok', value: '2' },
      unit: { state: 'edited', value: 'L2-21' }, street: { state: 'ok', value: 'Đường Sai' }, houseNumber: { state: 'ok', value: '99' } } }
    const x = osm([{ dataset: ds, place: p, review: r }])
    expect(x).toContain('<tag k="level" v="1"/>')          // "Tầng 2" with ground = 1: OSM level 1
    expect(x).toContain('<tag k="level:ref" v="2"/>')
    expect(x).toContain('<tag k="addr:unit" v="L2-21"/>')
    expect(x).toContain('<tag k="addr:street" v="Xa Lộ Hà Nội"/>')
    expect(x).toContain('<tag k="addr:housenumber" v="161"/>')
    expect(x).toContain('<tag k="addr:city" v="Thủ Đức"/>')
    expect(x).not.toContain('Đường Sai')
    expect(x).not.toContain('v="99"')
    // a building without an address: no addr:street at all
    const bare = osm([{ dataset: { ...ds, building: { name: 'X' } } as Dataset, place: p, review: r }])
    expect(bare).not.toContain('addr:street')
    expect(bare).not.toContain('addr:housenumber')
    expect(buildingAddr({ addr: { street: 'A' } })).toEqual({ street: 'A' })
  })

  it('an outdoor place keeps its street and gets no level tags', () => {
    const p: Place = { id: 's-1', name: 'Phở', status: 'open', fields: { name: { value: 'Phở' }, street: { value: 'Tạ Hiện' } },
      photos: [], pins: { algorithm: { lat: 10.7, lon: 106.7 } } }
    const x = osm([{ dataset: { ...ds, session: { id: 's' }, building: null } as Dataset, place: p,
      review: { decision: 'confirmed', fields: { street: { state: 'ok', value: 'Tạ Hiện' } } } }])
    expect(x).toContain('<tag k="addr:street" v="Tạ Hiện"/>')
    expect(x).not.toContain('k="level"')
  })

  it('CSV, GeoJSON, KML, Shapefile columns carry level, unit and building', () => {
    const pub = [{ dataset: ds, place: places[0], review: review(places[0]) }]
    const r = record(pub[0])
    expect([r.level, r.unit, r.building]).toEqual(['2', 'L2-15', 'Test Mall'])
    const c = csv(pub, []).replace('﻿', '').split('\r\n')
    expect(c[0].split(',')).toEqual([...CSV_COLS])
    expect(c[0]).toContain(',street,level,unit,building,')
    expect(c[1]).toContain(',2,L2-15,Test Mall,')
    expect(JSON.parse(geojson(pub, [])).features[0].properties).toMatchObject({ level: '2', unit: 'L2-15', building: 'Test Mall' })
    expect(rows(pub, [])[0]).toMatchObject({ level: '2', unit: 'L2-15' })
    expect(kml(pub, [])).toContain('<Data name="unit"><value>L2-15</value></Data>')
    expect([DBF_FIELDS.level, DBF_FIELDS.unit, DBF_FIELDS.building]).toEqual(['level', 'unit', 'building'])
  })
})

describe('importing an indoor result', () => {
  it('keeps the building, levels and directory', () => {
    const { dataset } = _test.fromResult({ schema: 'poi-result/1', session: { id: 'm', kind: 'indoor' }, track: ds.track,
      trackLevels: ds.trackLevels, shots: ds.shots, levels: ['2', '3'], building: ds.building,
      directory: [{ level: '2', unit: 'L2-16', name: 'Adidas', place: null, lat: 10.8, lon: 106.74 }],
      places: [{ ...places[0], photos: [] }], tasks: [] }, new Map())
    expect(dataset.building?.name).toBe('Test Mall')
    expect(dataset.levels).toEqual(['2', '3'])
    expect(dataset.trackLevels).toEqual(ds.trackLevels)
    expect(dataset.directory?.[0].name).toBe('Adidas')
    expect(dataset.places[0].unit).toBe('L2-15')
  })
})

import { osmLevel } from './indoor'
describe('OSM level numbers', () => {
  it('follow the building: ground is "1" unless it names a G floor', () => {
    expect(osmLevel('1', ['B1', '1', '2'])).toBe(0)
    expect(osmLevel('Tầng 2', ['1', '2'])).toBe(1)
    expect(osmLevel('B2', [])).toBe(-2)
    expect(osmLevel('G', ['G', '1'])).toBe(0)
    expect(osmLevel('1', ['G', '1'])).toBe(1)
    expect(osmLevel('Mezzanine', ['1'])).toBe(null)
  })
})

describe('floor plans (docs/indoor.md §7)', () => {
  // the same numbers as the processing's tests/test_plans.py ("TM")
  const TM = { origin: [10.80213, 106.74122] as [number, number], scaleMPerPx: 0.061, rotationDeg: 33.5 }

  it('maps plan pixels onto the map like the processing', () => {
    const [e, n] = planXY(812, 640, TM)
    expect(e).toBeCloseTo(19.7564126, 6)
    expect(n).toBeCloseTo(-59.8934452, 6)
    const [lat, lon] = planToLatLon(812, 640, TM)
    expect(lat).toBeCloseTo(10.801588174, 9)
    expect(lon).toBeCloseTo(106.741400676, 9)
    const [la2, lo2] = planToLatLon(2048, 1365, TM)
    expect(la2).toBeCloseTo(10.800878092, 9)
    expect(lo2).toBeCloseTo(106.741752419, 9)
    expect(planToLatLon(0, 0, TM)).toEqual([10.80213, 106.74122])
  })

  it('round trip, clockwise rotation, y down = south', () => {
    for (const [px, py] of [[0, 0], [812, 640], [2048, 1365], [-30.5, 99.25]]) {
      const [x, y] = latLonToPlan(...planToLatLon(px, py, TM), TM)
      expect(x).toBeCloseTo(px, 6)
      expect(y).toBeCloseTo(py, 6)
    }
    const tm = { origin: [10.8, 106.7] as [number, number], scaleMPerPx: 0.5, rotationDeg: 0 }
    expect(planXY(10, 0, tm)[0]).toBeCloseTo(5)
    expect(planXY(0, 10, tm)[1]).toBeCloseTo(-5)
    const r = planXY(10, 0, { ...tm, rotationDeg: 90 })
    expect(r[0]).toBeCloseTo(0)
    expect(r[1]).toBeCloseTo(-5)
  })

  it('the overlay matrix puts the image corners on the given points', () => {
    const w = 200, h = 100
    const p0 = { x: 50, y: 40 }, p1 = { x: 50 + 160, y: 40 + 120 }, p2 = { x: 50 - 30, y: 40 + 40 }
    const [a, b, c, d, e, f] = overlayMatrix(p0, p1, p2, w, h)
    const at = (x: number, y: number) => [a * x + c * y + e, b * x + d * y + f]
    expect(at(0, 0)).toEqual([50, 40])
    expect(at(w, 0)).toEqual([210, 160])
    expect(at(0, h)).toEqual([20, 80])
    expect(at(w, h)).toEqual([180, 200])                           // the fourth corner follows (a parallelogram)
    // the plan's own corners: pinned through toMap, so the image lands where the processing puts its pixels
    const plan = { file: '_plans/p.jpg', size: [2048, 1365] as [number, number], toMap: TM }
    const [c0, c1, c2] = planCorners(plan)
    expect(c0).toEqual(planToLatLon(0, 0, TM))
    expect(c1).toEqual(planToLatLon(2048, 0, TM))
    expect(c2).toEqual(planToLatLon(0, 1365, TM))
  })

  it('plans and units of the level shown; a unit opens its place', () => {
    const d = {
      ...ds, plans: [{ level: '2', file: '_plans/a.jpg', size: [10, 10], toMap: TM }, { level: 'Tầng 3', file: '_plans/b.jpg', size: [10, 10], toMap: TM }],
      planUnits: [{ level: '2', unit: 'L2-15', place: '01_Highlands' }, { level: '3', unit: 'L3-01', place: null }],
      places: [{ ...shop('a', 'Highlands', '2', 'L2-15'), folder: '01_Highlands' }],
    } as Dataset
    expect(plansOn(d, '2').map((p) => p.file)).toEqual(['_plans/a.jpg'])
    expect(plansOn(d, '3').map((p) => p.file)).toEqual(['_plans/b.jpg'])
    expect(plansOn(d, '')).toEqual([])
    expect(planUnitsOn(d, '3').map((u) => u.unit)).toEqual(['L3-01'])
    expect(placeOfUnit(d, d.planUnits![0])?.id).toBe('a')
    expect(placeOfUnit(d, d.planUnits![1])).toBeUndefined()
  })
})
