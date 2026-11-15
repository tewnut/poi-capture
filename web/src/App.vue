<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MapView from './components/MapView.vue'
import PlaceReview from './components/PlaceReview.vue'
import TaskDetail from './components/TaskDetail.vue'
import CopyLink from './components/CopyLink.vue'
import Photo from './components/Photo.vue'
import { state, load, add, remove, reviewOf, counts, gstat, openDataset, openPlace, openTask, setReviewer, putRemoval } from './store'
import { to, shareUrl } from './router'
import { importFiles } from './importer'
import { geojson, csv, osm, tasksGeojson, download, type Published, type Removed } from './publish'
import type { Dataset, Place } from './types'

const tab = ref<'sessions' | 'places' | 'tasks'>('sessions')
const busy = ref('')
const dragging = ref(false)
const publishOpen = ref(false)
const reviewRef = ref<InstanceType<typeof PlaceReview>>()
const fileInput = ref<HTMLInputElement>()
const dirInput = ref<HTMLInputElement>()

// filters
const q = ref('')
const fKind = ref('')
const fHold = ref('')
const fDecision = ref('')
const fGoogle = ref('')

const route = useRoute()
const router = useRouter()

/** The address decides what is open: #/ sessions, #/tasks, #/s/<session>, #/s/<session>/p/<place>, #/s/<session>/t/<task>. */
function applyRoute() {
  if (state.loading) return
  const ds = route.params.ds as string | undefined
  state.missing = ''
  if (ds && !state.datasets.some((d) => d.id === ds)) {
    state.missing = ds
    state.open = null; state.place = null; state.task = null
    tab.value = 'sessions'
    return
  }
  switch (route.name) {
    case 'session': state.open = ds!; state.place = null; state.task = null; tab.value = 'places'; break
    case 'place': {
      state.open = ds!; state.task = null; tab.value = 'places'
      const id = route.params.place as string
      state.place = openDataset.value?.places.some((p) => p.id === id) ? id : null
      if (!state.place) state.missing = `${ds} / place ${id}`
      break
    }
    case 'task': {
      state.open = ds!; state.place = null; tab.value = 'tasks'
      const i = Number(route.params.task)
      state.task = Number.isInteger(i) && openDataset.value?.tasks[i] ? i : null
      if (state.task == null) state.missing = `${ds} / task ${route.params.task}`
      break
    }
    case 'tasks': state.place = null; state.task = null; tab.value = 'tasks'; break
    default: state.place = null; state.task = null; tab.value = 'sessions'
  }
  if (state.open) state.shown.add(state.open)
}
watch(fKind, (k) => { if (k === 'poi') fHold.value = '' })
watch(() => route.fullPath, applyRoute)
watch(() => state.loading, applyRoute)
onMounted(load)

