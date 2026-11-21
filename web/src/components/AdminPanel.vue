<script setup lang="ts">
// Admin: the cloud server's access keys (AdminKeys.vue) and capture sessions (AdminSessions.vue), each a list or a
// map (?view=map; the last choice per section is kept in this tab). Routes: #/admin/keys, #/admin/sessions
// (#/admin → keys). Needs the admin password (the server's ADMIN_TOKEN), kept in sessionStorage only (cloud.ts) and
// separate from the reviewer's key.
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { t } from '../i18n'
import {
  me, adminTokens, fetchIndex, endpoint, loadAdminToken, saveAdminToken, CloudError,
  type Builtin, type CloudItem, type Me, type Token,
} from '../cloud'
import AdminKeys from './AdminKeys.vue'
import AdminSessions from './AdminSessions.vue'

const props = defineProps<{ url: string }>()
const route = useRoute()
const router = useRouter()

type Section = 'keys' | 'sessions'
type View = 'list' | 'map'
const ROUTE: Record<Section, string> = { keys: 'adminKeys', sessions: 'adminSessions' }
const section = computed<Section>(() => (route.name === 'adminSessions' ? 'sessions' : 'keys'))

/** The view last chosen in a section (this tab only). */
function storedView(s: Section): View {
  try { return sessionStorage.getItem(`adminView:${s}`) === 'map' ? 'map' : 'list' } catch { return 'list' }
}
const view = computed<View>(() => (route.query.view === 'map' ? 'map' : route.query.view === 'list' ? 'list' : storedView(section.value)))
function setView(v: View) {
  try { sessionStorage.setItem(`adminView:${section.value}`, v) } catch { /* storage blocked */ }
  router.replace({ name: ROUTE[section.value], query: v === 'map' ? { view: 'map' } : {} })
}
const linkTo = (s: Section) => ({ name: ROUTE[s], query: storedView(s) === 'map' ? { view: 'map' } : {} })

const pw = ref(loadAdminToken())
const who = ref<Me | null>(null)
const authErr = ref('')
const checking = ref(false)
const cfg = computed(() => ({ url: props.url, token: pw.value }))
const urlOk = computed(() => endpoint(props.url) != null)

function message(e: any): string {
  if (e instanceof CloudError && e.status === 401) return t('adminWrong')
  if (e instanceof CloudError && e.status === 403) return t('adminForbidden')
  return e?.message || String(e)
}

const keysRef = ref<InstanceType<typeof AdminKeys> | null>(null)
async function signIn() {
  authErr.value = ''; who.value = null
  if (!pw.value.trim() || !urlOk.value) return
  checking.value = true
  saveAdminToken(pw.value)
  try {
    const m = await me(cfg.value)
    if (!m.admin) {
      authErr.value = t('adminNotAdmin', { name: m.name, what: m.scopes.map((x) => t('scope_' + x)).join(', ') || t('scopesNone') })
      return
    }
    who.value = m
    await Promise.all([loadTokens(), loadUploads()])
  } catch (e: any) {
    authErr.value = message(e)
  } finally {
    checking.value = false
  }
}
function signOut() {
  pw.value = ''; saveAdminToken('')
  keysRef.value?.forget()
  who.value = null; tokens.value = null; builtins.value = []; uploads.value = null; authErr.value = ''
}
onMounted(() => { if (pw.value) signIn() })
/** A request in a section got 401/403: the password stopped working, so back to the password field. */
function authLost(e: unknown) { who.value = null; authErr.value = message(e) }

const tokens = ref<Token[] | null>(null)
const builtins = ref<Builtin[]>([])
const uploads = ref<CloudItem[] | null>(null)
const loadErr = ref('')
async function loadTokens() {
  loadErr.value = ''
  try { ({ tokens: tokens.value, builtins: builtins.value } = await adminTokens(cfg.value)) } catch (e) { onLoadError(e) }
}
async function loadUploads() {
  loadErr.value = ''
  try { uploads.value = await fetchIndex(cfg.value, fetch, '') } catch (e) { onLoadError(e) }
}
function onLoadError(e: unknown) {
  if (e instanceof CloudError && (e.status === 401 || e.status === 403)) authLost(e)
  else loadErr.value = message(e)
}
</script>

<template>
  <div class="adminpage">
    <div class="dialog admin">
      <div class="row">
        <h2>{{ t('adminTitle') }}</h2>
        <RouterLink class="small" to="/">← {{ t('adminBack') }}</RouterLink>
      </div>
      <p class="muted small">{{ endpoint(url) || url }}</p>

      <p v-if="!urlOk" class="small err-text">{{ t('cloudNotSet') }}</p>
      <form v-else-if="!who" class="adminauth" @submit.prevent="signIn">
        <label><span>{{ t('adminPassword') }}</span>
          <input v-model="pw" type="password" autocomplete="off" spellcheck="false" /></label>
        <button class="primary small" type="submit" :disabled="!pw.trim() || checking">{{ checking ? t('adminChecking') : t('adminSignIn') }}</button>
        <p v-if="authErr" class="small err-text">{{ authErr }}</p>
        <p class="muted small">{{ t('adminPasswordHint') }}</p>
      </form>

      <template v-else>
        <div class="row">
          <span class="small">{{ t('adminSignedIn', { name: who.name }) }}</span>
          <button class="ghost small" @click="signOut">{{ t('adminSignOut') }}</button>
        </div>
        <div class="adminlayout">
          <!-- a sidebar when the page is wide; a segmented menu on top when narrow (style.css) -->
          <nav class="adminnav" :aria-label="t('adminTitle')">
            <RouterLink :to="linkTo('keys')" :class="{ on: section === 'keys' }">
              <span>{{ t('navKeys') }}</span> <span class="n">{{ tokens?.length ?? '' }}</span></RouterLink>
            <RouterLink :to="linkTo('sessions')" :class="{ on: section === 'sessions' }">
              <span>{{ t('navSessions') }}</span> <span class="n">{{ uploads?.length ?? '' }}</span></RouterLink>
          </nav>
          <section class="adminmain">
            <div class="row adminhead">
              <h3>{{ section === 'keys' ? t('navKeys') : t('navSessions') }}</h3>
              <div class="seg viewseg" role="group" :aria-label="t('viewLabel')">
                <button :class="{ on: view === 'list' }" :aria-pressed="view === 'list'" @click="setView('list')">{{ t('viewList') }}</button>
                <button :class="{ on: view === 'map' }" :aria-pressed="view === 'map'" @click="setView('map')">{{ t('viewMap') }}</button>
              </div>
            </div>
            <p v-if="loadErr" class="small err-text">{{ loadErr }}</p>
            <AdminKeys v-if="section === 'keys'" ref="keysRef" :cfg="cfg" :tokens="tokens" :builtins="builtins" :view="view"
              @reload="loadTokens" @auth="authLost" />
            <AdminSessions v-else :cfg="cfg" :uploads="uploads" :view="view" @reload="loadUploads" @auth="authLost" />
          </section>
        </div>
      </template>
    </div>
  </div>
</template>
