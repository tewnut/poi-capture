<script setup lang="ts">
// Left panel: the sessions on the project's server (or all tasks across them); open a session to review its places
// and tasks (downloaded into this browser the first time, connection.ts). The map is in the middle, the selected
// place or task on the right. The address (#/...) decides what is open; #/settings and #/admin are pages of their own.
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MapView from './components/MapView.vue'
import PlaceReview from './components/PlaceReview.vue'
import TaskDetail from './components/TaskDetail.vue'
import TaskRow from './components/TaskRow.vue'
import StageBar from './components/StageBar.vue'
import Photo from './components/Photo.vue'
import AdminPanel from './components/AdminPanel.vue'
import SettingsPage from './components/SettingsPage.vue'
import { state, load, remove, reviewOf, photoBlob, gstat, openDataset, openPlace, openTask, stageOf, STAGES } from './store'
import { to } from './router'
import { t } from './i18n'
import { keptPhotos, exportNames } from './photos'
import { zipSync, strToU8 } from 'fflate'
import { geojson, csv, osm, tasksGeojson, download, type Published, type Removed } from './publish'
import { buildProject, projectName } from './project'
import { missingStreet, pendingSuggestions } from './fields'
import { humanSize, type CloudItem } from './cloud'
import { conn, connected, canRead, connect, refresh, rows, pull, type Row } from './connection'
import { swipeStep, neighbour, blocksSwipe, type Step } from './swipe'
import type { Dataset, Place } from './types'

type View = 'sessions' | 'allTasks' | 'places' | 'tasks'
const view = ref<View>('sessions')
const busy = ref('')
const publishOpen = ref(false)
const reviewRef = ref<InstanceType<typeof PlaceReview>>()

// filters
const fStage = ref('')
const fKind = ref('')
const fHold = ref('')
const q = ref('')
const fDecision = ref('')
const fGoogle = ref('')

const route = useRoute()
const router = useRouter()

/** #/ sessions · #/tasks all tasks · #/s/<id> places · #/s/<id>/tasks · #/s/<id>/p/<place> · #/s/<id>/t/<task> */
function applyRoute() {
  if (state.loading) return
  const ds = route.params.ds as string | undefined
  state.missing = ''
  if (ds && !state.datasets.some((d) => d.id === ds)) {
    state.open = null; state.place = null; state.task = null
    view.value = 'sessions'
    // a link to a session this browser has not downloaded yet: from the server, then open the link
    const item = rows(conn.items, state.datasets).find((r) => r.sessionId === ds)?.item
    if (item) { if (!conn.pulling) fetchSession(item, route.fullPath) }
    else if (conn.items || conn.status === 'error' || conn.status === 'none') state.missing = ds
    return
  }
  state.place = null; state.task = null
  switch (route.name) {
    case 'session': state.open = ds!; view.value = 'places'; break
    case 'sessionTasks': state.open = ds!; view.value = 'tasks'; break
    case 'place': {
      state.open = ds!; view.value = 'places'
      const id = route.params.place as string
      state.place = openDataset.value?.places.some((p) => p.id === id) ? id : null
      if (!state.place) state.missing = `${ds} / place ${id}`
      break
    }
    case 'task': {
      state.open = ds!; view.value = 'tasks'
      const i = Number(route.params.task)
      state.task = Number.isInteger(i) && openDataset.value?.tasks[i] ? i : null
      if (state.task == null) state.missing = `${ds} / task ${route.params.task}`
      break
    }
    case 'tasks': state.open = null; view.value = 'allTasks'; break
    default: state.open = null; view.value = 'sessions'
  }
  if (state.open) state.shown.add(state.open)
}
watch(fKind, (k) => { if (k === 'poi') fHold.value = '' })
watch(() => route.fullPath, applyRoute)
watch(() => state.loading, applyRoute)
watch(() => conn.items, applyRoute)
watch(() => state.open, () => { q.value = ''; fDecision.value = ''; fGoogle.value = '' })
onMounted(async () => { await load(); await connect() })

