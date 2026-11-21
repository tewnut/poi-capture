<script setup lang="ts">
// All shown sessions on one map; the selected place gets its three pins (algorithm or contributor / Google /
// agent), the photo lines that located it, and the final pin the reviewer can drag.
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { state, reviewOf, gstat, openPlace, openDataset, openTask } from '../store'
import { t, lang } from '../i18n'
import type { Dataset, Place, Review } from '../types'

const emit = defineEmits<{ select: [ds: string, place: string]; movePin: [lat: number, lon: number] }>()
const el = ref<HTMLDivElement>()
let map: L.Map
let base: L.LayerGroup
let sel: L.LayerGroup
let fitted = false

const DECISION_COLOR: Record<string, string> = { confirmed: '#1f7a52', rejected: '#8a949c', visit: '#b7791f' }
const G_COLOR = { found: '#1659c7', new: '#e8590c', todo: '#6b7280' }
/** Leaflet tooltips are HTML: imported text (names, reasons) is escaped. */
const esc = (s: unknown) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!)

function pinOf(p: Place, r: Review) {
  return r.pin || p.pins.agent || p.pins.algorithm || p.pins.contributor!
}

function icon(p: Place, r: Review, selected: boolean, n: string) {
  const color = r.decision !== 'todo' ? DECISION_COLOR[r.decision] : G_COLOR[gstat(p)]
  const ring = p.status !== 'open' ? 'removal' : p.confidence === 'low' ? 'low' : ''
  return L.divIcon({
    className: '', iconSize: [26, 26], iconAnchor: [13, 13],
    html: `<div class="pin ${ring} ${selected ? 'sel' : ''} ${esc(r.decision)}" style="background:${color}">${r.decision === 'confirmed' ? '✓' : r.decision === 'rejected' ? '✕' : esc(n)}</div>`,
  })
}

function drawAll() {
  if (!map) return
  base.clearLayers()
  const bounds: L.LatLngExpression[] = []
  for (const d of state.datasets) {
    if (!state.shown.has(d.id)) continue
    drawDataset(d, bounds)
  }
  if (!fitted && bounds.length) { map.fitBounds(L.latLngBounds(bounds), { padding: [30, 30] }); fitted = true }
  drawSelected()
}

function drawDataset(d: Dataset, bounds: L.LatLngExpression[]) {
  const dim = state.open && state.open !== d.id
  if (d.track.length > 1) L.polyline(d.track, { color: '#7d8c84', weight: 4, opacity: dim ? 0.25 : 0.55 }).addTo(base)
  if (state.open === d.id)
    for (const s of d.shots) L.circleMarker([s[1], s[2]], { radius: 2.2, color: '#4b5a52', weight: 1, fillOpacity: 0.8 })
      .bindTooltip(t('photoN', { n: s[0] })).addTo(base)
  d.places.forEach((p, k) => {
    const r = reviewOf(d.id, p)
    const at = pinOf(p, r)
    bounds.push([at.lat, at.lon])
    const m = L.marker([at.lat, at.lon], { icon: icon(p, r, state.place === p.id, String(k + 1)), zIndexOffset: 500, opacity: dim ? 0.55 : 1 })
      .bindTooltip(esc(p.name)).addTo(base)
    m.on('click', () => emit('select', d.id, p.id))
  })
  for (const tk of d.tasks.filter((x) => x.type !== 'visit')) {
    L.marker([tk.lat, tk.lon], { icon: L.divIcon({ className: '', iconSize: [18, 18], iconAnchor: [9, 9], html: '<div class="rm">✕</div>' }) })
      .bindTooltip(t(tk.type === 'removal' ? 'removalTip' : 'verifyTip', { name: esc(tk.name) })).addTo(base)
  }
}

function drawSelected() {
  sel.clearLayers()
  const tk = openTask.value
  if (tk) {
    L.circle([tk.lat, tk.lon], { radius: 12, color: tk.type === 'visit' ? '#b7791f' : '#d6336c', weight: 3, fillOpacity: 0.15 })
      .bindTooltip(esc(tk.name)).addTo(sel)
  }
  const d = openDataset.value, p = openPlace.value
  if (!d || !p) return
  const r = reviewOf(d.id, p)
  const first = p.pins.algorithm || p.pins.contributor
  if (first) {
    for (const s of first.sightings || []) {
      L.polyline([[s.lat, s.lon], [first.lat, first.lon]], { color: '#1f7a52', weight: 2 }).addTo(sel)
      L.circleMarker([s.lat, s.lon], { radius: 4, color: '#1f7a52', fillColor: '#fff', fillOpacity: 1, weight: 2 }).bindTooltip(t('photoN', { n: s.i })).addTo(sel)
    }
    if (first.uncertaintyM) L.circle([first.lat, first.lon], { radius: first.uncertaintyM, color: '#1f7a52', weight: 1, fillOpacity: 0.05 }).addTo(sel)
    L.circleMarker([first.lat, first.lon], { radius: 7, color: '#1f7a52', weight: 3, fillColor: '#fff', fillOpacity: 1 })
      .bindTooltip(t(p.pins.algorithm ? 'algorithm' : 'contributor')).addTo(sel)
  }
  const g = p.pins.google
  if (g) {
    L.circleMarker([g.lat, g.lon], { radius: 7, color: '#1659c7', weight: 2, dashArray: '3 3', fillOpacity: 0.1 }).bindTooltip(t('googleTip', { name: esc(g.name) })).addTo(sel)
  }
  const a = p.pins.agent
  if (a) L.circleMarker([a.lat, a.lon], { radius: 7, color: '#7c3aed', weight: 3, fillColor: '#fff', fillOpacity: 1 }).bindTooltip(t('agentTip', { why: esc(a.why) })).addTo(sel)
  const fin = pinOf(p, r)
  const drag = L.marker([fin.lat, fin.lon], {
    draggable: true, zIndexOffset: 1000,
    icon: L.divIcon({ className: '', iconSize: [30, 40], iconAnchor: [15, 38], html: '<div class="final-pin"></div>' }),
  }).bindTooltip(t('finalPinTip')).addTo(sel)
  drag.on('dragend', () => { const ll = drag.getLatLng(); emit('movePin', ll.lat, ll.lng) })
}

