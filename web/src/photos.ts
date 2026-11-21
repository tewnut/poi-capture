// A place's photos after the reviewer's edits (removed, added, cropped, cover): what the review panel shows and
// what the photo export writes.
import type { Place, Review } from './types'

export type Shot = {
  file: string            // the photo's own path (key for edits)
  show: string            // the path to display / export: the cropped copy when there is one
  index?: number | null
  why?: string | null
  source?: 'website'
  cover: boolean
  added?: boolean
  cropped?: boolean
}

/** Kept photos, cover first. */
export function keptPhotos(place: Place, review?: Review | null): Shot[] {
  const e = review?.photos || {}
  const rm = new Set(e.removed || [])
  const all = [
    ...place.photos.filter((p) => !rm.has(p.file)).map((p) => ({ file: p.file, index: p.index, why: p.why, source: p.source, c: !!p.cover })),
    ...(e.added || []).map((f) => ({ file: f, added: true, c: false })),
  ]
  const cover = e.cover && all.some((x) => x.file === e.cover) ? e.cover : all.find((x) => x.c)?.file ?? all[0]?.file
  return all
    .map(({ c: _c, ...x }) => ({ ...x, cover: x.file === cover, show: e.crops?.[x.file]?.file || x.file, cropped: !!e.crops?.[x.file] }))
    .sort((a, b) => Number(b.cover) - Number(a.cover))
}

/** The removed imported photos (to restore). */
export function removedPhotos(place: Place, review?: Review | null) {
  const rm = new Set(review?.photos?.removed || [])
  return place.photos.filter((p) => rm.has(p.file))
}

/** File names in the photo export: <place id>/<n>.<ext>, cover first. */
export function exportNames(place: Place, review?: Review | null) {
  return keptPhotos(place, review).map((p, k) => `${place.id}/${k + 1}.${(p.show.match(/\.(\w+)$/)?.[1] || 'jpg').toLowerCase()}`)
}
