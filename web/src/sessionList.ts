// The session list's stages, search, filters, sort and day groups (App.vue). Pure, for tests: App.vue gathers the
// facts of each row (its dataset, the server's processing claims and reviews) and keeps the filters in the address
// (#/?q=…&stage=…), so a filtered list can be shared.
import type { InjectionKey, Ref } from 'vue'

/**
 * Where a ride is, as the list's stage chips name it: captured (uploaded raw, waiting for an agent), processing (an
 * agent has claimed it), to review (processed, nobody started), reviewing (checked out, or partly decided), done
 * (reviewed: every place and removal decided, ready to publish / export), exported (published or exported after the
 * last change to a decision).
 */
export type ListStage = 'captured' | 'processing' | 'toReview' | 'reviewing' | 'done' | 'exported'
export const LIST_STAGES: ListStage[] = ['captured', 'processing', 'toReview', 'reviewing', 'done', 'exported']

/** What a ride was for: shops, house numbers, signs (a sweep's target), an indoor walk, a Place capture. */
export type ListKind = 'place' | 'address' | 'sign' | 'indoor' | 'poi'
export const LIST_KINDS: ListKind[] = ['place', 'address', 'sign', 'indoor', 'poi']
export const KIND_ICON: Record<ListKind, string> = { place: '🏪', address: '🏠', sign: '🚸', indoor: '🏬', poi: '📍' }

export type Period = '' | 'today' | '7d' | '30d'
export const PERIODS: Period[] = ['', 'today', '7d', '30d']
export type SortKey = 'new' | 'old' | 'stage' | 'name'
export const SORTS: SortKey[] = ['new', 'old', 'stage', 'name']

export type ListFilters = { q: string; stage: ListStage | ''; kind: ListKind | ''; period: Period; rider: string; sort: SortKey }
export const NO_FILTERS: ListFilters = Object.freeze({ q: '', stage: '', kind: '', period: '', rider: '', sort: 'new' }) as ListFilters

/** What the list needs to know about one row. text: everything the search looks in (name, street, rider, building). */
export type Facts = { key: string; name: string; date: string | null; stage: ListStage; kind: ListKind; rider: string; text: string }

/**
 * A row's stage. raw: nothing processed yet (a raw ride on the server, or a raw zip in this browser); processing: an
 * agent holds the ride; progress: the decisions made (this browser's copy when downloaded, else the last review saved
 * on the server; null = none saved); locked: somebody has the review checked out; exported: an export newer than the
 * last decision (isExported; known only for rides in this browser, whose review package carries its exports).
 */
export function listStage(x: { raw: boolean; processing: boolean; progress: { done: number | null; total: number | null } | null; locked: boolean; exported?: boolean }): ListStage {
  if (x.raw) return x.processing ? 'processing' : 'captured'
  const done = x.progress?.done ?? 0
  const total = x.progress?.total
  if (total != null && done >= total) return x.exported ? 'exported' : 'done'
  return x.locked || done > 0 ? 'reviewing' : 'toReview'
}

/** A session kind (sweep | poi | indoor) and a sweep's target (shops | addresses | signs) as one of the list's kinds. */
export function listKind(kind: string | null | undefined, target: string | null | undefined): ListKind {
  if (kind === 'poi') return 'poi'
  if (kind === 'indoor') return 'indoor'
  if (target === 'addresses') return 'address'
  if (target === 'signs') return 'sign'
  return 'place'
}

/** For accent-insensitive search: lower case, no tone or vowel marks, đ as d ("Đồng Văn Cống" -> "dong van cong"). */
export function fold(s: string): string {
  return s.normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[đĐ]/g, 'd').toLowerCase()
}

/** Every word of the query is somewhere in the text (accents ignored). */
export function matches(text: string, q: string): boolean {
  const words = fold(q).split(/\s+/).filter(Boolean)
  if (!words.length) return true
  const hay = fold(text)
  return words.every((w) => hay.includes(w))
}

const DAY = 86400000
/** Local midnight of a time. */
const startOfDay = (ms: number) => { const d = new Date(ms); d.setHours(0, 0, 0, 0); return d.getTime() }

