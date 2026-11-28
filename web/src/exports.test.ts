import { describe, expect, it } from 'vitest'
import { unzipSync, strFromU8 } from 'fflate'
import { allFormats, colName, DBF_FIELDS, gpx, kml, rows, shapefile, utf8Cut, xlsx } from './exports'
import { CSV_COLS, type Published, type Removed } from './publish'
import type { Dataset, Place, Review } from './types'

const place: Place = {
  id: 's1-01', name: 'Cà phê Đồng Khởi', category: 'cafe', status: 'open', confidence: 'high',
  fields: {
    name: { value: 'Cà phê Đồng Khởi', photo: 3 },
    phone: { value: '0886673868', photo: 3, google: 'differs', googleValue: '02811112222' },
    website: { value: 'caphe.vn', source: 'google', sure: 'medium', note: 'Google lists only one day' },
  },
  photos: [{ file: 'a/COVER.jpg', cover: true }],
  pins: {
    agent: { lat: 10.77695, lon: 106.75775, why: 'shopfront' },
    google: { lat: 10.7771, lon: 106.7576, name: 'Google name' },
  },
  google: { name: 'Google name', phone: '+84 999', address: 'Google address' },
}
const sign: Place = { id: 's1-s1', name: 'Cấm đỗ xe', category: 'traffic_sign', status: 'open', layer: 'sign',
  sign: { code: 'P.131a' }, fields: { signCode: { value: 'P.131a' }, name: { value: 'Cấm đỗ xe' } },
  photos: [], pins: { agent: { lat: 10.2, lon: 106.2 } } }
const addr: Place = { id: 's1-a1', name: '106 Nguyễn Thanh Sơn', category: 'address', status: 'open', layer: 'address',
  fields: { houseNumber: { value: '106' }, street: { value: 'Nguyễn Thanh Sơn' } }, photos: [], pins: { agent: { lat: 10.1, lon: 106.1 } } }
const dataset = { id: 's1', session: { id: 's1', name: 'NTS', startedAt: '2026-11-15T04:41:59Z' }, places: [place, sign, addr], tasks: [
  { type: 'removal', place: null, name: 'Tiệm cũ', lat: 10.77, lon: 106.75, why: 'cho thuê' },
] } as unknown as Dataset
const ok = (p: Place): Review => ({ decision: 'confirmed', reviewer: 'A', at: '2026-11-15T10:00:00Z',
  fields: Object.fromEntries(Object.entries(p.fields).map(([k, f]) => [k, { state: 'ok', value: f!.value }])) })
const pubs: Published[] = [place, sign, addr].map((p) => ({ dataset, place: p, review: ok(p) }))
const rem: Removed[] = [{ dataset, index: 0, review: { decision: 'confirmed', reviewer: 'A' } }]
const nasty: Published = { dataset, place: { ...place, id: 's1-02', name: 'A & B <x> "q"', fields: {} }, review: { decision: 'confirmed', fields: {} } }

const forbidden = ['Google address', '10.7771', '106.7576', 'differs', 'googleValue', '02811112222', 'only one day', 'Google name']
const allText = () => {
  const z = unzipSync(allFormats(pubs, rem, 'x'))
  const xl = unzipSync(z['x.xlsx'])
  return Object.entries({ ...z, ...xl }).filter(([k]) => !k.endsWith('.xlsx') && !k.endsWith('.shp') && !k.endsWith('.shx'))
    .map(([, b]) => strFromU8(b)).join('\n')
}

describe('rows', () => {
  it('one per place and removal, with layer, signCode and change', () => {
    const r = rows(pubs, rem)
    expect(r.map((x) => [x.layer, x.change])).toEqual([['place', 'add_or_update'], ['sign', 'add_or_update'], ['address', 'add_or_update'], [null, 'remove']])
    expect(r[1].signCode).toBe('P.131a')
    expect(r[0].fromGoogle).toBe('website')
  })
})

