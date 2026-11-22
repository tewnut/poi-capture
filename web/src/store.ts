// App state. Datasets, photos and reviews are kept in this browser (IndexedDB): nothing is uploaded, and each
// reviewer's browser has its own copy (no sign-in yet; docs/pipeline.md). Export / publish writes files.
import { reactive, computed } from 'vue'
import { createStore, get, set, del, keys, getMany, clear } from 'idb-keyval'
import type { Dataset, Review, RemovalReview, Place, Decision, Layer } from './types'
import type { Imported } from './importer'
import { googleMergeable } from './fields'
import type { Conflict, ReviewSet } from './merge'

const meta = createStore('poi-capture-reviewer', 'meta')      // dataset json, reviews
const blobs = createStore('poi-capture-reviewer-files', 'files') // photos: `${datasetId}/${path}`

export const state = reactive({
  datasets: [] as Dataset[],
  shown: new Set<string>(),            // dataset ids drawn on the map
  open: null as string | null,         // dataset whose places are listed
  place: null as string | null,        // selected place id
  task: null as number | null,         // selected task (index in the open dataset's tasks)
  layer: '' as '' | Layer,             // the open session's layer chip (places / addresses / signs); '' = all
  missing: '' as string,               // a link to a session that is not in this browser
  reviews: {} as Record<string, Review>,          // `${dataset}|${place}`
  removals: {} as Record<string, RemovalReview>,  // `${dataset}|${task index}`
  reviewer: localStorage.getItem('reviewer') || '',
  /** Sessions this reviewer has checked out on the server (connection.ts): only these can be edited. */
  editable: new Set<string>(),
  /** Checked-out sessions with changes not saved to the server yet. */
  dirty: new Set<string>(),
  /** Per session, the server review (its savedAt) last loaded into this browser. */
  synced: {} as Record<string, string>,
  /**
   * Per session, the server review this browser's work started from (loaded or last saved) and its version: the base
   * of the three-way merge when somebody else saved meanwhile (merge.ts).
   */
  base: {} as Record<string, ReviewSet & { version: string | null }>,
  /** Per session, places both this reviewer and somebody else changed differently, to decide before saving. */
  conflicts: {} as Record<string, Conflict[]>,
  loading: true,
  error: '' as string,
})

export const rkey = (ds: string, place: string) => `${ds}|${place}`

export async function load() {
  try {
    const ks = (await keys(meta)) as string[]
    const dsKeys = ks.filter((k) => k.startsWith('ds:'))
    state.datasets = ((await getMany(dsKeys, meta)) as Dataset[]).sort((a, b) => (b.session.startedAt || '').localeCompare(a.session.startedAt || ''))
    state.reviews = (await get('reviews', meta)) || {}
    state.removals = (await get('removals', meta)) || {}
    state.synced = (await get('synced', meta)) || {}
    state.dirty = new Set((await get('dirty', meta)) || [])
    state.base = (await get('base', meta)) || {}
    state.conflicts = (await get('conflicts', meta)) || {}
    state.shown = new Set(state.datasets.map((d) => d.id))
  } catch (e: any) {
    state.error = `Could not open the browser storage: ${e.message}`
  } finally {
    state.loading = false
  }
}

export async function add(items: Imported[]) {
  for (const { dataset, files } of items) {
    for (const [p, b] of files) await set(`${dataset.id}/${p}`, b, blobs)
    await set(`ds:${dataset.id}`, dataset, meta)
    const i = state.datasets.findIndex((d) => d.id === dataset.id)
    if (i >= 0) state.datasets.splice(i, 1, dataset)
    else state.datasets.unshift(dataset)
    state.shown.add(dataset.id)
  }
}

/** Remove every session, photo and review from this browser, to start again with fresh imports. */
export async function clearAll() {
  await clear(blobs)
  await clear(meta)
  for (const p of urls.values()) p.then((u) => u && URL.revokeObjectURL(u))
  urls.clear()
  state.datasets = []
  state.shown = new Set()
  state.reviews = {}
  state.removals = {}
  state.open = null; state.place = null; state.task = null; state.missing = ''
}