async function doImport(files: File[]) {
  if (!files.length) return
  busy.value = `Importing ${files.length} file${files.length > 1 ? 's' : ''}…`
  state.error = ''
  try {
    const items = await importFiles(files)
    if (!items.length) throw new Error('Nothing to import: pick a processed session folder (with result.json), a zip of it, or a phone export zip.')
    await add(items)
    if (items.length === 1) openSession(items[0].dataset.id)
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

const sessions = computed(() => state.datasets.filter((d) =>
  (!fKind.value || d.session.kind === fKind.value) &&
  // mounted / in hand is a sweep property: a Place capture never matches it
  (!fHold.value || (d.session.kind !== 'poi' && (d.session.holding || 'mount') === fHold.value))))

function openSession(id: string) { router.push(to.session(id)) }
function goTab(t: 'sessions' | 'places' | 'tasks') {
  if (t === 'sessions') router.push(to.sessions())
  else if (t === 'tasks') router.push(to.tasks())
  else if (state.open) router.push(to.session(state.open))
}
function toggleShown(id: string) {
  state.shown.has(id) ? state.shown.delete(id) : state.shown.add(id)
}
async function del(d: Dataset) {
  if (!window.confirm(`Remove "${d.session.name || d.id}" and its reviews from this browser?`)) return
  await remove(d.id)
  router.push(to.sessions())
}

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

const tasks = computed(() => state.datasets.filter((d) => state.shown.has(d.id))
  .flatMap((d) => d.tasks.map((t, i) => ({ d, t, i, r: state.removals[`${d.id}|${i}`] }))))

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
function exportTasks() {
  download(`poi-tasks-${stamp()}.geojson`, tasksGeojson(state.datasets.filter((d) => state.shown.has(d.id))), 'application/geo+json')
}

const filtersOn = computed(() => !!(fKind.value || fHold.value))
function clearSessionFilters() { fKind.value = ''; fHold.value = '' }
const placeFiltersOn = computed(() => !!(q.value.trim() || fDecision.value || fGoogle.value))
function clearPlaceFilters() { q.value = ''; fDecision.value = ''; fGoogle.value = '' }
const shownCount = computed(() => state.datasets.filter((d) => state.shown.has(d.id)).length)
const allTasks = computed(() => state.datasets.reduce((n, d) => n + d.tasks.length, 0))

const decisionLabel = { todo: 'to review', confirmed: 'confirmed', rejected: 'rejected', visit: 'needs a visit' }
function cover(p: Place) { return (p.photos.find((x) => x.cover) || p.photos[0])?.file }
</script>

<template>
  <div class="app" @dragover.prevent="dragging = true" @dragleave.self="dragging = false" @drop.prevent="onDrop">
    <header class="top">
      <div class="brand"><span class="logo">◉</span> POI Capture Reviewer</div>
      <div class="actions">
        <input ref="dirInput" type="file" webkitdirectory multiple hidden @change="onPick" />
        <input ref="fileInput" type="file" accept=".zip" multiple hidden @change="onPick" />
        <input class="who" :value="state.reviewer" placeholder="Your name (reviewer)" @change="setReviewer(($event.target as HTMLInputElement).value)" />
        <button class="primary" :title="confirmed.length ? '' : 'Nothing confirmed yet'" @click="publishOpen = true">Publish ({{ confirmed.length }})</button>
      </div>
    </header>

    <aside class="side">
      <nav class="tabs">
        <button :class="{ on: tab === 'sessions' }" @click="goTab('sessions')">Sessions <span class="n">{{ state.datasets.length }}</span></button>
        <button :class="{ on: tab === 'places' }" :disabled="!openDataset" @click="goTab('places')">Places <span v-if="openDataset" class="n">{{ openDataset.places.length }}</span></button>
        <button :class="{ on: tab === 'tasks' }" @click="goTab('tasks')">Tasks <span class="n">{{ tasks.length }}</span></button>
      </nav>

      <div v-if="busy" class="banner">{{ busy }}</div>
      <div v-if="state.missing" class="banner err"><span>This link points to <b>{{ state.missing }}</b>, which is not in this browser. Import that session (its folder or zip), then open the link again.</span> <button class="ghost" @click="state.missing = ''">✕</button></div>
      <div v-if="state.error" class="banner err">{{ state.error }} <button class="ghost" @click="state.error = ''">✕</button></div>

      <section v-if="tab === 'sessions'" class="pane">
        <div v-if="state.loading" class="empty soft">Loading the sessions saved in this browser…</div>
        <template v-else-if="!state.datasets.length">
          <div class="empty">
            <div class="ico">📂</div>
            <b>No sessions yet</b>
            <p>Import one of these, with the buttons below or by dropping it anywhere on the page:</p>
            <ul>
              <li><b>A processed session folder</b> (with <code>result.json</code>), from the process-sweep skill, or a zip of it: places ready to review.</li>
              <li><b>A zip exported from the phone</b>: the ride and its photos, or the places of a Place capture, not processed yet.</li>
            </ul>
            <p class="muted small">Everything stays in this browser; nothing is uploaded.</p>
          </div>
        </template>
        <template v-else>
          <div class="filters">
            <select v-model="fKind"><option value="">All kinds</option><option value="sweep">Street sweep</option><option value="poi">Place capture</option></select>
            <select v-if="fKind !== 'poi'" v-model="fHold"><option value="">Mounted + in hand</option><option value="mount">Mounted</option><option value="hand">In hand</option></select>
          </div>
          <div v-if="!sessions.length" class="empty soft">
            <b>No session matches these filters</b>
            <p class="muted">{{ state.datasets.length }} session{{ state.datasets.length > 1 ? 's are' : ' is' }} hidden by them.</p>
            <button class="secondary" @click="clearSessionFilters">Clear filters</button>
          </div>
          <article v-for="d in sessions" :key="d.id" :class="['ses', { open: state.open === d.id }]">
            <input type="checkbox" :checked="state.shown.has(d.id)" title="Show on the map" @change="toggleShown(d.id)" />
            <div class="body" @click="openSession(d.id)">
              <b>{{ d.session.name || d.session.id }}</b>
              <div class="muted">{{ (d.session.startedAt || '').slice(0, 10) || 'no date' }} · {{ d.session.kind === 'poi' ? 'Place capture' : d.session.holding === 'hand' ? 'Sweep, in hand' : 'Sweep, mounted' }}{{ d.source === 'raw' ? ' · raw, not processed' : '' }}</div>
              <div class="stats">
                <span>{{ d.places.length ? `${d.places.length} places` : 'no places yet' }}</span>
                <span class="chip ok" v-if="counts(d).confirmed">{{ counts(d).confirmed }} confirmed</span>
                <span class="chip todo" v-if="counts(d).todo">{{ counts(d).todo }} to review</span>
                <span class="chip new" v-if="d.places.filter((p) => gstat(p) === 'new').length">{{ d.places.filter((p) => gstat(p) === 'new').length }} new to Google</span>
                <span class="chip rm" v-if="d.tasks.filter((t) => t.type !== 'visit').length">{{ d.tasks.filter((t) => t.type !== 'visit').length }} removals</span>
              </div>
            </div>
            <button class="ghost del" title="Remove from this browser" @click="del(d)">🗑</button>
          </article>
        </template>
      </section>
      <footer v-if="tab === 'sessions'" class="importbar">
        <button class="primary" @click="dirInput?.click()">Import folder</button>
        <button class="secondary" @click="fileInput?.click()">Import zip</button>
        <span class="muted small">or drop it on the page</span>
      </footer>

      <section v-if="tab === 'places' && openDataset" class="pane">
        <div class="ptitle"><b>{{ openDataset.session.name }}</b>
          <span><CopyLink :url="shareUrl(to.session(openDataset.id))" /><button class="ghost" @click="router.push(to.sessions())">all sessions</button></span></div>
        <div v-if="openDataset.places.length" class="filters">
          <input v-model="q" placeholder="Search name, category, sign" />
          <select v-model="fDecision"><option value="">Any decision</option><option value="todo">To review</option><option value="confirmed">Confirmed</option><option value="visit">Needs a visit</option><option value="rejected">Rejected</option></select>
          <select v-model="fGoogle"><option value="">Google: any</option><option value="found">On Google</option><option value="new">Not on Google</option><option value="todo">Not checked</option></select>
        </div>
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
        <div v-else-if="!places.length" class="empty soft">
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
      </section>

      <section v-if="tab === 'tasks'" class="pane">
        <div v-if="!state.datasets.length" class="empty soft">
          <b>No tasks yet</b>
          <p class="muted">Tasks come with processed street sweeps: <b>visits</b> for places to check with Place capture (new to Google, changed, or partly read) and <b>removals</b> for places seen closed or gone. Import a processed session first.</p>
        </div>
        <div v-else-if="!allTasks" class="empty soft">
          <b>Nothing to do</b>
          <p class="muted">The imported sessions have no tasks: every place is on Google and was read fully, and none was seen closed.</p>
        </div>
        <div v-else-if="!tasks.length" class="empty soft">
          <b>No tasks in the sessions shown</b>
          <p class="muted">{{ allTasks }} task{{ allTasks > 1 ? 's are' : ' is' }} in sessions hidden from the map. Tick them in Sessions to list their tasks.</p>
          <button class="secondary" @click="goTab('sessions')">Go to Sessions</button>
        </div>
        <template v-else>
          <p class="muted small">Visits go to Place capture on the phone; removals are confirmed here or checked on site. Tasks of the sessions shown on the map.</p>
          <button class="secondary" @click="exportTasks">Export task list (GeoJSON)</button>
        </template>
        <div v-for="{ d, t, i, r } in tasks" :key="d.id + i" :class="['task', { sel: state.open === d.id && state.task === i }]" @click="selectTask(d.id, i)">
          <span :class="['chip', t.type === 'visit' ? 'todo' : 'rm']">{{ t.type === 'visit' ? 'visit' : t.type === 'removal' ? 'removal' : 'verify removal' }}</span>
          <b>{{ t.name }}</b>
          <div class="muted">{{ t.why }} · {{ d.session.name }}</div>
          <div v-if="t.type !== 'visit'" class="tact" @click.stop>
            <button :class="['secondary', { on: r?.decision === 'confirmed' }]" @click="putRemoval(d.id, i, 'confirmed')">Confirm removal</button>
            <button :class="['secondary', { on: r?.decision === 'verify' }]" @click="putRemoval(d.id, i, 'verify')">Check on site</button>
            <button :class="['ghost', { on: r?.decision === 'dismissed' }]" @click="putRemoval(d.id, i, 'dismissed')">Dismiss</button>
          </div>
          <button v-else-if="t.place" class="ghost" @click.stop="selectPlace(d.id, t.place)">show place</button>
        </div>
      </section>
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
      <TaskDetail :ds="openDataset" :task="openTask" :index="state.task" @close="router.push(to.tasks())" @open-place="(id: string) => selectPlace(openDataset!.id, id)" />
    </aside>

    <div v-if="dragging" class="drop">Drop a processed session folder, its zip, or a phone export zip</div>

    <div v-if="publishOpen" class="modal" @click.self="publishOpen = false">
      <div class="dialog">
        <h2>Publish</h2>
        <p v-if="confirmed.length || removed.length"><b>{{ confirmed.length }}</b> confirmed places and <b>{{ removed.length }}</b> confirmed removals, from all sessions in this browser.</p>
        <div v-else class="empty soft left">
          <b>Nothing to publish yet</b>
          <p class="muted">{{ state.datasets.length ? 'Confirm places in the review panel (open a place, check its fields and pin, then Confirm), or confirm removals in Tasks.' : 'Import a processed session and confirm its places first.' }}</p>
        </div>
        <template v-if="confirmed.length || removed.length">
        <p class="muted small">Only what the photos showed and you confirmed is written, with the pin you chose. Google's details and pins are never included.</p>
        <div class="pubs">
          <button class="primary" :disabled="!confirmed.length && !removed.length" @click="pub('geojson')">GeoJSON</button>
          <button class="primary" :disabled="!confirmed.length && !removed.length" @click="pub('csv')">CSV</button>
          <button class="primary" :disabled="!confirmed.length" @click="pub('osm')">OpenStreetMap (.osm)</button>
        </div>
        <p class="muted small">The .osm file holds new nodes for JOSM: check them against what is already mapped and follow the OSM import guidelines before uploading. Removals are only in the GeoJSON and CSV.</p>
        </template>
        <button class="ghost" @click="publishOpen = false">Close</button>
      </div>
    </div>
  </div>
</template>
