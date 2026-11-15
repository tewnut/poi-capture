<script setup lang="ts">
import { computed } from 'vue'
import Photo from './Photo.vue'
import CopyLink from './CopyLink.vue'
import { state, putRemoval } from '../store'
import { to, shareUrl } from '../router'
import type { Dataset, Task } from '../types'

const props = defineProps<{ ds: Dataset; task: Task; index: number }>()
const emit = defineEmits<{ close: []; openPlace: [id: string] }>()
const r = computed(() => state.removals[`${props.ds.id}|${props.index}`])
const place = computed(() => props.ds.places.find((p) => p.id === props.task.place) || null)
const photo = computed(() => props.task.photo || place.value?.photos.find((p) => p.cover)?.file || null)
const TYPE = { visit: 'Visit (Place capture)', removal: 'Removal', verify_removal: 'Verify removal on site' }
const gmapsish = computed(() => `https://www.openstreetmap.org/?mlat=${props.task.lat}&mlon=${props.task.lon}#map=19/${props.task.lat}/${props.task.lon}`)
</script>

<template>
  <div class="task">
    <header>
      <div>
        <span :class="['chip', task.type === 'visit' ? 'todo' : 'rm']">{{ TYPE[task.type] }}</span>
        <h2>{{ task.name }}</h2>
        <div class="muted">{{ ds.session.name }} · {{ task.lat.toFixed(6) }}, {{ task.lon.toFixed(6) }}</div>
      </div>
      <div class="hd">
        <CopyLink :url="shareUrl(to.task(ds.id, index))" />
        <button class="ghost" title="Close" @click="emit('close')">✕</button>
      </div>
    </header>
    <Photo v-if="photo" class="big" :ds="ds.id" :path="photo" :alt="task.name" />
    <p><b>Why:</b> {{ task.why }}</p>
    <p v-if="task.type === 'visit'" class="muted small">Go there with Place capture: confirm the name, take the sign, the door and the opening hours, and set the pin at the doorway.</p>
    <div v-if="task.type !== 'visit'" class="acts">
      <button :class="['secondary', { on: r?.decision === 'confirmed' }]" @click="putRemoval(ds.id, index, 'confirmed')">Confirm removal</button>
      <button :class="['secondary', { on: r?.decision === 'verify' }]" @click="putRemoval(ds.id, index, 'verify')">Check on site</button>
      <button :class="['ghost', { on: r?.decision === 'dismissed' }]" @click="putRemoval(ds.id, index, 'dismissed')">Dismiss</button>
      <div v-if="r" class="muted small">{{ r.decision }}{{ r.reviewer ? ` by ${r.reviewer}` : '' }}{{ r.at ? `, ${r.at.slice(0, 16).replace('T', ' ')}` : '' }}</div>
    </div>
    <div class="links">
      <button v-if="place" class="secondary" @click="emit('openPlace', place.id)">Open the place</button>
      <a :href="gmapsish" target="_blank" rel="noopener noreferrer">Open on OpenStreetMap</a>
    </div>
  </div>
</template>

<style scoped>
.task { display: flex; flex-direction: column; gap: 10px; }
header { display: flex; justify-content: space-between; gap: 8px; }
.hd { display: flex; align-items: flex-start; gap: 2px; }
h2 { margin: 6px 0 0; font-size: 19px; }
.big { width: 100%; max-height: 300px; object-fit: contain; background: #111; border-radius: 10px; }
p { margin: 0; }
.acts, .links { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
</style>
