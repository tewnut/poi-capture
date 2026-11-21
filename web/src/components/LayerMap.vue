<script setup lang="ts">
// A map of generic layers, the "Map" view of the admin page's sections: markers (an access key's last-seen place)
// and polylines (a ride's track), each with a colour, a label and an HTML popup (escaped by the caller), and a
// legend. Markers at the same spot (the IP lookup often gives one city centre) share one marker.
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { esc } from '../adminfmt'

export type MapMarker = { id: string; lat: number; lon: number; colour: string; label: string; popup: string }
export type MapLine = { id: string; pts: [number, number][]; colour: string; popup: string }
export type LegendItem = { label: string; colour: string; kind?: 'line' | 'dot' }

const props = defineProps<{ markers?: MapMarker[]; lines?: MapLine[]; legend?: LegendItem[]; popupFooter?: string }>()

/** Several markers on one spot: a neutral colour. */
const SHARED = '#37474f'

const el = ref<HTMLDivElement>()
let map: L.Map | null = null
let layer: L.LayerGroup

function draw() {
  if (!map) return
  layer.clearLayers()
  const bounds: L.LatLngExpression[] = []
  for (const ln of props.lines || []) {
    if (!ln.pts.length) continue
    L.polyline(ln.pts, { color: '#fff', weight: 7, opacity: 0.8, interactive: false }).addTo(layer)   // a halo, to stand out on any tile
    L.polyline(ln.pts, { color: ln.colour, weight: 4, opacity: 0.95 }).bindPopup(ln.popup).addTo(layer)
    L.circleMarker(ln.pts[0], { radius: 4, color: '#fff', weight: 2, fillColor: ln.colour, fillOpacity: 1 }).bindPopup(ln.popup).addTo(layer)
    bounds.push(...ln.pts)
  }
  const at = new Map<string, MapMarker[]>()
  for (const m of props.markers || []) {
    const k = `${m.lat.toFixed(4)},${m.lon.toFixed(4)}`
    at.set(k, [...(at.get(k) || []), m])
  }
  for (const ms of at.values()) {
    const ll: [number, number] = [ms[0].lat, ms[0].lon]
    L.circleMarker(ll, { radius: 8, color: '#fff', weight: 2, fillColor: ms.length === 1 ? ms[0].colour : SHARED, fillOpacity: 0.9 })
      .bindTooltip(ms.map((m) => esc(m.label)).join('<br>'), { permanent: true, direction: 'right', offset: [8, 0], className: 'devlabel' })
      .bindPopup(ms.map((m) => m.popup).join('') + (props.popupFooter || ''), { maxWidth: 320 })
      .addTo(layer)
    bounds.push(ll)
  }
  if (bounds.length) map.fitBounds(L.latLngBounds(bounds), { padding: [40, 40], maxZoom: 15 })
}

onMounted(() => {
  if (!el.value) return
  map = L.map(el.value, { maxZoom: 19, zoomSnap: 0.5 })
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxNativeZoom: 19, maxZoom: 19, attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
  }).addTo(map)
  map.setView([10.776, 106.757], 11)
  layer = L.layerGroup().addTo(map)
  draw()
})
onBeforeUnmount(() => { map?.remove(); map = null })
watch(() => [props.markers, props.lines, props.popupFooter], draw)
</script>

<template>
  <div class="layermap">
    <div ref="el" class="amap"></div>
    <div v-if="legend?.length" class="maplegend small">
      <span v-for="u in legend" :key="u.label + u.colour"><i :class="u.kind || 'line'" :style="{ background: u.colour }"></i>{{ u.label }}</span>
    </div>
  </div>
</template>
