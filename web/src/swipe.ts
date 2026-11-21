// Phone helpers for the open place / task panel: a horizontal swipe steps to the previous or next item, and the
// prev / next buttons step through the list the reviewer sees. Pure, so they are unit tested (swipe.test.ts).

export type Step = -1 | 1

/** Minimum horizontal travel (px) for a swipe, and how much more horizontal than vertical it must be. */
export const SWIPE_MIN_PX = 60
export const SWIPE_RATIO = 1.5
/** A slow drag is reading or scrolling, not a swipe. */
export const SWIPE_MAX_MS = 800

/** A finished touch -> -1 (swiped right: previous), 1 (swiped left: next) or null (not a swipe). */
export function swipeStep(dx: number, dy: number, ms: number): Step | null {
  if (!Number.isFinite(dx) || !Number.isFinite(dy)) return null
  if (ms > SWIPE_MAX_MS) return null
  if (Math.abs(dx) < SWIPE_MIN_PX) return null
  if (Math.abs(dx) < SWIPE_RATIO * Math.abs(dy)) return null
  return dx < 0 ? 1 : -1
}

/** The item `step` away from `current` in `ids` (no wrap); null at either end or when `current` is not listed. */
export function neighbour<T>(ids: readonly T[], current: T, step: Step): T | null {
  const i = ids.indexOf(current)
  if (i < 0) return null
  return ids[i + step] ?? null
}

/** Where a swipe must not start: fields, the crop editor, the photo viewer, the map, and anything marked. */
export const NO_SWIPE = 'input, textarea, select, [contenteditable], .crop, .viewer, .leaflet-container, [data-noswipe]'

/** The bit of an element this needs (a DOM Element fits). */
export interface SwipeEl {
  matches(sel: string): boolean
  parentElement: SwipeEl | null
  scrollWidth: number
  clientWidth: number
}

/** True when a touch starting at `el` (inside `root`) should not be read as a swipe: it is on a NO_SWIPE element
 *  or inside something that scrolls sideways (a photo strip), where the finger is scrolling that instead. */
export function blocksSwipe(el: SwipeEl | null, root: SwipeEl, overflowX: (el: SwipeEl) => string): boolean {
  for (let e = el; e && e !== root; e = e.parentElement) {
    if (e.matches(NO_SWIPE)) return true
    const o = overflowX(e)
    if ((o === 'auto' || o === 'scroll') && e.scrollWidth > e.clientWidth + 1) return true
  }
  return false
}
