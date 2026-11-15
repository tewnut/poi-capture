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
import CopyLink from './components/CopyLink.vue'
import Photo from './components/Photo.vue'
import { state, load, add, remove, reviewOf, gstat, openDataset, openPlace, openTask, setReviewer, stageOf, STAGES } from './store'
import { to, shareUrl } from './router'
import { importFiles } from './importer'
import { geojson, csv, osm, tasksGeojson, download, type Published, type Removed } from './publish'
import type { Dataset, Place } from './types'

type View = 'sessions' | 'allTasks' | 'places' | 'tasks'
const view = ref<View>('sessions')
const busy = ref('')
const dragging = ref(false)
const publishOpen = ref(false)
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
  busy.value = `Importing ${files.length} file${files.length > 1 ? 's' : ''}…`
  state.error = ''
  try {
    const items = await importFiles(files)
    if (!items.length) throw new Error('Nothing to import: pick a processed session folder (with result.json), a zip of it, or a phone export zip.')
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

// sessions
const sessions = computed(() => state.datasets.filter((d) =>
  (!fStage.value || stageOf(d).stage === fStage.value) &&
  (!fKind.value || d.session.kind === fKind.value) &&
  // mounted / in hand is a sweep property: a Place capture never matches it
  (!fHold.value || (d.session.kind !== 'poi' && (d.session.holding || 'mount') === fHold.value))))
function clearSessionFilters() { fStage.value = ''; fKind.value = ''; fHold.value = '' }
const stageCount = (s: string) => state.datasets.filter((d) => stageOf(d).stage === s).length
function kindLabel(d: Dataset) {
  return d.session.kind === 'poi' ? 'Place capture' : d.session.holding === 'hand' ? 'Street sweep, in hand' : 'Street sweep, mounted'
}
function toggleShown(id: string) { state.shown.has(id) ? state.shown.delete(id) : state.shown.add(id) }
async function del(d: Dataset) {
  if (!window.confirm(`Remove "${d.session.name || d.id}" and its reviews from this browser?`)) return
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
const confirmed = computed<Published[]>(() => state.datasets.flatMap((d) => d.places
  .map((p) => ({ dataset: d, place: p, review: reviewOf(d.id, p) })).filter((x) => x.review.decision === 'confirmed')))
const removed = computed<Removed[]>(() => state.datasets.flatMap((d) => d.tasks
  .map((_t, i) => ({ dataset: d, index: i, review: state.removals[`${d.id}|${i}`] }))
  .filter((x) => x.review?.decision === 'confirmed')))
const stamp = () => new Date().toISOString().slice(0, 16).replace(/[-:T]/g, '')
function pub(kind: 'geojson' | 'csv' | 'osm') {
  if (kind === 'geojson') download(`poi-published-${stamp()}.geojson`, geojson(confirmed.value, removed.value), 'application/geo+json')
  if (kind === 'csv') download(`poi-published-${stamp()}.csv`, csv(confirmed.value, removed.value), 'text/csv')
  if (kind === 'osm') download(`poi-published-${stamp()}.osm`, osm(confirmed.value), 'application/xml')
}
function exportTasks(ds?: Dataset) {
  const list = ds ? [ds] : state.datasets.filter((d) => state.shown.has(d.id))
  download(`poi-tasks-${ds ? ds.session.id + '-' : ''}${stamp()}.geojson`, tasksGeojson(list), 'application/geo+json')
}

const decisionLabel = { todo: 'to review', confirmed: 'confirmed', rejected: 'rejected', visit: 'needs a visit' }
function cover(p: Place) { return (p.photos.find((x) => x.cover) || p.photos[0])?.file }
</script>

<template>
  <div class="app" @dragover.prevent="dragging = true" @dragleave.self="dragging = false" @drop.prevent="onDrop">
    <aside class="side">
      <header class="sidehead">
        <div class="brand"><span class="logo">◉</span> POI Capture Reviewer</div>
        <button class="primary small" :title="confirmed.length || removed.length ? '' : 'Nothing confirmed yet'" @click="publishOpen = true">Publish{{ confirmed.length + removed.length ? ` (${confirmed.length + removed.length})` : '' }}</button>
      </header>
      <label class="who"><span class="muted">Reviewer</span>
        <input :value="state.reviewer" placeholder="your name" @change="setReviewer(($event.target as HTMLInputElement).value)" /></label>
      <input ref="dirInput" type="file" webkitdirectory multiple hidden @change="onPick" />
      <input ref="fileInput" type="file" accept=".zip" multiple hidden @change="onPick" />

      <div v-if="busy" class="banner">{{ busy }}</div>
      <div v-if="state.missing" class="banner err"><span>This link points to <b>{{ state.missing }}</b>, which is not in this browser. Import that session (its folder or zip), then open the link again.</span> <button class="ghost" @click="state.missing = ''">✕</button></div>
      <div v-if="state.error" class="banner err">{{ state.error }} <button class="ghost" @click="state.error = ''">✕</button></div>

      <!-- sessions / all tasks -->
      <template v-if="view === 'sessions' || view === 'allTasks'">
        <nav class="seg">
          <button :class="{ on: view === 'sessions' }" @click="router.push(to.sessions())">Sessions <span class="n">{{ state.datasets.length }}</span></button>
          <button :class="{ on: view === 'allTasks' }" @click="router.push(to.tasks())">All tasks <span class="n">{{ allTasks }}</span></button>
        </nav>

        <section v-if="view === 'sessions'" class="pane">
          <div v-if="state.loading" class="empty soft">Loading the sessions saved in this browser…</div>
          <div v-else-if="!state.datasets.length" class="empty">
            <div class="ico">📂</div>
            <b>No sessions yet</b>
            <p>Import one of these, with the buttons below or by dropping it anywhere on the page:</p>
            <ul>
              <li><b>A processed session folder</b> (with <code>result.json</code>), from the process-sweep skill, or a zip of it: places ready to review.</li>
              <li><b>A zip exported from the phone</b>: the ride and its photos, or the places of a Place capture, not processed yet.</li>
            </ul>
            <p class="muted small">Everything stays in this browser; nothing is uploaded.</p>
          </div>
          <template v-else>
            <div class="filters">
              <select v-model="fStage"><option value="">Any stage</option><option v-for="s in STAGES" :key="s.key" :value="s.key">{{ s.label }} ({{ stageCount(s.key) }})</option></select>
              <select v-model="fKind"><option value="">All kinds</option><option value="sweep">Street sweep</option><option value="poi">Place capture</option></select>
              <select v-if="fKind !== 'poi'" v-model="fHold"><option value="">Mounted + in hand</option><option value="mount">Mounted</option><option value="hand">In hand</option></select>
            </div>
            <div v-if="!sessions.length" class="empty soft">
              <b>No session matches these filters</b>
              <p class="muted">{{ state.datasets.length }} session{{ state.datasets.length > 1 ? 's are' : ' is' }} hidden by them.</p>
              <button class="secondary" @click="clearSessionFilters">Clear filters</button>
            </div>
            <article v-for="d in sessions" :key="d.id" class="ses">
              <input type="checkbox" :checked="state.shown.has(d.id)" title="Show on the map" @change="toggleShown(d.id)" />
              <div class="body" @click="router.push(to.session(d.id))">
                <b>{{ d.session.name || d.session.id }}</b>
                <div class="muted">{{ (d.session.startedAt || '').slice(0, 10) || 'no date' }} · {{ kindLabel(d) }}</div>
                <div class="stats">
                  <span>{{ d.places.length ? `${d.places.length} places` : 'no places yet' }}</span>
                  <span class="chip new" v-if="d.places.filter((p) => gstat(p) === 'new').length">{{ d.places.filter((p) => gstat(p) === 'new').length }} new to Google</span>
                  <span class="chip todo" v-if="d.tasks.length">{{ d.tasks.length }} tasks</span>
                </div>
                <StageBar :ds="d" compact />
              </div>
              <button class="ghost del" title="Remove from this browser" @click="del(d)">🗑</button>
            </article>
          </template>
        </section>

        <section v-else class="pane">
          <div v-if="!state.datasets.length" class="empty soft">
            <b>No tasks yet</b>
            <p class="muted">Tasks come with processed street sweeps: <b>visits</b> for places to check with Place capture (new to Google, changed, or partly read) and <b>removals</b> for places seen closed or gone. Import a processed session first.</p>
          </div>
          <div v-else-if="!allTasks" class="empty soft">
            <b>Nothing to do</b>
            <p class="muted">The imported sessions have no tasks: every place is on Google and was read fully, and none was seen closed.</p>
          </div>
          <div v-else-if="!shownTasks" class="empty soft">
            <b>No tasks in the sessions shown</b>
            <p class="muted">{{ allTasks }} task{{ allTasks > 1 ? 's are' : ' is' }} in sessions hidden from the map. Tick them in Sessions to list their tasks.</p>
            <button class="secondary" @click="router.push(to.sessions())">Go to Sessions</button>
          </div>
          <template v-else>
            <div class="row"><span class="muted small">Tasks of the sessions shown on the map, for planning visits.</span>
              <button class="secondary small" title="Download these tasks as GeoJSON" @click="exportTasks()">Export</button></div>
            <div v-for="g in taskGroups" :key="g.d.id" class="group">
              <button class="ghead" @click="router.push(to.sessionTasks(g.d.id))"><b>{{ g.d.session.name }}</b> <span class="muted">{{ g.items.length }} tasks ›</span></button>
              <TaskRow v-for="{ t, i } in g.items" :key="i" :ds="g.d" :task="t" :index="i" @open="selectTask(g.d.id, i)" @open-place="(id) => selectPlace(g.d.id, id)" />
            </div>
          </template>
        </section>

        <footer v-if="view === 'sessions'" class="importbar">
          <button class="primary" @click="dirInput?.click()">Import folder</button>
          <button class="secondary" @click="fileInput?.click()">Import zip</button>
          <span class="muted small">or drop it on the page</span>
        </footer>
      </template>

      <!-- one session: places / tasks -->
      <template v-else-if="openDataset">
        <div class="sesshead">
          <div class="row">
            <button class="ghost back" @click="router.push(to.sessions())">‹ Sessions</button>
            <CopyLink :url="shareUrl(view === 'tasks' ? to.sessionTasks(openDataset.id) : to.session(openDataset.id))" />
          </div>
          <h1>{{ openDataset.session.name || openDataset.session.id }}</h1>
          <div class="muted small">{{ (openDataset.session.startedAt || '').slice(0, 10) }} · {{ kindLabel(openDataset) }}</div>
          <StageBar :ds="openDataset" />
        </div>
        <nav class="seg">
          <button :class="{ on: view === 'places' }" @click="router.push(to.session(openDataset.id))">Places <span class="n">{{ openDataset.places.length }}</span></button>
          <button :class="{ on: view === 'tasks' }" @click="router.push(to.sessionTasks(openDataset.id))">Tasks <span class="n">{{ openDataset.tasks.length }}</span></button>
        </nav>

        <section v-if="view === 'places'" class="pane">
          <div v-if="!openDataset.places.length" class="empty soft">
            <template v-if="openDataset.source === 'raw' && openDataset.session.kind !== 'poi'">
              <b>Not processed yet</b>
              <p>This is a street sweep straight from the phone: its places are found by the processing step. You can look at the ride and its photo positions on the map.</p>
              <p class="muted small">Process it with the process-sweep skill, then import the processed folder (or its zip).</p>
            </template>
            <template v-else-if="openDataset.session.kind === 'poi'">
              <b>No places in this session</b>
              <p class="muted">No place was captured, or none had photos.</p>
            </template>
            <template v-else>
              <b>No places found</b>
              <p class="muted">The processing found no business on this ride.</p>
            </template>
          </div>
          <template v-else>
            <div class="filters">
              <input v-model="q" placeholder="Search name, category, sign" />
              <select v-model="fDecision"><option value="">Any decision</option><option value="todo">To review</option><option value="confirmed">Confirmed</option><option value="visit">Needs a visit</option><option value="rejected">Rejected</option></select>
              <select v-model="fGoogle"><option value="">Google: any</option><option value="found">On Google</option><option value="new">Not on Google</option><option value="todo">Not checked</option></select>
            </div>
            <div v-if="!places.length" class="empty soft">
              <b>No place matches these filters</b>
              <p class="muted">{{ openDataset.places.length }} places are hidden by them.</p>
              <button class="secondary" @click="clearPlaceFilters">Clear filters</button>
            </div>
            <button v-for="{ p, i } in places" :key="p.id" :class="['prow', { sel: state.place === p.id }]" @click="selectPlace(openDataset.id, p.id)">
              <Photo class="th" :ds="openDataset.id" :path="cover(p)" />
              <div class="pbody">
                <b>{{ i + 1 }}. {{ p.name }}</b>
                <div class="muted">{{ p.category }}<template v-if="p.fields.phone"> · {{ p.fields.phone.value }}</template></div>
                <span :class="['chip', reviewOf(openDataset.id, p).decision]">{{ decisionLabel[reviewOf(openDataset.id, p).decision] }}</span>
                <span v-if="gstat(p) === 'new'" class="chip new">not on Google</span>
                <span v-if="p.status !== 'open'" class="chip rm">{{ p.status.replace('_', ' ') }}</span>
              </div>
            </button>
          </template>
        </section>

        <section v-else class="pane">
          <div v-if="!openDataset.tasks.length" class="empty soft">
            <b>No tasks in this session</b>
            <p class="muted">{{ openDataset.source === 'raw' ? 'Tasks come from the processing step: process this session first.' : 'Every place is on Google and was read fully, and none was seen closed.' }}</p>
          </div>
          <template v-else>
            <div class="row"><span class="muted small">Visits go to Place capture on the phone; removals are confirmed here or checked on site.</span>
              <button class="secondary small" title="Download this session's tasks as GeoJSON" @click="exportTasks(openDataset)">Export</button></div>
            <TaskRow v-for="(t, i) in openDataset.tasks" :key="i" :ds="openDataset" :task="t" :index="i" @open="selectTask(openDataset.id, i)" @open-place="(id) => selectPlace(openDataset!.id, id)" />
          </template>
        </section>
      </template>
    </aside>

    <main class="mapwrap">
      <MapView @select="selectPlace" @move-pin="movePin" />
      <div v-if="!state.loading && (!state.datasets.length || !shownCount)" class="mapnote">
        <template v-if="!state.datasets.length"><b>Your sessions show up here</b><br />Import a session to see its ride, its places and the photos that found them.</template>
        <template v-else><b>No session on the map</b><br />Tick a session in the list to show it.</template>
      </div>
    </main>

    <aside v-if="openPlace && openDataset" class="detail">
      <PlaceReview ref="reviewRef" :ds="openDataset" :place="openPlace" @close="router.push(to.session(openDataset.id))" @next="nextPlace" />
    </aside>
    <aside v-else-if="openTask && openDataset && state.task != null" class="detail">
      <TaskDetail :ds="openDataset" :task="openTask" :index="state.task" @close="router.push(to.sessionTasks(openDataset.id))" @open-place="(id: string) => selectPlace(openDataset!.id, id)" />
    </aside>

    <div v-if="dragging" class="drop">Drop a processed session folder, its zip, or a phone export zip</div>

    <div v-if="publishOpen" class="modal" @click.self="publishOpen = false">
      <div class="dialog">
        <h2>Publish</h2>
        <p v-if="confirmed.length || removed.length"><b>{{ confirmed.length }}</b> confirmed places and <b>{{ removed.length }}</b> confirmed removals, from all sessions in this browser.</p>
        <div v-else class="empty soft left">
          <b>Nothing to publish yet</b>
          <p class="muted">{{ state.datasets.length ? 'Confirm places in the review panel (open a place, check its fields and pin, then Confirm), or confirm removals in a session\'s Tasks.' : 'Import a processed session and confirm its places first.' }}</p>
        </div>
        <template v-if="confirmed.length || removed.length">
          <p class="muted small">Only what the photos showed and you confirmed is written, with the pin you chose. Google's details and pins are never included.</p>
          <div class="pubs">
            <button class="primary" @click="pub('geojson')">GeoJSON</button>
            <button class="primary" @click="pub('csv')">CSV</button>
            <button class="primary" :disabled="!confirmed.length" @click="pub('osm')">OpenStreetMap (.osm)</button>
          </div>
          <p class="muted small">The .osm file holds new nodes for JOSM: check them against what is already mapped and follow the OSM import guidelines before uploading. Removals are only in the GeoJSON and CSV.</p>
        </template>
        <button class="ghost" @click="publishOpen = false">Close</button>
      </div>
    </div>
  </div>
</template>
