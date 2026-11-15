// Turn dropped files (a folder from the agentic processing, or a zip of it, or a raw session zip from the phone)
// into a Dataset plus its photo files. Nothing leaves the browser.
import { unzipSync, strFromU8 } from 'fflate'
import type { Dataset, Place, Task } from './types'

export type Imported = { dataset: Dataset; files: Map<string, Blob> }

const IMAGE = /\.(jpe?g|png|webp)$/i

/** Path inside the bundle: the common top folder (the session folder the user picked or zipped) removed. */
function stripTop(paths: string[]): (p: string) => string {
  const tops = new Set(paths.map((p) => (p.includes('/') ? p.split('/')[0] : '')))
  if (tops.size === 1 && !tops.has('')) {
    const top = [...tops][0] + '/'
    return (p) => (p.startsWith(top) ? p.slice(top.length) : p)
  }
  return (p) => p
}

/** Files from a folder picker (webkitRelativePath) or plain files; zips are opened. One dataset per bundle found. */
export async function importFiles(list: File[]): Promise<Imported[]> {
  const out: Imported[] = []
  const loose: { path: string; file: Blob }[] = []
  for (const f of list) {
    if (/\.zip$/i.test(f.name)) {
      const entries = unzipSync(new Uint8Array(await f.arrayBuffer()))
      const names = Object.keys(entries).filter((n) => !n.endsWith('/'))
      const strip = stripTop(names)
      const files = new Map<string, Blob>()
      for (const n of names) files.set(strip(n), new Blob([entries[n]], { type: IMAGE.test(n) ? 'image/jpeg' : 'application/octet-stream' }))
      out.push(...(await fromFiles(files, f.name)))
    } else {
      loose.push({ path: (f as any).webkitRelativePath || f.name, file: f })
    }
  }
  if (loose.length) {
    // a picked folder may hold several session folders (the whole poi-sweep-places folder): one bundle per result.json
    const results = loose.filter((x) => /(^|\/)result\.json$/.test(x.path) || /(^|\/)manifest\.json$/.test(x.path))
    for (const r of results) {
      const dir = r.path.replace(/(result|manifest)\.json$/, '')
      const files = new Map<string, Blob>()
      for (const x of loose) if (x.path.startsWith(dir)) files.set(x.path.slice(dir.length), x.file)
      out.push(...(await fromFiles(files, dir || r.path)))
    }
  }
  return out
}

async function text(b: Blob | undefined): Promise<string | null> {
  return b ? await b.text() : null
}

async function fromFiles(files: Map<string, Blob>, label: string): Promise<Imported[]> {
  const result = await text(files.get('result.json'))
  if (result) return [fromResult(JSON.parse(result), files)]
  const manifest = await text(files.get('manifest.json'))
  if (manifest) return [await fromRaw(JSON.parse(manifest), files)]
  throw new Error(`${label}: no result.json (agent output) or manifest.json (phone export) inside`)
}

/** Only image files are kept, always re-typed as images: a bundle cannot make the app serve HTML or script from
 *  its own origin (photos are shown and opened in a new tab through object URLs). */
function keepImages(files: Map<string, Blob>, wanted: Set<string>): Map<string, Blob> {
  const m = new Map<string, Blob>()
  for (const [p, b] of files) {
    if (!wanted.has(p) || !IMAGE.test(p)) continue
    const type = /\.png$/i.test(p) ? 'image/png' : /\.webp$/i.test(p) ? 'image/webp' : 'image/jpeg'
    m.set(p, new Blob([b], { type }))
  }
  return m
}

function fromResult(r: any, files: Map<string, Blob>): Imported {
  if (r.schema !== 'poi-result/1') throw new Error(`result.json: unknown schema ${r.schema}`)
  const places: Place[] = r.places
  const tasks: Task[] = r.tasks || []
  const wanted = new Set<string>([...places.flatMap((p) => p.photos.map((x) => x.file)), ...tasks.map((t) => t.photo || '').filter(Boolean)])
  const kept = keepImages(files, wanted)
  return {
    dataset: {
      id: r.session.id, source: 'result', importedAt: new Date().toISOString(), session: r.session,
      track: r.track || [], shots: r.shots || [], places, tasks, files: [...kept.keys()],
    },
    files: kept,
  }
}

/** A raw phone export: the ride, every photo, and (Place capture) the places with the contributor's pins. */
async function fromRaw(m: any, files: Map<string, Blob>): Promise<Imported> {
  const s = m.session
  const photos = (m.items || []).filter((i: any) => i.kind === 'photo')
  let track: [number, number][] = []
  const tj = await text(files.get(m.trackFile || 'track.geojson'))
  if (tj) {
    const g = JSON.parse(tj)
    for (const f of g.features || [g]) {
      const geom = f.geometry || f
      if (geom.type === 'LineString') track.push(...geom.coordinates.map((c: number[]) => [c[1], c[0]] as [number, number]))
      if (geom.type === 'Point') track.push([geom.coordinates[1], geom.coordinates[0]])
    }
  }
  if (!track.length) track = photos.filter((p: any) => p.lat != null).map((p: any) => [p.lat, p.lon])
  const shots = photos.map((p: any, i: number) => [i, p.lat, p.lon, p.cameraBearing ?? null] as [number, number, number, number | null])
    .filter((x: any) => x[1] != null)
  const shotFiles: Record<number, string> = {}
  photos.forEach((p: any, i: number) => (shotFiles[i] = p.file))
  const byId = new Map(photos.map((p: any, i: number) => [p.id, { p, i }]))
  const places: Place[] = (m.pois || []).map((poi: any, k: number) => {
    const ph = (poi.photos || []).map((id: string) => byId.get(id)).filter(Boolean) as { p: any; i: number }[]
    const read = poi.read || {}
    const fields: Place['fields'] = {}
    const name = poi.fields?.name || read.name
    if (name) fields.name = { value: name, sure: poi.fields?.name ? 'high' : 'low' }
    if (read.phones?.length) fields.phone = { value: read.phones.join(', '), sure: 'low' }
    if (read.houseNumber) fields.houseNumber = { value: String(read.houseNumber), sure: 'low' }
    return {
      id: `${s.id}-P${k + 1}`, name: name || `Place ${k + 1}`, category: poi.fields?.category || read.category || null,
      status: 'open', confidence: name ? 'low' : null, note: 'phone OCR only: not processed yet', fields,
      photos: ph.map(({ p, i }) => ({ file: p.file, index: i, cover: p.id === poi.cover })),
      pins: { contributor: { lat: poi.position.lat, lon: poi.position.lon, method: `contributor (${poi.position.source})`, uncertaintyM: poi.position.accuracyM } },
    } as Place
  })
  const kept = keepImages(files, new Set(photos.map((p: any) => p.file)))
  return {
    dataset: {
      id: `${s.id}-raw`, source: 'raw', importedAt: new Date().toISOString(),
      session: { id: s.id, name: s.name, kind: s.kind, holding: s.holding, startedAt: s.startedAt },
      track, shots, shotFiles, places, tasks: [], files: [...kept.keys()],
    },
    files: kept,
  }
}

export const _test = { stripTop, fromResult }
export { strFromU8 }
