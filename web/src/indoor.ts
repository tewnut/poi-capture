// Indoor walks (docs/indoor.md): one building, several levels. The map shows one level at a time (its places,
// its part of the walk, its photos); the review panel has level and unit fields; the OSM export writes level=* and
// addr:unit=* with the building's address (never a street from the phone).
import type { Building, Dataset, Place, Review, Shot } from './types'

export const isIndoor = (d: Dataset | null | undefined) => d?.session.kind === 'indoor'
/** A place of an indoor walk (the processing marks them; a level or unit says it too). */
export const isIndoorPlace = (p: Place) => !!p.indoor || p.level != null || p.unit != null

/** A level as the building names it, tidied the way the processing does (indoor.py norm_level): "Tầng 2" / "L2" ->
 *  "2", "Hầm 1" / "B1" -> "B1", "Trệt" / "GF" -> "G"; other names kept. */
export function normLevel(v: unknown): string | null {
  if (v == null) return null
  const raw = String(v).trim()
  if (!raw) return null
  const s = raw.toLowerCase().replace(/đ/g, 'd').normalize('NFD').replace(/[̀-ͯ]/g, '')
  if (/^(tret|gf|g|ground( floor)?)$/.test(s)) return 'G'
  let m = s.match(/^(?:ham|basement|b)\s*(\d+)$/)
  if (m) return `B${+m[1]}`
  m = s.match(/^(?:tang|lau|level|floor|l)?\s*(\d+)$/)
  if (m) return String(+m[1])
  return raw
}

/** Sort key: B2, B1, G, 1, 2, ...; names that are not levels last. */
export function levelOrder(a: string, b: string) {
  const k = (v: string): [number, number, string] => {
    const n = normLevel(v) || ''
    if (/^B\d+$/.test(n)) return [0, -+n.slice(1), '']
    if (n === 'G') return [1, 0, '']
    if (/^-?\d+$/.test(n)) return [1, +n, '']
    return [2, 0, n]
  }
  const x = k(a), y = k(b)
  return x[0] - y[0] || x[1] - y[1] || x[2].localeCompare(y[2])
}

/** The place's level: the reviewer's edit, else the level field, else the processing's. */
export function placeLevel(p: Place, r?: Review): string | null {
  const f = r?.fields.level
  if (f) return f.state === 'rejected' ? null : normLevel(f.value)
  return normLevel(p.fields.level?.value ?? p.level)
}

/** The levels of a walk, lowest first: the processing's list, the places' and the track's. */
export function levelsOf(d: Dataset, reviewOf?: (p: Place) => Review): string[] {
  const set = new Set<string>()
  for (const l of d.levels || []) { const n = normLevel(l); if (n) set.add(n) }
  for (const l of d.trackLevels || []) { const n = normLevel(l); if (n) set.add(n) }
  for (const p of d.places) { const n = placeLevel(p, reviewOf?.(p)); if (n) set.add(n) }
  return [...set].sort(levelOrder)
}

/** The level a walk opens on: the one with the most places (ties: the lowest); null outdoors or without levels. */
export function defaultLevel(d: Dataset, reviewOf?: (p: Place) => Review): string | null {
  if (!isIndoor(d)) return null
  const levels = levelsOf(d, reviewOf)
  if (!levels.length) return null
  const n = (l: string) => d.places.filter((p) => placeLevel(p, reviewOf?.(p)) === l).length
  return levels.reduce((best, l) => (n(l) > n(best) ? l : best), levels[0])
}

/** On the level shown: everything when no level is picked; a place without a level shows on every level. */
export function onLevel(p: Place, level: string | null | undefined, r?: Review) {
  if (!level) return true
  const l = placeLevel(p, r)
  return l == null || l === level
}

/** The walk's track on one level, as separate stretches (it leaves the level and comes back). */
export function trackOn(d: Dataset, level: string | null | undefined): [number, number][][] {
  if (!level || !d.trackLevels?.length) return d.track.length ? [d.track] : []
  const out: [number, number][][] = []
  let cur: [number, number][] = []
  d.track.forEach((pt, i) => {
    if (normLevel(d.trackLevels![i]) === level) cur.push(pt)
    else if (cur.length) { out.push(cur); cur = [] }
  })
  if (cur.length) out.push(cur)
  return out
}

/** The photo positions on one level (a photo without a level shows on every level). */
export function shotsOn(d: Dataset, level: string | null | undefined): Shot[] {
  if (!level) return d.shots
  return d.shots.filter((s) => s.length < 5 || s[4] == null || normLevel(s[4]) === level)
}

/** The building's rings for the map: outer rings (or the footprint) and holes, [lat, lon]. */
export function buildingRings(b: Building | null | undefined): { outer: [number, number][][]; holes: [number, number][][] } {
  const ok = (r: unknown): r is [number, number][] => Array.isArray(r) && r.length >= 3
  const outer = (b?.outer || []).filter(ok)
  if (!outer.length && ok(b?.footprint)) outer.push(b!.footprint!)
  return { outer, holes: (b?.holes || []).filter(ok) }
}

/** The building's address from its OSM tags: housenumber, street, ... (with or without the "addr:" prefix). */
export function buildingAddr(b: Building | null | undefined): Record<string, string> {
  const out: Record<string, string> = {}
  for (const [k, v] of Object.entries(b?.addr || {})) if (v) out[k.replace(/^addr:/, '')] = String(v)
  return out
}

/** All level names this walk uses: its levels list and every place's level. */
export function levelNames(d: Dataset): string[] {
  const s = new Set<string>()
  for (const l of d.levels || []) { const n = normLevel(l); if (n) s.add(n) }
  for (const p of d.places) { const n = normLevel(p.level); if (n) s.add(n) }
  return [...s]
}

/**
 * The OSM `level` number of a level name (Simple Indoor Tagging: 0 = ground, -1 = first basement). Vietnamese
 * buildings mostly call the ground floor "Tầng 1" / "L1" (the app's level picker does too); some name it "G" /
 * "Trệt" and then "1" is the floor above. So: B<n> -> -n; G -> 0; a number n -> n when the building also has a G
 * level, else n - 1. null when the name is not a level ("Mezzanine": only level:ref is written).
 */
export function osmLevel(name: unknown, names: string[] = []): number | null {
  const n = normLevel(name)
  if (!n) return null
  if (/^B\d+$/.test(n)) return -Number(n.slice(1))
  if (n === 'G') return 0
  if (/^\d+$/.test(n)) return names.map((x) => normLevel(x)).includes('G') ? Number(n) : Number(n) - 1
  return null
}
