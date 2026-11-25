<script setup lang="ts">
// Left panel: the sessions on the project's server, by stage, searched and filtered (sessionList.ts); open a session
// to review its places, with its tasks in a to-do section under them (downloaded into this browser the first time,
// connection.ts). The map is in the middle (on the list: the rides listed), the selected place or task on the right.
// The address (#/...) decides what is open; #/settings and #/admin are pages of their own.
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MapView from './components/MapView.vue'
import PlaceReview from './components/PlaceReview.vue'
import TaskDetail from './components/TaskDetail.vue'
import TaskRow from './components/TaskRow.vue'
import StageBar from './components/StageBar.vue'
import Photo from './components/Photo.vue'
import AdminPanel from './components/AdminPanel.vue'
import SettingsPage from './components/SettingsPage.vue'
import ReviewBar from './components/ReviewBar.vue'
import { state, load, remove, reviewOf, photoBlob, gstat, openDataset, openPlace, openTask, stageOf, seqFlags, dupes } from './store'
import { placeHints } from './hints'
import { to } from './router'
import { t, relTime } from './i18n'
import { keptPhotos, exportNames } from './photos'
import { zipSync, strToU8 } from 'fflate'
import { geojson, csv, osm, tasksGeojson, download, type Published, type Removed } from './publish'
import { kml, gpx, shapefile, xlsx, allFormats } from './exports'
import { buildProject, projectName } from './project'
import { missingStreet, pendingSuggestions } from './fields'
import { humanSize, decodePolyline, type CloudItem } from './cloud'
import {
  LIST_STAGES, LIST_KINDS, KIND_ICON, PERIODS, SORTS, NO_FILTERS, listStage, listKind, filterFacts, stageCounts, sortFacts, dayGroups,
  dayLabel, riders, toQuery, fromQuery, extraFilters, type Facts, type ListFilters,
} from './sessionList'
import { conn, connected, canRead, canRelease, stopProcessing, connect, refresh, rows, pull, loadSaved, saveToServer, type Row } from './connection'
import { swipeStep, neighbour, blocksSwipe, type Step } from './swipe'
import { LAYERS, layerOf, type Dataset, type Layer, type Place } from './types'
import { defaultLevel, isIndoor, levelsOf, onLevel, placeLevel } from './indoor'

const busy = ref('')
const publishOpen = ref(false)
const reviewRef = ref<InstanceType<typeof PlaceReview>>()
/** The open session's to-do section (its tasks, under the places): open when a task is. */
const todoOpen = ref(false)

// the open session's place filters
const q = ref('')
const fDecision = ref('')
const fGoogle = ref('')

const route = useRoute()
const router = useRouter()

/** #/ sessions (?q=…&stage=… its filters) · #/s/<id> places and tasks · #/s/<id>/p/<place> · #/s/<id>/t/<task>
 *  (older links: #/tasks lands on the list, #/s/<id>/tasks opens the session's to-do section) */
function applyRoute() {
  if (state.loading) return
  const ds = route.params.ds as string | undefined
  state.missing = ''
  if (ds && !state.datasets.some((d) => d.id === ds)) {
    state.open = null; state.place = null; state.task = null
    // a link to a session this browser has not downloaded yet: from the server, then open the link
    const item = rows(conn.items, state.datasets).find((r) => r.sessionId === ds)?.item
    if (item) { if (!conn.pulling) fetchSession(item, route.fullPath) }
    else if (conn.items || conn.status === 'error' || conn.status === 'none') state.missing = ds
    return
  }
  state.place = null; state.task = null
  switch (route.name) {
    case 'session': state.open = ds!; break
    case 'sessionTasks': state.open = ds!; todoOpen.value = true; break
    case 'place': {
      state.open = ds!
      const id = route.params.place as string
      state.place = openDataset.value?.places.some((p) => p.id === id) ? id : null
      if (!state.place) state.missing = `${ds} / place ${id}`
      break
    }
    case 'task': {
      state.open = ds!; todoOpen.value = true
      const i = Number(route.params.task)
      state.task = Number.isInteger(i) && openDataset.value?.tasks[i] ? i : null
      if (state.task == null) state.missing = `${ds} / task ${route.params.task}`
      break
    }
    default: state.open = null
  }
  if (state.open) state.shown.add(state.open)
}
watch(() => route.fullPath, applyRoute)
watch(() => state.loading, applyRoute)
watch(() => conn.items, applyRoute)
watch(() => state.open, () => { q.value = ''; fDecision.value = ''; fGoogle.value = ''; state.layer = ''; state.level = openLevel()
  todoOpen.value = route.name === 'task' || route.name === 'sessionTasks'; picked.value = ''; hot.value = '' })
/** An indoor walk opens on the level with the most places (indoor.ts); outdoors there is no level. */
function openLevel() {
  const d = openDataset.value
  return (d && defaultLevel(d, (p) => reviewOf(d.id, p))) || ''
}
watch(() => openDataset.value?.id, (id, old) => { if (id && id !== old && !state.level) state.level = openLevel() })
onMounted(async () => { await load(); await connect() })

// sessions: the server's (processed, or still waiting for the agent) and the ones downloaded into this browser
const dsOf = (r: Row) => state.datasets.find((d) => d.id === r.sessionId) || null
const allRows = computed(() => rows(conn.items, state.datasets))
const upload = (r: Row) => r.item || r.raw
/** What the list knows of a row (sessionList.ts): its stage from this browser's copy when downloaded, else from the
 *  server (waiting / being processed, the last review saved, who has it checked out); its kind; who uploaded it; and
 *  the words the search looks in (name, street, rider, building). */
