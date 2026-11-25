<script setup lang="ts">
// Captured -> Agent processed -> Human reviewed -> Exported as connected dots (the name on hover), with the review progress.
// A ride still waiting on the server (no dataset yet) is at the first stage: ds = null.
import { computed } from 'vue'
import { STAGES, stageOf } from '../store'
import { t } from '../i18n'
import type { Dataset } from '../types'

const props = defineProps<{ ds: Dataset | null }>()
const st = computed(() => (props.ds ? stageOf(props.ds) : { stage: 'captured' as const, done: 0, total: 0 }))
const at = computed(() => STAGES.findIndex((s) => s.key === st.value.stage))
const pct = computed(() => (st.value.total ? Math.round((100 * st.value.done) / st.value.total) : 100))
const NAME = { captured: 'stageCaptured', processed: 'stageProcessed', reviewed: 'stageReviewed', exported: 'stageExported' } as const
/** Dots before this one are full, this one is "now": a reviewed ride waits for its export, an exported one is through. */
const reached = computed(() => (st.value.stage === 'reviewed' || st.value.stage === 'exported' ? at.value + 1 : at.value))
</script>

<template>
  <div class="stagebar">
    <ol :aria-label="t(NAME[st.stage])">
      <li v-for="(s, i) in STAGES" :key="s.key" :class="{ done: i < reached, now: i === reached }"
        :data-tip="t(NAME[s.key])" :title="t(NAME[s.key])"><i></i></li>
    </ol>
    <div class="bar" v-if="st.stage !== 'captured'"><div :style="{ width: pct + '%' }"></div></div>
    <span class="muted">{{ st.stage === 'captured' ? t('notProcessed') : t('reviewedOf', { done: st.done, total: st.total }) }}</span>
    <!-- no hover on touch screens: the stage's name is written out -->
    <span class="stname">{{ t(NAME[st.stage]) }}</span>
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
.stname { display: none; }
@media (hover: none) {
  .stagebar { flex-wrap: wrap; row-gap: 2px; }
  .stname { display: block; flex-basis: 100%; color: var(--muted); font-weight: 600; }
}
@media (max-width: 700px) { .muted { white-space: normal; } }
</style>
