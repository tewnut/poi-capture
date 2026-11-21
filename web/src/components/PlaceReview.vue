<script setup lang="ts">
// One place: photos, what was read (each field with the photo it came from), the three pins, the decision.
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Photo from './Photo.vue'
import CropEditor from './CropEditor.vue'
import { reviewOf, putReview, gstat, photoUrl, putPhoto, delPhoto, cropPhoto } from '../store'
import { keptPhotos, removedPhotos } from '../photos'
import { confirmFields, pendingSuggestions, suggestionKind, missingStreet } from '../fields'
import { t } from '../i18n'
import type { Dataset, Place, Review, FieldKey, PinSource, PhotoEdits, CropRect } from '../types'
import { FIELD_KEYS } from '../types'

const props = defineProps<{ ds: Dataset; place: Place }>()
const emit = defineEmits<{ close: []; next: [] }>()

/** A plain copy to edit (stored reviews are reactive proxies, which structuredClone refuses). */
const clone = (x: Review): Review => JSON.parse(JSON.stringify(x))
const r = ref<Review>(clone(reviewOf(props.ds.id, props.place)))
watch(() => [props.ds.id, props.place.id], () => { r.value = clone(reviewOf(props.ds.id, props.place)); big.value = 0; openMarks.value = null })
const big = ref(0)
/** The field whose source dots are spelled out (tap on the dots; the same words are on hover). */
const openMarks = ref<FieldKey | null>(null)
const photos = computed(() => keptPhotos(props.place, r.value))
const removed = computed(() => removedPhotos(props.place, r.value))
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
  if (first.value) c.push({ key: firstLabel.value, label: t(firstLabel.value === 'algorithm' ? 'chipAlgo' : 'chipContributor'), color: 'green', can: true })
  if (props.place.pins.agent) c.push({ key: 'agent', label: t('chipAgent'), color: 'purple', can: true })
  c.push({ key: 'manual', label: t('chipPin'), color: 'orange', can: false })
  if (props.place.pins.google || google.value) c.push({ key: 'google', label: t('chipGoogle'), color: 'blue', can: false })
  return c
})
function pick(k: View) {
  view.value = k
  if (k === 'agent' || k === 'algorithm' || k === 'contributor') usePin(k)
}
const finalPin = computed(() => r.value.pin || (pinSource.value === 'agent' ? props.place.pins.agent : first.value))
const firstName = computed(() => t(firstLabel.value))