describe('KML', () => {
  const k = kml([...pubs, nasty], rem, 'NTS')
  it('folders per layer, Vietnamese kept, escaped', () => {
    for (const f of ['Places', 'Addresses', 'Signs', 'Removals']) expect(k).toContain(`<name>${f}</name>`)
    expect(k).toContain('<name>Cà phê Đồng Khởi</name>')
    expect(k).toContain('<name>A &amp; B &lt;x&gt; &quot;q&quot;</name>')
    expect(k).not.toContain('<x>')
    expect(k).toContain('<Data name="signCode"><value>P.131a</value></Data>')
    expect(k).toContain('<Data name="fromGoogle"><value>website</value></Data>')
    expect(k).toContain('<Data name="change"><value>remove</value></Data>')
    expect(k).toContain('<coordinates>106.75775,10.77695</coordinates>')
    expect(k.match(/<Placemark>/g)).toHaveLength(5)
  })
})

describe('GPX', () => {
  const g = gpx([...pubs, nasty], rem)
  it('a waypoint per row, type = layer', () => {
    expect(g.match(/<wpt /g)).toHaveLength(5)
    expect(g).toContain('<wpt lat="10.77695" lon="106.75775">')
    expect(g).toContain('<name>Cà phê Đồng Khởi</name>')
    expect(g).toContain('<name>A &amp; B &lt;x&gt; &quot;q&quot;</name>')
    expect(g).toContain('signCode: P.131a')
    expect(g).toContain('<type>sign</type>')
    expect(g).toContain('<type>address</type>')
    expect(g).toContain('<type>removal</type>')
    expect(g).toContain('<name>Tiệm cũ</name>')
  })
})

describe('Shapefile', () => {
  const z = unzipSync(shapefile([...pubs, nasty], rem, 'p'))
  it('has the five parts', () => {
    expect(Object.keys(z).sort()).toEqual(['p.cpg', 'p.dbf', 'p.prj', 'p.shp', 'p.shx'])
    expect(strFromU8(z['p.cpg'])).toBe('UTF-8')
    expect(strFromU8(z['p.prj'])).toContain('WGS_1984')
  })
  it('headers and record counts agree', () => {
    const n = 5
    const shp = new DataView(z['p.shp'].buffer, z['p.shp'].byteOffset, z['p.shp'].byteLength)
    expect(shp.getInt32(0)).toBe(9994)
    expect(shp.getInt32(24) * 2).toBe(z['p.shp'].byteLength)
    expect(z['p.shp'].byteLength).toBe(100 + 28 * n)
    expect(shp.getInt32(28, true)).toBe(1000)
    expect(shp.getInt32(32, true)).toBe(1)
    expect(shp.getFloat64(36, true)).toBeCloseTo(106.1)    // xmin
    expect(shp.getFloat64(52, true)).toBeCloseTo(106.75775) // xmax
    expect(shp.getInt32(100 + 28 * 4)).toBe(5)              // last record number
    expect(shp.getFloat64(100 + 12, true)).toBeCloseTo(106.75775)
    expect(shp.getFloat64(100 + 20, true)).toBeCloseTo(10.77695)
    const shx = new DataView(z['p.shx'].buffer, z['p.shx'].byteOffset, z['p.shx'].byteLength)
    expect(shx.getInt32(24) * 2).toBe(100 + 8 * n)
    expect(shx.getInt32(100 + 8)).toBe((100 + 28) / 2)
    const d = z['p.dbf']
    const dv = new DataView(d.buffer, d.byteOffset, d.byteLength)
    expect(dv.getUint32(4, true)).toBe(n)
    const hl = dv.getUint16(8, true), rl = dv.getUint16(10, true)
    expect(hl).toBe(32 + 32 * CSV_COLS.length + 1)
    expect(d.byteLength).toBe(hl + rl * n + 1)
    expect(d[d.byteLength - 1]).toBe(0x1a)
    // field names, at most 10 characters
    const names: string[] = []
    for (let o = 32; d[o] !== 0x0d; o += 32) names.push(strFromU8(d.slice(o, o + 11)).replace(/\0+$/, ''))
    expect(names).toEqual(CSV_COLS.map((c) => DBF_FIELDS[c]))
    expect(Math.max(...names.map((x) => x.length))).toBeLessThanOrEqual(10)
    // Vietnamese round-trips in UTF-8, the sign code and the removal are there
    const body = strFromU8(d.slice(hl))
    expect(body).toContain('Cà phê Đồng Khởi')
    expect(body).toContain('P.131a')
    expect(body).toContain('remove')
    expect(body).toContain('A & B <x> "q"')
  })
  it('cuts text at 254 bytes without splitting a character', () => {
    const s = 'Đ'.repeat(200)                // 2 bytes each
    const b = utf8Cut(s)
    expect(b.length).toBe(254)
    expect(strFromU8(b)).toBe('Đ'.repeat(127))
    const t = 'a' + 'ờ'.repeat(100)           // 3 bytes each: 1 + 84*3 = 253
    expect(utf8Cut(t).length).toBe(253)
    expect(strFromU8(utf8Cut(t))).toBe('a' + 'ờ'.repeat(84))
  })
})

