<script setup lang="ts">
// The open session's checkout (server/README.md "Reviews"): free → Check out; checked out by this reviewer → Save to
// server, Check in, Give up; by someone else → who and since when (read-only here; an admin or a key with the release scope may release it). A
// checkout that ended while this browser had unsaved changes (released by an admin, expired) keeps them until the
// reviewer chooses: check out again and keep them, or discard them. History: every checkout, save, check-in, release;
// an admin can make an earlier version the review again (undo a check-in).
import { computed, ref, watch } from 'vue'
import { t, relTime } from '../i18n'
import { state } from '../store'
import { conn, connected, canRelease, checkOut, saveToServer, giveUp, forceRelease, discardMine, restoreVersion, decideConflict } from '../connection'
import type { Conflict } from '../merge'
import type { Review, RemovalReview } from '../types'
import { CheckedOut, reviewHistory, type ReviewEvent } from '../cloud'
import type { Dataset } from '../types'

const props = defineProps<{ ds: Dataset }>()
const info = computed(() => conn.reviews[props.ds.id] || null)
const mine = computed(() => state.editable.has(props.ds.id))
const other = computed(() => (info.value?.lock && !mine.value ? info.value.lock : null))
const busy = computed(() => conn.reviewBusy === props.ds.id)
const dirty = computed(() => state.dirty.has(props.ds.id))
/** Not checked out by this reviewer, with changes not on the server: their checkout ended under them. */
const lost = computed(() => dirty.value && !mine.value)
const err = ref('')
const confirmGiveUp = ref(false)

/** Why this reviewer's checkout ended, when the server says it was theirs. */
const endedText = computed(() => {
  const e = info.value?.ended
  if (!e || e.of.id !== conn.me?.id) return t('lostUnknown')
  const p = { by: e.by || '', when: relTime(e.at) }
  return e.reason === 'released' ? t('lostReleased', p) : e.reason === 'expired' ? t('lostExpired', p)
    : e.reason === 'revoked' ? t('lostRevoked', p) : t('lostUnknown')
})
/** Someone saved after this browser last loaded the review: keeping mine replaces theirs. */
const newerOnServer = computed(() => {
  const s = info.value?.saved
  return s && (!state.synced[props.ds.id] || s.at > state.synced[props.ds.id]) ? s : null
})

async function run(f: () => Promise<unknown>) {
  err.value = ''
  try { await f() } catch (e: any) {
    err.value = e instanceof CheckedOut ? t('checkedOutBy', { name: e.info.lock?.by.name || '?', when: relTime(e.info.lock?.at || '') }) : e?.message || String(e)
  }
}
const take = () => run(() => checkOut(props.ds))
const takeKeep = () => {
  const n = newerOnServer.value
  if (n && !window.confirm(t('keepMineConfirm', { name: n.by.name, when: relTime(n.at) }))) return
  run(() => checkOut(props.ds, true))
}
// ---- conflicts: places this reviewer and somebody else both changed differently (merge.ts), decided one by one
const conflicts = computed(() => state.conflicts[props.ds.id] || [])
function cName(c: Conflict): string {
  if (c.kind === 'removal') return props.ds.tasks[Number(c.key)]?.name || `#${c.key}`
  return props.ds.places.find((p) => p.id === c.key)?.name || c.key
}
/** One side of a conflict in a few words: the decision, what was edited, the comment, and who. */
function side(v: Review | RemovalReview | null): string {
  if (!v) return t('conflictNone')
  const dec = t('dec' + v.decision.charAt(0).toUpperCase() + v.decision.slice(1))
  const parts = [dec]
  if ('fields' in v) {
    const edited = Object.values(v.fields || {}).filter((x) => x && x.state !== 'ok').length
    if (edited) parts.push(t('conflictEdited', { n: edited }))
    if (v.pin?.source === 'manual') parts.push(t('conflictPin'))   // moved by hand (a confirm records the chosen pin too)
    if (v.comment) parts.push(`“${v.comment.slice(0, 40)}”`)
  }
  if (v.reviewer) parts.push(t('conflictBy', { name: v.reviewer }))
  return parts.join(' · ')
}
const decide = (choice: 'mine' | 'theirs', c?: Conflict) => run(() => decideConflict(props.ds.id, choice, c && { kind: c.kind, key: c.key }))
const goTo = (c: Conflict) => { if (c.kind === 'place') state.place = c.key }

const discard = () => { if (window.confirm(t('discardConfirm'))) run(() => discardMine(props.ds)) }
const save = () => run(() => saveToServer(props.ds, false))
const checkin = () => run(() => saveToServer(props.ds, true))
const drop = () => { confirmGiveUp.value = false; run(() => giveUp(props.ds)) }
const release = () => { if (window.confirm(t('releaseConfirm', { name: other.value?.by.name || '' }))) run(() => forceRelease(props.ds.id)) }

// ---- history
const showHistory = ref(false)
const events = ref<ReviewEvent[] | null>(null)
const histErr = ref('')
async function loadHistory() {
  histErr.value = ''
  try { events.value = await reviewHistory(conn.cfg, props.ds.id) } catch (e: any) { histErr.value = e?.message || String(e) }
}
watch(showHistory, (v) => { if (v) loadHistory() })
// a save, check-in, release or restore here or elsewhere: the open history follows
watch(() => [info.value?.saved?.at, info.value?.lock?.at, info.value?.ended?.at], () => { if (showHistory.value) loadHistory() })
watch(() => props.ds.id, () => { showHistory.value = false; events.value = null })