// photos: full screen viewer and open in a new tab
const full = ref(false)
function step(d: number) { big.value = (big.value + d + photos.value.length) % photos.value.length }
async function newTab() {
  const u = await photoUrl(props.ds.id, photos.value[big.value]?.show)
  if (u) window.open(u, '_blank', 'noopener')
}
// photo edits: remove / restore, add, crop, cover. Imported photos are never changed (types.ts PhotoEdits).
const edits = (): PhotoEdits => (r.value.photos ||= {})
const busy = ref('')
async function removePhoto() {
  const p = photos.value[big.value]
  if (!p) return
  const e = edits()
  const crop = e.crops?.[p.file]
  if (p.added) {                          // the reviewer's own photo: gone for good
    e.added = (e.added || []).filter((f) => f !== p.file)
    await delPhoto(props.ds.id, p.file)
    if (crop) { await delPhoto(props.ds.id, crop.file); delete e.crops![p.file] }
  } else {
    e.removed = [...(e.removed || []), p.file]
  }
  if (e.cover === p.file) delete e.cover
  big.value = Math.max(0, Math.min(big.value, photos.value.length - 1))
  if (!photos.value.length) full.value = false
  await save()
}
async function restore(file: string) {
  const e = edits()
  e.removed = (e.removed || []).filter((f) => f !== file)
  await save()
}
async function makeCover() {
  const p = photos.value[big.value]
  if (!p) return
  edits().cover = p.file
  big.value = 0
  await save()
}
const picker = ref<HTMLInputElement>()
const IMG = /^image\/(jpeg|png|webp)$/
async function addPhotos(list: FileList | File[] | null | undefined) {
  const files = [...(list || [])].filter((f) => IMG.test(f.type))
  if (!files.length) return
  busy.value = t('adding')
  try {
    const e = edits()
    for (const [k, f] of files.entries()) {
      const ext = f.type === 'image/png' ? 'png' : f.type === 'image/webp' ? 'webp' : 'jpg'
      const path = `added/${props.place.id}/${Date.now().toString(36)}-${k}.${ext}`
      await putPhoto(props.ds.id, path, new Blob([f], { type: f.type }))
      e.added = [...(e.added || []), path]
    }
    await save()
    big.value = photos.value.length - 1
  } finally { busy.value = '' }
}
function onPick(ev: Event) {
  const i = ev.target as HTMLInputElement
  addPhotos(i.files).finally(() => (i.value = ''))
}
const dropOver = ref(false)
function onDrop(ev: DragEvent) {
  dropOver.value = false
  addPhotos(ev.dataTransfer?.files)
}
/** Paste a screenshot or copied image anywhere in the panel (not while typing in a field). */
function onPaste(ev: ClipboardEvent) {
  const fs = [...(ev.clipboardData?.files || [])]
  if (fs.length && !(ev.target as HTMLElement)?.closest?.('input, textarea')) { ev.preventDefault(); addPhotos(fs) }
}
const cropping = ref<{ file: string; url: string; rect: CropRect | null } | null>(null)
async function startCrop() {
  const p = photos.value[big.value]
  if (!p) return
  const url = await photoUrl(props.ds.id, p.file)    // always crop from the original
  if (url) cropping.value = { file: p.file, url, rect: r.value.photos?.crops?.[p.file]?.rect || null }
}
async function saveCrop(rect: CropRect | null) {
  const c = cropping.value
  cropping.value = null
  if (!c) return
  const e = edits()
  const old = e.crops?.[c.file]
  busy.value = t('cropping')
  try {
    if (rect && !(rect[0] <= 0.005 && rect[1] <= 0.005 && rect[2] >= 0.99 && rect[3] >= 0.99)) {
      const path = `crop/${props.place.id}/${Date.now().toString(36)}.jpg`
      await putPhoto(props.ds.id, path, await cropPhoto(props.ds.id, c.file, rect))
      e.crops = { ...(e.crops || {}), [c.file]: { rect, file: path } }
    } else if (e.crops) {
      delete e.crops[c.file]
    }
    if (old) await delPhoto(props.ds.id, old.file)
    await save()
  } finally { busy.value = '' }
}
const edited = computed(() => {
  const e = r.value.photos
  return !!e && !!(e.removed?.length || e.added?.length || Object.keys(e.crops || {}).length || e.cover)
})

function onKey(e: KeyboardEvent) {
  if (cropping.value) { if (e.key === 'Escape') cropping.value = null; return }
  if (!full.value) return
  if (e.key === 'Escape') full.value = false
  if (e.key === 'ArrowRight') step(1)
  if (e.key === 'ArrowLeft') step(-1)
}
onMounted(() => { window.addEventListener('keydown', onKey); window.addEventListener('paste', onPaste) })
onBeforeUnmount(() => { window.removeEventListener('keydown', onKey); window.removeEventListener('paste', onPaste) })

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

/** Confirm the place: the fields not decided yet are accepted as fields.ts says (clear readings, Google and website
 *  values); suggestions (unsure readings, the ride's road) stay open, shown, until the reviewer accepts them. */
async function decide(d: Review['decision']) {
  if (d === 'confirmed') {
    r.value.fields = confirmFields(props.place, r.value)
    if (!r.value.pin) usePin(pinSource.value)
  }
  r.value.decision = d
  await save()
  if (d !== 'todo') emit('next')
}
defineExpose({ setPin: async (lat: number, lon: number) => { r.value.pin = { lat, lon, source: 'manual' }; await save() } })
const G_LABEL = { found: 'gFound', new: 'gNew', todo: 'gTodo' } as const
const pending = computed(() => pendingSuggestions(props.place, r.value))
const noStreet = computed(() => missingStreet(props.place, r.value))
/** A suggestion not decided yet: 'low' (unsure reading) or 'ride' (the road the photos were taken on). */
function suggestion(k: FieldKey) { return fieldState(k) ? null : suggestionKind(props.place.fields[k]) }
function rowClass(k: FieldKey) {
  return [fieldState(k), suggestion(k) ? 'suggest' : '', k === 'street' && noStreet.value ? 'missing' : '']
}

