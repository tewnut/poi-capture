// Publish: the confirmed places as GeoJSON, CSV and an OpenStreetMap (.osm) file; the task list as GeoJSON.
// Only what the photos showed or the reviewer confirmed is written: never the Google block or Google's pin
// (Google's terms; docs/pipeline.md).
import type { Dataset, Place, Review, FieldKey, RemovalReview, Task } from './types'
import { FIELD_KEYS } from './types'
import { exportNames } from './photos'

export type Published = {
  dataset: Dataset
  place: Place
  review: Review
}

/** The reviewed value of a field: the edit, the read value if confirmed, nothing if rejected or never shown. */
export function value(p: Place, r: Review, k: FieldKey): string | null {
  const f = r.fields[k]
  if (f?.state === 'rejected') return null
  if (f) return f.value || null
  return null
}

/** A field counts only once confirmed or edited; the name falls back to the place name the reviewer kept. */
export function record(x: Published) {
  const { dataset, place, review } = x
  const pin = review.pin || (place.pins.agent || place.pins.algorithm || place.pins.contributor)!
  const v: Record<string, string | null> = {}
  for (const k of FIELD_KEYS) v[k] = value(place, review, k)
  // fields whose value is still Google's (filled from Google and not edited by the reviewer)
  const fromGoogle = FIELD_KEYS.filter((k) => v[k] && place.fields[k]?.source === 'google' && review.fields[k]?.state !== 'edited')
  return {
    id: place.id,
    name: v.name || place.name,
    category: review.category || place.category || null,
    status: place.status,
    phone: v.phone, houseNumber: v.houseNumber, street: v.street, openingHours: v.openingHours, website: v.website,
    lat: +pin.lat.toFixed(7), lon: +pin.lon.toFixed(7),
    pinSource: review.pin?.source || (place.pins.agent ? 'agent' : place.pins.algorithm ? 'algorithm' : 'contributor'),
    session: dataset.session.id, sessionName: dataset.session.name || '', captured: dataset.session.startedAt || '',
    reviewer: review.reviewer || '', reviewedAt: review.at || '', comment: review.comment || '',
    fromGoogle: fromGoogle.join(' '),
    photos: exportNames(place, review).join(' '),   // files in the photo export (cover first)
  }
}

export type Removed = { dataset: Dataset; index: number; review: RemovalReview }

export function geojson(places: Published[], removed: Removed[]) {
  const feats = places.map((x) => {
    const r = record(x)
    return { type: 'Feature', geometry: { type: 'Point', coordinates: [r.lon, r.lat] }, properties: { ...r, change: 'add_or_update' } }
  })
  for (const { dataset, index, review } of removed) {
    const t = dataset.tasks[index]
    feats.push({
      type: 'Feature', geometry: { type: 'Point', coordinates: [t.lon, t.lat] },
      properties: { id: `${dataset.session.id}-removal-${index}`, name: t.name, change: 'remove', why: t.why, session: dataset.session.id,
        reviewer: review.reviewer || '', reviewedAt: review.at || '' } as any,
    })
  }
  return JSON.stringify({ type: 'FeatureCollection', features: feats }, null, 1)
}

const CSV_COLS = ['id', 'change', 'name', 'category', 'status', 'phone', 'houseNumber', 'street', 'openingHours', 'website',
  'fromGoogle', 'lat', 'lon', 'pinSource', 'session', 'sessionName', 'captured', 'reviewer', 'reviewedAt', 'comment', 'photos'] as const

/** A CSV cell. Text starting with = + - @ (or a tab / return) is prefixed with ' so spreadsheets do not run it
 *  as a formula (names and fields come from photos and imports). Numbers are left as numbers. */
export function cell(v: unknown) {
  let s = v == null ? '' : String(v)
  if (typeof v === 'string' && /^[=+\-@\t\r]/.test(s)) s = `'${s}`
  return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
}

export function csv(places: Published[], removed: Removed[]) {
  const rows = [CSV_COLS.join(',')]
  for (const x of places) {
    const r: any = { ...record(x), change: 'add_or_update' }
    rows.push(CSV_COLS.map((c) => cell(r[c])).join(','))
  }
  for (const { dataset, index, review } of removed) {
    const t = dataset.tasks[index]
    const r: any = { id: `${dataset.session.id}-removal-${index}`, change: 'remove', name: t.name, lat: t.lat, lon: t.lon,
      session: dataset.session.id, comment: t.why, reviewer: review.reviewer, reviewedAt: review.at }
    rows.push(CSV_COLS.map((c) => cell(r[c])).join(','))
  }
  return '﻿' + rows.join('\r\n') + '\r\n'   // BOM: Excel reads it as UTF-8 (Vietnamese)
}

/** Our categories -> OpenStreetMap tags (the main ones; unknown categories are left for the mapper). */
export const OSM_TAGS: Record<string, Record<string, string>> = {
  cafe: { amenity: 'cafe' }, restaurant: { amenity: 'restaurant' }, fast_food_kiosk: { amenity: 'fast_food' },
  bakery: { shop: 'bakery' }, juice_bar: { amenity: 'cafe', cuisine: 'juice' }, juice_kiosk: { shop: 'kiosk', cuisine: 'juice' },
  pharmacy: { amenity: 'pharmacy', healthcare: 'pharmacy' }, bank: { amenity: 'bank' }, beauty: { shop: 'beauty' },
  massage: { shop: 'massage' }, physiotherapy: { healthcare: 'physiotherapist' }, fitness: { leisure: 'fitness_centre' },
  sports: { leisure: 'sports_centre' }, office: { office: 'company' }, education: { amenity: 'training' },
  car_care: { shop: 'car_repair' }, grocery: { shop: 'greengrocer' }, furniture: { shop: 'furniture' },
  laundry: { shop: 'laundry' }, interior_design: { shop: 'interior_decoration' }, hardware: { shop: 'hardware' },
  drinks: { amenity: 'cafe' }, phone_shop: { shop: 'mobile_phone' }, convenience: { shop: 'convenience' },
}