function line(e: ReviewEvent): string {
  const p = { by: e.by?.name || '', of: e.of || '', n: e.total != null ? `${e.done ?? 0}/${e.total}` : '', when: e.note ? relTime(e.note) : '' }
  return t('hist_' + e.action, p)
}
const canRestore = computed(() => !!conn.me?.admin && !info.value?.lock)
function restore(e: ReviewEvent) {
  if (!e.version || !window.confirm(t('restoreConfirm', { name: e.by?.name || '', when: relTime(e.at) }))) return
  run(() => restoreVersion(props.ds, e.version!))
}
</script>

<template>
  <div v-if="connected && ds.source === 'result'" :class="['reviewbar', mine ? 'mine' : lost ? 'lost' : other ? 'other' : 'free']">
    <template v-if="mine">
      <div class="small"><b>{{ t('checkedOutYou') }}</b>
        <span class="muted"> · {{ dirty ? t('unsaved') : info?.saved ? t('savedWhen', { when: relTime(info.saved.at) }) : t('notSavedYet') }}</span></div>
      <div class="btns">
        <button class="secondary small" :disabled="busy || !dirty || !!conflicts.length" @click="save">{{ t('saveToServer') }}</button>
        <button class="primary small" :disabled="busy || !!conflicts.length" :title="t('checkInTitle')" @click="checkin">{{ busy ? t('working') : t('checkIn') }}</button>
        <span v-if="confirmGiveUp" class="confirm small">{{ t('giveUpConfirm') }}
          <button class="danger small" @click="drop">{{ t('giveUp') }}</button>
          <button class="ghost small" @click="confirmGiveUp = false">{{ t('cancel') }}</button></span>
        <button v-else class="ghost small" :disabled="busy" @click="confirmGiveUp = true">{{ t('giveUp') }}</button>
      </div>
      <div v-if="conflicts.length" class="conflicts small">
        <p><b>⚠ {{ t('conflictsTitle', { n: conflicts.length }) }}</b> <span class="muted">{{ t('conflictsHelp') }}</span></p>
        <ul>
          <li v-for="c in conflicts" :key="c.kind + c.key">
            <button class="link" @click="goTo(c)">{{ cName(c) }}</button>
            <div class="sides">
              <span><b>{{ t('conflictMine') }}:</b> {{ side(c.mine) }}</span>
              <span><b>{{ t('conflictTheirs') }}:</b> {{ side(c.theirs) }}</span>
            </div>
            <div class="btns">
              <button class="secondary small" :disabled="busy" @click="decide('mine', c)">{{ t('keepMine') }}</button>
              <button class="secondary small" :disabled="busy" @click="decide('theirs', c)">{{ t('takeTheirs') }}</button>
            </div>
          </li>
        </ul>
        <div v-if="conflicts.length > 1" class="btns">
          <button class="ghost small" :disabled="busy" @click="decide('mine')">{{ t('keepAllMine') }}</button>
          <button class="ghost small" :disabled="busy" @click="decide('theirs')">{{ t('takeAllTheirs') }}</button>
        </div>
      </div>
    </template>
    <template v-else-if="lost">
      <div class="small">⚠ <b>{{ endedText }}</b>
        <span class="muted"> · {{ t('lostKept') }}</span>
        <span v-if="other" class="muted"> {{ t('lostTaken', { name: other.by.name }) }}</span></div>
      <div class="btns">
        <button v-if="!other" class="primary small" :disabled="busy" @click="takeKeep">{{ busy ? t('working') : t('checkOutKeep') }}</button>
        <button class="ghost small" :disabled="busy" @click="discard">{{ t('discardMine') }}</button>
      </div>
    </template>
    <template v-else-if="other">
      <div class="small">🔒 <b>{{ t('checkedOutBy', { name: other.by.name, when: relTime(other.at) }) }}</b>
        <span class="muted"> · {{ t('readOnly') }}</span></div>
      <div class="btns"><button v-if="canRelease" class="ghost small" @click="release">{{ t('releaseLock') }}</button></div>
    </template>
    <template v-else>
      <div class="small"><span class="muted">{{ info?.saved ? t('lastSavedBy', { name: info.saved.by.name, when: relTime(info.saved.at) }) : t('notReviewedYet') }}</span>
        · {{ t('readOnly') }}</div>
      <div class="btns"><button class="primary small" :disabled="busy" @click="take">{{ busy ? t('working') : t('checkOut') }}</button></div>
    </template>
    <div class="histtoggle"><button class="ghost small" :aria-expanded="showHistory" @click="showHistory = !showHistory">{{ showHistory ? '▾' : '▸' }} {{ t('history') }}</button></div>
    <p v-if="err" class="small err-text">{{ err }}</p>
    <div v-if="showHistory" class="history small">
      <p v-if="histErr" class="err-text">{{ histErr }}</p>
      <p v-else-if="!events" class="muted">{{ t('working') }}</p>
      <p v-else-if="!events.length" class="muted">{{ t('historyEmpty') }}</p>
      <ol v-else>
        <li v-for="(e, i) in events" :key="i" :class="['h-' + e.action, { current: !!e.version && e.version === info?.saved?.version }]">
          <span class="muted" :title="new Date(e.at).toLocaleString()">{{ relTime(e.at) }}</span>
          <span>{{ line(e) }}<b v-if="e.version && e.version === info?.saved?.version" class="chip ok">{{ t('currentVersion') }}</b></span>
          <button v-if="e.version && canRestore && e.version !== info?.saved?.version" class="ghost small" :disabled="busy" :title="t('restoreTitle')" @click="restore(e)">{{ t('restore') }}</button>
        </li>
      </ol>
      <p v-if="conn.me?.admin && info?.lock && events?.some((e) => e.version)" class="muted">{{ t('restoreLocked') }}</p>
    </div>
  </div>
</template>