/** Where a field's value came from and how it compares, as small dots (the words on hover). */
function marks(k: FieldKey) {
  const f = props.place.fields[k]
  if (!f) return []
  const out: { cls: string; tip: string }[] = []
  const src = f.source === 'google' ? 'Google' : f.source === 'website' ? 'website' : null
  if (src && f.clarified) out.push({ cls: f.source === 'google' ? 'g' : 'w', tip: t('clarifiedBy', { src, v: f.photoValue || '' }) })
  else if (f.source === 'google') out.push({ cls: 'g', tip: t('fromGoogle') + (f.note ? ` · ${t('googleOneDay')}` : '') })
  else if (f.source === 'website') out.push({ cls: 'w', tip: t('fromWebsite') })
  if (f.google === 'match') out.push({ cls: 'ok', tip: t('matchesGoogle') })
  if (f.google === 'differs') out.push({ cls: 'warn', tip: `Google: ${f.googleValue || ''}` })
  if (f.website === 'match') out.push({ cls: 'ok', tip: t('matchesWebsite') })
  if (f.website === 'differs') out.push({ cls: 'warn', tip: `Website: ${f.websiteValue || ''}` })
  if (f.source === 'ride') out.push({ cls: 'r', tip: t('fromRide') })
  if (f.ride === 'differs') out.push({ cls: 'warn', tip: t('rideDiffers', { v: f.rideValue || '' }) })
  return out
}
</script>

