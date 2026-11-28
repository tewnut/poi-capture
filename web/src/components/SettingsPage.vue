<script setup lang="ts">
// Settings, a page of its own like the admin page (#/settings/...): this reviewer's access key to the project's server
// (the server itself is the build's; the key's name is the name saved with each review), the interface
// language, and what this browser keeps (downloaded sessions, reviews). Routes: #/settings/connection (#/settings →
// it), #/settings/language, #/settings/browser.
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { t, lang, setLang, LANGS, type Lang } from '../i18n'
import { parseKeyInput } from '../cloud'
import { conn, connected, canRead, connect, setKey, signOut } from '../connection'
import { state, clearAll } from '../store'

const route = useRoute()
type Section = 'connection' | 'language' | 'browser'
const SECTIONS: { key: Section; route: string; label: string }[] = [
  { key: 'connection', route: 'settingsConnection', label: 'setConnection' },
  { key: 'language', route: 'settingsLanguage', label: 'language' },
  { key: 'browser', route: 'settingsBrowser', label: 'setBrowser' },
]
const section = computed<Section>(() => SECTIONS.find((s) => s.route === route.name)?.key || 'connection')

// ---- connection
const keyDraft = ref('')
const replacing = ref(false)   // connected: the key field shows only after "Change key"
const keyErr = ref('')
async function saveKey() {
  const k = parseKeyInput(keyDraft.value)
  if (!k) { keyErr.value = t('keyNotKey'); return }
  keyErr.value = ''
  await setKey(k.token)   // a setup link's server address is ignored: the server is the build's
  if (connected.value) { keyDraft.value = ''; replacing.value = false }
}
function forget() {
  if (!window.confirm(t('keyForgetConfirm'))) return
  signOut(); keyDraft.value = ''
}
const scopes = computed(() => (conn.me ? (conn.me.admin ? t('scopesAll') : conn.me.scopes.map((x) => t('scope_' + x)).join(', ') || t('scopesNone')) : ''))
onMounted(() => { if (conn.status === 'none' && conn.cfg.token) connect() })

// ---- this browser
const used = ref<string>('')
async function measure() {
  try {
    const e = await navigator.storage?.estimate?.()
    used.value = e?.usage != null ? `${(e.usage / 1048576).toFixed(0)} MB` : ''
  } catch { used.value = '' }
}
onMounted(measure)
const reviewCount = computed(() => Object.values(state.reviews).filter((r) => r.decision !== 'todo').length + Object.keys(state.removals).length)
async function wipe() {
  if (!state.datasets.length && !reviewCount.value) return
  if (!window.confirm(t('clearAllConfirm', { n: state.datasets.length }))) return
  await clearAll()
  await measure()
}
</script>

<template>
  <div class="adminpage">
    <div class="dialog admin">
      <div class="row">
        <h2>{{ t('settings') }}</h2>
        <RouterLink class="small" to="/">← {{ t('settingsBack') }}</RouterLink>
      </div>
      <div class="adminlayout">
        <nav class="adminnav" :aria-label="t('settings')">
          <RouterLink v-for="s in SECTIONS" :key="s.key" :to="{ name: s.route }" :class="{ on: section === s.key }">
            <span>{{ t(s.label) }}</span>
            <span v-if="s.key === 'connection'" :class="['dot', conn.status]" :title="t('connStatus_' + conn.status)"></span>
          </RouterLink>
        </nav>

        <section class="adminmain setmain">
          <!-- connection: the server and this reviewer's access key -->
          <template v-if="section === 'connection'">
            <h3>{{ t('setConnection') }}</h3>
            <p class="muted small">{{ t('connIntro') }}</p>

            <div class="setblock">
              <b class="small">{{ t('accessKey') }}</b>
              <p v-if="conn.status === 'checking'" class="muted small">{{ t('adminChecking') }}</p>
              <template v-else-if="connected && conn.me">
                <p class="ok-text">✓ {{ t('connectedAs', { name: conn.me.name }) }}</p>
                <p class="muted small">{{ t('keyCan', { what: scopes }) }}</p>
                <p v-if="!canRead" class="small err-text">{{ t('keyNoRead') }}</p>
                <p class="muted small">{{ t('reviewsSignedAs', { name: conn.me.name }) }}</p>
              </template>
              <p v-else-if="conn.status === 'error'" class="small err-text">
                {{ conn.keyRefused ? t('keyRefused') : t('serverUnreachable', { why: conn.error }) }}</p>
              <div v-if="connected && !replacing" class="row">
                <button class="ghost small" @click="replacing = true">{{ t('keyChange') }}</button>
                <button class="ghost small" @click="forget">{{ t('keyForget') }}</button>
              </div>
              <form v-else class="setform" @submit.prevent="saveKey">
                <input v-model="keyDraft" type="password" autocomplete="off" spellcheck="false"
                  :placeholder="conn.cfg.token ? t('keyReplace') : t('keyPaste')" />
                <div class="row">
                  <button class="primary small" type="submit" :disabled="!keyDraft.trim() || conn.status === 'checking'">{{ t('connect') }}</button>
                  <button v-if="conn.status === 'error' && conn.cfg.token" class="ghost small" type="button" @click="connect()">{{ t('retry') }}</button>
                  <button v-if="replacing" class="ghost small" type="button" @click="replacing = false; keyDraft = ''">{{ t('cancel') }}</button>
                  <button v-else-if="conn.cfg.token" class="ghost small" type="button" @click="forget">{{ t('keyForget') }}</button>
                </div>
                <p v-if="keyErr" class="small err-text">{{ keyErr }}</p>
              </form>
              <p class="muted small">{{ t('keyHint') }}</p>
            </div>
          </template>

          <!-- language -->
          <template v-else-if="section === 'language'">
            <h3>{{ t('language') }}</h3>
            <div class="seg langseg" role="group" :aria-label="t('language')">
              <button v-for="l in LANGS" :key="l.key" :class="{ on: lang === l.key }" :aria-pressed="lang === l.key"
                @click="setLang(l.key as Lang)">{{ l.label }}</button>
            </div>
            <p class="muted small">{{ t('languageHint') }}</p>
          </template>

          <!-- what this browser keeps -->
          <template v-else>
            <h3>{{ t('setBrowser') }}</h3>
            <p class="muted small">{{ t('browserIntro') }}</p>
            <dl class="setfacts">
              <dt>{{ t('sessions') }}</dt><dd>{{ state.datasets.length }}</dd>
              <dt>{{ t('reviewsKept') }}</dt><dd>{{ reviewCount }}</dd>
              <dt v-if="used">{{ t('storageUsed') }}</dt><dd v-if="used">{{ used }}</dd>
            </dl>
            <div class="setblock">
              <button class="danger small" :disabled="!state.datasets.length && !reviewCount" @click="wipe">{{ t('clearAll') }}</button>
              <p class="muted small">{{ t('clearAllHint') }}</p>
            </div>
          </template>
        </section>
      </div>
    </div>
  </div>
</template>
