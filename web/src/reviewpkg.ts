// A session's review as it travels to and from the server (server/README.md "Reviews"): one zip with review.json
// (every place's review and every removal decision, keyed by place id / task index) and the photos the reviewer added
// or cropped (their stored paths, added/... and crop/..., as PhotoEdits names them). Pure: the caller reads and stores.
import { zipSync, unzipSync, strToU8, strFromU8 } from 'fflate'
import type { Dataset, Review, RemovalReview } from './types'
import type { ExportRecord } from './sessionList'

export const REVIEW_SCHEMA = 'poi-review/1'

export type ReviewDoc = {
  schema: typeof REVIEW_SCHEMA
  sessionId: string
  reviewer: string
  savedAt: string
  reviews: Record<string, Review>          // place id → review
  removals: Record<string, RemovalReview>  // task index → decision
  /** The exports and publishes of the reviewed places (sessionList.ts isExported); missing in older packages. */
  exports?: ExportRecord[]
}

/** This session's reviews and removals out of the app's maps (keyed `${ds}|...`). */
export function collect(ds: string, all: Record<string, Review>, removals: Record<string, RemovalReview>) {
  const pre = ds + '|'
  const pick = <T>(m: Record<string, T>) => Object.fromEntries(Object.entries(m).filter(([k]) => k.startsWith(pre)).map(([k, v]) => [k.slice(pre.length), v]))
  return { reviews: pick(all), removals: pick(removals) }
}

/** The reviewer's own photo files the reviews point to. */
export function reviewFiles(reviews: Record<string, Review>): string[] {
  const out = new Set<string>()
  for (const r of Object.values(reviews)) {
    for (const a of r.photos?.added || []) out.add(a)
    for (const c of Object.values(r.photos?.crops || {})) out.add(c.file)
  }
  return [...out].filter(safePath)
}

/** Decided / to decide, as the session list shows it: places plus removal tasks (store.ts stageOf). */
export function progress(d: Pick<Dataset, 'places' | 'tasks'>, reviews: Record<string, Review>, removals: Record<string, RemovalReview>) {
  const rm = d.tasks.map((t, i) => ({ t, i })).filter(({ t }) => t.type !== 'visit')
  const total = d.places.length + rm.length
  const done = d.places.filter((p) => (reviews[p.id]?.decision || 'todo') !== 'todo').length +
    rm.filter(({ i }) => (removals[String(i)]?.decision || 'todo') !== 'todo').length
  return { done, total }
}

const safePath = (p: string) => /^(added|crop)\/[A-Za-z0-9_.\-/]+$/.test(p) && !p.includes('..')

export function build(doc: ReviewDoc, files: Map<string, Uint8Array>): Uint8Array {
  const z: Record<string, Uint8Array | [Uint8Array, { level: 0 | 6 }]> = { 'review.json': [strToU8(JSON.stringify(doc)), { level: 6 }] }
  for (const [p, b] of files) if (safePath(p)) z[p] = b
  return zipSync(z, { level: 0 })
}

/** The zip from the server → its review and photo files; throws on anything that is not a review of [sessionId]. */
export function parse(zip: Uint8Array, sessionId: string): { doc: ReviewDoc; files: Map<string, Uint8Array> } {
  const all = unzipSync(zip)
  const raw = all['review.json']
  if (!raw) throw new Error('not a review package (no review.json)')
  const doc = JSON.parse(strFromU8(raw)) as ReviewDoc
  if (doc?.schema !== REVIEW_SCHEMA) throw new Error(`unknown review schema ${doc?.schema}`)
  if (doc.sessionId !== sessionId) throw new Error(`the review is of ${doc.sessionId}, not ${sessionId}`)
  if (!doc.reviews || typeof doc.reviews !== 'object' || !doc.removals || typeof doc.removals !== 'object') throw new Error('review.json is incomplete')
  if (doc.exports != null && !Array.isArray(doc.exports)) doc.exports = []
  const files = new Map<string, Uint8Array>()
  for (const [p, b] of Object.entries(all)) if (p !== 'review.json' && safePath(p)) files.set(p, b)
  return { doc, files }
}