function rowFacts(r: Row): Facts & { row: Row } {
  const d = dsOf(r)
  const info = conn.reviews[r.sessionId]
  const raw = d ? d.source === 'raw' : r.state === 'waiting'
  const st = d && !raw ? stageOf(d) : null
  const rider = upload(r)?.by?.name || ''
  const streets = d ? new Set(d.places.map((p) => p.fields.street?.value).filter(Boolean)) : []
  const meta = upload(r)?.metadata || {}
  const text = [r.name, d?.session.name, d?.session.street, d?.building?.name, ...streets, rider,
    ...['street', 'building'].map((k) => (typeof meta[k] === 'string' ? meta[k] : ''))].filter(Boolean).join(' · ')
  return {
    row: r, key: r.key, name: r.name, date: r.date, rider, text,
    stage: listStage({ raw, processing: !!conn.processing[r.sessionId], progress: st ?? info?.saved ?? null, locked: !!info?.lock }),
    kind: listKind(d?.session.kind ?? r.kind, d?.session.target ?? r.target),
  }
}
const allFacts = computed(() => allRows.value.map(rowFacts))

// the list's search, filters and sort, kept in the address (#/?q=…&stage=…) while the list is shown
const lf = reactive<ListFilters>({ ...NO_FILTERS })
const filtersOpen = ref(false)
const now = ref(Date.now())
const sameQuery = (a: Record<string, unknown>, b: Record<string, unknown>) => JSON.stringify(Object.entries(a).sort()) === JSON.stringify(Object.entries(b).sort())
watch(() => (route.name === 'sessions' ? route.query : null), (qy) => {
  if (!qy) return
  const f = fromQuery(qy)
  Object.assign(lf, f)
  if (extraFilters(f)) filtersOpen.value = true
  // an address with values this version does not know: replaced by what it understood
  if (!sameQuery(toQuery(f), qy)) router.replace(to.sessions(toQuery(f)))
}, { immediate: true })
watch(lf, () => {
  if (route.name === 'sessions' && !sameQuery(toQuery(lf), route.query)) router.replace(to.sessions(toQuery(lf)))
}, { deep: true })
/** The list, with its filters (back from a session, after removing one). */
const toList = () => to.sessions(toQuery(lf))
function clearListFilters() { Object.assign(lf, { ...NO_FILTERS, sort: lf.sort }) }

const listed = computed(() => sortFacts(filterFacts(allFacts.value, lf, now.value), lf.sort))
const groups = computed(() => dayGroups(listed.value, lf.sort))
const counts = computed(() => stageCounts(allFacts.value, lf, now.value))
const riderList = computed(() => { const l = riders(allFacts.value); return lf.rider && !l.includes(lf.rider) ? [...l, lf.rider] : l })
function groupTitle(key: string) {
  const l = dayLabel(key, now.value)
  return l.day === 'today' ? t('dayToday') : l.day === 'yesterday' ? t('dayYesterday') : l.day === 'none' ? t('noDate') : l.text
}
/** The row's time: the hour under a day header, else the day. */
function rowWhen(r: Row) {
  const ms = r.date ? Date.parse(r.date) : NaN
  if (!Number.isFinite(ms)) return t('noDate')
  const d = new Date(ms)
  const p = (n: number) => String(n).padStart(2, '0')
  return lf.sort === 'new' || lf.sort === 'old' ? `${p(d.getHours())}:${p(d.getMinutes())}` : `${p(d.getDate())}/${p(d.getMonth() + 1)}/${d.getFullYear()}`
}
/** Places once downloaded, else the photos the upload says it has. */
function rowSize(r: Row) {
  const d = dsOf(r)
  if (d) return d.places.length ? t('nPlaces', { n: d.places.length }) : t('noPlacesYet')
  const n = upload(r)?.trip?.photos
  return n ? t('nPhotos', { n }) : ''
}
// the map on the list: the rides listed; the hovered (or picked on the map) row's route bold
const hot = ref('')
const picked = ref('')
const listedIds = computed(() => (state.open ? null : listed.value.map((x) => x.key)))
const listedRoutes = computed(() => (state.open ? [] : listed.value.filter((x) => !dsOf(x.row)).flatMap((x) => {
  const tr = upload(x.row)?.trip?.track
  try { return tr ? [{ id: x.key, name: x.name, track: decodePolyline(tr) }] : [] } catch { return [] }
})))
/** A route clicked on the map: its row is picked (scrolled to, lit up); clicked again, the session opens. */
function pickRow(id: string) {
  const f = listed.value.find((x) => x.key === id)
  if (!f) return
  if (picked.value === id) { openRow(f.row); return }
  picked.value = id
  nextTick(() => document.querySelector(`[data-sid="${CSS.escape(id)}"]`)?.scrollIntoView({ block: 'nearest', behavior: 'smooth' }))
}
function kindLabel(x: Row | Dataset) {
  const d = 'session' in x ? x : dsOf(x)
  const kind = d ? d.session.kind : (x as Row).kind
  if (kind === 'poi') return t('kindPoi')
  if (kind === 'indoor') return t('kindIndoor')
  // not downloaded yet: the server does not say how the phone was held
  const hold = d ? d.session.holding || 'mount' : (x as Row).item?.metadata.holding ?? (x as Row).raw?.metadata.holding
  return hold == null ? t('sweep') : t(hold === 'hand' ? 'kindSweepHand' : 'kindSweepMount')
}
/** The ride's target when it is not plain shops (the default needs no word): downloaded, or from the upload metadata. */
function targetLabel(x: Row | Dataset) {
  const d = 'session' in x ? x : dsOf(x)
  const tg = d?.session.target || (x as Row).target
  return tg === 'addresses' || tg === 'signs' ? t('target_' + tg) : ''
}
/** Download a session from the server, then open it (or [then], the address that asked for it). */
async function fetchSession(item: CloudItem, then?: string) {
  busy.value = t('downloading', { name: item.name || item.sessionId || item.filename, size: humanSize(item.size) })
  state.error = ''
  try {
    const id = await pull(item)
    if (then) { await router.replace(then); applyRoute() } else router.push(to.session(id))
  } catch (e: any) {
    state.error = e.message || String(e)
  } finally {
    busy.value = ''
  }
}
// a session open read-only shows the latest review saved on the server (another reviewer's progress)
watch(() => [openDataset.value?.id, conn.reviews[openDataset.value?.id || '']?.saved?.at], () => {
  const d = openDataset.value
  if (d && connected.value && !state.editable.has(d.id)) loadSaved(d).catch((e) => { state.error = e?.message || String(e) })
})
// checked out with unsaved changes: saved to the server every 2 minutes (the lock stays)
const AUTOSAVE_MS = 2 * 60 * 1000
let autosave: ReturnType<typeof setInterval> | undefined
onMounted(() => { autosave = setInterval(() => {
  for (const id of [...state.dirty]) {
    const d = state.datasets.find((x) => x.id === id)
    if (d && state.editable.has(id) && !conn.reviewBusy && !state.conflicts[id]?.length) saveToServer(d, false).catch(() => {})
  }
}, AUTOSAVE_MS) })
onBeforeUnmount(() => clearInterval(autosave))
/** Free a ride being processed (its agent, or a key with the release scope), after asking. */
function stopProc(r: Row) {
  if (window.confirm(t('stopProcessingConfirm', procParams(r)))) stopProcessing(r.sessionId).catch((e) => window.alert(e?.message || String(e)))
}
/** A ride being processed: who and since when (GET /processing). */
function procParams(r: Row) {
  const p = conn.processing[r.sessionId]
  return { name: p?.by.name ?? '', when: p ? relTime(p.at) : '', until: p ? relTime(p.until) : '' }
}
/** The list's word on a session's review: who has it checked out, or how far the last saved review got. */
function lockLabel(r: Row): { text: string; cls: string; title: string } | null {
  const i = conn.reviews[r.sessionId]
  // checkout ended (released, expired) with changes still only in this browser: the reviewer must decide (ReviewBar)
  if (state.dirty.has(r.sessionId) && !state.editable.has(r.sessionId)) return { text: t('lockLost'), cls: 'rm', title: t('lockLostTitle') }
  if (i?.lock) {
    const p = { name: i.lock.by.name, when: relTime(i.lock.at), until: relTime(i.lock.until) }
    return { text: i.lock.by.id === conn.me?.id ? t('lockYou', p) : t('lockOther', p), cls: i.lock.by.id === conn.me?.id ? 'ok' : 'visit', title: t('lockTitle', p) }
  }
  if (i?.saved && i.saved.total != null) {
    const p = { done: i.saved.done ?? 0, total: i.saved.total, name: i.saved.by.name, when: relTime(i.saved.at) }
    return { text: t('reviewProgress', p), cls: 'todo', title: t('reviewProgressTitle', p) }
  }
  return null
}
// who has what checked out changes from other browsers: refresh the list every minute while this tab is shown,
// and at once when it comes back to the front
const LIST_REFRESH_MS = 60 * 1000
let listTimer: ReturnType<typeof setInterval> | undefined
const onVisible = () => { if (document.visibilityState === 'visible' && connected.value && !conn.listing) refresh().catch(() => {}) }
onMounted(() => {
  listTimer = setInterval(() => { now.value = Date.now(); if (document.visibilityState === 'visible' && connected.value && !conn.listing) refresh().catch(() => {}) }, LIST_REFRESH_MS)
  document.addEventListener('visibilitychange', onVisible)
})
onBeforeUnmount(() => { clearInterval(listTimer); document.removeEventListener('visibilitychange', onVisible) })
function openRow(r: Row) {
  if (dsOf(r)) router.push(to.session(r.sessionId))
  else if (r.item && !conn.pulling) fetchSession(r.item)
}
async function del(d: Dataset) {
  if (!window.confirm(t('removeConfirm', { name: d.session.name || d.id }))) return
  await remove(d.id)
  router.push(toList())
}

