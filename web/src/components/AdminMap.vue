<script setup lang="ts">
// Admin map: where each token was last seen (Cloudflare's IP lookup: approximate, city level at best) and the
// phones' rides (raw-session uploads with a `track` in their metadata, drawn without downloading the zips), one
// colour per uploader. Filters: uploader and period. server/README.md, "Last seen" and "Trip metadata".
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { t, lang, relTime } from '../i18n'
import { decodePolyline, seenPlace, shortClient, type Builtin, type CloudItem, type Seen, type Token } from '../cloud'

const props = defineProps<{ tokens: Token[]; builtins: Builtin[]; uploads: CloudItem[] }>()

/** Distinct on the OSM tiles (no greens or pale tones that sink into parks and roads), then repeated. */
const PALETTE = ['#d62728', '#1f5fd6', '#e67e00', '#8e24aa', '#00897b', '#c2185b', '#6d4c41', '#3949ab', '#7cb342', '#212121']
const UNKNOWN = '#78909c'
const DEVICE = '#37474f'

type Device = { id: string; name: string; seen: Seen & { lat: number; lon: number }; builtin: boolean }
type Trip = { item: CloudItem; who: string; name: string; at: string | null; pts: [number, number][] }

const devices = computed<Device[]>(() => [
  ...props.builtins.map((b) => ({ id: b.id, name: b.name, seen: b.seen, builtin: true })),
  ...props.tokens.map((k) => ({ id: k.id, name: k.name, seen: k.seen, builtin: false })),
].filter((d): d is Device => d.seen?.lat != null && d.seen?.lon != null))

const trips = computed<Trip[]>(() => props.uploads.flatMap((u) => {
  const pts = u.trip?.track ? decodePolyline(u.trip.track) : []
  if (!pts.length) return []
  return [{ item: u, who: u.by?.id ?? '', name: u.by?.name ?? t('mapUnknown'), at: u.trip?.startedAt || u.createdAt, pts }]
}))

/** Uploaders with a trip, in a fixed order (by id) so a colour does not change with the filters. */
const uploaders = computed(() => {
  const m = new Map<string, string>()
  for (const tr of trips.value) if (!m.has(tr.who)) m.set(tr.who, tr.name)
  return [...m].sort(([a], [b]) => (a === '' ? 1 : b === '' ? -1 : a.localeCompare(b))).map(([id, name]) => ({ id, name }))
})
const colour = computed(() => {
  const m = new Map<string, string>()
  let i = 0
  for (const u of uploaders.value) m.set(u.id, u.id === '' ? UNKNOWN : PALETTE[i++ % PALETTE.length])
  return m
})
/** Every name to filter on: uploaders with trips and seen devices. */
const people = computed(() => {
  const m = new Map<string, string>()
  for (const u of uploaders.value) if (u.id) m.set(u.id, u.name)
  for (const d of devices.value) if (!m.has(d.id)) m.set(d.id, d.name)
  return [...m].map(([id, name]) => ({ id, name })).sort((a, b) => a.name.localeCompare(b.name))
})

const who = ref('')
const period = ref<'7' | '30' | 'all'>('30')
const since = computed(() => (period.value === 'all' ? null : Date.now() - Number(period.value) * 86400e3))
const recent = (iso: string | null) => since.value == null || (iso != null && Date.parse(iso) >= since.value)

const shownTrips = computed(() => trips.value.filter((tr) => (!who.value || tr.who === who.value) && recent(tr.at)))
const shownDevices = computed(() => devices.value.filter((d) => (!who.value || d.id === who.value) && recent(d.seen.at)))
const legend = computed(() => {
  const ids = new Set(shownTrips.value.map((tr) => tr.who))
  return uploaders.value.filter((u) => ids.has(u.id)).map((u) => ({ ...u, name: u.id ? u.name : t('mapUnknown'), colour: colour.value.get(u.id)! }))
})
const nothing = computed(() => !devices.value.length && !trips.value.length)

/** Leaflet popups are HTML: names and clients come from the server and are escaped. */
const esc = (s: unknown) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!)
const when = (s: string | null) => (s ? new Date(s).toLocaleString(lang.value, { dateStyle: 'medium', timeStyle: 'short' }) : '–')
const km = (m: number | null) => (m == null ? '–' : m < 1000 ? `${Math.round(m)} m` : `${(m / 1000).toFixed(1)} km`)
function duration(a: string | null, b: string | null) {
  const ms = a && b ? Date.parse(b) - Date.parse(a) : NaN
  if (!Number.isFinite(ms) || ms < 0) return '–'
  const min = Math.round(ms / 60000)
  return min < 60 ? `${min} min` : `${Math.floor(min / 60)} h ${String(min % 60).padStart(2, '0')}`
}
const line = (k: string, v: string) => `<div><span class="k">${esc(t(k))}</span> ${esc(v)}</div>`

