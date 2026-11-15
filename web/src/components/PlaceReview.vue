<script setup lang="ts">
// One place: photos, what was read (each field with the photo it came from), the three pins, the decision.
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Photo from './Photo.vue'
import CopyLink from './CopyLink.vue'
import { to, shareUrl } from '../router'
import { reviewOf, putReview, gstat, photoUrl } from '../store'
import type { Dataset, Place, Review, FieldKey, PinSource } from '../types'
import { FIELD_KEYS, FIELD_LABEL } from '../types'

const props = defineProps<{ ds: Dataset; place: Place }>()
const emit = defineEmits<{ close: []; next: [] }>()

/** A plain copy to edit (stored reviews are reactive proxies, which structuredClone refuses). */
const clone = (x: Review): Review => JSON.parse(JSON.stringify(x))
const r = ref<Review>(clone(reviewOf(props.ds.id, props.place)))
watch(() => [props.ds.id, props.place.id], () => { r.value = clone(reviewOf(props.ds.id, props.place)); big.value = 0 })
const big = ref(0)
const photos = computed(() => [...props.place.photos].sort((a, b) => Number(!!b.cover) - Number(!!a.cover)))
const first = computed(() => props.place.pins.algorithm || props.place.pins.contributor)
const firstLabel = computed<PinSource>(() => (props.place.pins.algorithm ? 'algorithm' : 'contributor'))
const google = computed(() => props.place.google || null)
/** Only plain web links from imported data become clickable (no javascript: or data: URLs). */
const googleUrl = computed(() => { const u = google.value?.url; return typeof u === 'string' && /^https:\/\//i.test(u) ? u : null })
const pinSource = computed<PinSource>(() => r.value.pin?.source || (props.place.pins.agent ? 'agent' : firstLabel.value))

// positions: one chip each (algorithm or contributor / agent / your pin / Google); the info line shows the chip
// looked at. Picking algorithm or agent makes it the final pin; Google is for checking only, never the final pin.
type View = PinSource | 'google'
const view = ref<View>(pinSource.value)
watch(() => props.place.id, () => (view.value = pinSource.value))
const chips = computed(() => {
  const c: { key: View; label: string; color: string; can: boolean }[] = []
  if (first.value) c.push({ key: firstLabel.value, label: firstLabel.value === 'algorithm' ? 'Algo' : 'Contributor', color: 'green', can: true })
  if (props.place.pins.agent) c.push({ key: 'agent', label: 'Agent', color: 'purple', can: true })
  c.push({ key: 'manual', label: 'Pin', color: 'orange', can: false })
  if (props.place.pins.google || google.value) c.push({ key: 'google', label: 'Google', color: 'blue', can: false })
  return c
})
function pick(k: View) {
  view.value = k
  if (k === 'agent' || k === 'algorithm' || k === 'contributor') usePin(k)
}
const finalPin = computed(() => r.value.pin || (pinSource.value === 'agent' ? props.place.pins.agent : first.value))

// photos: full screen viewer and open in a new tab
const full = ref(false)
function step(d: number) { big.value = (big.value + d + photos.value.length) % photos.value.length }
async function newTab() {
  const u = await photoUrl(props.ds.id, photos.value[big.value]?.file)
  if (u) window.open(u, '_blank', 'noopener')
}
function onKey(e: KeyboardEvent) {
  if (!full.value) return
  if (e.key === 'Escape') full.value = false
  if (e.key === 'ArrowRight') step(1)
  if (e.key === 'ArrowLeft') step(-1)
}
onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))

function distM(a: { lat: number; lon: number }, b: { lat: number; lon: number }) {
  const x = (a.lon - b.lon) * 111320 * Math.cos((a.lat * Math.PI) / 180), y = (a.lat - b.lat) * 110540
  return Math.round(Math.hypot(x, y))
}

function fieldState(k: FieldKey) { return r.value.fields[k]?.state }
function readValue(k: FieldKey) { return props.place.fields[k]?.value ?? '' }
function shown(k: FieldKey) { return r.value.fields[k]?.value ?? readValue(k) }
function setField(k: FieldKey, state: 'ok' | 'edited' | 'rejected', value?: string) {
  r.value.fields[k] = { state, value: value ?? shown(k) }
  save()
}
function edit(k: FieldKey, e: Event) {
  const v = (e.target as HTMLInputElement).value
  setField(k, v === readValue(k) ? 'ok' : 'edited', v)
}
function usePin(src: PinSource) {
  const p = src === 'agent' ? props.place.pins.agent : first.value
  if (p) { r.value.pin = { lat: p.lat, lon: p.lon, source: src }; save() }
}
async function save() { await putReview(props.ds.id, props.place, r.value) }

