// More publish formats, all from the same flat rows as the CSV (publish.ts): KML (Google Earth / My Maps), GPX
// (GPS devices, OsmAnd), a zipped Shapefile, Excel (.xlsx) and one zip with every format.
// The same rules as publish.ts hold: never the Google block or Google's pin; fields still Google's are marked in
// `fromGoogle`; comparison metadata (match / differs, source, note) is never a value. The .osm file is publish.ts's.
// Written by hand (no extra dependency); GeoPackage is left out: it is an SQLite database, which needs an SQLite
// writer (sql.js / wasm, ~1 MB) to produce safely.
import { zipSync, strToU8 } from 'fflate'
import { CSV_COLS, csv, geojson, osm, record, type Published, type Removed } from './publish'

export type Row = Record<(typeof CSV_COLS)[number], string | number | null>

/** One flat row per confirmed place (change add_or_update) and per confirmed removal (change remove), CSV columns. */
export function rows(places: Published[], removed: Removed[]): Row[] {
  const out: Row[] = []
  const blank = () => Object.fromEntries(CSV_COLS.map((c) => [c, null])) as Row
  for (const x of places) {
    const r: Record<string, any> = record(x)
    const row = blank()
    for (const c of CSV_COLS) if (c in r) row[c] = r[c] ?? null
    row.change = 'add_or_update'
    out.push(row)
  }
  for (const { dataset, index, review } of removed) {
    const t = dataset.tasks[index]
    out.push({ ...blank(), id: `${dataset.session.id}-removal-${index}`, change: 'remove', name: t.name, lat: t.lat, lon: t.lon,
      session: dataset.session.id, comment: t.why, reviewer: review.reviewer || '', reviewedAt: review.at || '' })
  }
  return out
}