function focusPlace() {
  const d = openDataset.value, p = openPlace.value
  if (!d || !p) return
  const r = reviewOf(d.id, p)
  const pts: L.LatLngExpression[] = [[pinOf(p, r).lat, pinOf(p, r).lon]]
  for (const s of (p.pins.algorithm || p.pins.contributor)?.sightings || []) pts.push([s.lat, s.lon])
  if (p.pins.google) pts.push([p.pins.google.lat, p.pins.google.lon])
  map.fitBounds(L.latLngBounds(pts), { padding: [60, 60], maxZoom: 20 })
}

function fitOpen() {
  const d = openDataset.value
  if (!d) return
  const pts: L.LatLngExpression[] = [...d.track, ...d.places.map((p) => [pinOf(p, reviewOf(d.id, p)).lat, pinOf(p, reviewOf(d.id, p)).lon] as [number, number])]
  if (pts.length) map.fitBounds(L.latLngBounds(pts), { padding: [30, 30] })
}

onMounted(() => {
  map = L.map(el.value!, { maxZoom: 21, zoomSnap: 0.5 })
  const osm = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxNativeZoom: 19, maxZoom: 21, attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
  }).addTo(map)
  const sat = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', {
    maxNativeZoom: 19, maxZoom: 21, attribution: 'Imagery &copy; Esri, Maxar, Earthstar Geographics',
  })
  L.control.layers({ [t('osmLayer')]: osm, [t('satLayer')]: sat }, undefined, { position: 'topright' }).addTo(map)
  map.setView([10.776, 106.757], 15)
  base = L.layerGroup().addTo(map)
  sel = L.layerGroup().addTo(map)
  drawAll()
})
// the map's box changes size without a window resize (the phone layout, the map shown above an open place)
let ro: ResizeObserver | null = null
onMounted(() => {
  if (typeof ResizeObserver === 'undefined') return
  ro = new ResizeObserver(() => map?.invalidateSize())
  ro.observe(el.value!)
})
onBeforeUnmount(() => { ro?.disconnect(); map?.remove() })

watch(() => [state.datasets.length, state.shown.size, state.open, state.reviews, lang.value], drawAll, { deep: true })
watch(() => state.place, () => { drawAll(); focusPlace() })
watch(() => [state.task, state.open], () => {
  drawAll()
  const tk = openTask.value
  if (tk) map.setView([tk.lat, tk.lon], Math.max(map.getZoom(), 19))
})
watch(() => state.open, (v) => { if (v && !state.place) fitOpen(); else if (!v) fitAll() })

/** Back on the session list: all the sessions shown. */
function fitAll() {
  const pts: L.LatLngExpression[] = []
  for (const d of state.datasets) {
    if (!state.shown.has(d.id)) continue
    pts.push(...d.track)
    for (const p of d.places) { const at = pinOf(p, reviewOf(d.id, p)); pts.push([at.lat, at.lon]) }
  }
  if (pts.length) map.fitBounds(L.latLngBounds(pts), { padding: [30, 30] })
}
/** The open place, or else the open task, or else the open session. */
function focusOpen() {
  map.invalidateSize()
  const tk = openTask.value
  if (openPlace.value) focusPlace()
  else if (tk) map.setView([tk.lat, tk.lon], Math.max(map.getZoom(), 19))
  else fitOpen()
}
defineExpose({ fitOpen, focusOpen })
</script>

<template>
  <div ref="el" class="map"></div>
</template>

<style>
.map { width: 100%; height: 100%; }
.pin { width: 26px; height: 26px; border-radius: 50%; color: #fff; font: 700 11px/22px system-ui; text-align: center;
  border: 2px solid #fff; box-shadow: 0 1px 4px rgba(0,0,0,.35); }
.pin.low { border-style: dashed; border-color: #222; }
.pin.removal { border-color: #d6336c; border-width: 3px; }
.pin.sel { transform: scale(1.3); box-shadow: 0 0 0 4px rgba(31,122,82,.35); }
.leaflet-marker-icon > .rm { width: 18px; height: 18px; border-radius: 4px; background: #d6336c; color: #fff; font: 700 11px/18px system-ui; text-align: center; }
.final-pin { width: 30px; height: 40px; background: no-repeat center/contain
  url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 30 40'%3E%3Cpath d='M15 39C15 39 2 22 2 14a13 13 0 0 1 26 0c0 8-13 25-13 25z' fill='%23f59f00' stroke='white' stroke-width='2'/%3E%3Ccircle cx='15' cy='14' r='5' fill='white'/%3E%3C/svg%3E"); }
</style>
