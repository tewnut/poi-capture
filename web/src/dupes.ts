// The same place on two rides: rides that overlap (they meet at a junction, or ride the same stretch) each find the
// shop (Gà Rán Street Food at 553 Nguyễn Thị Định on both Đường số 18 and Lê Văn Thịnh). With several sessions
// shown, a place with a pin close to one on another ride (<= 40 m) and a like name or the same phone, or an address
// with the same house number, is listed as "also on ride X", so the reviewer confirms one and rejects the other as a
// duplicate. Pure: store.ts feeds it the shown sessions with the reviewer's edits.
import type { Dataset, LatLon, Place, Review } from './types'
import { parseHouseNumber } from './addresses'

export const DUPE_RADIUS_M = 40

export type Dupe = {
  ds: Dataset
  place: Place
  distM: number
  /** why it is taken for the same place */
  why: 'name' | 'phone' | 'name+phone' | 'number'
}

export function distM(a: LatLon, b: LatLon): number {
  const x = (a.lon - b.lon) * 111320 * Math.cos((a.lat * Math.PI) / 180), y = (a.lat - b.lat) * 110540
  return Math.hypot(x, y)
}

/** Name words without case, accents or punctuation. */
export function nameWords(s: string | null | undefined): string[] {
  return String(s ?? '').normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/đ/gi, 'd').toLowerCase()
    .split(/[^a-z0-9]+/).filter(Boolean)
}

/** Like names: most words of the shorter one are in the other ("Mắt Kính" ~ "Mắt Kính 553"; "Cà Phê An" ≁ "Cà Phê Bình"). */
export function likeNames(a: string | null | undefined, b: string | null | undefined): boolean {
  const A = new Set(nameWords(a)), B = new Set(nameWords(b))
  if (!A.size || !B.size) return false
  let common = 0
  for (const w of A) if (B.has(w)) common++
  return common / Math.min(A.size, B.size) >= 0.75
}

/** Phone numbers in a field as their last 9 digits (+84 / 0 prefixes alike); shorter runs are left out. */
export function phones(s: string | null | undefined): string[] {
  return String(s ?? '').split(/[,;/]|\s{2,}/).map((x) => x.replace(/\D/g, '')).filter((d) => d.length >= 9).map((d) => d.slice(-9))
}

/** The pin the reviewer would publish: the reviewer's, else the agent's, else the algorithm's / contributor's. */
export function pinOf(p: Place, r: Review): LatLon | null {
  return r.pin || p.pins.agent || p.pins.algorithm || p.pins.contributor || null
}

const val = (p: Place, r: Review, k: 'name' | 'phone' | 'houseNumber' | 'street') =>
  r.fields[k]?.state === 'rejected' ? '' : (r.fields[k]?.value ?? p.fields[k]?.value ?? '')

/** Why [a] and [b] look like the same place (null: they do not); both of one layer, places or addresses. */
export function sameWhy(a: Place, ra: Review, b: Place, rb: Review): Dupe['why'] | null {
  const la = a.layer || 'place', lb = b.layer || 'place'
  if (la !== lb) return null
  if (la === 'address') {
    const na = val(a, ra, 'houseNumber').trim().toUpperCase().replace(/\s+/g, ''), nb = val(b, rb, 'houseNumber').trim().toUpperCase().replace(/\s+/g, '')
    if (!na || na !== nb || parseHouseNumber(na)?.partial) return null
    return 'number'
  }
  if (la !== 'place') return null   // a sign: the same code at one spot can face the other way
  const name = likeNames(val(a, ra, 'name') || a.name, val(b, rb, 'name') || b.name)
  const pa = new Set(phones(val(a, ra, 'phone'))), phone = phones(val(b, rb, 'phone')).some((x) => pa.has(x))
  return name && phone ? 'name+phone' : name ? 'name' : phone ? 'phone' : null
}

/** For each place of [open], the places of the [others] sessions it looks like (closest first). Other sessions of
 *  the same ride (its raw copy) and raw phone exports are left out. */
export function crossRideDupes(open: Dataset, others: Dataset[], review: (ds: Dataset, p: Place) => Review,
  radiusM = DUPE_RADIUS_M): Map<string, Dupe[]> {
  const out = new Map<string, Dupe[]>()
  const cands = others.filter((d) => d.id !== open.id && d.source === 'result' && d.session.id !== open.session.id)
    .flatMap((ds) => ds.places.map((place) => ({ ds, place, r: review(ds, place) })))
    .map((x) => ({ ...x, pin: pinOf(x.place, x.r) })).filter((x) => x.pin)
  if (!cands.length) return out
  const dLat = radiusM / 110540
  for (const p of open.places) {
    const r = review(open, p), pin = pinOf(p, r)
    if (!pin) continue
    const dLon = radiusM / (111320 * Math.max(0.01, Math.cos((pin.lat * Math.PI) / 180)))
    const found: Dupe[] = []
    for (const c of cands) {
      if (Math.abs(c.pin!.lat - pin.lat) > dLat || Math.abs(c.pin!.lon - pin.lon) > dLon) continue
      const d = distM(pin, c.pin!)
      if (d > radiusM) continue
      const why = sameWhy(p, r, c.place, c.r)
      if (why) found.push({ ds: c.ds, place: c.place, distM: Math.round(d), why })
    }
    if (found.length) out.set(p.id, found.sort((x, y) => x.distM - y.distM))
  }
  return out
}

/** The comment a "duplicate" rejection leaves (the review has no reason field; the comment travels with it). */
export function duplicateComment(other: Dupe, existing?: string): string {
  const line = `Duplicate of "${other.place.name}" on ride ${other.ds.session.name || other.ds.session.id} (${other.ds.id} / ${other.place.id}).`
  const rest = (existing || '').split('\n').filter((l) => l.trim() && !l.startsWith('Duplicate of ')).join('\n')
  return rest ? `${line}\n${rest}` : line
}
