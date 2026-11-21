<script setup lang="ts">
import { computed } from 'vue'
import Photo from './Photo.vue'
import { state, putRemoval } from '../store'
import { t, tWhy } from '../i18n'
import type { Dataset, Task } from '../types'

const props = defineProps<{ ds: Dataset; task: Task; index: number }>()
const emit = defineEmits<{ close: []; openPlace: [id: string] }>()
const r = computed(() => state.removals[`${props.ds.id}|${props.index}`])
const place = computed(() => props.ds.places.find((p) => p.id === props.task.place) || null)
const photo = computed(() => props.task.photo || place.value?.photos.find((p) => p.cover)?.file || null)
const TYPE = { visit: 'taskTypeVisit', removal: 'taskTypeRemoval', verify_removal: 'taskTypeVerify' } as const
const DECISION = { confirmed: 'removalConfirmed', verify: 'willCheck', dismissed: 'dismissed', todo: 'toReview' } as const
const osmUrl = computed(() => `https://www.openstreetmap.org/?mlat=${props.task.lat}&mlon=${props.task.lon}#map=19/${props.task.lat}/${props.task.lon}`)
</script>

<template>
  <div class="task">
    <header>
      <div>
        <span :class="['chip', task.type === 'visit' ? 'todo' : 'rm']">{{ t(TYPE[task.type]) }}</span>
        <h2>{{ task.name }}</h2>
        <div class="muted">{{ ds.session.name }} · {{ task.lat.toFixed(6) }}, {{ task.lon.toFixed(6) }}</div>
      </div>
      <button class="ghost closex" :title="t('close')" :aria-label="t('close')" @click="emit('close')">✕</button>
    </header>
    <Photo v-if="photo" class="big" :ds="ds.id" :path="photo" :alt="task.name" />
    <p><b>{{ t('why') }}:</b> {{ tWhy(task.why) }}</p>
    <p v-if="task.type === 'visit'" class="muted small">{{ t('visitHowTo') }}</p>
    <div v-if="task.type !== 'visit'" class="acts">
      <button :class="['secondary', { on: r?.decision === 'confirmed' }]" @click="putRemoval(ds.id, index, 'confirmed')">{{ t('confirmRemoval') }}</button>
      <button :class="['secondary', { on: r?.decision === 'verify' }]" @click="putRemoval(ds.id, index, 'verify')">{{ t('checkOnSite') }}</button>
      <button :class="['ghost', { on: r?.decision === 'dismissed' }]" @click="putRemoval(ds.id, index, 'dismissed')">{{ t('dismiss') }}</button>
      <div v-if="r" class="muted small">{{ t(DECISION[r.decision]) }}{{ r.reviewer ? ` ${t('by')} ${r.reviewer}` : '' }}{{ r.at ? `, ${r.at.slice(0, 16).replace('T', ' ')}` : '' }}</div>
    </div>
    <div class="links">
      <button v-if="place" class="secondary" @click="emit('openPlace', place.id)">{{ t('openPlace') }}</button>
      <a :href="osmUrl" target="_blank" rel="noopener noreferrer">{{ t('openOsm') }}</a>
    </div>
  </div>
</template>

<style scoped>
.task { display: flex; flex-direction: column; gap: 10px; }
header { display: flex; justify-content: space-between; gap: 8px; }
h2 { margin: 6px 0 0; font-size: 19px; }
.big { width: 100%; max-height: 300px; object-fit: contain; background: #111; border-radius: 10px; }
p { margin: 0; }
.acts, .links { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
@media (pointer: coarse) { .links a { display: inline-flex; align-items: center; min-height: 44px; } }
@media (max-width: 1099px) { .closex { display: none; } }   /* the panel bar has back (style.css) */
@media (min-width: 1600px) and (min-height: 900px) { .big { max-height: 380px; } }
@media (max-width: 700px) {
  .big { max-height: 50vh; }
  .acts button { flex: 1 1 auto; }
}
</style>