function esc(s: string) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

/** OpenStreetMap opening_hours from "07:00–22:00" (every day assumed only when the reviewer kept it as is). */
export function osmHours(v: string | null): string | null {
  if (!v) return null
  const m = v.replace(/[–—]/g, '-').match(/^\s*(\d{1,2})[:h](\d{2})\s*-\s*(\d{1,2})[:h](\d{2})\s*$/)
  if (!m) return v.includes(';') || /^(Mo|Tu|We|Th|Fr|Sa|Su)/.test(v) ? v : null
  const t = (h: string, mm: string) => `${h.padStart(2, '0')}:${mm}`
  return `Mo-Su ${t(m[1], m[2])}-${t(m[3], m[4])}`
}

/** New nodes (negative ids) for JOSM: open it, check against what is already mapped, then upload by hand. */
export function osm(places: Published[]) {
  const lines = ['<?xml version="1.0" encoding="UTF-8"?>',
    '<osm version="0.6" generator="poi-capture-reviewer" upload="never">',
    '  <!-- New places confirmed in POI Capture Reviewer. Check against existing OSM data in JOSM before uploading;',
    '       follow the OSM import / organised editing guidelines. Removals are in the GeoJSON / CSV export. -->']
  places.forEach((x, i) => {
    const r: Record<string, any> = { ...record(x) }
    // OpenStreetMap does not accept data copied from Google: those fields stay out of the .osm file
    for (const k of (r.fromGoogle as string).split(' ').filter(Boolean)) r[k] = null
    const tags: Record<string, string> = { name: r.name, ...(OSM_TAGS[r.category || ''] || {}) }
    if (r.phone) tags.phone = r.phone.split(/[,;]\s*/).map((p: string) => p.replace(/^0/, '+84 ').replace(/\s+/g, ' ')).join(';')
    if (r.houseNumber) tags['addr:housenumber'] = r.houseNumber
    if (r.street) tags['addr:street'] = r.street.split(',')[0].trim()
    const oh = osmHours(r.openingHours)
    if (oh) tags.opening_hours = oh
    if (r.website && /\./.test(r.website) && !/\s/.test(r.website)) tags.website = r.website.startsWith('http') ? r.website : `https://${r.website}`
    if (x.place.status !== 'open') tags.disused = 'yes'
    tags.check_date = (r.reviewedAt || r.captured || '').slice(0, 10)
    lines.push(`  <node id="-${i + 1}" lat="${r.lat}" lon="${r.lon}" version="0">`)
    for (const [k, v] of Object.entries(tags)) if (v) lines.push(`    <tag k="${esc(k)}" v="${esc(v)}"/>`)
    lines.push('  </node>')
  })
  lines.push('</osm>')
  return lines.join('\n') + '\n'
}

/** The work left to do on site, as GeoJSON the phone app imports (schema poi-tasks/1, docs/pipeline.md):
 *  the agent's visits, removal checks not yet settled here (removals the reviewer confirmed or dismissed are left
 *  out; one sent to "check on site" goes in), and places the reviewer marked "visit". */
export function tasksGeojson(ds: Dataset[], reviews: Record<string, Review> = {}, removals: Record<string, RemovalReview> = {},
  createdBy = '') {
  const feats: any[] = []
  const add = (id: string, type: Task['type'], name: string, lat: number, lon: number, why: string, place: string | null, session: string) =>
    feats.push({ type: 'Feature', geometry: { type: 'Point', coordinates: [lon, lat] }, properties: { id, type, name, why, place, session } })
  for (const d of ds) {
    d.tasks.forEach((t, i) => {
      const r = removals[`${d.id}|${i}`]?.decision || 'todo'
      if (t.type !== 'visit' && (r === 'confirmed' || r === 'dismissed')) return
      if (t.type === 'removal' && r !== 'verify') return     // the reviewer has not sent it on site
      add(`${d.session.id}-task-${i}`, t.type === 'removal' ? 'verify_removal' : t.type, t.name, t.lat, t.lon, t.why, t.place, d.session.id)
    })
    for (const p of d.places) {
      const r = reviews[`${d.id}|${p.id}`]
      if (r?.decision !== 'visit' || feats.some((f) => f.properties.session === d.session.id && f.properties.place === p.id)) continue
      const pin = r.pin || p.pins.agent || p.pins.contributor || p.pins.algorithm
      if (!pin) continue
      add(`${d.session.id}-visit-${p.id}`, 'visit', r.fields.name?.value || p.name, pin.lat, pin.lon, r.comment || 'Reviewer: check on site', p.id, d.session.id)
    }
  }
  return JSON.stringify({ type: 'FeatureCollection', schema: 'poi-tasks/1', createdAt: new Date().toISOString(), createdBy, features: feats }, null, 1)
}

export function download(name: string, body: BlobPart, type: string) {
  const a = document.createElement('a')
  a.href = URL.createObjectURL(new Blob([body], { type }))
  a.download = name
  a.click()
  setTimeout(() => URL.revokeObjectURL(a.href), 2000)
}
