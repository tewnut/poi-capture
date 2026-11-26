// A floor plan drawn on the map (docs/indoor.md §7). Leaflet's ImageOverlay is axis-aligned; a plan is rotated, so
// this small layer puts an <img> in its own pane and pins three of its corners to the map with a CSS matrix
// (indoor.ts overlayMatrix), recomputed when the zoom changes (panning moves the whole pane, nothing to do). The
// image is hidden during the zoom animation (leaflet-zoom-hide) and shown again in place at its end.
import L from 'leaflet'
import { overlayMatrix, planCorners } from './indoor'
import type { FloorPlan } from './types'

export const PLAN_PANE = 'planPane'

export class PlanOverlay extends L.Layer {
  private img: HTMLImageElement | null = null
  private host: L.Map | null = null

  constructor(private plan: FloorPlan, private url: string, private opacity = 0.7) {
    super({ pane: PLAN_PANE } as L.LayerOptions)
  }

  onAdd(map: L.Map) {
    this.host = map
    const img = L.DomUtil.create('img', 'plan-overlay leaflet-image-layer leaflet-zoom-hide') as HTMLImageElement
    img.src = this.url
    img.alt = ''
    img.draggable = false
    Object.assign(img.style, {
      position: 'absolute', left: '0', top: '0', width: `${this.plan.size[0]}px`, height: `${this.plan.size[1]}px`,
      maxWidth: 'none', maxHeight: 'none', transformOrigin: '0 0', pointerEvents: 'none', opacity: String(this.opacity),
    })
    ;(map.getPane(PLAN_PANE) || map.getPanes().overlayPane).appendChild(img)
    this.img = img
    map.on('zoomend viewreset', this.update, this)
    this.update()
    return this
  }

  onRemove(map: L.Map) {
    map.off('zoomend viewreset', this.update, this)
    this.img?.remove()
    this.img = null
    this.host = null
    return this
  }

  setOpacity(o: number) {
    this.opacity = o
    if (this.img) this.img.style.opacity = String(o)
  }

  update() {
    if (!this.img || !this.host) return
    const [a, b, c] = planCorners(this.plan).map((ll) => this.host!.latLngToLayerPoint(ll))
    this.img.style.transform = `matrix(${overlayMatrix(a, b, c, this.plan.size[0], this.plan.size[1]).join(',')})`
  }
}
