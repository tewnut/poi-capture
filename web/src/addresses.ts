// House numbers of a ride's address layer (layer "address", docs/sweep-targets.md): reading a number ("12", "12A",
// "12/3", "38/1", "170-172", "K12", a partly read "58…"), where it came from (plate / shop sign / painted), and a
// hint for the reviewer when a number breaks the order along the ride (34 among 38 … 22, or Đồng Văn Cống's old and
// new numbers mixed: 170, 188, 226, then 92, then 1400). A hint, never an error: the same number twice (558 and
// 558B, two shops at 638) is in order. Pure: store.ts feeds it the open session with the reviewer's edits.
import type { Place, Review } from './types'

/** Where a house number was read: a number plate, a shop sign's address line, painted on a wall or pillar, other. */
export type NumberSource = 'plate' | 'sign' | 'painted' | 'other'
const SOURCES: NumberSource[] = ['plate', 'sign', 'painted', 'other']

export type HouseNumber = {
  /** the first (main) number: 12 for "12A", "12/3", "K12"; 170 for "170-172" */
  lo: number
  /** the last number of a range ("170-172" → 172), else lo */
  hi: number
  /** some digits could not be read ("58…", "…"): not placed in the order */
  partial: boolean
}

const PARTIAL = /…|\.\.\.|\?/

/** The number in a house-number text, or null when there is none to compare. "Số 12", "12A", "12/3", "K12" → 12;
 *  "170-172" → 170..172; "58…" → partial (no number to compare). */
export function parseHouseNumber(s: string | null | undefined): HouseNumber | null {
  const v = String(s ?? '').trim()
  if (!v) return null
  if (PARTIAL.test(v)) return { lo: NaN, hi: NaN, partial: true }
  const m = v.replace(/^(số|so|no\.?)\s*/i, '').match(/^[a-zA-Z]{0,2}\s*(\d+)\s*[a-zA-Z]{0,2}(?:\s*[-–]\s*(\d+))?/)
  if (!m) return null
  const lo = Number(m[1])
  const hi = m[2] != null && Number(m[2]) >= lo ? Number(m[2]) : lo
  return { lo, hi, partial: false }
}

/** A number partly read on the photos ("58…", "1…", "…"). */
export const isPartial = (s: string | null | undefined) => PARTIAL.test(String(s ?? ''))

const str = (v: unknown) => (typeof v === 'string' || typeof v === 'number') && String(v).trim() ? String(v).trim() : null

export type AddressExtras = {
  source: NumberSource | null
  /** the old number on the same plate / gate ("số cũ 53C", an old-style plate next to the new one) */
  old: string | null
  /** other numbers on the same gate (34 and 18 on one gate) */
  others: string[]
  /** said to be read only in part (else: the number has "…") */
  partial: boolean
}

/** What the processing may add to an address entry, all optional (an older result has none of it): result.json
 *  `numbering: {source, oldNumber, altNumbers, partial}`; also taken from the entry's top level (source, old / alt)
 *  or the houseNumber field. Anything else is ignored. */
export function addressExtras(p: Place): AddressExtras {
  const a = p as Place & Record<string, any>
  const nb = (a.numbering && typeof a.numbering === 'object' ? a.numbering : {}) as Record<string, any>
  const hn = (p.fields.houseNumber || {}) as Record<string, any>
  const src = [nb.source, a.source, a.numberSource, hn.source].find((x) => SOURCES.includes(x)) ?? null
  const old = str(nb.oldNumber) ?? str(a.oldNumber) ?? str(a.old) ?? str(hn.old) ?? null
  const list = (v: unknown) => (Array.isArray(v) ? v : v == null ? [] : [v]).map(str).filter((x): x is string => !!x)
  const others = [...new Set([...list(nb.altNumbers), ...list(a.altNumbers), ...list(a.alt), ...list(hn.alt)])].filter((x) => x !== old)
  return { source: src as NumberSource | null, old, others, partial: nb.partial === true }
}

/** One address as the order check sees it. */
export type SeqEntry = {
  id: string
  /** the house number as it stands now (the reviewer's edit, else the reading) */
  number: string
  /** other numbers on the same door (the old one, others on the gate) */
  alts?: string[]
  /** the street it is on ('' = not known); numbers on different streets are not compared */
  street: string
  /** where it is along the ride (photo index); ties = the same spot, any order */
  along: number
}

export type SeqFlag = {
  /** the numbers it should sit between in the ride order; null at the start / end of the run */
  prev: string | null
  next: string | null
  /** the numbers in the same stretch that could be the odd one out instead (34 next to 32 in a falling run) */
  or?: string[]
  /** its other number that would fit there */
  altFits?: string
}

/** Street names compared without case, accents or extra spaces ("Đường số 16" = "đường Số 16"). */
export function streetKey(s: string | null | undefined): string {
  return String(s ?? '').normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/đ/gi, 'd').toLowerCase().replace(/\s+/g, ' ').trim()
}

type Item = { e: SeqEntry; n: HouseNumber; k: number }

/** a can come before b (non-strictly) in a rising / falling run; a range counts as any of its numbers */
const fits = (a: HouseNumber, b: HouseNumber, up: boolean) => (up ? a.lo <= b.hi : a.hi >= b.lo)