// sessions: the server's (processed, or still waiting for the agent) and the ones downloaded into this browser
const dsOf = (r: Row) => state.datasets.find((d) => d.id === r.sessionId) || null
/** A session not downloaded yet is processed (or, waiting for the agent, captured); a downloaded one says itself. */
function rowStage(r: Row) { const d = dsOf(r); return d ? stageOf(d).stage : r.state === 'waiting' ? 'captured' : 'processed' }
function rowHolding(r: Row) { const d = dsOf(r); return d ? d.session.holding || 'mount' : String(r.item?.metadata.holding || r.raw?.metadata.holding || 'mount') }
const allRows = computed(() => rows(conn.items, state.datasets))
const sessions = computed(() => allRows.value.filter((r) =>
  (!fStage.value || rowStage(r) === fStage.value) &&
  (!fKind.value || (r.kind || 'sweep') === fKind.value) &&
  // mounted / in hand is a sweep property: a Place capture never matches it
  (!fHold.value || (r.kind !== 'poi' && rowHolding(r) === fHold.value))))
function clearSessionFilters() { fStage.value = ''; fKind.value = ''; fHold.value = '' }
const stageCount = (s: string) => allRows.value.filter((r) => rowStage(r) === s).length
function kindLabel(x: Row | Dataset) {
  const kind = 'session' in x ? x.session.kind : x.kind
  const hold = 'session' in x ? x.session.holding || 'mount' : rowHolding(x)
  return t(kind === 'poi' ? 'kindPoi' : hold === 'hand' ? 'kindSweepHand' : 'kindSweepMount')
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
function openRow(r: Row) {
  if (dsOf(r)) router.push(to.session(r.sessionId))
  else if (r.item && !conn.pulling) fetchSession(r.item)
}
const STAGE_NAME = { captured: 'stageCaptured', processed: 'stageProcessed', reviewed: 'stageReviewed' } as const
function toggleShown(id: string) { state.shown.has(id) ? state.shown.delete(id) : state.shown.add(id) }
async function del(d: Dataset) {
  if (!window.confirm(t('removeConfirm', { name: d.session.name || d.id }))) return
  await remove(d.id)
  router.push(to.sessions())
}

// one session
const places = computed(() => {
  const d = openDataset.value
  if (!d) return []
  const s = q.value.trim().toLowerCase()
  return d.places.map((p, i) => ({ p, i })).filter(({ p }) => {
    const r = reviewOf(d.id, p)
    return (!s || `${p.name} ${p.category || ''} ${p.fields.signText?.value || ''}`.toLowerCase().includes(s)) &&
      (!fDecision.value || r.decision === fDecision.value) && (!fGoogle.value || gstat(p) === fGoogle.value)
  })
})
function clearPlaceFilters() { q.value = ''; fDecision.value = ''; fGoogle.value = '' }
function selectPlace(ds: string, id: string) { router.push(to.place(ds, id)) }
function selectTask(ds: string, i: number) { router.push(to.task(ds, i)) }
function nextPlace() {
  const d = openDataset.value
  if (!d) return
  const i = d.places.findIndex((p) => p.id === state.place)
  const next = d.places.slice(i + 1).find((p) => reviewOf(d.id, p).decision === 'todo') || d.places.find((p) => reviewOf(d.id, p).decision === 'todo')
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
  if (d) router.push(openPlace.value ? to.session(d.id) : to.sessionTasks(d.id))
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

// tasks across sessions (shown on the map), grouped by session
const taskGroups = computed(() => state.datasets.filter((d) => state.shown.has(d.id) && d.tasks.length)
  .map((d) => ({ d, items: d.tasks.map((t, i) => ({ t, i })) })))
const shownTasks = computed(() => taskGroups.value.reduce((n, g) => n + g.items.length, 0))
const allTasks = computed(() => state.datasets.reduce((n, d) => n + d.tasks.length, 0))
const shownCount = computed(() => state.datasets.filter((d) => state.shown.has(d.id)).length)

// publish
// publish: the open session's confirmed places and removals
const confirmed = computed<Published[]>(() => { const d = openDataset.value; return d ? d.places
  .map((p) => ({ dataset: d, place: p, review: reviewOf(d.id, p) })).filter((x) => x.review.decision === 'confirmed') : [] })
const removed = computed<Removed[]>(() => { const d = openDataset.value; return d ? d.tasks
  .map((_t, i) => ({ dataset: d, index: i, review: state.removals[`${d.id}|${i}`] }))
  .filter((x) => x.review?.decision === 'confirmed') : [] })
const stamp = () => new Date().toISOString().slice(0, 16).replace(/[-:T]/g, '')
function pub(kind: 'geojson' | 'csv' | 'osm') {
  const base = `poi-published-${openDataset.value?.session.id || 'session'}-${stamp()}`
  if (kind === 'geojson') download(`${base}.geojson`, geojson(confirmed.value, removed.value), 'application/geo+json')
  if (kind === 'csv') download(`${base}.csv`, csv(confirmed.value, removed.value), 'text/csv')
  if (kind === 'osm') download(`${base}.osm`, osm(confirmed.value), 'application/xml')
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
function exportTasks(ds?: Dataset) {
  const list = ds ? [ds] : state.datasets.filter((d) => state.shown.has(d.id))
  download(`poi-tasks-${ds ? ds.session.id + '-' : ''}${stamp()}.geojson`, tasksGeojson(list, state.reviews, state.removals, state.reviewer), 'application/geo+json')
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
        <div class="brand"><span class="logo">◉</span> {{ t('appName') }}</div>
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

      <!-- sessions / all tasks -->
      <template v-if="view === 'sessions' || view === 'allTasks'">
        <nav class="seg">
          <button :class="{ on: view === 'sessions' }" @click="router.push(to.sessions())">{{ t('sessions') }} <span class="n">{{ allRows.length }}</span></button>
          <button :class="{ on: view === 'allTasks' }" @click="router.push(to.tasks())">{{ t('allTasks') }} <span class="n">{{ allTasks }}</span></button>
        </nav>

        <section v-if="view === 'sessions'" class="pane">
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
            <div class="filters">
              <select v-model="fStage"><option value="">{{ t('anyStage') }}</option><option v-for="s in STAGES" :key="s.key" :value="s.key">{{ t(STAGE_NAME[s.key]) }} ({{ stageCount(s.key) }})</option></select>
              <select v-model="fKind"><option value="">{{ t('allKinds') }}</option><option value="sweep">{{ t('sweep') }}</option><option value="poi">{{ t('poi') }}</option></select>
              <select v-if="fKind !== 'poi'" v-model="fHold"><option value="">{{ t('mountAndHand') }}</option><option value="mount">{{ t('mounted') }}</option><option value="hand">{{ t('inHand') }}</option></select>
            </div>
            <div v-if="!sessions.length" class="empty soft">
              <b>{{ t('noSessionMatch') }}</b>
              <p class="muted">{{ t('sessionsHidden', { n: allRows.length }) }}</p>
              <button class="secondary" @click="clearSessionFilters">{{ t('clearFilters') }}</button>
            </div>
            <article v-for="r in sessions" :key="r.key" :class="['ses', r.state, { busy: conn.pulling === r.sessionId }]">
              <input v-if="dsOf(r)" type="checkbox" :checked="state.shown.has(r.sessionId)" :title="t('showOnMap')" :aria-label="t('showOnMap')" @change="toggleShown(r.sessionId)" />
              <span v-else class="cbspace"></span>
              <div class="body" :role="r.state === 'waiting' ? undefined : 'button'" @click="openRow(r)">
                <b>{{ r.name }}</b>
                <div class="muted">{{ (r.date || '').slice(0, 10) || t('noDate') }} · {{ kindLabel(r) }}{{ (r.item || r.raw)?.by ? ' · ' + (r.item || r.raw)!.by!.name : '' }}</div>
                <template v-if="dsOf(r)">
                  <div class="stats">
                    <span>{{ dsOf(r)!.places.length ? t('nPlaces', { n: dsOf(r)!.places.length }) : t('noPlacesYet') }}</span>
                    <span class="chip new" v-if="dsOf(r)!.places.filter((p) => gstat(p) === 'new').length">{{ t('newToGoogle', { n: dsOf(r)!.places.filter((p) => gstat(p) === 'new').length }) }}</span>
                    <span class="chip todo" v-if="dsOf(r)!.tasks.length">{{ t('nTasks', { n: dsOf(r)!.tasks.length }) }}</span>
                    <span class="chip" v-if="r.state === 'local' && !r.item && connected">{{ t('onlyHere') }}</span>
                  </div>
                  <StageBar :ds="dsOf(r)!" />
                </template>
                <div v-else class="stats">
                  <span v-if="conn.pulling === r.sessionId" class="chip">{{ t('downloadingShort') }}</span>
                  <span v-else-if="r.state === 'waiting'" class="chip">{{ t('waitingAgent') }}</span>
                  <span v-else class="chip ok">{{ t('onServer', { size: humanSize(r.item?.size ?? null) }) }}</span>
                </div>
              </div>
              <button v-if="r.state === 'updated' && r.item" class="secondary small" :disabled="!!conn.pulling" :title="t('updateTitle')" @click="fetchSession(r.item)">{{ t('update') }}</button>
              <button v-if="dsOf(r)" class="ghost del" :title="t('removeSession')" :aria-label="t('removeSession')" @click="del(dsOf(r)!)">🗑</button>
            </article>
          </template>
        </section>

        <section v-else class="pane">
          <div v-if="!state.datasets.length" class="empty soft">
            <b>{{ t('noTasksYet') }}</b>
            <p class="muted">{{ t('noTasksYetText') }}</p>
          </div>
          <div v-else-if="!allTasks" class="empty soft">
            <b>{{ t('nothingToDo') }}</b>
            <p class="muted">{{ t('nothingToDoText') }}</p>
          </div>
          <div v-else-if="!shownTasks" class="empty soft">
            <b>{{ t('noTasksShown') }}</b>
            <p class="muted">{{ t('tasksHidden', { n: allTasks }) }}</p>
            <button class="secondary" @click="router.push(to.sessions())">{{ t('goSessions') }}</button>
          </div>
          <template v-else>
            <div class="row"><span class="muted small">{{ t('allTasksHint') }}</span>
              <button class="secondary small" :title="t('exportTitle')" @click="exportTasks()">{{ t('export') }}</button></div>
            <div v-for="g in taskGroups" :key="g.d.id" class="group">
              <button class="ghead" @click="router.push(to.sessionTasks(g.d.id))"><b>{{ g.d.session.name }}</b> <span class="muted">{{ t('nTasks', { n: g.items.length }) }} ›</span></button>
              <TaskRow v-for="it in g.items" :key="it.i" :ds="g.d" :task="it.t" :index="it.i" @open="selectTask(g.d.id, it.i)" @open-place="(id: string) => selectPlace(g.d.id, id)" />
            </div>
          </template>
        </section>

        <footer v-if="view === 'sessions' && connected" class="importbar">
          <button class="secondary small" :disabled="conn.listing" @click="refresh()">↻ {{ t('cloudRefresh') }}</button>
          <span class="muted small">{{ t('connectedAs', { name: conn.me?.name }) }}</span>
        </footer>
      </template>

      <!-- one session: places / tasks -->
      <template v-else-if="openDataset">
        <div class="sesshead">
          <div class="row">
            <button class="ghost back" @click="router.push(to.sessions())">‹ {{ t('back') }}</button>
            <button v-if="openDataset.source === 'result'" class="primary small" :title="confirmed.length || removed.length ? '' : t('nothingConfirmed')" @click="publishOpen = true">{{ t('publish') }}{{ confirmed.length + removed.length ? ` (${confirmed.length + removed.length})` : '' }}</button>
          </div>
          <h1>{{ openDataset.session.name || openDataset.session.id }}</h1>
          <div class="muted small">{{ (openDataset.session.startedAt || '').slice(0, 10) }} · {{ kindLabel(openDataset) }}</div>
          <StageBar :ds="openDataset" />
        </div>
        <nav class="seg">
          <button :class="{ on: view === 'places' }" @click="router.push(to.session(openDataset.id))">{{ t('places') }} <span class="n">{{ openDataset.places.length }}</span></button>
          <button :class="{ on: view === 'tasks' }" @click="router.push(to.sessionTasks(openDataset.id))">{{ t('tasks') }} <span class="n">{{ openDataset.tasks.length }}</span></button>
        </nav>

        <section v-if="view === 'places'" class="pane">
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
            <div class="filters">
              <input v-model="q" :placeholder="t('search')" />
              <select v-model="fDecision"><option value="">{{ t('anyDecision') }}</option><option value="todo">{{ t('toReview') }}</option><option value="confirmed">{{ t('confirmedF') }}</option><option value="visit">{{ t('needsVisit') }}</option><option value="rejected">{{ t('rejectedF') }}</option></select>
              <select v-model="fGoogle"><option value="">{{ t('googleAny') }}</option><option value="found">{{ t('onGoogle') }}</option><option value="new">{{ t('notOnGoogle') }}</option><option value="todo">{{ t('notChecked') }}</option></select>
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
                <div class="muted">{{ p.category }}<template v-if="p.fields.phone"> · {{ p.fields.phone.value }}</template></div>
                <span :class="['chip', reviewOf(openDataset.id, p).decision]">{{ t(DECISION[reviewOf(openDataset.id, p).decision]) }}</span>
                <span v-if="gstat(p) === 'new'" class="chip new">{{ t('notOnGoogleChip') }}</span>
                <span v-if="missingStreet(p, reviewOf(openDataset.id, p))" class="chip new">{{ t('noStreet') }}</span>
                <span v-else-if="reviewOf(openDataset.id, p).decision === 'confirmed' && pendingSuggestions(p, reviewOf(openDataset.id, p)).length" class="chip visit"
                  :title="pendingSuggestions(p, reviewOf(openDataset.id, p)).map((k) => t('field.' + k)).join(', ')">{{ t('suggestionsOpen', { n: pendingSuggestions(p, reviewOf(openDataset.id, p)).length }) }}</span>
                <span v-if="p.status !== 'open'" class="chip rm">{{ t('status_' + p.status) }}</span>
              </div>
            </button>
          </template>
        </section>

        <section v-else class="pane">
          <div v-if="!openDataset.tasks.length" class="empty soft">
            <b>{{ t('noTasksSession') }}</b>
            <p class="muted">{{ t(openDataset.source === 'raw' ? 'noTasksSessionRaw' : 'noTasksSessionDone') }}</p>
          </div>
          <template v-else>
            <div class="row"><span class="muted small">{{ t('sessionTasksHint') }}</span>
              <button class="secondary small" :title="t('exportTitle')" @click="exportTasks(openDataset)">{{ t('export') }}</button></div>
            <TaskRow v-for="(tk, i) in openDataset.tasks" :key="i" :ds="openDataset" :task="tk" :index="i" @open="selectTask(openDataset.id, i)" @open-place="(id: string) => selectPlace(openDataset!.id, id)" />
          </template>
        </section>
      </template>
    </aside>

    <main class="mapwrap">
      <MapView ref="mapRef" @select="selectPlace" @move-pin="movePin" />
      <div v-if="!state.loading && (!state.datasets.length || !shownCount)" class="mapnote">
        <template v-if="!state.datasets.length"><b>{{ t('mapEmptyTitle') }}</b><br />{{ t('mapEmptyText') }}</template>
        <template v-else><b>{{ t('mapHiddenTitle') }}</b><br />{{ t('mapHiddenText') }}</template>
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
      <TaskDetail v-else-if="openTask && state.task != null" :ds="openDataset" :task="openTask" :index="state.task" @close="router.push(to.sessionTasks(openDataset.id))" @open-place="(id: string) => selectPlace(openDataset!.id, id)" />
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
            <button class="primary" :disabled="!confirmed.length" @click="pubPhotos">{{ t('pubPhotos') }}</button>
            <button class="primary" @click="pubProject">{{ t('pubProject') }}</button>
          </div>
          <p class="muted small">{{ t('pubProjectNote') }}</p>
          <p class="muted small">{{ t('pubPhotosNote') }}</p>
          <p class="muted small">{{ t('osmNote') }}</p>
        </template>
        <button class="ghost" @click="publishOpen = false">{{ t('close') }}</button>
      </div>
    </div>
  </div>
</template>
