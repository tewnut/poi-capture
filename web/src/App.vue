<script setup lang="ts">
// Left panel: the sessions (or all tasks across them); open a session to review its places and tasks. The map
// is in the middle, the selected place or task on the right. The address (#/...) decides what is open.
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MapView from './components/MapView.vue'
import PlaceReview from './components/PlaceReview.vue'
import TaskDetail from './components/TaskDetail.vue'
import TaskRow from './components/TaskRow.vue'
import StageBar from './components/StageBar.vue'
import Photo from './components/Photo.vue'
import { state, load, add, remove, clearAll, reviewOf, photoBlob, gstat, openDataset, openPlace, openTask, setReviewer, stageOf, STAGES } from './store'
import { to } from './router'
import { t, lang, setLang, LANGS, type Lang } from './i18n'
import { importFiles } from './importer'
import { keptPhotos, exportNames } from './photos'
import { zipSync, strToU8 } from 'fflate'
import { geojson, csv, osm, tasksGeojson, download, type Published, type Removed } from './publish'
import { buildProject, projectName } from './project'
import { missingStreet, pendingSuggestions } from './fields'
import { loadCloud, saveCloud, DEFAULT_CLOUD_URL, fetchIndex, downloadItem, processedItems, importState, humanSize, endpoint, type CloudItem } from './cloud'
import type { Dataset, Place } from './types'

type View = 'sessions' | 'allTasks' | 'places' | 'tasks'
const view = ref<View>('sessions')
const busy = ref('')
const dragging = ref(false)
const publishOpen = ref(false)
const settingsOpen = ref(false)
const reviewRef = ref<InstanceType<typeof PlaceReview>>()
const fileInput = ref<HTMLInputElement>()
const dirInput = ref<HTMLInputElement>()

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
    state.missing = ds
    state.open = null; state.place = null; state.task = null
    view.value = 'sessions'
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
watch(() => state.open, () => { q.value = ''; fDecision.value = ''; fGoogle.value = '' })
onMounted(load)

async function doImport(files: File[]) {
  if (!files.length) return
  busy.value = t('importing', { n: files.length })
  state.error = ''
  try {
    const items = await importFiles(files)
    if (!items.length) throw new Error(t('nothingToImport'))
    await add(items)
    if (items.length === 1) router.push(to.session(items[0].dataset.id))
  } catch (e: any) {
    state.error = e.message || String(e)
  } finally {
    busy.value = ''
  }
}
function onPick(e: Event) {
  const t = e.target as HTMLInputElement
  doImport([...(t.files || [])])
  t.value = ''
}
function onDrop(e: DragEvent) {
  dragging.value = false
  doImport([...(e.dataTransfer?.files || [])])
}

// cloud storage (cloud.ts): list the processed sessions on the server, pull the picked ones through the importer
const cloud = ref(loadCloud())
function setCloud(k: 'url' | 'token', v: string) {
  cloud.value = { ...cloud.value, [k]: v.trim() || (k === 'url' ? DEFAULT_CLOUD_URL : '') }
  saveCloud(cloud.value)
}
const cloudOk = computed(() => endpoint(cloud.value.url) != null)
const cloudOpen = ref(false)
const cloudItems = ref<CloudItem[] | null>(null)
const cloudErr = ref('')
const cloudLoading = ref(false)
const cloudPick = ref<string[]>([])
async function refreshCloud() {
  if (!cloudOk.value) return
  cloudLoading.value = true; cloudErr.value = ''
  try {
    cloudItems.value = processedItems(await fetchIndex(cloud.value))
    // preselect what is not in this browser yet
    cloudPick.value = cloudItems.value.filter((i) => importState(i, state.datasets) !== 'imported').map((i) => i.id)
  } catch (e: any) {
    cloudErr.value = e.message || String(e)
  } finally {
    cloudLoading.value = false
  }
}
function toggleCloud() {
  cloudOpen.value = !cloudOpen.value
  if (cloudOpen.value) refreshCloud()
}
async function pullCloud() {
  const items = (cloudItems.value || []).filter((i) => cloudPick.value.includes(i.id))
  if (!items.length) return
  busy.value = t('cloudDownloading', { n: items.length })
  state.error = ''
  const files: File[] = []
  try {
    for (const i of items) files.push(await downloadItem(cloud.value, i))
  } catch (e: any) {
    state.error = e.message || String(e)
    busy.value = ''
    return
  }
  busy.value = ''
  await doImport(files)
  if (!state.error) { cloudOpen.value = false; cloudPick.value = [] }
}

// sessions
const sessions = computed(() => state.datasets.filter((d) =>
  (!fStage.value || stageOf(d).stage === fStage.value) &&
  (!fKind.value || d.session.kind === fKind.value) &&
  // mounted / in hand is a sweep property: a Place capture never matches it
  (!fHold.value || (d.session.kind !== 'poi' && (d.session.holding || 'mount') === fHold.value))))