describe('Excel', () => {
  it('a zip with the SpreadsheetML parts, inline strings, numbers for lat / lon, no formulas', () => {
    const f = { ...nasty, place: { ...nasty.place, id: 's1-03', name: '=HYPERLINK("http://x")' } }
    const z = unzipSync(xlsx([...pubs, nasty, f], rem))
    expect(Object.keys(z).sort()).toEqual(['[Content_Types].xml', '_rels/.rels', 'xl/_rels/workbook.xml.rels',
      'xl/styles.xml', 'xl/workbook.xml', 'xl/worksheets/sheet1.xml'])
    expect(strFromU8(z['xl/workbook.xml'])).toContain('<sheet name="Places"')
    const s = strFromU8(z['xl/worksheets/sheet1.xml'])
    expect(s.match(/<row /g)).toHaveLength(7)          // header + 6
    expect(s).toContain('<t xml:space="preserve">Cà phê Đồng Khởi</t>')
    expect(s).toContain('<t xml:space="preserve">A &amp; B &lt;x&gt; &quot;q&quot;</t>')
    expect(s).toContain('<t xml:space="preserve">P.131a</t>')
    expect(s).toContain('<t xml:space="preserve">remove</t>')
    const lat = colName(CSV_COLS.indexOf('lat'))
    expect(s).toContain(`<c r="${lat}2"><v>10.77695</v></c>`)
    expect(s).toContain('=HYPERLINK(&quot;http://x&quot;)')
    expect(s).not.toMatch(/<f[ >]/)
  })
  it('names columns like a spreadsheet', () => {
    expect([colName(0), colName(25), colName(26), colName(27), colName(701), colName(702)]).toEqual(['A', 'Z', 'AA', 'AB', 'ZZ', 'AAA'])
  })
})

describe('all formats zip', () => {
  it('bundles every format', () => {
    const z = unzipSync(allFormats(pubs, rem, 'x'))
    expect(Object.keys(z).sort()).toEqual(['shapefile/x.cpg', 'shapefile/x.dbf', 'shapefile/x.prj', 'shapefile/x.shp', 'shapefile/x.shx',
      'x.csv', 'x.geojson', 'x.gpx', 'x.kml', 'x.osm', 'x.xlsx'])
    expect(strFromU8(z['x.osm'])).not.toContain('caphe.vn')       // Google's value stays out of the .osm file
    expect(strFromU8(z['x.kml'])).toContain('caphe.vn')           // and is marked fromGoogle elsewhere
  })
  it('never carries the Google block, Google pin or comparison metadata', () => {
    const t = allText()
    for (const s of forbidden) expect(t).not.toContain(s)
    expect(t).toContain('0886673868')
  })
})