export async function remove(id: string) {
  const d = state.datasets.find((x) => x.id === id)
  if (!d) return
  // its photos, and the ones the reviewer added or cropped
  for (const k of (await keys(blobs)) as string[]) if (k.startsWith(id + '/')) await del(k, blobs)
  await del(`ds:${id}`, meta)
  state.datasets = state.datasets.filter((x) => x.id !== id)
  state.shown.delete(id)
  for (const k of Object.keys(state.reviews)) if (k.startsWith(id + '|')) delete state.reviews[k]
  for (const k of Object.keys(state.removals)) if (k.startsWith(id + '|')) delete state.removals[k]
  await saveReviews()
  if (state.open === id) { state.open = null; state.place = null }
}

const urls = new Map<string, Promise<string | null>>()
/** An object URL for a stored photo (cached for the page's life). */
export function photoUrl(ds: string, path: string | null | undefined): Promise<string | null> {
  if (!path) return Promise.resolve(null)
  const k = `${ds}/${path}`
  if (!urls.has(k)) urls.set(k, get(k, blobs).then((b: Blob | undefined) => (b ? URL.createObjectURL(b) : null)))
  return urls.get(k)!
}

/** A stored photo file (for export). */
export async function photoBlob(ds: string, path: string): Promise<Blob | undefined> {
  return get(`${ds}/${path}`, blobs)
}

/** Store a photo the reviewer added or cropped. */
export async function putPhoto(ds: string, path: string, b: Blob) {
  await set(`${ds}/${path}`, b, blobs)
}

export async function delPhoto(ds: string, path: string) {
  const k = `${ds}/${path}`
  await del(k, blobs)
  urls.get(k)?.then((u) => u && URL.revokeObjectURL(u))
  urls.delete(k)
}

/** A JPEG of part of a stored photo; rect in fractions of the photo as shown (EXIF rotation applied). */
export async function cropPhoto(ds: string, path: string, [x, y, w, h]: [number, number, number, number]): Promise<Blob> {
  const b = await photoBlob(ds, path)
  if (!b) throw new Error('photo not found')
  const bm = await createImageBitmap(b, { imageOrientation: 'from-image' })
  const sx = Math.round(x * bm.width), sy = Math.round(y * bm.height)
  const sw = Math.max(1, Math.round(w * bm.width)), sh = Math.max(1, Math.round(h * bm.height))
  const c = document.createElement('canvas')
  c.width = sw; c.height = sh
  c.getContext('2d')!.drawImage(bm, sx, sy, sw, sh, 0, 0, sw, sh)
  bm.close()
  return new Promise((res, rej) => c.toBlob((o) => (o ? res(o) : rej(new Error('could not encode the crop'))), 'image/jpeg', 0.92))
}

export async function saveReviews() {
  await set('reviews', JSON.parse(JSON.stringify(state.reviews)), meta)
  await set('removals', JSON.parse(JSON.stringify(state.removals)), meta)
  await set('dirty', [...state.dirty], meta)
  await set('conflicts', JSON.parse(JSON.stringify(state.conflicts)), meta)
}

/** What this browser's work on [ds] now starts from: the server review [s] (version [version]). */
export async function setBase(ds: string, s: ReviewSet, version: string | null) {
  state.base[ds] = JSON.parse(JSON.stringify({ ...s, version }))
  await set('base', JSON.parse(JSON.stringify(state.base)), meta)
}

/** Replace this session's reviews and removals in this browser with [s]. */
export function replaceSet(ds: string, s: ReviewSet) {
  for (const k of Object.keys(state.reviews)) if (k.startsWith(ds + '|')) delete state.reviews[k]
  for (const k of Object.keys(state.removals)) if (k.startsWith(ds + '|')) delete state.removals[k]
  for (const [id, r] of Object.entries(s.reviews)) state.reviews[rkey(ds, id)] = r
  for (const [i, r] of Object.entries(s.removals)) state.removals[`${ds}|${i}`] = r
}

