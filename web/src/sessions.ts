// The reviewer's session list (App.vue): the server's sessions (processed, or still waiting for the agent) merged with
// the ones downloaded into this browser. Pure, for tests; connection.ts keeps the live state.
import { PROCESSED, importState, type CloudItem } from './cloud'
import type { Dataset } from './types'

/**
 * One row of the session list: a session the server has processed (with its download state), one still waiting for
 * the agent (a raw ride, nothing to review yet), or one only in this browser (downloaded before, gone from the server).
 */
export type Row = {
  key: string
  sessionId: string
  name: string
  date: string | null
  kind: string | null
  target: string | null         // shops | addresses | signs; null = not said (shops)
  item: CloudItem | null        // the newest processed result on the server
  raw: CloudItem | null         // the newest raw ride on the server
  state: 'here' | 'updated' | 'remote' | 'waiting' | 'local'
}

export function rows(items: CloudItem[] | null, datasets: Dataset[]): Row[] {
  const out = new Map<string, Row>()
  const done = (items || []).filter((i) => i.complete)
  for (const i of done.filter((x) => x.type === PROCESSED)) {
    const id = i.sessionId || i.id
    if (out.has(id)) continue   // newest first: the first one is the one to review
    const s = importState(i, datasets)
    out.set(id, { key: id, sessionId: id, name: i.name || id, date: i.trip?.startedAt || i.createdAt, kind: i.kind, target: i.target, item: i, raw: null,
      state: s === 'imported' ? 'here' : s === 'updated' ? 'updated' : 'remote' })
  }
  for (const i of done.filter((x) => x.type === 'raw-session')) {
    const id = i.sessionId || i.id
    const r = out.get(id)
    if (r) { r.raw = r.raw || i; r.target = r.target || i.target; continue }
    out.set(id, { key: id, sessionId: id, name: i.name || id, date: i.trip?.startedAt || i.createdAt, kind: i.kind, target: i.target, item: null, raw: i, state: 'waiting' })
  }
  for (const d of datasets) {
    const r = out.get(d.id)
    if (r) { if (r.state === 'waiting') r.state = 'local'; continue }
    out.set(d.id, { key: d.id, sessionId: d.id, name: d.session.name || d.id, date: d.session.startedAt || null, kind: d.session.kind ?? null, target: d.session.target ?? null, item: null, raw: null, state: 'local' })
  }
  return [...out.values()].sort((a, b) => (b.date || '').localeCompare(a.date || ''))
}

