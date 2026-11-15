<script setup lang="ts">
// Captured -> Agent processed -> Human reviewed as connected dots (the name on hover), with the review progress.
import { computed } from 'vue'
import { STAGES, stageOf } from '../store'
import { t } from '../i18n'
import type { Dataset } from '../types'

const props = defineProps<{ ds: Dataset }>()
const st = computed(() => stageOf(props.ds))
const at = computed(() => STAGES.findIndex((s) => s.key === st.value.stage))
const pct = computed(() => (st.value.total ? Math.round((100 * st.value.done) / st.value.total) : 100))
const NAME = { captured: 'stageCaptured', processed: 'stageProcessed', reviewed: 'stageReviewed' } as const
</script>

<template>
  <div class="stagebar">
    <ol :aria-label="t(NAME[st.stage])">
      <li v-for="(s, i) in STAGES" :key="s.key" :class="{ done: i < at || st.stage === 'reviewed', now: i === at && st.stage !== 'reviewed' }"
        :data-tip="t(NAME[s.key])" :title="t(NAME[s.key])"><i></i></li>
    </ol>
    <div class="bar" v-if="st.stage !== 'captured'"><div :style="{ width: pct + '%' }"></div></div>
    <span class="muted">{{ st.stage === 'captured' ? t('notProcessed') : t('reviewedOf', { done: st.done, total: st.total }) }}</span>
  </div>
</template>

<style scoped>
.stagebar { display: flex; align-items: center; gap: 10px; font-size: 12px; }
ol { display: flex; align-items: center; list-style: none; margin: 0; padding: 0; flex: none; }
li { position: relative; display: flex; align-items: center; }
li + li { padding-left: 14px; }
li + li::before { content: ''; position: absolute; left: 1px; width: 12px; top: 50%; border-top: 2px solid #c3ccc7; }
li.done + li::before { border-color: var(--accent); }
li i { width: 11px; height: 11px; border-radius: 50%; border: 2px solid #c3ccc7; background: #fff; display: block; }
li.done i { background: var(--accent); border-color: var(--accent); }
li.now i { border-color: #b7791f; background: #ffe8b3; }
li:hover::after { content: attr(data-tip); position: absolute; bottom: 150%; left: 50%; transform: translateX(-50%);
  background: #1d2621; color: #fff; padding: 3px 8px; border-radius: 6px; white-space: nowrap; font-size: 11px; z-index: 10; pointer-events: none; }
.bar { flex: 1; height: 6px; border-radius: 3px; background: var(--line); overflow: hidden; min-width: 40px; }
.bar div { height: 100%; background: var(--accent); }
.muted { white-space: nowrap; }
</style>