function clearSessionFilters() { fStage.value = ''; fKind.value = ''; fHold.value = '' }
const stageCount = (s: string) => state.datasets.filter((d) => stageOf(d).stage === s).length
function kindLabel(d: Dataset) {
  return t(d.session.kind === 'poi' ? 'kindPoi' : d.session.holding === 'hand' ? 'kindSweepHand' : 'kindSweepMount')
}
const STAGE_NAME = { captured: 'stageCaptured', processed: 'stageProcessed', reviewed: 'stageReviewed' } as const
function toggleShown(id: string) { state.shown.has(id) ? state.shown.delete(id) : state.shown.add(id) }
async function wipe() {
  if (!state.datasets.length) return
  if (!window.confirm(t('clearAllConfirm', { n: state.datasets.length }))) return
  await clearAll()
  settingsOpen.value = false
  router.push(to.sessions())
}
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
  <div class="app" @dragover.prevent="dragging = true" @dragleave.self="dragging = false" @drop.prevent="onDrop">
    <aside class="side">
      <header class="sidehead">
        <div class="brand"><span class="logo">◉</span> {{ t('appName') }}</div>
        <button :class="['ghost', 'gear', { on: settingsOpen }]" :title="t('settings')" :aria-label="t('settings')" @click="settingsOpen = !settingsOpen">⚙</button>
      </header>
      <div v-if="settingsOpen" class="settings">
        <label><span>{{ t('reviewer') }}</span>
          <input :value="state.reviewer" :placeholder="t('yourName')" @change="setReviewer(($event.target as HTMLInputElement).value)" /></label>
        <label><span>{{ t('language') }}</span>
          <select :value="lang" @change="setLang(($event.target as HTMLSelectElement).value as Lang)">
            <option v-for="l in LANGS" :key="l.key" :value="l.key">{{ l.label }}</option>
          </select></label>
        <p class="muted small">{{ t('reviewerHint') }}</p>
        <div class="cloudset">
          <b class="small">{{ t('cloudStorage') }}</b>
          <label><span>{{ t('cloudUrl') }}</span>
            <input :value="cloud.url" :placeholder="DEFAULT_CLOUD_URL" spellcheck="false" @change="setCloud('url', ($event.target as HTMLInputElement).value)" /></label>
          <label><span>{{ t('cloudToken') }}</span>
            <input type="password" autocomplete="off" :value="cloud.token" :placeholder="t('cloudTokenNone')" @change="setCloud('token', ($event.target as HTMLInputElement).value)" /></label>
          <p v-if="cloud.url && !cloudOk" class="small err-text">{{ t('cloudBadUrl') }}</p>
          <p class="muted small">{{ t('cloudHint') }}</p>
        </div>
        <div class="wipe">
          <button class="danger small" :disabled="!state.datasets.length" @click="wipe">{{ t('clearAll') }}</button>
          <span class="muted small">{{ t('clearAllHint') }}</span>
        </div>
      </div>
      <input ref="dirInput" type="file" webkitdirectory multiple hidden @change="onPick" />
      <input ref="fileInput" type="file" accept=".zip" multiple hidden @change="onPick" />

      <div v-if="busy" class="banner">{{ busy }}</div>
      <div v-if="state.missing" class="banner err"><span>{{ t('missingLink', { what: state.missing }) }}</span> <button class="ghost" @click="state.missing = ''">✕</button></div>
      <div v-if="state.error" class="banner err">{{ state.error }} <button class="ghost" @click="state.error = ''">✕</button></div>

      <!-- sessions / all tasks -->
      <template v-if="view === 'sessions' || view === 'allTasks'">
        <nav class="seg">
          <button :class="{ on: view === 'sessions' }" @click="router.push(to.sessions())">{{ t('sessions') }} <span class="n">{{ state.datasets.length }}</span></button>
          <button :class="{ on: view === 'allTasks' }" @click="router.push(to.tasks())">{{ t('allTasks') }} <span class="n">{{ allTasks }}</span></button>
        </nav>

        <section v-if="view === 'sessions'" class="pane">
          <div v-if="state.loading" class="empty soft">{{ t('loading') }}</div>
          <div v-else-if="!state.datasets.length" class="empty">
            <div class="ico">📂</div>
            <b>{{ t('noSessions') }}</b>
            <p>{{ t('importIntro') }}</p>
            <ul>
              <li><b>{{ t('importProcessed') }}</b> {{ t('importProcessedRest') }}</li>
              <li><b>{{ t('importRaw') }}</b>{{ t('importRawRest') }}</li>
            </ul>
            <p class="muted small">{{ t('staysLocal') }}</p>
          </div>
          <template v-else>
            <div class="filters">
              <select v-model="fStage"><option value="">{{ t('anyStage') }}</option><option v-for="s in STAGES" :key="s.key" :value="s.key">{{ t(STAGE_NAME[s.key]) }} ({{ stageCount(s.key) }})</option></select>
              <select v-model="fKind"><option value="">{{ t('allKinds') }}</option><option value="sweep">{{ t('sweep') }}</option><option value="poi">{{ t('poi') }}</option></select>
              <select v-if="fKind !== 'poi'" v-model="fHold"><option value="">{{ t('mountAndHand') }}</option><option value="mount">{{ t('mounted') }}</option><option value="hand">{{ t('inHand') }}</option></select>
            </div>
            <div v-if="!sessions.length" class="empty soft">
              <b>{{ t('noSessionMatch') }}</b>
              <p class="muted">{{ t('sessionsHidden', { n: state.datasets.length }) }}</p>
              <button class="secondary" @click="clearSessionFilters">{{ t('clearFilters') }}</button>
            </div>
            <article v-for="d in sessions" :key="d.id" class="ses">
              <input type="checkbox" :checked="state.shown.has(d.id)" :title="t('showOnMap')" @change="toggleShown(d.id)" />
              <div class="body" @click="router.push(to.session(d.id))">
                <b>{{ d.session.name || d.session.id }}</b>
                <div class="muted">{{ (d.session.startedAt || '').slice(0, 10) || t('noDate') }} · {{ kindLabel(d) }}</div>
                <div class="stats">
                  <span>{{ d.places.length ? t('nPlaces', { n: d.places.length }) : t('noPlacesYet') }}</span>
                  <span class="chip new" v-if="d.places.filter((p) => gstat(p) === 'new').length">{{ t('newToGoogle', { n: d.places.filter((p) => gstat(p) === 'new').length }) }}</span>
                  <span class="chip todo" v-if="d.tasks.length">{{ t('nTasks', { n: d.tasks.length }) }}</span>
                </div>
                <StageBar :ds="d" />
              </div>
              <button class="ghost del" :title="t('removeSession')" @click="del(d)">🗑</button>
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

        <section v-if="view === 'sessions' && cloudOpen" class="cloud">
          <div class="row">
            <b>{{ t('cloudTitle') }}</b>
            <span>
              <button v-if="cloudOk" class="ghost small" :disabled="cloudLoading" @click="refreshCloud">{{ t('cloudRefresh') }}</button>
              <button class="ghost small" :aria-label="t('close')" @click="cloudOpen = false">✕</button>
            </span>
          </div>
          <p v-if="!cloudOk" class="muted small">{{ t('cloudNotSet') }} <button class="ghost small" @click="settingsOpen = true">⚙ {{ t('settings') }}</button></p>
          <p v-else-if="cloudLoading" class="muted small">{{ t('cloudLoading') }}</p>
          <p v-else-if="cloudErr" class="small err-text">{{ cloudErr }}</p>
          <p v-else-if="cloudItems && !cloudItems.length" class="muted small">{{ t('cloudEmpty') }}</p>
          <template v-else-if="cloudItems">
            <label v-for="i in cloudItems" :key="i.id" class="citem">
              <input type="checkbox" :value="i.id" v-model="cloudPick" />
              <span class="body">
                <b>{{ i.name || i.sessionId || i.filename }}</b>
                <span class="muted small">{{ (i.createdAt || '').slice(0, 16).replace('T', ' ') }} · {{ humanSize(i.size) }}{{ i.kind ? ' · ' + (i.kind === 'poi' ? t('poi') : t('sweep')) : '' }}</span>
              </span>
              <span v-if="importState(i, state.datasets) === 'imported'" class="chip ok">{{ t('cloudImported') }}</span>
              <span v-else-if="importState(i, state.datasets) === 'updated'" class="chip todo">{{ t('cloudUpdated') }}</span>
            </label>
            <button class="primary small" :disabled="!cloudPick.length || !!busy" @click="pullCloud">{{ t('cloudImport', { n: cloudPick.length }) }}</button>
          </template>
        </section>
        <footer v-if="view === 'sessions'" class="importbar">
          <button class="primary" @click="dirInput?.click()">{{ t('importFolder') }}</button>
          <button class="secondary" @click="fileInput?.click()">{{ t('importZip') }}</button>
          <button :class="['secondary', { on: cloudOpen }]" :title="t('fromServerTitle')" @click="toggleCloud">☁ {{ t('fromServer') }}</button>
          <span class="muted small">{{ t('orDrop') }}</span>
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
      <MapView @select="selectPlace" @move-pin="movePin" />
      <div v-if="!state.loading && (!state.datasets.length || !shownCount)" class="mapnote">
        <template v-if="!state.datasets.length"><b>{{ t('mapEmptyTitle') }}</b><br />{{ t('mapEmptyText') }}</template>
        <template v-else><b>{{ t('mapHiddenTitle') }}</b><br />{{ t('mapHiddenText') }}</template>
      </div>
    </main>

    <aside v-if="openPlace && openDataset" class="detail">
      <PlaceReview ref="reviewRef" :ds="openDataset" :place="openPlace" @close="router.push(to.session(openDataset.id))" @next="nextPlace" />
    </aside>
    <aside v-else-if="openTask && openDataset && state.task != null" class="detail">
      <TaskDetail :ds="openDataset" :task="openTask" :index="state.task" @close="router.push(to.sessionTasks(openDataset.id))" @open-place="(id: string) => selectPlace(openDataset!.id, id)" />
    </aside>

    <div v-if="dragging" class="drop">{{ t('dropHere') }}</div>

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