<template>
  <div class="review">
    <header>
      <div>
        <h2>{{ place.name }}</h2>
        <div class="muted">
          <input class="cat" :value="r.category ?? place.category ?? ''" :placeholder="t('category')" @change="r.category = ($event.target as HTMLInputElement).value; save()" />
          · <span :class="['chip', place.status !== 'open' ? 'bad' : '']">{{ t('status_' + place.status) }}</span>
          <span v-if="place.confidence === 'low'" class="chip warn">{{ t('namePartly') }}</span>
          <span v-if="noStreet" class="chip warn">{{ t('noStreet') }}</span>
          <span :class="['chip', 'g-' + gstat(place)]">{{ t(G_LABEL[gstat(place)]) }}</span>
        </div>
      </div>
      <button class="ghost closex" :title="t('close')" :aria-label="t('close')" @click="emit('close')">✕</button>
    </header>

    <div :class="['photobox', { over: dropOver }]" @dragover.prevent.stop="dropOver = true" @dragleave="dropOver = false" @drop.prevent.stop="onDrop">
      <div v-if="!photos.length" class="nophotos muted">{{ t('noPhotoKept') }}</div>
      <div v-else class="photos">
        <div class="frame">
          <Photo class="big" :ds="ds.id" :path="photos[big]?.show" :alt="place.name" @click="full = true" />
          <div class="tools">
            <button :title="t('cropT')" :aria-label="t('cropT')" @click="startCrop">✂</button>
            <button v-if="!photos[big]?.cover" :title="t('makeCover')" :aria-label="t('makeCover')" @click="makeCover">★</button>
            <button :title="photos[big]?.added ? t('deleteAdded') : t('removePhoto')" :aria-label="photos[big]?.added ? t('deleteAdded') : t('removePhoto')" @click="removePhoto">🗑</button>
            <button :title="t('fullScreen')" :aria-label="t('fullScreen')" @click="full = true">⤢</button>
            <button :title="t('newTab')" :aria-label="t('newTab')" @click="newTab">↗</button>
          </div>
        </div>
        <div class="why muted">
          <span v-if="photos[big]?.cover && photos.length > 1" class="tag">{{ t('cover') }}</span>
          <span v-if="photos[big]?.added" class="tag">{{ t('addedByYou') }}</span>
          <span v-if="photos[big]?.cropped" class="tag">{{ t('croppedTag') }}</span>
          <template v-if="photos[big]?.index != null">#{{ photos[big]?.index }}</template>
          <template v-if="photos[big]?.why"> · {{ photos[big]?.why }}</template>
        </div>
      </div>
      <div class="thumbs" data-strip>
        <template v-if="photos.length > 1">
          <button v-for="(p, i) in photos" :key="p.file" :class="{ on: i === big }" @click="big = i"><Photo :ds="ds.id" :path="p.show" /></button>
        </template>
        <button class="addph" :title="t('addPhotosT')" :disabled="!!busy" @click="picker?.click()">{{ busy || '+ ' + t('addPhotos') }}</button>
        <input ref="picker" type="file" accept="image/jpeg,image/png,image/webp" multiple hidden @change="onPick" />
      </div>
      <div v-if="removed.length" class="removed muted">
        {{ t('removedN', { n: removed.length }) }}
        <button v-for="p in removed" :key="p.file" :title="t('restore')" @click="restore(p.file)"><Photo :ds="ds.id" :path="p.file" /><span>↺</span></button>
      </div>
      <div v-if="edited" class="muted small">{{ t('photoEditsNote') }}</div>
    </div>
    <p v-if="place.note" class="note">{{ place.note }}</p>

    <h3>{{ t('whatPhotosSay') }}</h3>
    <table class="fields">
      <tr v-for="k in FIELD_KEYS.filter((k) => place.fields[k] || r.fields[k] || ['phone', 'houseNumber', 'street', 'openingHours'].includes(k))" :key="k" :class="rowClass(k)">
        <th>{{ t('field.' + k) }}
          <button v-if="marks(k).length" type="button" class="marks" :aria-expanded="openMarks === k" :aria-label="t('marksT')"
            @click="openMarks = openMarks === k ? null : k"><span v-for="(m, i) in marks(k)" :key="i" :class="['mark', m.cls]" :title="m.tip"></span></button>
          <ul v-if="openMarks === k" class="marktips"><li v-for="(m, i) in marks(k)" :key="i"><span :class="['mark', m.cls]"></span>{{ m.tip }}</li></ul>
          <div v-if="place.fields[k] && !place.fields[k]?.source" class="src">{{ t('photoN', { n: place.fields[k]?.photo ?? '?' }) }}<span v-if="place.fields[k]?.sure && place.fields[k]?.sure !== 'high'"> · {{ t('sure_' + place.fields[k]?.sure) }}</span></div>
          <div v-if="suggestion(k)" class="sugg">{{ t(suggestion(k) === 'ride' ? 'suggestRide' : 'suggestLow') }}</div>
        </th>
        <td><input :value="shown(k)" :placeholder="place.fields[k] ? '' : k === 'street' ? t('streetMissing') : t('notOnPhotos')" @change="edit(k, $event)" /></td>
        <td class="acts">
          <button :class="['ok', { on: fieldState(k) === 'ok' || fieldState(k) === 'edited' }]" :title="t('correct')" :disabled="!shown(k)" @click="setField(k, 'ok')">✓</button>
          <button :class="['no', { on: fieldState(k) === 'rejected' }]" :title="t('wrongLeaveOut')" :disabled="!shown(k)" @click="setField(k, 'rejected')">✕</button>
        </td>
      </tr>
    </table>
    <p v-if="pending.length" :class="['pending', { after: r.decision === 'confirmed' }]">
      {{ t(r.decision === 'confirmed' ? 'pendingAfterConfirm' : 'pendingBeforeConfirm', { f: pending.map((k) => t('field.' + k)).join(', ') }) }}
    </p>

    <h3>{{ t('position') }}</h3>
    <div class="chips">
      <button v-for="c in chips" :key="c.key" :class="['pchip', c.color, { view: view === c.key, final: pinSource === c.key }]"
        :title="c.can ? t('chipUse') : c.key === 'google' ? t('chipShowGoogle') : t('chipShow')" @click="pick(c.key)">
        <i class="dot"></i>{{ c.label }}<span v-if="pinSource === c.key" class="tick">✓</span>
      </button>
    </div>
    <div class="pinfo">
      <template v-if="view === 'algorithm' || view === 'contributor'">
        <b>{{ t(view === 'algorithm' ? 'algorithmT' : 'contributorT') }}</b><span v-if="pinSource === view" class="fin"> · {{ t('finalPin') }}</span><br />
        {{ first?.method }}<template v-if="first?.uncertaintyM">, ±{{ first.uncertaintyM }} m</template>.
        <template v-if="first?.sightings?.length"> {{ t('greenLines') }}</template>
      </template>
      <template v-else-if="view === 'agent' && place.pins.agent">
        <b>{{ t('chipAgent') }}</b><span v-if="pinSource === 'agent'" class="fin"> · {{ t('finalPin') }}</span><br />
        {{ place.pins.agent.why }}<template v-if="first">, {{ t('fromPin', { m: distM(first, place.pins.agent), pin: firstName }) }}</template>.
      </template>
      <template v-else-if="view === 'manual'">
        <b>{{ t('yourPin') }}</b><span v-if="pinSource === 'manual'" class="fin"> · {{ t('finalPin') }}</span><br />
        {{ t('dragPin') }}
        <template v-if="finalPin"><br /><span class="muted">{{ t('nowAt') }} {{ finalPin.lat.toFixed(6) }}, {{ finalPin.lon.toFixed(6) }}</span></template>
      </template>
      <template v-else-if="view === 'google'">
        <b>{{ t('googleMaps') }}</b> <span class="muted">· {{ t('checkingOnly') }}</span><br />
        <template v-if="google && google.name">
          {{ google.name }}<template v-if="place.pins.google && first">, {{ t('fromPin', { m: distM(first, place.pins.google), pin: firstName }) }}</template><br />
          <span v-if="google.address">{{ google.address }}<br /></span>
          <span v-if="google.phone">{{ google.phone }}<br /></span>
          <span v-if="google.website">{{ google.website }}<br /></span>
          <span v-if="google.openingHours" class="muted">{{ google.openingHours.join(' · ') }}<br /></span>
          <span v-if="google.businessStatus && google.businessStatus !== 'OPERATIONAL'" class="warn">Google: {{ google.businessStatus.toLowerCase().replace('_', ' ') }}<br /></span>
          <a v-if="googleUrl" :href="googleUrl" target="_blank" rel="noopener noreferrer">{{ t('openGoogle') }}</a>
        </template>
        <template v-else>{{ t('googleNotFound') }}</template>
      </template>
    </div>

    <textarea :value="r.comment || ''" :placeholder="t('comment')" @change="r.comment = ($event.target as HTMLTextAreaElement).value; save()"></textarea>

    <div v-if="full" class="viewer" @click.self="full = false">
      <Photo class="vimg" :ds="ds.id" :path="photos[big]?.show" :alt="place.name" />
      <div class="vbar">
        <span>{{ big + 1 }} / {{ photos.length }}<template v-if="photos[big]?.index != null"> · {{ t('photoN', { n: photos[big]?.index }) }}</template><template v-if="photos[big]?.why"> · {{ photos[big]?.why }}</template></span>
        <span>
          <button v-if="photos.length > 1" :title="t('prev')" :aria-label="t('prev')" @click="step(-1)">‹</button>
          <button v-if="photos.length > 1" :title="t('next')" :aria-label="t('next')" @click="step(1)">›</button>
          <button :title="t('cropT')" :aria-label="t('cropT')" @click="startCrop">✂</button>
          <button v-if="!photos[big]?.cover" :title="t('makeCover')" :aria-label="t('makeCover')" @click="makeCover">★</button>
          <button :title="photos[big]?.added ? t('deleteAdded') : t('removePhoto')" :aria-label="photos[big]?.added ? t('deleteAdded') : t('removePhoto')" @click="removePhoto">🗑</button>
          <button :title="t('newTab')" :aria-label="t('newTab')" @click="newTab">↗</button>
          <button :title="t('closeEsc')" :aria-label="t('closeEsc')" @click="full = false">✕</button>
        </span>
      </div>
    </div>

    <CropEditor v-if="cropping" :url="cropping.url" :rect="cropping.rect" @save="saveCrop" @cancel="cropping = null" />

    <footer>
      <button class="primary" @click="decide('confirmed')">{{ r.decision === 'confirmed' ? t('confirmedBtn') : t('confirm') }}</button>
      <button class="secondary" @click="decide('visit')">{{ r.decision === 'visit' ? t('visitQueued') : t('needsVisit') }}</button>
      <button class="danger" @click="decide('rejected')">{{ r.decision === 'rejected' ? t('rejectedF') : t('reject') }}</button>
      <button v-if="r.decision !== 'todo'" class="ghost" @click="decide('todo')">{{ t('undo') }}</button>
    </footer>
  </div>