function devicePopup(ds: Device[]) {
  return ds.map((d) => {
    const s = d.seen
    return `<div class="mpop"><b>${esc(d.name)}</b>`
      + line('seenAt', `${relTime(s.at)} (${when(s.at)})`)
      + line('seenPlace', [s.city, s.region, s.country].filter(Boolean).join(', ') || '–')
      + (s.network ? line('seenNetwork', s.network) : '')
      + line('seenClient', s.client || s.userAgent || '–')
      + '</div>'
  }).join('') + `<div class="mpop muted">${esc(t('seenApproxShort'))}</div>`
}

function tripPopup(tr: Trip) {
  const u = tr.item, tp = u.trip!
  return `<div class="mpop"><b>${esc(u.name || u.sessionId || u.filename)}</b>`
    + (u.name && u.sessionId ? line('tripSession', u.sessionId) : '')
    + line('tripStarted', when(tp.startedAt || u.createdAt))
    + (tp.endedAt ? line('tripDuration', duration(tp.startedAt, tp.endedAt)) : '')
    + line('tripDistance', km(tp.distanceM))
    + line('tripPhotos', tp.photos == null ? '–' : String(tp.photos))
    + line('tripBy', tr.name)
    + '</div>'
}

const el = ref<HTMLDivElement>()
let map: L.Map | null = null
let layer: L.LayerGroup

function draw() {
  if (!map) return
  layer.clearLayers()
  const bounds: L.LatLngExpression[] = []
  for (const tr of shownTrips.value) {
    const c = colour.value.get(tr.who) || UNKNOWN
    L.polyline(tr.pts, { color: '#fff', weight: 7, opacity: 0.8, interactive: false }).addTo(layer)   // a halo, to stand out on any tile
    L.polyline(tr.pts, { color: c, weight: 4, opacity: 0.95 }).bindPopup(tripPopup(tr)).addTo(layer)
    L.circleMarker(tr.pts[0], { radius: 4, color: '#fff', weight: 2, fillColor: c, fillOpacity: 1 }).bindPopup(tripPopup(tr)).addTo(layer)
    bounds.push(...tr.pts)
  }
  // devices at the same place (the IP lookup often gives one city centre) share one marker
  const at = new Map<string, Device[]>()
  for (const d of shownDevices.value) {
    const k = `${d.seen.lat.toFixed(4)},${d.seen.lon.toFixed(4)}`
    at.set(k, [...(at.get(k) || []), d])
  }
  for (const ds of at.values()) {
    const ll: [number, number] = [ds[0].seen.lat, ds[0].seen.lon]
    const one = ds.length === 1 ? ds[0] : null
    const fill = one && !one.builtin ? colour.value.get(one.id) || DEVICE : DEVICE
    L.circleMarker(ll, { radius: 8, color: '#fff', weight: 2, fillColor: fill, fillOpacity: 0.9 })
      .bindTooltip(ds.map((d) => esc(d.name)).join('<br>'), { permanent: true, direction: 'right', offset: [8, 0], className: 'devlabel' })
      .bindPopup(devicePopup(ds), { maxWidth: 320 })
      .addTo(layer)
    bounds.push(ll)
  }
  if (bounds.length) map.fitBounds(L.latLngBounds(bounds), { padding: [40, 40], maxZoom: 15 })
}

function mount() {
  if (map || !el.value) return
  map = L.map(el.value, { maxZoom: 19, zoomSnap: 0.5 })
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxNativeZoom: 19, maxZoom: 19, attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
  }).addTo(map)
  map.setView([10.776, 106.757], 11)
  layer = L.layerGroup().addTo(map)
  draw()
}
onMounted(mount)
onBeforeUnmount(() => { map?.remove(); map = null })
// the map element appears once there is something to show
watch(nothing, (v) => { if (v) { map?.remove(); map = null } else mount() }, { flush: 'post' })
watch([shownTrips, shownDevices, lang], draw)
</script>

<template>
  <div class="adminmap">
    <div class="row mapfilters">
      <label class="small">{{ t('mapUploader') }}
        <select v-model="who">
          <option value="">{{ t('mapAllUploaders') }}</option>
          <option v-for="p in people" :key="p.id" :value="p.id">{{ p.name }}</option>
        </select>
      </label>
      <label class="small">{{ t('mapPeriod') }}
        <select v-model="period">
          <option value="7">{{ t('mapPeriod7') }}</option>
          <option value="30">{{ t('mapPeriod30') }}</option>
          <option value="all">{{ t('mapPeriodAll') }}</option>
        </select>
      </label>
      <span class="muted small">{{ t('mapDevices', { n: shownDevices.length }) }} · {{ t('mapTrips', { n: shownTrips.length }) }}</span>
      <slot name="actions" />
    </div>
    <p v-if="nothing" class="empty soft small">{{ t('mapEmpty') }}</p>
    <template v-else>
      <p v-if="!shownTrips.length && !shownDevices.length" class="muted small">{{ t('mapNoneShown') }}</p>
      <div ref="el" class="amap"></div>
      <div v-if="legend.length" class="maplegend small">
        <span v-for="u in legend" :key="u.id"><i :style="{ background: u.colour }"></i>{{ u.name }}</span>
      </div>
      <p class="muted small">{{ t('seenApprox') }}</p>
    </template>
  </div>
</template>
