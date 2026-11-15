<script setup lang="ts">
// Captured -> Agent processed -> Human reviewed, with the review progress.
import { computed } from 'vue'
import { STAGES, stageOf } from '../store'
import type { Dataset } from '../types'

const props = defineProps<{ ds: Dataset; compact?: boolean }>()
const st = computed(() => stageOf(props.ds))
const at = computed(() => STAGES.findIndex((s) => s.key === st.value.stage))
const SHORT = { captured: 'Captured', processed: 'Processed', reviewed: 'Reviewed' }
const pct = computed(() => (st.value.total ? Math.round((100 * st.value.done) / st.value.total) : 100))
</script>

<template>
  <div :class="['stagebar', { compact }]" :title="`${STAGES[at].label}${st.stage === 'processed' ? `: ${st.done} of ${st.total} reviewed` : ''}`">
    <ol>
      <li v-for="(s, i) in STAGES" :key="s.key" :class="{ done: i < at || st.stage === 'reviewed', now: i === at && st.stage !== 'reviewed' }">
        <i></i><span>{{ compact ? SHORT[s.key] : s.label }}</span>
      </li>
    </ol>
    <div v-if="st.stage !== 'captured'" class="prog">
      <div class="bar"><div :style="{ width: pct + '%' }"></div></div>
      <span class="muted">{{ st.done }}/{{ st.total }} reviewed</span>
    </div>
    <div v-else class="prog muted">not processed yet</div>
  </div>
</template>

<style scoped>
.stagebar { font-size: 12px; }
ol { display: flex; list-style: none; margin: 0; padding: 0; gap: 0; }
li { display: flex; align-items: center; gap: 5px; color: var(--muted); flex: 1; position: relative; }
li { padding-left: 14px; }
li:first-child { padding-left: 0; }
li + li::before { content: ''; position: absolute; left: 2px; width: 9px; top: 50%; border-top: 2px solid #c3ccc7; }
li.done + li::before { border-color: var(--accent); }
li i { width: 10px; height: 10px; border-radius: 50%; border: 2px solid #c3ccc7; background: #fff; flex: none; }
li.done { color: var(--accent); }
li.done i { background: var(--accent); border-color: var(--accent); }
li.now { color: var(--ink); font-weight: 600; }
li.now i { border-color: #b7791f; background: #ffe8b3; }
li span { white-space: nowrap; }
.prog { display: flex; align-items: center; gap: 8px; margin-top: 6px; }
.bar { flex: 1; height: 6px; border-radius: 3px; background: var(--line); overflow: hidden; }
.bar div { height: 100%; background: var(--accent); }
.compact li span { font-size: 11px; }
</style>