/** Within the period, counted in whole local days back from today; a ride without a date is in no period. */
export function inPeriod(date: string | null, period: Period, now: number): boolean {
  if (!period) return true
  const ms = date ? Date.parse(date) : NaN
  if (!Number.isFinite(ms)) return false
  const days = period === 'today' ? 1 : period === '7d' ? 7 : 30
  return ms >= startOfDay(now) - (days - 1) * DAY
}

/** The rows the filters keep (stage too, unless [withStage] is false: the stage chips' counts). */
export function filterFacts<T extends Facts>(list: T[], f: ListFilters, now: number, withStage = true): T[] {
  return list.filter((x) =>
    (!withStage || !f.stage || x.stage === f.stage) &&
    (!f.kind || x.kind === f.kind) &&
    (!f.rider || x.rider === f.rider) &&
    inPeriod(x.date, f.period, now) &&
    matches(x.text, f.q))
}

/** How many rows each stage chip has, with the other filters applied; all = the "All" chip. */
export function stageCounts(list: Facts[], f: ListFilters, now: number): Record<ListStage | 'all', number> {
  const c = { all: 0, captured: 0, processing: 0, toReview: 0, reviewing: 0, done: 0, exported: 0 }
  for (const x of filterFacts(list, f, now, false)) { c.all++; c[x.stage]++ }
  return c
}

const byNewest = (a: Facts, b: Facts) => (b.date || '').localeCompare(a.date || '')
/** Sorted copy: newest / oldest first (no date last), by stage (pipeline order, newest first within), by name. */
export function sortFacts<T extends Facts>(list: T[], sort: SortKey): T[] {
  const out = [...list]
  if (sort === 'old') return out.sort((a, b) => (!a.date ? 1 : !b.date ? -1 : a.date.localeCompare(b.date)))
  if (sort === 'stage') return out.sort((a, b) => LIST_STAGES.indexOf(a.stage) - LIST_STAGES.indexOf(b.stage) || byNewest(a, b))
  if (sort === 'name') return out.sort((a, b) => a.name.localeCompare(b.name, 'vi', { sensitivity: 'base', numeric: true }) || byNewest(a, b))
  return out.sort(byNewest)
}

