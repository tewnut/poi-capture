// Three-way merge of a session's review (server/README.md "Reviews", "Conflicts"): a reviewer whose checkout ended
// kept changes in this browser while somebody else checked the session out and saved. Checking out again with
// "keep my changes" merges, place by place, what this browser started from (base), its changes (mine) and the
// server's newer save (theirs): a place changed on one side only takes that change; changed the same way on both, it
// is kept; changed differently on both, it is a conflict the reviewer decides (mine or theirs). Pure, for tests.
import type { Review, RemovalReview } from './types'

export type ReviewSet = { reviews: Record<string, Review>; removals: Record<string, RemovalReview> }

/** One place (or removal task) both sides changed differently: the reviewer keeps one of them. */
export type Conflict = {
  kind: 'place' | 'removal'
  key: string                                 // place id / task index
  mine: Review | RemovalReview | null         // null: no review on that side (never reviewed, or cleared)
  theirs: Review | RemovalReview | null
  choice?: 'mine' | 'theirs'
}

/** The content of a review: who wrote it and when do not make two reviews differ. */
function same(a: unknown, b: unknown): boolean {
  return JSON.stringify(strip(a)) === JSON.stringify(strip(b))
}
function strip(v: any): any {
  if (v == null) return null
  if (Array.isArray(v)) return v.map(strip)
  if (typeof v !== 'object') return v
  const out: Record<string, unknown> = {}
  for (const k of Object.keys(v).sort()) if (k !== 'reviewer' && k !== 'at' && v[k] !== undefined) out[k] = strip(v[k])
  // an untouched review is the same as none
  if (out.decision === 'todo' && Object.keys(out).every((k) => k === 'decision' || (k === 'fields' && !Object.keys(out.fields as object).length))) return null
  return out
}

function mergeMap<T>(kind: Conflict['kind'], base: Record<string, T> | null, mine: Record<string, T>, theirs: Record<string, T>,
  out: Record<string, T>, conflicts: Conflict[]) {
  for (const k of new Set([...Object.keys(mine), ...Object.keys(theirs), ...Object.keys(base || {})])) {
    const m = mine[k] ?? null, t = theirs[k] ?? null, b = base ? base[k] ?? null : undefined
    let pick: T | null
    if (same(m, t)) pick = m ?? t
    else if (b !== undefined && same(m, b)) pick = t           // only they changed it
    else if (b !== undefined && same(t, b)) pick = m           // only I changed it
    else { conflicts.push({ kind, key: k, mine: m as any, theirs: t as any }); pick = m }   // mine until decided
    if (pick != null) out[k] = pick
  }
}

/**
 * Merge [mine] and [theirs] against [base] (null: unknown, e.g. a review loaded before this was recorded: then every
 * place that differs is a conflict, never silently overwritten). The merged set holds mine for each conflict until
 * [resolve] applies the choices.
 */
export function merge3(base: ReviewSet | null, mine: ReviewSet, theirs: ReviewSet): { merged: ReviewSet; conflicts: Conflict[] } {
  const merged: ReviewSet = { reviews: {}, removals: {} }
  const conflicts: Conflict[] = []
  mergeMap('place', base?.reviews ?? null, mine.reviews, theirs.reviews, merged.reviews, conflicts)
  mergeMap('removal', base?.removals ?? null, mine.removals, theirs.removals, merged.removals, conflicts)
  return { merged, conflicts }
}

/** The merged set with each decided conflict's choice applied (undecided ones stay mine). */
export function resolve(merged: ReviewSet, conflicts: Conflict[]): ReviewSet {
  const out: ReviewSet = { reviews: { ...merged.reviews }, removals: { ...merged.removals } }
  for (const c of conflicts) {
    const v = c.choice === 'theirs' ? c.theirs : c.mine
    const m = (c.kind === 'place' ? out.reviews : out.removals) as Record<string, unknown>
    if (v == null) delete m[c.key]
    else m[c.key] = v
  }
  return out
}