</template>

<style scoped>
.review { display: flex; flex-direction: column; gap: 10px; }
header { display: flex; justify-content: space-between; gap: 8px; align-items: flex-start; }
h2 { margin: 0; font-size: 19px; letter-spacing: -0.2px; }
h3 { margin: 8px 0 0; font-size: 13px; text-transform: uppercase; letter-spacing: .5px; color: var(--muted); }
.cat { border: 0; border-bottom: 1px dashed var(--line); font: inherit; color: var(--muted); width: 140px; background: transparent; }
.photos .big { width: 100%; max-height: 300px; object-fit: contain; background: #111; border-radius: 10px; display: block; }
.nophotos { background: var(--canvas); border-radius: 10px; padding: 18px; text-align: center; font-size: 13px; }
.why { font-size: 12px; margin-top: 4px; }
.thumbs { display: flex; gap: 6px; margin-top: 6px; flex-wrap: wrap; }
.thumbs button { padding: 0; border: 2px solid transparent; border-radius: 6px; background: none; cursor: pointer; }
.thumbs button.on { border-color: var(--accent); }
.thumbs img { height: 54px; width: 80px; object-fit: cover; border-radius: 4px; display: block; }
.thumbs .addph { height: 58px; min-width: 84px; padding: 0 10px; border: 1.5px dashed var(--line); border-radius: 6px; color: var(--muted); font-size: 12px; font-weight: 600; }
.thumbs .addph:hover { border-color: var(--accent); color: var(--accent); }
.photobox { border-radius: 12px; outline: 2px dashed transparent; outline-offset: 3px; }
.photobox.over { outline-color: var(--accent); }
.tag { display: inline-block; font-size: 11px; padding: 0 6px; border-radius: 8px; background: #eef0f2; color: #333; font-weight: 600; margin-right: 4px; }
.removed { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; font-size: 12px; margin-top: 6px; }
.removed button { position: relative; padding: 0; border: 0; background: none; cursor: pointer; opacity: .55; }
.removed button:hover { opacity: 1; }
.removed img { height: 36px; width: 54px; object-fit: cover; border-radius: 4px; display: block; filter: grayscale(1); }
.removed span { position: absolute; inset: 0; display: grid; place-items: center; color: #fff; font-weight: 700; text-shadow: 0 0 3px #000; }
.small { font-size: 12px; }
.note { margin: 0; background: #fff8e6; border-radius: 8px; padding: 6px 9px; font-size: 13px; }
.fields { width: 100%; border-collapse: collapse; }
.fields th { text-align: left; font-weight: 600; font-size: 13px; width: 112px; vertical-align: top; padding: 6px 6px 6px 0; }
.fields .src { font-weight: 400; font-size: 11px; color: var(--muted); }
.mark { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-left: 4px; vertical-align: 1px; cursor: help; }
.marks { border: 0; background: none; padding: 0 2px; margin: 0; cursor: pointer; line-height: 1; border-radius: 6px; }
.marks:focus-visible { outline: 2px solid var(--accent); }
.marktips { list-style: none; margin: 4px 0 0; padding: 6px 8px; background: var(--canvas); border-radius: 8px; font-weight: 400; font-size: 12px;
  display: grid; gap: 3px; overflow-wrap: anywhere; }
.marktips .mark { margin: 0 6px 0 0; }
.mark.g { background: #1659c7; } .mark.w { background: #7c3aed; } .mark.ok { background: var(--accent); }
.mark.warn { background: #f59f00; }
.fields td { padding: 4px 0; vertical-align: top; }
.fields input { width: 100%; padding: 6px 8px; border: 1px solid var(--line); border-radius: 8px; font: inherit; background: #fff; }
.fields tr.edited input { border-color: #b7791f; }
.fields tr.rejected input { text-decoration: line-through; color: var(--muted); }
.acts { width: 70px; text-align: right; white-space: nowrap; }
.acts button { width: 30px; height: 30px; border-radius: 8px; border: 1px solid var(--line); background: #fff; cursor: pointer; margin-left: 3px; }
.acts .ok.on { background: var(--accent); color: #fff; border-color: var(--accent); }
.fields tr.suggest input { border-style: dashed; border-color: #f59f00; background: #fffaf0; }
.fields tr.suggest .acts .ok { border-color: #f59f00; color: #8a5a00; }
.fields tr.missing input { border-color: #e8590c; }
.fields .sugg { font-weight: 400; font-size: 11px; color: #8a5a00; }
.mark.r { background: #8a5a00; }
.pending { margin: 0; font-size: 12px; color: #8a5a00; background: #fff8e6; border-radius: 8px; padding: 5px 9px; }
.pending.after { color: #9b1c1c; background: #fdecea; }
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

/* touch: 44 px targets (buttons, field checks, thumbnails, position chips) */
@media (pointer: coarse) {
  .acts { width: 98px; }
  .acts button { width: 44px; height: 44px; font-size: 16px; }
  .tools button, .vbar button { width: 44px; height: 44px; font-size: 18px; }
  .thumbs button { min-height: 44px; }
  .thumbs .addph { height: 58px; min-width: 96px; }
  .removed button { min-width: 54px; min-height: 44px; }
  .removed img { height: 44px; }
  .pchip { min-height: 44px; padding: 6px 14px; }
  .marks { min-width: 44px; min-height: 44px; padding: 6px; margin: -12px 0; vertical-align: middle; }
  .mark { width: 10px; height: 10px; }
  footer button { min-height: 44px; }
  .closex { min-width: 44px; min-height: 44px; }
  .fields input, textarea, .cat { font-size: 16px; }   /* no zoom-in on focus (iOS) */
}
/* phone (<= 700 px, style.css): field name above its value; the photo strip scrolls sideways */
@media (max-width: 700px) {
  .fields, .fields tbody { display: block; }
  .fields tr { display: grid; grid-template-columns: minmax(0, 1fr) auto; column-gap: 6px; padding: 4px 0; border-bottom: 1px solid var(--line); }
  .fields th { grid-column: 1 / -1; width: auto; padding: 4px 0 2px; }
  .fields td { padding: 2px 0; }
  .acts { width: auto; }
  .thumbs { flex-wrap: nowrap; overflow-x: auto; overscroll-behavior-x: contain; padding-bottom: 4px; scrollbar-width: thin; }
  .thumbs > * { flex: none; }
  .photos .big { max-height: 46vh; }
  .cat { width: 120px; }
  .closex { display: none; }
  footer { padding-bottom: calc(8px + env(safe-area-inset-bottom)); }
  footer button { flex: 1 1 auto; }
  .vbar { flex-wrap: wrap; padding: 10px 12px calc(10px + env(safe-area-inset-bottom)); }
  .vbar span:last-child { flex-wrap: wrap; }
  .viewer { padding-top: env(safe-area-inset-top); }
}
</style>
