<script setup lang="ts">
import { computed } from 'vue'
import { state, putRemoval } from '../store'
import type { Dataset, Task } from '../types'

const props = defineProps<{ ds: Dataset; task: Task; index: number; showSession?: boolean }>()
const emit = defineEmits<{ open: []; openPlace: [id: string] }>()
const r = computed(() => state.removals[`${props.ds.id}|${props.index}`])
const sel = computed(() => state.open === props.ds.id && state.task === props.index)
const LABEL = { visit: 'visit', removal: 'removal', verify_removal: 'verify removal' }
</script>

<template>
  <div :class="['task', { sel }]" @click="emit('open')">
    <div class="th">
      <span :class="['chip', task.type === 'visit' ? 'todo' : 'rm']">{{ LABEL[task.type] }}</span>
      <b>{{ task.name }}</b>
      <span v-if="r && r.decision !== 'todo'" class="chip ok">{{ r.decision === 'confirmed' ? 'removal confirmed' : r.decision === 'verify' ? 'check on site' : 'dismissed' }}</span>
      <button v-if="task.type === 'visit' && task.place" class="ghost small go" title="Show the place" @click.stop="emit('openPlace', task.place)">place ›</button>
    </div>
    <div class="muted">{{ task.why }}<template v-if="showSession"> · {{ ds.session.name }}</template></div>
    <div v-if="task.type !== 'visit'" class="tact" @click.stop>
      <button :class="['secondary', { on: r?.decision === 'confirmed' }]" @click="putRemoval(ds.id, index, 'confirmed')">Confirm removal</button>
      <button :class="['secondary', { on: r?.decision === 'verify' }]" @click="putRemoval(ds.id, index, 'verify')">Check on site</button>
      <button :class="['ghost', { on: r?.decision === 'dismissed' }]" @click="putRemoval(ds.id, index, 'dismissed')">Dismiss</button>
    </div>
  </div>
</template>

<style scoped>
.th { display: flex; align-items: center; gap: 6px; }
.th b { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; margin: 0; }
.go { color: var(--accent); flex: none; }
.muted { font-size: 12px; margin-top: 2px; }
</style>