// one session
const places = computed(() => {
  const d = openDataset.value
  if (!d) return []
  const s = q.value.trim().toLowerCase()
  return d.places.map((p, i) => ({ p, i })).filter(({ p }) => {
    const r = reviewOf(d.id, p)
    return (!state.layer || layerOf(p) === state.layer) && onLevel(p, state.level, r) &&
      (!s || `${p.name} ${p.category || ''} ${p.fields.signText?.value || ''}`.toLowerCase().includes(s)) &&
      (!fDecision.value || r.decision === fDecision.value) && (!fGoogle.value || gstat(p) === fGoogle.value)
  })
})
function clearPlaceFilters() { q.value = ''; fDecision.value = ''; fGoogle.value = ''; state.layer = ''; state.level = '' }
/** An indoor walk's levels with their place counts (the level chips; indoor.ts). */
const levelCounts = computed(() => {
  const d = openDataset.value
  if (!d || !isIndoor(d)) return []
  const rv = (p: Place) => reviewOf(d.id, p)
  return levelsOf(d, rv).map((l) => ({ l, n: d.places.filter((p) => placeLevel(p, rv(p)) === l).length }))
})
/** The open session's layers with their counts; chips only when there is more than one. */
const layerCounts = computed(() => {
  const d = openDataset.value
  const n = (l: Layer) => d?.places.filter((p) => layerOf(p) === l).length || 0
  return LAYERS.map((l) => ({ l, n: n(l) })).filter((x) => x.n)
})
function placeLine(p: Place) {
  const l = layerOf(p)
  if (l === 'sign') return [p.fields.signCode?.value || p.sign?.code, p.fields.other?.value].filter(Boolean).join(' · ')
  if (l === 'address') return p.placeRef ? t('placeRef', { name: p.placeRef }) : ''
  const where = p.indoor || p.unit ? [placeLevel(p) ? t('levelN', { l: placeLevel(p) }) : '', p.fields.unit?.value || p.unit || ''].filter(Boolean).join(' · ') : ''
  return [where, `${p.category || ''}${p.fields.phone ? ' · ' + p.fields.phone.value : ''}`].filter(Boolean).join(' · ')
}
function selectPlace(ds: string, id: string) { router.push(to.place(ds, id)) }
function selectTask(ds: string, i: number) { router.push(to.task(ds, i)) }
function nextPlace() {
  const d = openDataset.value
  if (!d) return
  // the next one to do in the layer shown
  const list = d.places.filter((p) => (!state.layer || layerOf(p) === state.layer) && onLevel(p, state.level, reviewOf(d.id, p)))
  const i = list.findIndex((p) => p.id === state.place)
  const next = list.slice(i + 1).find((p) => reviewOf(d.id, p).decision === 'todo') || list.find((p) => reviewOf(d.id, p).decision === 'todo')
  router.push(next ? to.place(d.id, next.id) : to.session(d.id))
}
function movePin(lat: number, lon: number) { reviewRef.value?.setPin(lat, lon) }

