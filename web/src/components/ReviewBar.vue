<script setup lang="ts">
// The open session's checkout (server/README.md "Reviews"): free → Check out; checked out by this reviewer → Save to
// server, Check in, Give up; by someone else → who and since when (read-only here; an admin may release it).
import { computed, ref } from 'vue'
import { t, relTime } from '../i18n'
import { state } from '../store'
import { conn, connected, checkOut, saveToServer, giveUp, forceRelease } from '../connection'
import { CheckedOut } from '../cloud'
import type { Dataset } from '../types'

const props = defineProps<{ ds: Dataset }>()
const info = computed(() => conn.reviews[props.ds.id] || null)
const mine = computed(() => state.editable.has(props.ds.id))
const other = computed(() => (info.value?.lock && !mine.value ? info.value.lock : null))
const busy = computed(() => conn.reviewBusy === props.ds.id)
const dirty = computed(() => state.dirty.has(props.ds.id))
const err = ref('')
const confirmGiveUp = ref(false)

async function run(f: () => Promise<unknown>) {
  err.value = ''
  try { await f() } catch (e: any) {
    err.value = e instanceof CheckedOut ? t('checkedOutBy', { name: e.info.lock?.by.name || '?', when: relTime(e.info.lock?.at || '') }) : e?.message || String(e)
  }
}
const take = () => run(() => checkOut(props.ds))
const save = () => run(() => saveToServer(props.ds, false))
const checkin = () => run(() => saveToServer(props.ds, true))
const drop = () => { confirmGiveUp.value = false; run(() => giveUp(props.ds)) }
const release = () => { if (window.confirm(t('releaseConfirm', { name: other.value?.by.name || '' }))) run(() => forceRelease(props.ds.id)) }
</script>

<template>
  <div v-if="connected && ds.source === 'result'" :class="['reviewbar', mine ? 'mine' : other ? 'other' : 'free']">
    <template v-if="mine">
      <div class="small"><b>{{ t('checkedOutYou') }}</b>
        <span class="muted"> · {{ dirty ? t('unsaved') : info?.saved ? t('savedWhen', { when: relTime(info.saved.at) }) : t('notSavedYet') }}</span></div>
      <div class="btns">
        <button class="secondary small" :disabled="busy || !dirty" @click="save">{{ t('saveToServer') }}</button>
        <button class="primary small" :disabled="busy" :title="t('checkInTitle')" @click="checkin">{{ busy ? t('working') : t('checkIn') }}</button>
        <span v-if="confirmGiveUp" class="confirm small">{{ t('giveUpConfirm') }}
          <button class="danger small" @click="drop">{{ t('giveUp') }}</button>
          <button class="ghost small" @click="confirmGiveUp = false">{{ t('cancel') }}</button></span>
        <button v-else class="ghost small" :disabled="busy" @click="confirmGiveUp = true">{{ t('giveUp') }}</button>
      </div>
    </template>
    <template v-else-if="other">
      <div class="small">🔒 <b>{{ t('checkedOutBy', { name: other.by.name, when: relTime(other.at) }) }}</b>
        <span class="muted"> · {{ t('readOnly') }}</span></div>
      <div class="btns"><button v-if="conn.me?.admin" class="ghost small" @click="release">{{ t('releaseLock') }}</button></div>
    </template>
    <template v-else>
      <div class="small"><span class="muted">{{ info?.saved ? t('lastSavedBy', { name: info.saved.by.name, when: relTime(info.saved.at) }) : t('notReviewedYet') }}</span>
        · {{ t('readOnly') }}</div>
      <div class="btns"><button class="primary small" :disabled="busy" @click="take">{{ busy ? t('working') : t('checkOut') }}</button></div>
    </template>
    <p v-if="err" class="small err-text">{{ err }}</p>
  </div>
</template>
