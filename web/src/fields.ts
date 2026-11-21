// What Confirm does with the fields the reviewer has not decided one by one, in one place (PlaceReview uses it,
// fields.test.ts pins it down):
//   accepted on Confirm: a value read clearly on the photos (sure high / medium), and a value from Google or the
//     place's website (sure medium; marked, so Google's stay out of the .osm file and are listed in fromGoogle);
//   a suggestion, never accepted by Confirm: an unsure reading (sure low: a photo reading, Google's one-day hours)
//     and the road the photos were taken on (source "ride": a corner shop or a place inside a building may be on
//     another street). A suggestion is shown as such and published only once the reviewer accepts (✓) or edits it.
import type { Field, FieldKey, Place, Review } from './types'
import { FIELD_KEYS } from './types'

export type ConfirmRule = 'accept' | 'suggest'

export function confirmRule(f: Field): ConfirmRule {
  if (f.sure === 'low') return 'suggest'
  if (f.source === 'ride') return 'suggest'
  return 'accept'
}

/** Why a field is only a suggestion: 'low' (unsure reading) or 'ride' (the road the photos were taken on). */
export function suggestionKind(f: Field | undefined): 'low' | 'ride' | null {
  if (!f || !f.value || confirmRule(f) === 'accept') return null
  return f.sure === 'low' ? 'low' : 'ride'
}

/** The review's fields after Confirm: the reviewer's own decisions kept, the accepted fields added as checked. */
export function confirmFields(place: Place, review: Review): Review['fields'] {
  const out: Review['fields'] = { ...review.fields }
  for (const k of FIELD_KEYS) {
    const f = place.fields[k]
    if (f && f.value && !out[k] && confirmRule(f) === 'accept') out[k] = { state: 'ok', value: f.value }
  }
  return out
}

/** Suggestions the reviewer has not decided yet: not published until accepted. */
export function pendingSuggestions(place: Place, review: Review): FieldKey[] {
  return FIELD_KEYS.filter((k) => !review.fields[k] && suggestionKind(place.fields[k]) != null)
}

/** No street at all: nothing read, suggested or typed, or the reviewer rejected it without typing another. */
export function missingStreet(place: Place, review: Review): boolean {
  const r = review.fields.street
  if (r) return r.state === 'rejected' || !r.value
  return !place.fields.street?.value
}

/** A Google result that is this place, as the processing decides (result.py google_mergeable): name match >= 0.5
 *  and within 100 m. Only such a result is merged into the fields and counts as "on Google Maps" (store.gstat). */
export function googleMergeable(g: Record<string, any> | null | undefined): boolean {
  return !!g && !!g.name && (g.nameMatch ?? 0) >= 0.5 && typeof g.distanceM === 'number' && g.distanceM <= 100
}
