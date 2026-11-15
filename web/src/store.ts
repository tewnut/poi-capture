// App state. Datasets, photos and reviews are kept in this browser (IndexedDB): nothing is uploaded, and each
// reviewer's browser has its own copy (no sign-in yet; docs/pipeline.md). Export / publish writes files.
import { reactive, computed } from 'vue'
import { createStore, get, set, del, keys, getMany } from 'idb-keyval'
import type { Dataset, Review, RemovalReview, Place, Decision } from './types'
import type { Imported } from './importer'

const meta = createStore('poi-capture-reviewer', 'meta')      // dataset json, reviews
const blobs = createStore('poi-capture-reviewer-files', 'files') // photos: `${datasetId}/${path}`

export const state = reactive({
  datasets: [] as Dataset[],
  shown: new Set<string>(),            // dataset ids drawn on the map
  open: null as string | null,         // dataset whose places are listed
  place: null as string | null,        // selected place id
  task: null as number | null,         // selected task (index in the open dataset's tasks)
  missing: '' as string,               // a link to a session that is not in this browser
  reviews: {} as Record<string, Review>,          // `${dataset}|${place}`
  removals: {} as Record<string, RemovalReview>,  // `${dataset}|${task index}`
  reviewer: localStorage.getItem('reviewer') || '',
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

export async function remove(id: string) {
  const d = state.datasets.find((x) => x.id === id)
  if (!d) return
  for (const p of d.files) await del(`${id}/${p}`, blobs)
  await del(`ds:${id}`, meta)
  state.datasets = state.datasets.filter((x) => x.id !== id)
  state.shown.delete(id)
  for (const k of Object.keys(state.reviews)) if (k.startsWith(id + '|')) delete state.reviews[k]
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

export async function saveReviews() {
  await set('reviews', JSON.parse(JSON.stringify(state.reviews)), meta)
  await set('removals', JSON.parse(JSON.stringify(state.removals)), meta)
}

export function setReviewer(name: string) {
  state.reviewer = name
  localStorage.setItem('reviewer', name)
}

export function reviewOf(ds: string, p: Place): Review {
  return state.reviews[rkey(ds, p.id)] || { decision: 'todo', fields: {} }
}

export async function putReview(ds: string, p: Place, r: Review) {
  state.reviews[rkey(ds, p.id)] = { ...r, reviewer: state.reviewer || undefined, at: new Date().toISOString() }
  await saveReviews()
}

export async function putRemoval(ds: string, i: number, decision: RemovalReview['decision']) {
  state.removals[`${ds}|${i}`] = { decision, reviewer: state.reviewer || undefined, at: new Date().toISOString() }
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

/** Google status of a place, from the agent's lookup. */
export function gstat(p: Place): 'found' | 'new' | 'todo' {
  const g = p.google
  if (!g) return 'todo'
  if (g.name) return 'found'
  return g.status ? 'new' : 'todo'
}
