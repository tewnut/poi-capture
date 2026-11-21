<script setup lang="ts">
// Crop a photo: drag on the photo to draw the frame, drag inside it to move it, drag a corner to resize.
// Pointer events, so a mouse, a pen and a finger work alike; a second finger is ignored while one drags.
import { ref } from 'vue'
import { t } from '../i18n'
import type { CropRect } from '../types'

const props = defineProps<{ url: string; rect?: CropRect | null }>()
const emit = defineEmits<{ save: [rect: CropRect | null]; cancel: [] }>()

const box = ref<HTMLElement>()
const r = ref<CropRect>(props.rect ? [...props.rect] as CropRect : [0.1, 0.1, 0.8, 0.8])
let drag: null | { mode: 'draw' | 'move'; x0: number; y0: number; r0: CropRect; id: number } = null

const clamp = (v: number, lo = 0, hi = 1) => Math.min(hi, Math.max(lo, v))
function pt(e: PointerEvent) {
  const b = box.value!.getBoundingClientRect()
  return [clamp((e.clientX - b.left) / b.width), clamp((e.clientY - b.top) / b.height)]
}
function down(e: PointerEvent) {
  if (drag) return
  const [x, y] = pt(e)
  const [rx, ry, rw, rh] = r.value
  const inside = x > rx && x < rx + rw && y > ry && y < ry + rh
  drag = { mode: inside ? 'move' : 'draw', x0: x, y0: y, r0: [...r.value] as CropRect, id: e.pointerId }
  box.value!.setPointerCapture(e.pointerId)
}
/** A corner: draw from the opposite corner. */
function corner(e: PointerEvent, cx: 0 | 1, cy: 0 | 1) {
  if (drag) return
  const [rx, ry, rw, rh] = r.value
  drag = { mode: 'draw', x0: cx ? rx : rx + rw, y0: cy ? ry : ry + rh, r0: [...r.value] as CropRect, id: e.pointerId }
  box.value!.setPointerCapture(e.pointerId)
}
function move(e: PointerEvent) {
  if (!drag || e.pointerId !== drag.id) return
  const [x, y] = pt(e)
  if (drag.mode === 'draw') {
    r.value = [Math.min(drag.x0, x), Math.min(drag.y0, y), Math.abs(x - drag.x0), Math.abs(y - drag.y0)]
  } else {
    const [rx, ry, rw, rh] = drag.r0
    r.value = [clamp(rx + x - drag.x0, 0, 1 - rw), clamp(ry + y - drag.y0, 0, 1 - rh), rw, rh]
  }
}
function up(e: PointerEvent) {
  if (!drag || e.pointerId !== drag.id) return
  if ( (r.value[2] < 0.03 || r.value[3] < 0.03)) r.value = drag.r0   // a click, not a frame
  drag = null
}
const pct = (v: number) => `${(v * 100).toFixed(3)}%`
</script>

<template>
  <div class="crop" data-noswipe>
    <div class="stage">
      <div ref="box" class="box" @pointerdown.prevent="down" @pointermove="move" @pointerup="up" @pointercancel="up">
        <img :src="url" alt="" draggable="false" />
        <div class="frame" :style="{ left: pct(r[0]), top: pct(r[1]), width: pct(r[2]), height: pct(r[3]) }">
          <i class="h nw" @pointerdown.stop.prevent="corner($event, 0, 0)"></i>
          <i class="h ne" @pointerdown.stop.prevent="corner($event, 1, 0)"></i>
          <i class="h sw" @pointerdown.stop.prevent="corner($event, 0, 1)"></i>
          <i class="h se" @pointerdown.stop.prevent="corner($event, 1, 1)"></i>
        </div>
      </div>
    </div>
    <div class="bar">
      <span class="muted">{{ t('cropHint') }}</span>
      <span>
        <button v-if="rect" class="whole" @click="emit('save', null)">{{ t('cropWhole') }}</button>
        <button @click="emit('cancel')">{{ t('cancel') }}</button>
        <button class="primary" @click="emit('save', r.map((v) => +v.toFixed(4)) as CropRect)">{{ t('cropSave') }}</button>
      </span>
    </div>
  </div>
</template>

<style scoped>
.crop { position: fixed; inset: 0; z-index: 4100; background: #0b0b0b; display: flex; flex-direction: column; }
.stage { flex: 1; min-height: 0; display: grid; place-items: center; padding: 16px; }
.box { position: relative; touch-action: none; user-select: none; cursor: crosshair; overflow: hidden; line-height: 0; }
.box img { display: block; max-width: calc(100vw - 32px); max-height: calc(100vh - 100px); max-height: calc(100dvh - 100px); }
.frame { position: absolute; box-shadow: 0 0 0 9999px rgba(0,0,0,.6); outline: 2px solid #fff; cursor: move; }
.h { position: absolute; width: 16px; height: 16px; background: #fff; border-radius: 3px; }
.nw { left: -8px; top: -8px; cursor: nwse-resize; } .se { right: -8px; bottom: -8px; cursor: nwse-resize; }
.ne { right: -8px; top: -8px; cursor: nesw-resize; } .sw { left: -8px; bottom: -8px; cursor: nesw-resize; }
.bar { display: flex; justify-content: space-between; align-items: center; gap: 10px; padding: 10px 16px; color: #ddd; font-size: 13px; flex-wrap: wrap; }
.bar span:last-child { display: flex; gap: 8px; }
.bar button { padding: 7px 14px; border-radius: 8px; border: 0; background: rgba(255,255,255,.15); color: #fff; font: inherit; cursor: pointer; }
.bar button.primary { background: var(--accent); }
.muted { color: #aaa; }
.h::after { content: ''; position: absolute; inset: -6px; }   /* a bigger grip than the square shows */
@media (pointer: coarse) {
  .h { width: 22px; height: 22px; }
  .nw { left: -11px; top: -11px; } .se { right: -11px; bottom: -11px; }
  .ne { right: -11px; top: -11px; } .sw { left: -11px; bottom: -11px; }
  .h::after { inset: -11px; }   /* 44 px to grab */
  .bar button { min-height: 44px; padding: 10px 16px; }
}
/* phone: the photo fills the screen, the hint and the buttons below it, Save the widest */
@media (max-width: 700px) {
  .crop { padding: env(safe-area-inset-top) env(safe-area-inset-right) 0 env(safe-area-inset-left); }
  .stage { padding: 14px 12px; }
  .box img { max-width: calc(100vw - 24px); max-height: calc(100vh - 170px); max-height: calc(100dvh - 170px - env(safe-area-inset-top) - env(safe-area-inset-bottom)); }
  .bar { flex-direction: column; align-items: stretch; gap: 8px; padding: 8px 12px calc(10px + env(safe-area-inset-bottom)); }
  .bar .muted { text-align: center; font-size: 12px; }
  .bar span:last-child { display: grid; grid-template-columns: 1fr 1fr; }
  .bar button { min-height: 48px; font-size: 15px; }
  .bar button.whole { grid-column: 1 / -1; }
  .bar button.primary { font-weight: 700; }
}
</style>
