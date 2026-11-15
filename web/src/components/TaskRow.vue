<script setup lang="ts">
import { computed } from 'vue'
import { state, putRemoval } from '../store'
import { t, tWhy } from '../i18n'
import type { Dataset, Task } from '../types'

const props = defineProps<{ ds: Dataset; task: Task; index: number }>()
const emit = defineEmits<{ open: []; openPlace: [id: string] }>()
const r = computed(() => state.removals[`${props.ds.id}|${props.index}`])
const sel = computed(() => state.open === props.ds.id && state.task === props.index)
const LABEL = { visit: 'taskVisit', removal: 'taskRemoval', verify_removal: 'taskVerify' } as const
const DONE = { confirmed: 'removalConfirmed', verify: 'willCheck', dismissed: 'dismissed', todo: 'toReview' } as const
</script>

<template>
  <div :class="['task', { sel }]" @click="emit('open')">
    <div class="th">
      <span :class="['chip', task.type === 'visit' ? 'todo' : 'rm']">{{ t(LABEL[task.type]) }}</span>
      <b>{{ task.name }}</b>
      <span v-if="r && r.decision !== 'todo'" class="chip ok">{{ t(DONE[r.decision]) }}</span>
      <button v-if="task.type === 'visit' && task.place" class="ghost small go" :title="t('showPlace')" @click.stop="emit('openPlace', task.place)">{{ t('placeLink') }}</button>
    </div>
    <div class="muted">{{ tWhy(task.why) }}</div>
    <div v-if="task.type !== 'visit'" class="tact" @click.stop>
      <button :class="['secondary', { on: r?.decision === 'confirmed' }]" @click="putRemoval(ds.id, index, 'confirmed')">{{ t('confirmRemoval') }}</button>
      <button :class="['secondary', { on: r?.decision === 'verify' }]" @click="putRemoval(ds.id, index, 'verify')">{{ t('checkOnSite') }}</button>
      <button :class="['ghost', { on: r?.decision === 'dismissed' }]" @click="putRemoval(ds.id, index, 'dismissed')">{{ t('dismiss') }}</button>
    </div>
  </div>
</template>

<style scoped>
.th { display: flex; align-items: center; gap: 6px; }
.th b { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; margin: 0; }
.go { color: var(--accent); flex: none; }
.muted { font-size: 12px; margin-top: 2px; }
</style>