/** The local day of a date (yyyy-mm-dd), '' when there is none. */
export function dayKey(date: string | null): string {
  const ms = date ? Date.parse(date) : NaN
  if (!Number.isFinite(ms)) return ''
  const d = new Date(ms)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** A day header: today, yesterday, a date (dd/mm/yyyy), or none (rides without a date). */
export function dayLabel(key: string, now: number): { day: 'today' | 'yesterday' | 'date' | 'none'; text: string } {
  if (!key) return { day: 'none', text: '' }
  if (key === dayKey(new Date(now).toISOString())) return { day: 'today', text: '' }
  if (key === dayKey(new Date(startOfDay(now) - DAY / 2).toISOString())) return { day: 'yesterday', text: '' }
  const [y, m, d] = key.split('-')
  return { day: 'date', text: `${d}/${m}/${y}` }
}

/** The sorted list in day groups when sorted by date; otherwise one group without a header (key null). */
export function dayGroups<T extends Facts>(list: T[], sort: SortKey): { key: string | null; items: T[] }[] {
  if (sort !== 'new' && sort !== 'old') return list.length ? [{ key: null, items: list }] : []
  const out: { key: string; items: T[] }[] = []
  for (const x of list) {
    const k = dayKey(x.date)
    if (out.length && out[out.length - 1].key === k) out[out.length - 1].items.push(x)
    else out.push({ key: k, items: [x] })
  }
  return out
}

/** The riders (uploaders) of the rows, sorted, without blanks. */
export function riders(list: Facts[]): string[] {
  return [...new Set(list.map((x) => x.rider).filter(Boolean))].sort((a, b) => a.localeCompare(b, 'vi'))
}

/** The filters as an address query: only what differs from no filter. */
export function toQuery(f: ListFilters): Record<string, string> {
  const q: Record<string, string> = {}
  if (f.q.trim()) q.q = f.q
  if (f.stage) q.stage = f.stage
  if (f.kind) q.kind = f.kind
  if (f.period) q.period = f.period
  if (f.rider) q.rider = f.rider
  if (f.sort !== 'new') q.sort = f.sort
  return q
}

/** The filters from an address query; anything unknown falls back to no filter. */
export function fromQuery(q: Record<string, unknown>): ListFilters {
  const one = (k: string) => { const v = q[k]; const s = Array.isArray(v) ? v[0] : v; return typeof s === 'string' ? s : '' }
  const pick = <T extends string>(k: string, ok: readonly T[], dflt: T): T => (ok.includes(one(k) as T) ? (one(k) as T) : dflt)
  return {
    q: one('q'), stage: pick<ListStage | ''>('stage', LIST_STAGES, ''), kind: pick<ListKind | ''>('kind', LIST_KINDS, ''),
    period: pick('period', PERIODS, ''), rider: one('rider'), sort: pick('sort', SORTS, 'new'),
  }
}

/** How many filters other than the search, stage and sort are set (the filter button's badge). */
export const extraFilters = (f: ListFilters) => [f.kind, f.period, f.rider].filter(Boolean).length

/** The sessions the list's search and filters hide (keys), for "hidden by the filters" next to a link to one. */
export function hiddenKeys(list: Facts[], f: ListFilters, now: number): Set<string> {
  const kept = new Set(filterFacts(list, f, now).map((x) => x.key))
  return new Set(list.map((x) => x.key).filter((k) => !kept.has(k)))
}
/** App.vue provides the keys its list hides; the review panel's "also on another ride" box marks those rides. */
export const HIDDEN_BY_FILTERS: InjectionKey<Readonly<Ref<Set<string>>>> = Symbol('hiddenByFilters')

/**
 * The words of a ride's uploads the search looks in before it is downloaded: the name, and the street(s) and
 * building the phone (raw ride) or the agent (processed result) put in the upload metadata. Both uploads count:
 * a processed result may say less than the raw ride did.
 */
export function metaWords(...metas: (Record<string, unknown> | null | undefined)[]): string[] {
  const out: string[] = []
  for (const m of metas) for (const k of ['name', 'street', 'building']) {
    const v = m?.[k]
    if (typeof v === 'string' && v.trim() && !out.includes(v.trim())) out.push(v.trim())
  }
  return out
}

/** One export or publish of a session's reviewed places: when, by whom (the reviewer's name), which format. */
export type ExportRecord = { at: string; by: string | null; format: string }

/** The newest time any place review or removal decision of the session changed (ISO), '' when none has one. */
export function lastChange(reviews: Iterable<{ at?: string }>, removals: Iterable<{ at?: string }> = []): string {
  let last = ''
  for (const r of [...reviews, ...removals]) if (r?.at && r.at > last) last = r.at
  return last
}

/** The newest export, or null. */
export function latestExport(list: ExportRecord[] | null | undefined): ExportRecord | null {
  let best: ExportRecord | null = null
  for (const x of list || []) if (x?.at && (!best || x.at > best.at)) best = x
  return best
}

/** Exported: the newest export is newer than the last change to a decision (a later change makes it stale). */
export function isExported(list: ExportRecord[] | null | undefined, lastChangeAt: string): boolean {
  const e = latestExport(list)
  return !!e && e.at > lastChangeAt
}

/** Two export lists as one (this browser's and the server's review package), oldest first, without repeats, the
 *  newest [max] kept. */
export function mergeExports(a: ExportRecord[] | null | undefined, b: ExportRecord[] | null | undefined, max = 50): ExportRecord[] {
  const seen = new Map<string, ExportRecord>()
  for (const x of [...(a || []), ...(b || [])]) {
    if (!x || typeof x.at !== 'string' || typeof x.format !== 'string') continue
    const k = `${x.at}|${x.format}|${x.by ?? ''}`
    if (!seen.has(k)) seen.set(k, { at: x.at, by: x.by ?? null, format: x.format })
  }
  return [...seen.values()].sort((p, q) => p.at.localeCompare(q.at)).slice(-max)
}

/** "24/11" of an ISO time, local day. */
export function shortDay(iso: string): string {
  const ms = Date.parse(iso)
  if (!Number.isFinite(ms)) return ''
  const d = new Date(ms)
  return `${String(d.getDate()).padStart(2, '0')}/${String(d.getMonth() + 1).padStart(2, '0')}`
}