export async function setSynced(ds: string, at: string | null) {
  if (at) state.synced[ds] = at
  else delete state.synced[ds]
  await set('synced', { ...state.synced }, meta)
}

/** A review loaded from the server replaces this session's reviews, removals and reviewer photos in this browser. */
export async function applyReview(ds: string, reviews: Record<string, Review>, removals: Record<string, RemovalReview>,
  files: Map<string, Blob>, savedAt: string | null, version: string | null = null) {
  replaceSet(ds, { reviews, removals })
  for (const [path, b] of files) await putPhoto(ds, path, b)
  state.dirty.delete(ds)
  delete state.conflicts[ds]
  await saveReviews()
  await setSynced(ds, savedAt)
  await setBase(ds, { reviews, removals }, version)
}

export function setReviewer(name: string) {
  state.reviewer = name
  localStorage.setItem('reviewer', name)
}

export function reviewOf(ds: string, p: Place): Review {
  return state.reviews[rkey(ds, p.id)] || { decision: 'todo', fields: {} }
}

/** Reviews change only in a session this reviewer has checked out (state.editable); anything else is ignored. */
export async function putReview(ds: string, p: Place, r: Review) {
  if (!state.editable.has(ds)) return
  state.reviews[rkey(ds, p.id)] = { ...r, reviewer: state.reviewer || undefined, at: new Date().toISOString() }
  state.dirty.add(ds)
  await saveReviews()
}

export async function putRemoval(ds: string, i: number, decision: RemovalReview['decision']) {
  if (!state.editable.has(ds)) return
  state.removals[`${ds}|${i}`] = { decision, reviewer: state.reviewer || undefined, at: new Date().toISOString() }
  state.dirty.add(ds)
  await saveReviews()
}

export const openDataset = computed(() => state.datasets.find((d) => d.id === state.open) || null)
export const openPlace = computed(() => openDataset.value?.places.find((p) => p.id === state.place) || null)
export const openTask = computed(() => (state.task == null ? null : openDataset.value?.tasks[state.task] || null))

export function counts(d: Dataset) {
  const c: Record<Decision, number> = { todo: 0, confirmed: 0, rejected: 0, visit: 0 }
  for (const p of d.places) c[reviewOf(d.id, p).decision]++
  return c
}

export type Stage = 'captured' | 'processed' | 'reviewed'
export const STAGES: { key: Stage; label: string }[] = [
  { key: 'captured', label: 'Captured' }, { key: 'processed', label: 'Agent processed' }, { key: 'reviewed', label: 'Human reviewed' },
]

/** Where a session is in the pipeline: captured (raw phone export), agent processed (result bundle), human
 *  reviewed (every place decided, and every removal task too). done / total counts those decisions. */
export function stageOf(d: Dataset): { stage: Stage; done: number; total: number } {
  if (d.source === 'raw') return { stage: 'captured', done: 0, total: d.places.length }
  const removals = d.tasks.map((t, i) => ({ t, i })).filter(({ t }) => t.type !== 'visit')
  const total = d.places.length + removals.length
  const done = d.places.filter((p) => reviewOf(d.id, p).decision !== 'todo').length +
    removals.filter(({ i }) => (state.removals[`${d.id}|${i}`]?.decision || 'todo') !== 'todo').length
  return { stage: done >= total ? 'reviewed' : 'processed', done, total }
}

/** Google status of a place, from the agent's lookup: a result too far or too unlike the sign is "new". */
export function gstat(p: Place): 'found' | 'new' | 'todo' {
  const g = p.google
  if (!g) return 'todo'
  if (googleMergeable(g)) return 'found'
  if (g.name) return 'new'
  return g.status && !String(g.status).startsWith('error') ? 'new' : 'todo'
}