/** XML text / attribute value: escaped, without the control characters XML 1.0 does not allow. */
export function xml(v: unknown) {
  return (v == null ? '' : String(v)).replace(/[\x00-\x08\x0B\x0C\x0E-\x1F￾￿]/g, '')
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

const FOLDERS: [string, (r: Row) => boolean][] = [
  ['Places', (r) => r.change !== 'remove' && r.layer === 'place'],
  ['Addresses', (r) => r.change !== 'remove' && r.layer === 'address'],
  ['Signs', (r) => r.change !== 'remove' && r.layer === 'sign'],
  ['Removals', (r) => r.change === 'remove'],
]

/** The main fields as "key: value" lines (KML description, GPX desc). */
function summary(r: Row) {
  return (['category', 'signCode', 'status', 'phone', 'houseNumber', 'street', 'building', 'level', 'unit', 'openingHours', 'website', 'fromGoogle', 'comment'] as const)
    .filter((k) => r[k] != null && r[k] !== '').map((k) => `${k}: ${r[k]}`).join('\n')
}

/** KML 2.2: a Folder per layer (Places / Addresses / Signs / Removals), a Placemark per row, the columns as ExtendedData. */
export function kml(places: Published[], removed: Removed[], title = 'POI') {
  const all = rows(places, removed)
  const out = ['<?xml version="1.0" encoding="UTF-8"?>', '<kml xmlns="http://www.opengis.net/kml/2.2">', '<Document>',
    `  <name>${xml(title)}</name>`]
  for (const [name, test] of FOLDERS) {
    const list = all.filter(test)
    if (!list.length) continue
    out.push('  <Folder>', `    <name>${name}</name>`)
    for (const r of list) {
      out.push('    <Placemark>', `      <name>${xml(r.name)}</name>`, `      <description>${xml(summary(r))}</description>`, '      <ExtendedData>')
      for (const c of CSV_COLS) if (r[c] != null && r[c] !== '') out.push(`        <Data name="${c}"><value>${xml(r[c])}</value></Data>`)
      out.push('      </ExtendedData>', `      <Point><coordinates>${r.lon},${r.lat}</coordinates></Point>`, '    </Placemark>')
    }
    out.push('  </Folder>')
  }
  out.push('</Document>', '</kml>')
  return out.join('\n') + '\n'
}

/** GPX 1.1: a waypoint per row; name, the main fields in desc, type = the layer (or "removal"). */
export function gpx(places: Published[], removed: Removed[]) {
  const out = ['<?xml version="1.0" encoding="UTF-8"?>',
    '<gpx version="1.1" creator="poi-capture-reviewer" xmlns="http://www.topografix.com/GPX/1/1">']
  for (const r of rows(places, removed)) {
    out.push(`  <wpt lat="${r.lat}" lon="${r.lon}">`, `    <name>${xml(r.name)}</name>`, `    <desc>${xml(summary(r))}</desc>`,
      `    <type>${r.change === 'remove' ? 'removal' : xml(r.layer)}</type>`, '  </wpt>')
  }
  out.push('</gpx>')
  return out.join('\n') + '\n'
}

// ---------- Shapefile ----------

/** DBF field names (at most 10 characters) for the CSV columns. lat / lon are numbers (N 18.7), the rest text (C). */
export const DBF_FIELDS: Record<(typeof CSV_COLS)[number], string> = {
  id: 'id', change: 'change', name: 'name', category: 'category', layer: 'layer', signCode: 'sign_code', signPost: 'sign_post',
  signOrder: 'sign_order', status: 'status',
  phone: 'phone', houseNumber: 'house_no', street: 'street', level: 'level', unit: 'unit', building: 'building', openingHours: 'open_hours', website: 'website',
  fromGoogle: 'from_ggl', lat: 'lat', lon: 'lon', pinSource: 'pin_source', session: 'session', sessionName: 'sess_name',
  captured: 'captured', reviewer: 'reviewer', reviewedAt: 'reviewed', comment: 'comment', photos: 'photos',
}

/** UTF-8 bytes of a text, cut to at most `max` bytes without splitting a character. */
export function utf8Cut(s: string, max = 254) {
  const b = strToU8(s)
  if (b.length <= max) return b
  let n = max
  while (n > 0 && (b[n] & 0xc0) === 0x80) n--     // b[n] is a continuation byte: the character starts before n
  return b.slice(0, n)
}

export const WGS84_PRJ = 'GEOGCS["GCS_WGS_1984",DATUM["D_WGS_1984",SPHEROID["WGS_1984",6378137.0,298.257223563]],PRIMEM["Greenwich",0.0],UNIT["Degree",0.0174532925199433]]'

function shpHeader(v: DataView, words: number, box: number[]) {
  v.setInt32(0, 9994)            // file code, big endian
  v.setInt32(24, words)          // file length in 16-bit words, big endian
  v.setInt32(28, 1000, true)     // version
  v.setInt32(32, 1, true)        // shape type: point
  box.forEach((x, i) => v.setFloat64(36 + 8 * i, x, true))   // Xmin Ymin Xmax Ymax (Z / M stay 0)
}

function dbf(list: Row[]) {
  const num = new Set(['lat', 'lon'])
  const cells = list.map((r) => CSV_COLS.map((c) => num.has(c)
    ? strToU8(r[c] == null ? '' : (+r[c]!).toFixed(7))
    : utf8Cut(r[c] == null ? '' : String(r[c]))))
  const fields = CSV_COLS.map((c, i) => ({ name: DBF_FIELDS[c], num: num.has(c),
    len: num.has(c) ? 18 : Math.max(1, ...cells.map((row) => row[i].length)) }))
  const headerLen = 32 + 32 * fields.length + 1
  const recLen = 1 + fields.reduce((n, f) => n + f.len, 0)
  const out = new Uint8Array(headerLen + recLen * list.length + 1)
  const v = new DataView(out.buffer)
  const now = new Date()
  out.set([0x03, now.getFullYear() - 1900, now.getMonth() + 1, now.getDate()])
  v.setUint32(4, list.length, true)
  v.setUint16(8, headerLen, true)
  v.setUint16(10, recLen, true)
  fields.forEach((f, i) => {
    const o = 32 + 32 * i
    out.set(strToU8(f.name), o)
    out[o + 11] = (f.num ? 'N' : 'C').charCodeAt(0)
    out[o + 16] = f.len
    out[o + 17] = f.num ? 7 : 0
  })
  out[headerLen - 1] = 0x0d
  let o = headerLen
  for (const row of cells) {
    out[o++] = 0x20                              // not deleted
    fields.forEach((f, i) => {
      const b = row[i]
      out.fill(0x20, o, o + f.len)
      out.set(b, f.num ? o + f.len - b.length : o)   // numbers right-aligned, text left-aligned, spaces around
      o += f.len
    })
  }
  out[o] = 0x1a
  return out
}

/** A point Shapefile (.shp .shx .dbf .prj .cpg, WGS84, UTF-8 attributes) as the files, named <base>.*. */
export function shapefileFiles(places: Published[], removed: Removed[], base = 'places'): Record<string, Uint8Array> {
  const list = rows(places, removed)
  const n = list.length
  const box = n ? [Math.min(...list.map((r) => +r.lon!)), Math.min(...list.map((r) => +r.lat!)),
    Math.max(...list.map((r) => +r.lon!)), Math.max(...list.map((r) => +r.lat!))] : [0, 0, 0, 0]
  const shp = new Uint8Array(100 + 28 * n)
  const shx = new Uint8Array(100 + 8 * n)
  const sv = new DataView(shp.buffer), xv = new DataView(shx.buffer)
  shpHeader(sv, shp.length / 2, box)
  shpHeader(xv, shx.length / 2, box)
  list.forEach((r, i) => {
    const o = 100 + 28 * i
    sv.setInt32(o, i + 1)                 // record number, from 1
    sv.setInt32(o + 4, 10)                // content length in words (20 bytes)
    sv.setInt32(o + 8, 1, true)           // point
    sv.setFloat64(o + 12, +r.lon!, true)
    sv.setFloat64(o + 20, +r.lat!, true)
    xv.setInt32(100 + 8 * i, o / 2)
    xv.setInt32(100 + 8 * i + 4, 10)
  })
  return { [`${base}.shp`]: shp, [`${base}.shx`]: shx, [`${base}.dbf`]: dbf(list), [`${base}.prj`]: strToU8(WGS84_PRJ), [`${base}.cpg`]: strToU8('UTF-8') }
}

export function shapefile(places: Published[], removed: Removed[], base = 'places') {
  return zipSync(shapefileFiles(places, removed, base))
}

// ---------- Excel ----------

/** Column letters: 0 -> A, 25 -> Z, 26 -> AA. */
export function colName(i: number) {
  let s = ''
  for (i++; i > 0; i = Math.floor((i - 1) / 26)) s = String.fromCharCode(65 + ((i - 1) % 26)) + s
  return s
}

/** A minimal .xlsx: one sheet "Places", a header row, text as inline strings (never formulas), lat / lon as numbers. */
export function xlsx(places: Published[], removed: Removed[]) {
  const str = (ref: string, v: unknown) =>
    `<c r="${ref}" t="inlineStr"><is><t xml:space="preserve">${xml(String(v).slice(0, 32767))}</t></is></c>`
  const sheetRows = [`<row r="1">${CSV_COLS.map((c, i) => str(`${colName(i)}1`, c)).join('')}</row>`]
  rows(places, removed).forEach((r, k) => {
    const n = k + 2
    const cs = CSV_COLS.map((c, i) => {
      const v = r[c], ref = `${colName(i)}${n}`
      if (v == null || v === '') return ''
      if ((c === 'lat' || c === 'lon') && Number.isFinite(+v)) return `<c r="${ref}"><v>${+v}</v></c>`
      return str(ref, v)
    })
    sheetRows.push(`<row r="${n}">${cs.join('')}</row>`)
  })
  const head = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
  const files: Record<string, Uint8Array> = {
    '[Content_Types].xml': strToU8(head + '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
      + '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
      + '<Default Extension="xml" ContentType="application/xml"/>'
      + '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
      + '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
      + '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
      + '</Types>'),
    '_rels/.rels': strToU8(head + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
      + '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
      + '</Relationships>'),
    'xl/workbook.xml': strToU8(head + '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
      + 'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
      + '<sheets><sheet name="Places" sheetId="1" r:id="rId1"/></sheets></workbook>'),
    'xl/_rels/workbook.xml.rels': strToU8(head + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
      + '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>'
      + '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>'
      + '</Relationships>'),
    'xl/styles.xml': strToU8(head + '<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
      + '<fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>'
      + '<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>'
      + '<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>'
      + '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>'
      + '<cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>'
      + '</styleSheet>'),
    'xl/worksheets/sheet1.xml': strToU8(head + '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
      + '<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>'
      + `<sheetData>${sheetRows.join('')}</sheetData></worksheet>`),
  }
  return zipSync(files)
}

// ---------- everything ----------

/** One zip: <base>.geojson / .csv / .osm / .kml / .gpx / .xlsx and shapefile/<base>.shp (.shx .dbf .prj .cpg). */
export function allFormats(places: Published[], removed: Removed[], base = 'places') {
  const files: Record<string, Uint8Array | [Uint8Array, { level: 0 }]> = {
    [`${base}.geojson`]: strToU8(geojson(places, removed)),
    [`${base}.csv`]: strToU8(csv(places, removed)),
    [`${base}.osm`]: strToU8(osm(places)),
    [`${base}.kml`]: strToU8(kml(places, removed, base)),
    [`${base}.gpx`]: strToU8(gpx(places, removed)),
    [`${base}.xlsx`]: [xlsx(places, removed), { level: 0 }],   // already a zip
  }
  for (const [k, b] of Object.entries(shapefileFiles(places, removed, base))) files[`shapefile/${k}`] = b
  return zipSync(files)
}