// phone (<= 700 px in style.css): the open place or task is a full-screen panel; tablet (701-1099 px): the panel
// takes the map's place. Both have back, previous / next and a horizontal swipe; "Map" shows the map above the panel
// (to check or drag the pin). PANEL is the widest of the two queries.
const PANEL = '(max-width: 1099px)'
const panelMq = typeof window !== 'undefined' && window.matchMedia ? window.matchMedia(PANEL) : null
const panel = ref(!!panelMq?.matches)
const onPanelMq = (e: MediaQueryListEvent) => (panel.value = e.matches)
onMounted(() => panelMq?.addEventListener?.('change', onPanelMq))
onBeforeUnmount(() => panelMq?.removeEventListener?.('change', onPanelMq))
const detailEl = ref<HTMLElement>()
const mapRef = ref<InstanceType<typeof MapView>>()
const mapPeek = ref(false)
/** The places the panel steps through: the list as filtered, or every place when the open one is filtered out. */
const placeIds = computed(() => {
  const d = openDataset.value
  if (!d) return []
  const listed = places.value.map((x) => x.p.id)
  return state.place && listed.includes(state.place) ? listed : d.places.map((p) => p.id)
})
const taskIds = computed(() => (openDataset.value ? openDataset.value.tasks.map((_t, i) => i) : []))
const stepList = computed<(string | number)[]>(() => (openPlace.value ? placeIds.value : taskIds.value))
const stepAt = computed<string | number | null>(() => (openPlace.value ? state.place : state.task))
const stepPos = computed(() => (stepAt.value == null ? -1 : stepList.value.indexOf(stepAt.value)))
const canStep = (s: Step) => stepAt.value != null && neighbour(stepList.value, stepAt.value, s) != null
/** Previous / next in the panel: replaces the address, so the phone's back button still goes to the list. */
function stepOpen(s: Step) {
  const d = openDataset.value
  if (!d || stepAt.value == null) return
  const n = neighbour(stepList.value, stepAt.value, s)
  if (n == null) return
  router.replace(openPlace.value ? to.place(d.id, n as string) : to.task(d.id, n as number))
}
function closeDetail() {
  const d = openDataset.value
  if (d) router.push(to.session(d.id))
}
let touch: { x: number; y: number; t: number } | null = null
function touchStart(e: TouchEvent) {
  touch = null
  if (!panel.value || e.touches.length !== 1 || !detailEl.value) return
  if (blocksSwipe(e.target as Element, detailEl.value, (el) => getComputedStyle(el as Element).overflowX)) return
  touch = { x: e.touches[0].clientX, y: e.touches[0].clientY, t: Date.now() }
}
function touchEnd(e: TouchEvent) {
  const s0 = touch
  touch = null
  if (!s0 || e.touches.length) return
  const p = e.changedTouches[0]
  const s = p && swipeStep(p.clientX - s0.x, p.clientY - s0.y, Date.now() - s0.t)
  if (s) stepOpen(s)
}
// a new item starts at its top; leaving the panel hides the map again
watch(() => [state.place, state.task], () => detailEl.value?.scrollTo?.({ top: 0 }))
watch(() => !!(openPlace.value || openTask.value), (open) => { if (!open) mapPeek.value = false })
watch(mapPeek, async (v) => { if (v) { await nextTick(); mapRef.value?.focusOpen() } })

// publish
// publish: the open session's confirmed places and removals
const confirmed = computed<Published[]>(() => { const d = openDataset.value; return d ? d.places
  .map((p) => ({ dataset: d, place: p, review: reviewOf(d.id, p) })).filter((x) => x.review.decision === 'confirmed') : [] })
const removed = computed<Removed[]>(() => { const d = openDataset.value; return d ? d.tasks
  .map((_t, i) => ({ dataset: d, index: i, review: state.removals[`${d.id}|${i}`] }))
  .filter((x) => x.review?.decision === 'confirmed') : [] })