/** For each element: is it in every longest run, in some, or in none (O(n²); a street side holds tens of numbers). */
function runs(xs: Item[], up: boolean): { len: number; on: boolean[]; inAll: boolean[] } {
  const n = xs.length
  const f = new Array(n).fill(1), g = new Array(n).fill(1)   // longest run ending at i / starting at i
  for (let i = 0; i < n; i++) for (let j = 0; j < i; j++) if (fits(xs[j].n, xs[i].n, up)) f[i] = Math.max(f[i], f[j] + 1)
  for (let i = n - 1; i >= 0; i--) for (let j = i + 1; j < n; j++) if (fits(xs[i].n, xs[j].n, up)) g[i] = Math.max(g[i], g[j] + 1)
  const len = Math.max(0, ...f)
  const on = xs.map((_x, i) => f[i] + g[i] - 1 === len)
  // in every longest run: on one, and the only element on one at its step
  const atStep = new Map<number, number>()
  xs.forEach((_x, i) => { if (on[i]) atStep.set(f[i], (atStep.get(f[i]) || 0) + 1) })
  return { len, on, inAll: xs.map((_x, i) => on[i] && atStep.get(f[i]) === 1) }
}

/** The addresses that break the order of their street side (odd / even), with the numbers around them.
 *  The direction is the one that keeps the most numbers in order; a number in no longest run, or in only some of
 *  them (34 next to 32 in a falling run: one of the two is out), is flagged between the nearest sure numbers. */
export function sequenceFlags(entries: SeqEntry[]): Map<string, SeqFlag> {
  const out = new Map<string, SeqFlag>()
  const groups = new Map<string, Item[]>()
  entries.forEach((e, k) => {
    const n = parseHouseNumber(e.number)
    if (!n || n.partial) return
    const key = `${streetKey(e.street)}|${n.lo % 2}`
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key)!.push({ e, n, k })
  })
  for (const items of groups.values()) {
    if (items.length < 3) continue
    // the ride order; numbers seen at the same spot (stacked plates) may be in either order: sorted the run's way
    const sorted = (up: boolean) => [...items].sort((a, b) => a.e.along - b.e.along || (up ? a.n.lo - b.n.lo : b.n.lo - a.n.lo) || a.k - b.k)
    const upXs = sorted(true), downXs = sorted(false)
    const ru = runs(upXs, true), rd = runs(downXs, false)
    let up: boolean
    if (ru.len !== rd.len) up = ru.len > rd.len
    else {
      // as long either way: the way most steps go, else no hint at all
      let s = 0
      for (let i = 1; i < upXs.length; i++) s += Math.sign(upXs[i].n.lo - upXs[i - 1].n.lo)
      if (!s) continue
      up = s > 0
    }
    const xs = up ? upXs : downXs, { on, inAll } = up ? ru : rd
    if (inAll.filter(Boolean).length < 2) continue   // no order to speak of
    xs.forEach((x, i) => {
      if (inAll[i]) return
      let p = i - 1, q = i + 1
      while (p >= 0 && !inAll[p]) p--
      while (q < xs.length && !inAll[q]) q++
      const prev = p >= 0 ? xs[p] : null, next = q < xs.length ? xs[q] : null
      const flag: SeqFlag = { prev: prev?.e.number ?? null, next: next?.e.number ?? null }
      if (on[i]) {
        const or = xs.slice(p + 1, q).filter((y, j) => y !== x && on[p + 1 + j]).map((y) => y.e.number)
        if (or.length) flag.or = or
      }
      const alt = (x.e.alts || []).find((s) => {
        const n = parseHouseNumber(s)
        return n && !n.partial && (!prev || fits(prev.n, n, up)) && (!next || fits(n, next.n, up))
      })
      if (alt) flag.altFits = alt
      out.set(x.e.id, flag)
    })
  }
  return out
}

/** Where a place is along the ride: its cover photo's index (else its first photo's, else its first sighting's). */
export function alongRide(p: Place, fallback: number): number {
  const idx = (p.photos || []).map((x) => x.index).filter((i): i is number => typeof i === 'number')
  const cover = p.photos?.find((x) => x.cover && typeof x.index === 'number')?.index
  if (typeof cover === 'number') return cover
  if (idx.length) return Math.min(...idx)
  const s = (p.pins.algorithm || p.pins.contributor)?.sightings?.[0]?.i
  return typeof s === 'number' ? s : fallback
}

const altsOf = (x: AddressExtras) => [...(x.old ? [x.old] : []), ...x.others]

/** The order check's input for one session: its addresses, as the reviewer has them now (edits in, rejected out). */
export function seqEntries(places: Place[], review: (p: Place) => Review): SeqEntry[] {
  const out: SeqEntry[] = []
  places.forEach((p, i) => {
    if ((p.layer || 'place') !== 'address') return
    const r = review(p)
    if (r.decision === 'rejected') return
    const hn = r.fields.houseNumber
    if (hn?.state === 'rejected') return
    const st = r.fields.street
    out.push({
      id: p.id, number: hn?.value ?? p.fields.houseNumber?.value ?? '', alts: altsOf(addressExtras(p)),
      street: st && st.state !== 'rejected' ? st.value : p.fields.street?.value ?? '', along: alongRide(p, i),
    })
  })
  return out
}