/** Confirm the place: every read field not decided yet counts as checked unless the reading was unsure. */
async function decide(d: Review['decision']) {
  if (d === 'confirmed') {
    for (const k of FIELD_KEYS) {
      const f = props.place.fields[k]
      if (f && !r.value.fields[k] && f.sure !== 'low') r.value.fields[k] = { state: 'ok', value: f.value }
    }
    if (!r.value.pin) usePin(pinSource.value)
  }
  r.value.decision = d
  await save()
  if (d !== 'todo') emit('next')
}
defineExpose({ setPin: async (lat: number, lon: number) => { r.value.pin = { lat, lon, source: 'manual' }; await save() } })

const STATUS_LABEL: Record<string, string> = { open: 'open', closed: 'closed', for_rent: 'for rent', under_construction: 'under construction', gone: 'gone', unknown: 'unknown' }
</script>

<template>
  <div class="review">
    <header>
      <div>
        <h2>{{ place.name }}</h2>
        <div class="muted">
          <input class="cat" :value="r.category ?? place.category ?? ''" placeholder="category" @change="r.category = ($event.target as HTMLInputElement).value; save()" />
          · <span :class="['chip', place.status !== 'open' ? 'bad' : '']">{{ STATUS_LABEL[place.status] }}</span>
          <span v-if="place.confidence === 'low'" class="chip warn">name partly read</span>
          <span :class="['chip', 'g-' + gstat(place)]">{{ { found: 'on Google Maps', new: 'not on Google nearby', todo: 'Google not checked' }[gstat(place)] }}</span>
        </div>
      </div>
      <div class="hd"><CopyLink :url="shareUrl(to.place(ds.id, place.id))" /><button class="ghost" title="Close" @click="emit('close')">✕</button></div>
    </header>

    <div v-if="!photos.length" class="nophotos muted">No photo was kept for this place.</div>
    <div v-else class="photos">
      <div class="frame">
        <Photo class="big" :ds="ds.id" :path="photos[big]?.file" :alt="place.name" @click="full = true" />
        <div class="tools">
          <button title="Full screen" @click="full = true">⤢</button>
          <button title="Open the photo in a new tab" @click="newTab">↗</button>
        </div>
      </div>
      <div v-if="photos[big]?.why" class="why muted">#{{ photos[big]?.index }} · {{ photos[big]?.why }}</div>
      <div v-if="photos.length > 1" class="thumbs">
        <button v-for="(p, i) in photos" :key="p.file" :class="{ on: i === big }" @click="big = i"><Photo :ds="ds.id" :path="p.file" /></button>
      </div>
    </div>
    <p v-if="place.note" class="note">{{ place.note }}</p>

    <h3>What the photos say</h3>
    <table class="fields">
      <tr v-for="k in FIELD_KEYS.filter((k) => place.fields[k] || r.fields[k] || ['phone', 'houseNumber', 'openingHours'].includes(k))" :key="k" :class="fieldState(k)">
        <th>{{ FIELD_LABEL[k] }}<div v-if="place.fields[k]" class="src">photo #{{ place.fields[k]?.photo ?? '?' }}<span v-if="place.fields[k]?.sure && place.fields[k]?.sure !== 'high'"> · {{ place.fields[k]?.sure }}</span></div></th>
        <td><input :value="shown(k)" :placeholder="place.fields[k] ? '' : 'not on the photos'" @change="edit(k, $event)" /></td>
        <td class="acts">
          <button :class="['ok', { on: fieldState(k) === 'ok' || fieldState(k) === 'edited' }]" title="Correct" :disabled="!shown(k)" @click="setField(k, 'ok')">✓</button>
          <button :class="['no', { on: fieldState(k) === 'rejected' }]" title="Wrong: leave out" :disabled="!shown(k)" @click="setField(k, 'rejected')">✕</button>
        </td>
      </tr>
    </table>

    <h3>Position</h3>
    <div class="chips">
      <button v-for="c in chips" :key="c.key" :class="['pchip', c.color, { view: view === c.key, final: pinSource === c.key }]"
        :title="c.can ? 'Show, and use as the final pin' : c.key === 'google' ? 'Show (for checking only)' : 'Show'" @click="pick(c.key)">
        <i class="dot"></i>{{ c.label }}<span v-if="pinSource === c.key" class="tick">✓</span>
      </button>
    </div>
    <div class="pinfo">
      <template v-if="view === 'algorithm' || view === 'contributor'">
        <b>{{ view === 'algorithm' ? 'Algorithm' : 'Contributor' }}</b><span v-if="pinSource === view" class="fin"> · final pin</span><br />
        {{ first?.method }}<template v-if="first?.uncertaintyM">, ±{{ first.uncertaintyM }} m</template>.
        <template v-if="first?.sightings?.length"> Green lines on the map: the photos it was located from.</template>
      </template>
      <template v-else-if="view === 'agent' && place.pins.agent">
        <b>Agent</b><span v-if="pinSource === 'agent'" class="fin"> · final pin</span><br />
        {{ place.pins.agent.why }}<template v-if="first">, {{ distM(first, place.pins.agent) }} m from the {{ firstLabel }} pin</template>.
      </template>
      <template v-else-if="view === 'manual'">
        <b>Your pin</b><span v-if="pinSource === 'manual'" class="fin"> · final pin</span><br />
        Drag the orange pin on the map to the doorway; it becomes the final pin.
        <template v-if="finalPin"><br /><span class="muted">Now at {{ finalPin.lat.toFixed(6) }}, {{ finalPin.lon.toFixed(6) }}</span></template>
      </template>
      <template v-else-if="view === 'google'">
        <b>Google Maps</b> <span class="muted">· for checking only, never published</span><br />
        <template v-if="google && google.name">
          {{ google.name }}<template v-if="place.pins.google && first">, {{ distM(first, place.pins.google) }} m from the {{ firstLabel }} pin</template><br />
          <span v-if="google.address">{{ google.address }}<br /></span>
          <span v-if="google.phone">{{ google.phone }}<br /></span>
          <span v-if="google.website">{{ google.website }}<br /></span>
          <span v-if="google.openingHours" class="muted">{{ google.openingHours.join(' · ') }}<br /></span>
          <span v-if="google.businessStatus && google.businessStatus !== 'OPERATIONAL'" class="warn">Google: {{ google.businessStatus.toLowerCase().replace('_', ' ') }}<br /></span>
          <a v-if="googleUrl" :href="googleUrl" target="_blank" rel="noopener noreferrer">open on Google Maps</a>
        </template>
        <template v-else>Not found nearby: probably a place Google does not have.</template>
      </template>
    </div>

    <textarea :value="r.comment || ''" placeholder="Comment (optional)" @change="r.comment = ($event.target as HTMLTextAreaElement).value; save()"></textarea>

    <div v-if="full" class="viewer" @click.self="full = false">
      <Photo class="vimg" :ds="ds.id" :path="photos[big]?.file" :alt="place.name" />
      <div class="vbar">
        <span>{{ big + 1 }} / {{ photos.length }}<template v-if="photos[big]?.index != null"> · photo #{{ photos[big]?.index }}</template><template v-if="photos[big]?.why"> · {{ photos[big]?.why }}</template></span>
        <span>
          <button v-if="photos.length > 1" title="Previous (←)" @click="step(-1)">‹</button>
          <button v-if="photos.length > 1" title="Next (→)" @click="step(1)">›</button>
          <button title="Open in a new tab" @click="newTab">↗</button>
          <button title="Close (Esc)" @click="full = false">✕</button>
        </span>
      </div>
    </div>

    <footer>
      <button class="primary" @click="decide('confirmed')">{{ r.decision === 'confirmed' ? '✓ Confirmed' : 'Confirm' }}</button>
      <button class="secondary" @click="decide('visit')">{{ r.decision === 'visit' ? 'Visit queued' : 'Needs a visit' }}</button>
      <button class="danger" @click="decide('rejected')">{{ r.decision === 'rejected' ? 'Rejected' : 'Reject' }}</button>
      <button v-if="r.decision !== 'todo'" class="ghost" @click="decide('todo')">Undo</button>
    </footer>
  </div>