const stamp = () => new Date().toISOString().slice(0, 16).replace(/[-:T]/g, '')
function pub(kind: 'geojson' | 'csv' | 'osm' | 'kml' | 'gpx' | 'shp' | 'xlsx' | 'all') {
  const base = `poi-published-${openDataset.value?.session.id || 'session'}-${stamp()}`
  const [c, r] = [confirmed.value, removed.value]
  if (kind === 'geojson') download(`${base}.geojson`, geojson(c, r), 'application/geo+json')
  if (kind === 'csv') download(`${base}.csv`, csv(c, r), 'text/csv')
  if (kind === 'osm') download(`${base}.osm`, osm(c), 'application/xml')
  if (kind === 'kml') download(`${base}.kml`, kml(c, r, openDataset.value?.session.name || base), 'application/vnd.google-earth.kml+xml')
  if (kind === 'gpx') download(`${base}.gpx`, gpx(c, r), 'application/gpx+xml')
  if (kind === 'shp') download(`${base}-shapefile.zip`, shapefile(c, r, base), 'application/zip')
  if (kind === 'xlsx') download(`${base}.xlsx`, xlsx(c, r), 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet')
  if (kind === 'all') download(`${base}-all.zip`, allFormats(c, r, base), 'application/zip')
}
/** The kept (and cropped) photos of the confirmed places, named as in the photos column, with places.geojson. */
async function pubPhotos() {
  const d = openDataset.value
  if (!d) return
  const files: Record<string, Uint8Array> = { 'places.geojson': strToU8(geojson(confirmed.value, removed.value)) }
  for (const x of confirmed.value) {
    const names = exportNames(x.place, x.review)
    for (const [k, p] of keptPhotos(x.place, x.review).entries()) {
      const b = await photoBlob(d.id, p.show)
      if (b) files[names[k]] = new Uint8Array(await b.arrayBuffer())
    }
  }
  download(`poi-published-${d.session.id}-${stamp()}-photos.zip`, zipSync(files, { level: 0 }), 'application/zip')
}
/** The reviewed session as one package for the VMAP step: project.json, tasks.jsonl, README.md and the photos
 *  (project.ts; docs/pipeline.md). */
async function pubProject() {
  const d = openDataset.value
  if (!d) return
  const reviews = Object.fromEntries(d.places.map((p) => [p.id, reviewOf(d.id, p)]))
  const removals = Object.fromEntries(d.tasks.map((_t, i) => [i, state.removals[`${d.id}|${i}`]]).filter(([, r]) => r))
  const exportedAt = new Date().toISOString()
  const input = { dataset: d, reviews, removals, reviewer: state.reviewer, exportedAt }
  const blobs: Record<string, Uint8Array> = {}
  const missing = new Set<string>()
  for (const f of buildProject(input).files) {
    const b = await photoBlob(d.id, f.from)
    if (b) blobs[f.path] = new Uint8Array(await b.arrayBuffer())
    else missing.add(f.from)
  }
  const pj = buildProject({ ...input, missing })
  const files: Record<string, Uint8Array | [Uint8Array, { level: 0 | 6 }]> = {
    'README.md': [strToU8(pj.readme), { level: 6 }], 'project.json': [strToU8(pj.json), { level: 6 }],
    'tasks.jsonl': [strToU8(pj.tasksJsonl), { level: 6 }],
  }
  for (const f of pj.files) if (blobs[f.path]) files[f.path] = blobs[f.path]
  download(projectName(d.session.id, exportedAt), zipSync(files, { level: 0 }), 'application/zip')
}
function exportTasks(ds: Dataset) {
  download(`poi-tasks-${ds.session.id}-${stamp()}.geojson`, tasksGeojson([ds], state.reviews, state.removals, state.reviewer), 'application/geo+json')
}

const DECISION = { todo: 'decTodo', confirmed: 'decConfirmed', rejected: 'decRejected', visit: 'decVisit' } as const
function cover(p: Place) { const d = openDataset.value; return d ? keptPhotos(p, reviewOf(d.id, p))[0]?.show : undefined }
</script>

<template>
  <!-- #/admin: the cloud server's admin page, on its own (reached by address only) -->
  <AdminPanel v-if="String(route.name ?? '').startsWith('admin')" :url="conn.cfg.url" />
  <SettingsPage v-else-if="String(route.name ?? '').startsWith('settings')" />
  <div v-else :class="['app', { sess: !!state.open, peek: mapPeek && !!(openPlace || openTask) }]">
    <aside class="side">
      <header class="sidehead">
        <div class="brand"><span class="logo" aria-hidden="true"><svg viewBox="0.5 0.9 10.5 13.8"><path fill="#0a0a0a" d="M8.80469 1.63863L10.8018 5.87398L10.9023 6.08785L10.8008 6.30172L7.12598 14.0654L6.87207 14.6035L6.38574 14.2597L4.87402 13.1914L4.38867 12.8486L2.39355 11.4384L2.04883 11.1943L2.23145 10.8144C2.51433 10.2234 2.51594 9.47976 2.23828 8.89059C1.99917 8.38367 1.60549 8.09033 1.20996 8.0234L1.04102 8.00875H0.5V4.18844H0.500977V1.36617H1.04297L8.35059 1.35152L8.66895 1.35055L8.80469 1.63863ZM3.70898 5.18258C4.28475 5.6568 4.77774 6.28836 5.13867 7.05367C5.44546 7.70427 5.63106 8.39754 5.70801 9.09664L7.56445 5.17379L3.70898 5.18258Z"/></svg></span> {{ t('appName') }}</div>
        <RouterLink class="ghost gear" :to="to.settings()" :title="t('settings')" :aria-label="t('settings')">
          ⚙<span v-if="conn.status === 'error' || conn.status === 'none'" class="dot error"></span></RouterLink>
      </header>
      <div v-if="busy" class="banner">{{ busy }}</div>
      <div v-if="state.missing" class="banner err"><span>{{ t('missingLink', { what: state.missing }) }}</span> <button class="ghost" @click="state.missing = ''">✕</button></div>
      <div v-if="state.error" class="banner err">{{ state.error }} <button class="ghost" @click="state.error = ''">✕</button></div>
      <div v-if="conn.status === 'error'" class="banner err">
        <span>{{ conn.keyRefused ? t('keyRefused') : t('serverUnreachable', { why: conn.error }) }}</span>
        <RouterLink class="small" :to="to.settings()">{{ t('settings') }}</RouterLink></div>
      <div v-else-if="connected && !canRead" class="banner err">
        <span>{{ t('keyNoRead') }}</span> <RouterLink class="small" :to="to.settings()">{{ t('settings') }}</RouterLink></div>
      <div v-else-if="conn.listError" class="banner err">{{ conn.listError }} <button class="ghost small" @click="refresh()">{{ t('retry') }}</button></div>

      <!-- the session list: stage chips, search, filters, rows by day -->
      <template v-if="!openDataset">
        <section class="pane list">
          <div v-if="state.loading || conn.status === 'checking'" class="empty soft">{{ t('loading') }}</div>
          <div v-else-if="conn.status === 'none' && !allRows.length" class="empty">
            <div class="ico">☁</div>
            <b>{{ t('connectTitle') }}</b>
            <p>{{ t('connectText') }}</p>
            <RouterLink class="button primary" :to="to.settings()">{{ t('connectButton') }}</RouterLink>
          </div>
          <div v-else-if="!allRows.length" class="empty soft">
            <b>{{ conn.listing ? t('loading') : t('noSessions') }}</b>
            <p v-if="!conn.listing" class="muted">{{ t('noSessionsText') }}</p>
          </div>
          <template v-else>
            <div class="ltop">
              <nav class="lstages" :aria-label="t('stagesAria')">
                <button :class="{ on: !lf.stage }" :aria-pressed="!lf.stage" @click="lf.stage = ''">{{ t('ls_all') }} <span class="n">{{ counts.all }}</span></button>
                <button v-for="s in LIST_STAGES" :key="s" :class="['ls-' + s, { on: lf.stage === s }]" :aria-pressed="lf.stage === s"
                  @click="lf.stage = lf.stage === s ? '' : s">{{ t('ls_' + s) }} <span class="n">{{ counts[s] }}</span></button>
              </nav>
              <div class="ltools">
                <input v-model="lf.q" type="search" class="lsearch" :placeholder="t('listSearch')" :aria-label="t('listSearch')" />
                <button :class="['secondary', 'small', 'lfbtn', { on: filtersOpen || extraFilters(lf) }]" :aria-expanded="filtersOpen"
                  @click="filtersOpen = !filtersOpen">{{ t('filterBtn') }}<span v-if="extraFilters(lf)" class="n">{{ extraFilters(lf) }}</span></button>
              </div>
              <div v-if="filtersOpen" class="lfilters">
                <select v-model="lf.kind" :aria-label="t('allTargets')"><option value="">{{ t('allTargets') }}</option>
                  <option v-for="k in LIST_KINDS" :key="k" :value="k">{{ KIND_ICON[k] }} {{ t('kind_' + k) }}</option></select>
                <select v-model="lf.period" :aria-label="t('period_')"><option v-for="p in PERIODS" :key="p" :value="p">{{ t('period_' + p) }}</option></select>
                <select v-model="lf.rider" :aria-label="t('allRiders')"><option value="">{{ t('allRiders') }}</option>
                  <option v-for="n in riderList" :key="n" :value="n">{{ n }}</option></select>
                <select v-model="lf.sort" :aria-label="t('sortAria')"><option v-for="s in SORTS" :key="s" :value="s">{{ t('sort_' + s) }}</option></select>
              </div>
            </div>
            <div v-if="!listed.length" class="empty soft">
              <b>{{ t('noSessionMatch') }}</b>
              <p class="muted">{{ t('sessionsHidden', { n: allRows.length }) }}</p>
              <button class="secondary" @click="clearListFilters">{{ t('clearFilters') }}</button>
            </div>
            <template v-for="g in groups" :key="g.key ?? 'all'">
              <h3 v-if="g.key != null" class="dayhead">{{ groupTitle(g.key) }} <span class="n">{{ g.items.length }}</span></h3>
              <article v-for="f in g.items" :key="f.key" :data-sid="f.key"
                :class="['ses', f.row.state, { busy: conn.pulling === f.key, hot: hot === f.key || picked === f.key }]"
                @mouseenter="hot = f.key" @mouseleave="hot = ''">
                <div class="body" :role="f.row.state === 'waiting' ? undefined : 'button'" :tabindex="f.row.state === 'waiting' ? undefined : 0"
                  @click="openRow(f.row)" @keydown.enter="openRow(f.row)">
                  <div class="sline"><b class="sname" :title="f.name">{{ f.name }}</b>
                    <span :class="['chip', 'lst', 'ls-' + f.stage]">{{ t('ls_' + f.stage) }}</span></div>
                  <div class="muted sline2">
                    <span>{{ rowWhen(f.row) }}</span>
                    <span :title="kindLabel(f.row)">{{ KIND_ICON[f.kind] }} {{ t('kind_' + f.kind) }}</span>
                    <span v-if="f.rider">{{ f.rider }}</span>
                    <span v-if="rowSize(f.row)">{{ rowSize(f.row) }}</span>
                  </div>
                  <div class="stats">
                    <template v-if="dsOf(f.row)">
                      <span class="chip new" v-if="dsOf(f.row)!.places.filter((p) => gstat(p) === 'new').length">{{ t('newToGoogle', { n: dsOf(f.row)!.places.filter((p) => gstat(p) === 'new').length }) }}</span>
                      <span class="chip todo" v-if="dsOf(f.row)!.tasks.length">{{ t('nTasks', { n: dsOf(f.row)!.tasks.length }) }}</span>
                      <span class="chip" v-if="f.row.state === 'local' && !f.row.item && connected">{{ t('onlyHere') }}</span>
                    </template>
                    <template v-else>
                      <span v-if="conn.pulling === f.key" class="chip">{{ t('downloadingShort') }}</span>
                      <span v-else-if="f.row.state === 'waiting' && conn.processing[f.key]" class="chip visit"
                        :title="t('processingTitle', procParams(f.row))">{{ t('processingBy', procParams(f.row)) }}</span>
                      <button v-if="f.row.state === 'waiting' && conn.processing[f.key] && (canRelease || conn.processing[f.key].by.id === conn.me?.id)"
                        class="ghost small" :title="t('stopProcessingTitle', procParams(f.row))" @click.stop="stopProc(f.row)">{{ t('stopProcessing') }}</button>
                      <a v-else-if="f.row.state === 'waiting'" class="ghost small guide" href="./agents.md" target="_blank" rel="noopener"
                        :title="t('agentGuideTitle')" @click.stop>{{ t('agentGuide') }}</a>
                      <span v-else class="chip ok">{{ t('onServer', { size: humanSize(f.row.item?.size ?? null) }) }}</span>
                    </template>
                    <span v-if="lockLabel(f.row)" :class="['chip', lockLabel(f.row)!.cls]" :title="lockLabel(f.row)!.title">{{ lockLabel(f.row)!.text }}</span>
                  </div>
                  <StageBar v-if="dsOf(f.row)" :ds="dsOf(f.row)!" />
                  <!-- uploaded, not processed yet: "Captured", the stepper on its first step -->
                  <StageBar v-else-if="f.row.state === 'waiting'" :ds="null" />
                </div>
                <button v-if="f.row.state === 'updated' && f.row.item" class="secondary small" :disabled="!!conn.pulling" :title="t('updateTitle')" @click="fetchSession(f.row.item)">{{ t('update') }}</button>
                <button v-if="dsOf(f.row)" class="ghost del" :title="t('removeSession')" :aria-label="t('removeSession')" @click="del(dsOf(f.row)!)">🗑</button>
              </article>
            </template>
          </template>
        </section>

        <footer v-if="connected" class="importbar">
          <button class="secondary small" :disabled="conn.listing" @click="refresh()">↻ {{ t('cloudRefresh') }}</button>
          <span class="muted small">{{ t('connectedAs', { name: conn.me?.name }) }}</span>
        </footer>
      </template>

      <!-- one session: its places, and its tasks in a to-do section under them -->
      <template v-else>
        <div class="sesshead">
          <div class="row">
            <button class="ghost back" @click="router.push(toList())">‹ {{ t('back') }}</button>
            <button v-if="openDataset.source === 'result'" class="primary small" :title="confirmed.length || removed.length ? '' : t('nothingConfirmed')" @click="publishOpen = true">{{ t('publish') }}{{ confirmed.length + removed.length ? ` (${confirmed.length + removed.length})` : '' }}</button>
          </div>
          <h1>{{ openDataset.session.name || openDataset.session.id }}</h1>
          <div class="muted small">{{ (openDataset.session.startedAt || '').slice(0, 10) }} · {{ kindLabel(openDataset) }}
            <span v-if="targetLabel(openDataset)" class="chip target" :title="t('targetTitle')">{{ targetLabel(openDataset) }}</span></div>
          <StageBar :ds="openDataset" />
          <ReviewBar :ds="openDataset" />
        </div>

        <section class="pane">
          <div v-if="!openDataset.places.length" class="empty soft">
            <template v-if="openDataset.source === 'raw' && openDataset.session.kind !== 'poi'">
              <b>{{ t('notProcessedTitle') }}</b>
              <p>{{ t('notProcessedText') }}</p>
              <p class="muted small">{{ t('notProcessedHint') }}</p>
            </template>
            <template v-else-if="openDataset.session.kind === 'poi'">
              <b>{{ t('noPlacesPoi') }}</b>
              <p class="muted">{{ t('noPlacesPoiText') }}</p>
            </template>
            <template v-else>
              <b>{{ t('noPlacesFound') }}</b>
              <p class="muted">{{ t('noPlacesFoundText') }}</p>
            </template>
          </div>
          <template v-else>
            <nav v-if="levelCounts.length" class="seg levels" :aria-label="t('levelsAria')">
              <button :class="{ on: !state.level }" @click="state.level = ''">{{ t('levelAll') }} <span class="n">{{ openDataset.places.length }}</span></button>
              <button v-for="x in levelCounts" :key="x.l" :class="{ on: state.level === x.l }" @click="state.level = x.l">{{ x.l }} <span class="n">{{ x.n }}</span></button>
            </nav>
            <p v-if="openDataset.building?.name" class="muted small building">{{ t('inBuilding', { name: openDataset.building.name }) }}</p>
            <nav v-if="layerCounts.length > 1" class="seg layers">
              <button :class="{ on: !state.layer }" @click="state.layer = ''">{{ t('layerAll') }} <span class="n">{{ openDataset.places.length }}</span></button>
              <button v-for="x in layerCounts" :key="x.l" :class="['ly-' + x.l, { on: state.layer === x.l }]" @click="state.layer = x.l">{{ t('layer_' + x.l) }} <span class="n">{{ x.n }}</span></button>
            </nav>
            <div class="filters">
              <input v-model="q" :placeholder="t('search')" />
              <select v-model="fDecision"><option value="">{{ t('anyDecision') }}</option><option value="todo">{{ t('toReview') }}</option><option value="confirmed">{{ t('confirmedF') }}</option><option value="visit">{{ t('needsVisit') }}</option><option value="rejected">{{ t('rejectedF') }}</option></select>
              <select v-if="!state.layer || state.layer === 'place'" v-model="fGoogle"><option value="">{{ t('googleAny') }}</option><option value="found">{{ t('onGoogle') }}</option><option value="new">{{ t('notOnGoogle') }}</option><option value="todo">{{ t('notChecked') }}</option></select>
            </div>
            <div v-if="!places.length" class="empty soft">
              <b>{{ t('noPlaceMatch') }}</b>
              <p class="muted">{{ t('placesHidden', { n: openDataset.places.length }) }}</p>
              <button class="secondary" @click="clearPlaceFilters">{{ t('clearFilters') }}</button>
            </div>
            <button v-for="{ p, i } in places" :key="p.id" :class="['prow', { sel: state.place === p.id }]" @click="selectPlace(openDataset.id, p.id)">
              <Photo class="th" :ds="openDataset.id" :path="cover(p)" />
              <div class="pbody">
                <b>{{ i + 1 }}. {{ p.name }}</b>
                <div class="muted"><span v-if="layerOf(p) !== 'place'" :class="['lymark', 'ly-' + layerOf(p)]"></span>{{ placeLine(p) }}</div>
                <span :class="['chip', reviewOf(openDataset.id, p).decision]">{{ t(DECISION[reviewOf(openDataset.id, p).decision]) }}</span>
                <span v-for="h in placeHints(p, reviewOf(openDataset.id, p), { seq: seqFlags.get(p.id), dupes: dupes.get(p.id), list: true })" :key="h.key"
                  :class="['chip', 'hint', h.cls]" :title="h.title">{{ h.text }}</span>
                <span v-if="gstat(p) === 'new'" class="chip new">{{ t('notOnGoogleChip') }}</span>
                <span v-if="missingStreet(p, reviewOf(openDataset.id, p))" class="chip new">{{ t('noStreet') }}</span>
                <span v-else-if="reviewOf(openDataset.id, p).decision === 'confirmed' && pendingSuggestions(p, reviewOf(openDataset.id, p)).length" class="chip visit"
                  :title="pendingSuggestions(p, reviewOf(openDataset.id, p)).map((k) => t('field.' + k)).join(', ')">{{ t('suggestionsOpen', { n: pendingSuggestions(p, reviewOf(openDataset.id, p)).length }) }}</span>
                <span v-if="p.status !== 'open'" class="chip rm">{{ t('status_' + p.status) }}</span>
              </div>
            </button>
          </template>
          <!-- the session's tasks: visits for Place capture, removals to confirm -->
          <div :class="['todo', { open: todoOpen }]">
            <button class="todohead" :aria-expanded="todoOpen" @click="todoOpen = !todoOpen">
              <span class="caret" aria-hidden="true">{{ todoOpen ? '▾' : '▸' }}</span> {{ t('todoSection', { n: openDataset.tasks.length }) }}</button>
            <template v-if="todoOpen">
              <p v-if="!openDataset.tasks.length" class="muted small">{{ t(openDataset.source === 'raw' ? 'noTasksSessionRaw' : 'noTasksSessionDone') }}</p>
              <template v-else>
                <div class="row"><span class="muted small">{{ t('sessionTasksHint') }}</span>
                  <button class="secondary small" :title="t('exportTitle')" @click="exportTasks(openDataset)">{{ t('export') }}</button></div>
                <TaskRow v-for="(tk, i) in openDataset.tasks" :key="i" :ds="openDataset" :task="tk" :index="i" @open="selectTask(openDataset.id, i)" @open-place="(id: string) => selectPlace(openDataset!.id, id)" />
              </template>
            </template>
          </div>
        </section>
      </template>
    </aside>

    <main class="mapwrap">
      <MapView ref="mapRef" :only="listedIds" :routes="listedRoutes" :hot="hot || picked" @select="selectPlace" @move-pin="movePin"
        @pick="pickRow" @hover="(id: string) => (hot = id)" />
      <div v-if="!state.loading && !state.datasets.length && !listedRoutes.length" class="mapnote">
        <b>{{ t('mapEmptyTitle') }}</b><br />{{ t('mapEmptyText') }}
      </div>
    </main>

    <aside v-if="openDataset && (openPlace || (openTask && state.task != null))" ref="detailEl" :class="['detail', { peek: mapPeek }]"
      @touchstart.passive="touchStart" @touchend="touchEnd" @touchcancel="touch = null">
      <!-- phone and tablet only (style.css): back, where we are, the map, previous / next -->
      <nav class="dnav">
        <button class="ghost back" @click="closeDetail">‹ {{ openPlace ? t('places') : t('tasks') }}</button>
        <span class="dpos muted small">{{ stepPos + 1 }} / {{ stepList.length }}</span>
        <button :class="['secondary', 'small', { on: mapPeek }]" :aria-pressed="mapPeek" @click="mapPeek = !mapPeek">{{ t(mapPeek ? 'hideMap' : 'showMap') }}</button>
        <button class="secondary dstep" :disabled="!canStep(-1)" :title="t(openPlace ? 'prevPlace' : 'prevTask')" :aria-label="t(openPlace ? 'prevPlace' : 'prevTask')" @click="stepOpen(-1)">‹</button>
        <button class="secondary dstep" :disabled="!canStep(1)" :title="t(openPlace ? 'nextPlace' : 'nextTask')" :aria-label="t(openPlace ? 'nextPlace' : 'nextTask')" @click="stepOpen(1)">›</button>
      </nav>
      <PlaceReview v-if="openPlace" ref="reviewRef" :ds="openDataset" :place="openPlace" @close="router.push(to.session(openDataset.id))" @next="nextPlace" />
      <TaskDetail v-else-if="openTask && state.task != null" :ds="openDataset" :task="openTask" :index="state.task" @close="router.push(to.session(openDataset.id))" @open-place="(id: string) => selectPlace(openDataset!.id, id)" />
    </aside>

    <div v-if="publishOpen && openDataset" class="modal" @click.self="publishOpen = false">
      <div class="dialog">
        <h2>{{ t('publishTitle', { name: openDataset.session.name || openDataset.session.id }) }}</h2>
        <p v-if="confirmed.length || removed.length">{{ t('publishCount', { places: confirmed.length, removals: removed.length }) }}</p>
        <div v-else class="empty soft left">
          <b>{{ t('nothingToPublish') }}</b>
          <p class="muted">{{ t('nothingToPublishText') }}</p>
        </div>
        <template v-if="confirmed.length || removed.length">
          <p class="muted small">{{ t('publishRule') }}</p>
          <div class="pubs">
            <button class="primary" @click="pub('geojson')">GeoJSON</button>
            <button class="primary" @click="pub('csv')">CSV</button>
            <button class="primary" :disabled="!confirmed.length" @click="pub('osm')">OpenStreetMap (.osm)</button>
            <button class="primary" @click="pub('kml')">KML (Google Earth)</button>
            <button class="primary" @click="pub('gpx')">GPX</button>
            <button class="primary" @click="pub('shp')">Shapefile (.zip)</button>
            <button class="primary" @click="pub('xlsx')">Excel (.xlsx)</button>
            <button class="primary" @click="pub('all')">{{ t('pubAll') }}</button>
            <button class="primary" :disabled="!confirmed.length" @click="pubPhotos">{{ t('pubPhotos') }}</button>
            <button class="primary" @click="pubProject">{{ t('pubProject') }}</button>
          </div>
          <p class="muted small">{{ t('pubProjectNote') }}</p>
          <p class="muted small">{{ t('pubPhotosNote') }}</p>
          <p class="muted small">{{ t('osmNote') }}</p>
          <p class="muted small">{{ t('pubFormatsNote') }}</p>
        </template>
        <button class="ghost" @click="publishOpen = false">{{ t('close') }}</button>
      </div>
    </div>
  </div>
</template>
