// Small hints on a place for the reviewer, the same in the list (App.vue) and the review panel (PlaceReview.vue):
// how sure the reading is (low / medium), a house number partly read, where it was read (plate, shop sign, painted),
// its other (old) number, a number out of order along the ride (addresses.ts), the same place on another ride shown
// (dupes.ts). Hints only: none of them changes the review.
import type { Place, Review } from './types'
import { layerOf } from './types'
import { addressExtras, isPartial, type SeqFlag } from './addresses'
import type { Dupe } from './dupes'
import { t } from './i18n'

export type Hint = { key: string; text: string; cls: 'h-warn' | 'h-info' | 'h-src'; title?: string }

/** The out-of-order words: "out of order: between 188 and 226 (or 32); its other number 34 fits". */
export function seqText(f: SeqFlag): string {
  let s = f.prev && f.next ? t('seqBetween', { a: f.prev, b: f.next }) : f.prev ? t('seqAfter', { a: f.prev }) : t('seqBefore', { b: f.next ?? '' })
  if (f.or?.length) s += ' ' + t('seqOr', { n: f.or.join(', ') })
  if (f.altFits) s += '; ' + t('seqAltFits', { n: f.altFits })
  return s
}

export function placeHints(p: Place, r: Review, ctx: { seq?: SeqFlag; dupes?: Dupe[]; list?: boolean } = {}): Hint[] {
  const out: Hint[] = []
  const layer = layerOf(p)
  const conf = String(p.confidence || '').toLowerCase()
  if (layer === 'address') {
    const { source, old, others, partial } = addressExtras(p)
    const num = r.fields.houseNumber?.value ?? p.fields.houseNumber?.value ?? ''
    const edited = r.fields.houseNumber?.state === 'edited'
    if (source) out.push({ key: 'src', text: t('numSrc_' + source), cls: 'h-src', title: t('numSrcTitle') })
    if (isPartial(num) || (partial && !edited)) out.push({ key: 'partial', text: t('numPartly'), cls: 'h-warn', title: t('numPartlyTitle') })
    else if (conf === 'low' || conf === 'medium') out.push({ key: 'conf', text: t('conf_' + conf), cls: conf === 'low' ? 'h-warn' : 'h-info' })
    if (old) out.push({ key: 'old', text: t('numOld', { n: old }), cls: 'h-src', title: t('numOldTitle') })
    if (others.length) out.push({ key: 'others', text: t('numOthers', { n: others.join(', ') }), cls: 'h-src', title: t('numOthersTitle') })
    if (ctx.seq) out.push({ key: 'seq', text: seqText(ctx.seq), cls: 'h-warn', title: t('seqTitle') })
  } else if (conf === 'low' || conf === 'medium') {
    out.push({ key: 'conf', text: layer === 'place' && conf === 'low' ? t('namePartly') : t('conf_' + conf), cls: conf === 'low' ? 'h-warn' : 'h-info' })
  }
  if (ctx.list && ctx.dupes?.length) {
    const rides = [...new Set(ctx.dupes.map((d) => d.ds.session.name || d.ds.id))]
    out.push({ key: 'dupe', text: t('alsoOnRide', { n: rides.length }), cls: 'h-info', title: rides.join(', ') })
  }
  return out
}