</template>

<style scoped>
.review { display: flex; flex-direction: column; gap: 10px; }
header { display: flex; justify-content: space-between; gap: 8px; align-items: flex-start; }
h2 { margin: 0; font-size: 19px; letter-spacing: -0.2px; }
h3 { margin: 8px 0 0; font-size: 13px; text-transform: uppercase; letter-spacing: .5px; color: var(--muted); }
.hd { display: flex; align-items: flex-start; gap: 2px; }
.cat { border: 0; border-bottom: 1px dashed var(--line); font: inherit; color: var(--muted); width: 140px; background: transparent; }
.photos .big { width: 100%; max-height: 300px; object-fit: contain; background: #111; border-radius: 10px; display: block; }
.nophotos { background: var(--canvas); border-radius: 10px; padding: 18px; text-align: center; font-size: 13px; }
.why { font-size: 12px; margin-top: 4px; }
.thumbs { display: flex; gap: 6px; margin-top: 6px; flex-wrap: wrap; }
.thumbs button { padding: 0; border: 2px solid transparent; border-radius: 6px; background: none; cursor: pointer; }
.thumbs button.on { border-color: var(--accent); }
.thumbs img { height: 54px; width: 80px; object-fit: cover; border-radius: 4px; display: block; }
.note { margin: 0; background: #fff8e6; border-radius: 8px; padding: 6px 9px; font-size: 13px; }
.fields { width: 100%; border-collapse: collapse; }
.fields th { text-align: left; font-weight: 600; font-size: 13px; width: 112px; vertical-align: top; padding: 6px 6px 6px 0; }
.fields .src { font-weight: 400; font-size: 11px; color: var(--muted); }
.fields td { padding: 4px 0; vertical-align: top; }
.fields input { width: 100%; padding: 6px 8px; border: 1px solid var(--line); border-radius: 8px; font: inherit; background: #fff; }
.fields tr.edited input { border-color: #b7791f; }
.fields tr.rejected input { text-decoration: line-through; color: var(--muted); }
.acts { width: 70px; text-align: right; white-space: nowrap; }
.acts button { width: 30px; height: 30px; border-radius: 8px; border: 1px solid var(--line); background: #fff; cursor: pointer; margin-left: 3px; }
.acts .ok.on { background: var(--accent); color: #fff; border-color: var(--accent); }
.acts .no.on { background: #d6336c; color: #fff; border-color: #d6336c; }
.acts button:disabled { opacity: .35; cursor: default; }
.chips { display: flex; gap: 6px; flex-wrap: wrap; }
.pchip { display: inline-flex; align-items: center; gap: 6px; padding: 4px 11px; border-radius: 999px; border: 1px solid var(--line);
  background: #fff; font-size: 13px; font-weight: 600; cursor: pointer; }
.pchip .dot { width: 9px; height: 9px; border-radius: 50%; display: inline-block; }
.pchip.green .dot { background: #1f7a52; } .pchip.purple .dot { background: #7c3aed; }
.pchip.orange .dot { background: #f59f00; } .pchip.blue .dot { background: #1659c7; border-radius: 2px; }
.pchip.view { border-color: var(--ink); }
.pchip.final { background: #f1faf5; border-color: var(--accent); color: #1b5e3c; }
.pchip.blue.view { background: #f5f8ff; }
.tick { font-size: 11px; }
.pinfo { font-size: 13px; line-height: 1.5; background: var(--canvas); border-radius: 10px; padding: 8px 10px; }
.pinfo .fin { color: var(--accent); font-weight: 600; }
.pinfo .warn { color: #9b1c47; }
.frame { position: relative; }
.frame .big { cursor: zoom-in; }
.tools { position: absolute; top: 8px; right: 8px; display: flex; gap: 6px; }
.tools button, .vbar button { width: 32px; height: 32px; border-radius: 8px; border: 0; background: rgba(0,0,0,.55); color: #fff; font-size: 16px; cursor: pointer; }
.tools button:hover, .vbar button:hover { background: rgba(0,0,0,.8); }
.viewer { position: fixed; inset: 0; background: rgba(0,0,0,.92); z-index: 4000; display: flex; flex-direction: column; }
.viewer .vimg { flex: 1; min-height: 0; width: 100%; object-fit: contain; }
.vbar { display: flex; justify-content: space-between; align-items: center; gap: 10px; padding: 10px 16px; color: #ddd; font-size: 13px; }
.vbar span:last-child { display: flex; gap: 6px; }
textarea { width: 100%; min-height: 54px; border: 1px solid var(--line); border-radius: 10px; padding: 8px; font: inherit; resize: vertical; }
footer { display: flex; gap: 8px; flex-wrap: wrap; position: sticky; bottom: 0; background: var(--card); padding: 8px 0 2px; }
.chip { display: inline-block; font-size: 11px; padding: 1px 7px; border-radius: 9px; background: #e7f6ec; color: #1b5e3c; font-weight: 600; margin-left: 2px; }
.chip.bad { background: #fde8ef; color: #9b1c47; } .chip.warn { background: #fdecea; color: #9b1c1c; }
.chip.g-found { background: #e8f0fe; color: #123f8c; } .chip.g-new { background: #fff0e6; color: #a23b05; } .chip.g-todo { background: #eef0f2; color: #555; }
</style>
