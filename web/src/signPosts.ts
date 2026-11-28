// Signs on one post (docs/sweep-targets.md, "Signs on one post"): result.json sign.post is the id of the pole or
// gantry a sign shares with other signs of the same ride, sign.order its place on it (1 = top; on a gantry the
// leftmost board). A pole's signs share one position: the map draws them as one marker with the drawings stacked
// in order, the review panel lists the others, and the OSM export makes one node with all their codes. A gantry's
// boards (side "over") each hang over their own lane at their own point: they stay markers / nodes of their own.
import type { LatLon, Place } from './types'
import { layerOf } from './types'

/** The post id of a sign that shares a post, else null. */
export function postOf(p: Place): string | null {
  const id = layerOf(p) === 'sign' ? p.sign?.post : null
  return id == null || id === '' ? null : String(id)
}

/** A gantry board (over the road): never merged with the other boards into one marker / node. */
export const overRoad = (p: Place) => p.sign?.side === 'over'

/** Signs in their order on the post (order, then as listed). */
export function byOrder(list: Place[]): Place[] {
  return list.map((p, i) => [p, i] as const)
    .sort((a, b) => (a[0].sign?.order ?? Infinity) - (b[0].sign?.order ?? Infinity) || a[1] - b[1])
    .map(([p]) => p)
}

/** Every sign on the same post as p (p included), in order; [p] alone when it names no post. */
export function postSigns(places: Place[], p: Place): Place[] {
  const id = postOf(p)
  if (!id) return [p]
  return byOrder(places.filter((q) => postOf(q) === id))
}

const near = (a: LatLon, b: LatLon) => Math.abs(a.lat - b.lat) < 5e-6 && Math.abs(a.lon - b.lon) < 5e-6   // ~0.5 m

/** Map markers: the places to draw, each group one marker. The signs of one pole (not a gantry) whose final pins
 *  still coincide are one group, in order (a sign the reviewer moved away is drawn on its own); the rest alone. */
export function markerGroups(places: Place[], pinOf: (p: Place) => LatLon): Place[][] {
  const out: Place[][] = []
  const open = new Map<string, Place[][]>()
  for (const p of places) {
    const id = postOf(p)
    if (!id || overRoad(p)) { out.push([p]); continue }
    const groups = open.get(id) || []
    const g = groups.find((x) => near(pinOf(x[0]), pinOf(p)))
    if (g) g.push(p)
    else { const n = [p]; groups.push(n); out.push(n); open.set(id, groups) }
  }
  return out.map((g) => (g.length > 1 ? byOrder(g) : g))
}

/** One OSM traffic_sign value for the signs of one post, top first: unrelated signs are separated by ";", the
 *  country prefix repeated ("VN:P.104;VN:P.127"), https://wiki.openstreetmap.org/wiki/Key:traffic_sign. A sign's
 *  supplementary plate is not a sign of its own here (it is the "extra" / other field), so "," is not used. */
export function trafficSignValue(codes: string[]): string {
  return [...new Set(codes.map((c) => c.trim()).filter(Boolean))].map((c) => `VN:${c}`).join(';')
}
