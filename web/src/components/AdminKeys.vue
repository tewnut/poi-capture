<script setup lang="ts">
// Admin, "Access keys": every identity, the built-in one (the admin password: a server secret, read-only) and
// the issued keys, as a list or a map of where each was last seen (IP lookup: approximate). A click on a key, in either
// view, opens it in the side panel: rename, change permissions, revoke, its full last-seen record. "New key" opens the
// same panel: name and permissions, then the secret, shown once, with the phone's setup QR code (drawn here, never by a
// web service).
import { computed, ref, watch } from 'vue'
import QRCode from 'qrcode'
import { t, relTime } from '../i18n'
import {
  issueToken, updateToken, revokeToken, setupLink, endpoint, twoClients, CloudError, PRESETS,
  type Builtin, type CloudConfig, type Scope, type Token,
} from '../cloud'
import { when, short, seenLine, seenTitle, twoTitle, since, within, colours, seenPopup, type Period } from '../adminfmt'
import ScopePicker from './ScopePicker.vue'
import ScopeChips from './ScopeChips.vue'
import LayerMap, { type MapMarker } from './LayerMap.vue'

const props = defineProps<{ cfg: CloudConfig; tokens: Token[] | null; builtins: Builtin[]; view: 'list' | 'map' }>()
const emit = defineEmits<{ reload: []; auth: [e: unknown] }>()

/** A 401/403 means the admin password stopped working: the page goes back to the password field. */
function failed(e: unknown, into: { value: string }) {
  if (e instanceof CloudError && (e.status === 401 || e.status === 403)) emit('auth', e)
  else into.value = (e as any)?.message || String(e)
}

// filters, shared by both views
const q = ref('')
const status = ref<'all' | 'active' | 'revoked'>('all')
const period = ref<Period>('all')
const match = (name: string) => !q.value.trim() || name.toLocaleLowerCase().includes(q.value.trim().toLocaleLowerCase())
const shownTokens = computed(() => {
  const from = since(period.value)
  return (props.tokens || []).filter((k) => match(k.name)
    && (status.value === 'all' || (status.value === 'revoked') === !!k.revokedAt)
    && (from == null || within(k.seen?.at ?? k.lastUsedAt, from)))
})
const shownBuiltins = computed(() => {
  const from = since(period.value)
  return props.builtins.filter((b) => match(b.name) && status.value !== 'revoked' && (from == null || within(b.seen?.at ?? null, from)))
})
const filtered = computed(() => !!q.value.trim() || status.value !== 'all' || period.value !== 'all')

// the map: one marker per key (and built-in) at its last-seen place, a colour per key
const colour = computed(() => colours([...props.builtins.map((b) => b.id), ...(props.tokens || []).map((k) => k.id)]))
const located = computed(() => [...shownBuiltins.value, ...shownTokens.value].filter((k) => k.seen?.lat != null && k.seen?.lon != null))
const markers = computed<MapMarker[]>(() => located.value.map((k) => ({
  id: k.id, lat: k.seen!.lat!, lon: k.seen!.lon!, colour: colour.value.get(k.id)!, label: k.name, popup: seenPopup(k.name, k.seen!),
})))
const legend = computed(() => located.value.map((k) => ({ label: k.name, colour: colour.value.get(k.id)!, kind: 'dot' as const })))
const unlocated = computed(() => shownBuiltins.value.length + shownTokens.value.length - located.value.length)

// the side panel: a key or built-in (by id), or the new-key form
const panel = ref<string | 'new' | null>(null)
const openKey = computed(() => (props.tokens || []).find((k) => k.id === panel.value) || null)
const openBuiltin = computed(() => props.builtins.find((b) => b.id === panel.value) || null)
function open(id: string) {
  panel.value = id; issued.value = null; revoking.value = false; editErr.value = ''
  const k = (props.tokens || []).find((x) => x.id === id)
  if (k) { editName.value = k.name; editScopes.value = [...k.scopes] }
}
function closePanel() {
  if (panel.value === 'new') { newName.value = ''; newScopes.value = [...PRESETS.phone]; issueErr.value = '' }
  panel.value = null; issued.value = null; revoking.value = false
}

// one key: rename, permissions, revoke
const editName = ref('')
const editScopes = ref<Scope[]>([])
const editErr = ref('')
const saving = ref(false)
const revoking = ref(false)
const dirty = computed(() => {
  const k = openKey.value
  if (!k) return false
  return editName.value.trim() !== k.name || editScopes.value.length !== k.scopes.length || !editScopes.value.every((x) => k.scopes.includes(x))
})
async function save() {
  const k = openKey.value
  if (!k || !dirty.value || !editName.value.trim() || !editScopes.value.length) return
  editErr.value = ''; saving.value = true
  try {
    const change: { name?: string; scopes?: Scope[] } = {}
    if (editName.value.trim() !== k.name) change.name = editName.value.trim()
    if (editScopes.value.length !== k.scopes.length || !editScopes.value.every((x) => k.scopes.includes(x))) change.scopes = editScopes.value
    await updateToken(props.cfg, k.id, change)
    emit('reload')
  } catch (e) {
    if (e instanceof CloudError && e.status === 409) { editErr.value = t('tokEditRevoked'); emit('reload') }
    else failed(e, editErr)
  } finally {
    saving.value = false
  }
}
async function revoke() {
  const k = openKey.value
  if (!k) return
  editErr.value = ''
  try { await revokeToken(props.cfg, k.id); revoking.value = false; emit('reload') } catch (e) { failed(e, editErr) }
}
// after a reload the open key is the server's: the fields follow it unless being edited
watch(openKey, (k, old) => { if (k && (!old || old.id !== k.id || !dirty.value)) { editName.value = k.name; editScopes.value = [...k.scopes] } })

// issue, in the side panel: the secret lives only in `issued` until the panel closes
function openNew() { panel.value = 'new'; issued.value = null; issueErr.value = '' }
const newName = ref('')
const newScopes = ref<Scope[]>([...PRESETS.phone])
const issuing = ref(false)
const issueErr = ref('')
const issued = ref<{ name: string; scopes: Scope[]; secret: string; link: string; qr: string } | null>(null)
const copied = ref('')
async function issue() {
  issueErr.value = ''
  issuing.value = true
  try {
    const r = await issueToken(props.cfg, newName.value, newScopes.value)
    const link = setupLink(endpoint(props.cfg.url)!, r.secret, r.token.name)
    const qr = await QRCode.toString(link, { type: 'svg', errorCorrectionLevel: 'M', margin: 2 })
    issued.value = { name: r.token.name, scopes: r.token.scopes, secret: r.secret, link, qr }
    copied.value = ''
    newName.value = ''
    newScopes.value = [...PRESETS.phone]
    emit('reload')
  } catch (e) {
    failed(e, issueErr)
  } finally {
    issuing.value = false
  }
}
async function copy(what: 'secret' | 'link') {
  if (!issued.value) return
  try {
    await navigator.clipboard.writeText(what === 'secret' ? issued.value.secret : issued.value.link)
    copied.value = what
  } catch {
    copied.value = 'fail'
  }
}
defineExpose({ forget: closePanel })
const anyRows = computed(() => shownTokens.value.length + shownBuiltins.value.length > 0)
</script>

<template>
  <div class="adminpane">
    <div class="row adminfilters">
      <input v-model="q" type="search" class="small" :placeholder="t('keySearch')" :aria-label="t('keySearch')" />
      <label class="small">{{ t('tokStatus') }}
        <select v-model="status">
          <option value="all">{{ t('statusAll') }}</option>
          <option value="active">{{ t('statusActive') }}</option>
          <option value="revoked">{{ t('statusRevoked') }}</option>
        </select>
      </label>
      <label class="small">{{ t('tokLastSeen') }}
        <select v-model="period">
          <option value="7">{{ t('mapPeriod7') }}</option>
          <option value="30">{{ t('mapPeriod30') }}</option>
          <option value="all">{{ t('mapPeriodAll') }}</option>
        </select>
      </label>
      <span class="muted small">{{ filtered ? t('shownOf', { n: shownTokens.length, total: tokens?.length ?? 0 }) : t('keysCount', { n: tokens?.length ?? 0 }) }}</span>
      <span class="btns">
        <button class="ghost small" type="button" @click="emit('reload')">{{ t('cloudRefresh') }}</button>
        <button class="primary small" type="button" @click="openNew">+ {{ t('newKey') }}</button>
      </span>
    </div>

    <template v-if="view === 'map'">
      <p v-if="!markers.length" class="empty soft small">{{ t('mapEmptyKeys') }}</p>
      <LayerMap v-else :markers="markers" :legend="legend" pickable @pick="open" />
      <p class="muted small">{{ unlocated ? t('mapNoPlace', { n: unlocated }) + ' ' : '' }}{{ t('seenApprox') }}</p>
    </template>

    <template v-else>
      <p v-if="tokens && !anyRows" class="muted small">{{ filtered ? t('noneMatch') : t('tokNone') }}</p>
      <div v-else-if="tokens" class="tablewrap">
        <!-- the "wide" columns give way below ~980 px of room (their values move under the name, "sub");
             below ~720 px the table becomes cards (style.css, container queries on .adminpane) -->
        <table class="atable cards tokens">
          <thead><tr>
            <th>{{ t('tokName') }}</th><th>{{ t('tokPerms') }}</th><th class="wide">{{ t('tokPrefix') }}</th><th class="wide">{{ t('tokCreated') }}</th>
            <th>{{ t('tokLastSeen') }}</th><th class="num">{{ t('tokUploads') }}</th><th>{{ t('tokStatus') }}</th><th></th>
          </tr></thead>
          <tbody>
            <!-- built-in identities: server secrets, read-only -->
            <tr v-for="b in shownBuiltins" :key="b.id" class="builtin pick" :class="{ on: panel === b.id }" tabindex="0"
              @click="open(b.id)" @keydown.enter="open(b.id)">
              <td class="name"><b>{{ b.name }}</b> <span class="chip">{{ t('builtinChip') }}</span>
                <div class="sub muted small">{{ t('builtinSecret') }}</div></td>
              <td class="perms" :data-label="t('tokPerms')"><ScopeChips :scopes="b.scopes" /></td>
              <td class="wide nw muted" :data-label="t('tokPrefix')">{{ t('builtinSecret') }}</td>
              <td class="wide nw muted" :data-label="t('tokCreated')">–</td>
              <td class="seen" :data-label="t('tokLastSeen')">
                <template v-if="b.seen"><span class="nw" :title="seenTitle(b.seen)">{{ relTime(b.seen.at) }}</span>
                  <span v-if="twoClients(b.seen)" class="chip visit" :title="twoTitle(b.seen)">{{ t('seenTwo') }}</span>
                  <div class="muted small">{{ seenLine(b.seen) }}</div></template>
                <span v-else class="muted">{{ t('tokNever') }}</span>
              </td>
              <td class="num muted" :data-label="t('tokUploads')">–</td>
              <td class="status" :data-label="t('tokStatus')"><span class="chip ok">{{ t('tokActive') }}</span></td>
              <td class="act muted">›</td>
            </tr>
            <tr v-for="k in shownTokens" :key="k.id" class="pick" :class="{ revoked: k.revokedAt, on: panel === k.id }" tabindex="0"
              @click="open(k.id)" @keydown.enter="open(k.id)">
              <td class="name"><b>{{ k.name }}</b>
                <div class="sub muted small"><code>{{ k.prefix }}…</code> · <span :title="when(k.createdAt)">{{ short(k.createdAt) }}</span></div></td>
              <td class="perms" :data-label="t('tokPerms')"><ScopeChips :scopes="k.scopes" /></td>
              <td class="wide nw" :data-label="t('tokPrefix')"><code>{{ k.prefix }}…</code></td>
              <td class="wide nw" :data-label="t('tokCreated')" :title="when(k.createdAt)">{{ short(k.createdAt) }}</td>
              <td class="seen" :data-label="t('tokLastSeen')">
                <template v-if="k.seen"><span class="nw" :title="seenTitle(k.seen)">{{ relTime(k.seen.at) }}</span>
                  <span v-if="twoClients(k.seen)" class="chip visit" :title="twoTitle(k.seen)">{{ t('seenTwo') }}</span>
                  <div class="muted small">{{ seenLine(k.seen) }}</div></template>
                <span v-else-if="k.lastUsedAt" class="nw" :title="t('tokLastUsed') + ': ' + when(k.lastUsedAt)">{{ short(k.lastUsedAt) }}</span>
                <span v-else class="muted">{{ t('tokNever') }}</span>
              </td>
              <td class="num" :data-label="t('tokUploads')">{{ k.uploads }}</td>
              <td class="status" :data-label="t('tokStatus')">
                <span v-if="k.revokedAt" class="chip rm" :title="when(k.revokedAt)">{{ t('tokRevoked', { date: short(k.revokedAt) }) }}</span>
                <span v-else class="chip ok">{{ t('tokActive') }}</span>
              </td>
              <td class="act muted">›</td>
            </tr>
          </tbody>
        </table>
      </div>

    </template>

    <!-- the side panel (a full-height sheet on a phone): one key, a built-in, or New key -->
    <div v-if="panel" class="drawerback" @click.self="issued ? null : closePanel()">
      <aside class="drawer" role="dialog" aria-modal="true"
        :aria-label="panel === 'new' ? t('issueTitle') : (openKey?.name || openBuiltin?.name || '')">

        <!-- New key -->
        <template v-if="panel === 'new'">
          <div class="row">
            <h3>{{ issued ? t('issuedTitle', { name: issued.name }) : t('issueTitle') }}</h3>
            <button class="ghost" :aria-label="t('close')" @click="closePanel">✕</button>
          </div>
          <div v-if="issued" class="issued">
            <div class="small">{{ t('tokPerms') }}: <ScopeChips :scopes="issued.scopes" /></div>
            <p class="small warn">{{ t('issuedOnce') }}</p>
            <div class="secret">
              <code>{{ issued.secret }}</code>
              <button class="secondary small" @click="copy('secret')">{{ copied === 'secret' ? t('copied') : t('copy') }}</button>
            </div>
            <div class="qrrow">
              <div class="qr" role="img" :aria-label="t('issuedQrAlt')" v-html="issued.qr"></div>
              <div class="small">
                <p>{{ t('issuedQr') }}</p>
                <button class="secondary small" @click="copy('link')">{{ copied === 'link' ? t('copied') : t('copyLink') }}</button>
                <p v-if="copied === 'fail'" class="err-text">{{ t('copyFailed') }}</p>
              </div>
            </div>
            <div class="row">
              <button class="primary small" @click="closePanel">{{ t('issuedDone') }}</button>
              <button class="ghost small" @click="issued = null">{{ t('issueAnother') }}</button>
            </div>
          </div>
          <form v-else class="issue" @submit.prevent="issue">
            <label class="small namefield"><b>{{ t('tokName') }}</b>
              <input v-model="newName" maxlength="80" :placeholder="t('issueNamePh')" autofocus /></label>
            <b class="small">{{ t('tokPerms') }}</b>
            <ScopePicker v-model="newScopes" presets :disabled="issuing" />
            <p class="muted small">{{ t('issueHint') }}</p>
            <p v-if="issueErr" class="small err-text">{{ issueErr }}</p>
            <div class="row">
              <button class="primary" type="submit" :disabled="!newName.trim() || !newScopes.length || issuing">{{ issuing ? t('adminChecking') : t('issueBtn') }}</button>
              <button class="ghost" type="button" @click="closePanel">{{ t('cancel') }}</button>
            </div>
          </form>
        </template>

        <!-- one issued key -->
        <template v-else-if="openKey">
          <div class="row">
            <h3>{{ openKey.name }}</h3>
            <button class="ghost" :aria-label="t('close')" @click="closePanel">✕</button>
          </div>
          <div>
            <span v-if="openKey.revokedAt" class="chip rm">{{ t('tokRevoked', { date: short(openKey.revokedAt) }) }}</span>
            <span v-else class="chip ok">{{ t('tokActive') }}</span>
          </div>
          <dl class="setfacts small">
            <dt>{{ t('tokPrefix') }}</dt><dd><code>{{ openKey.prefix }}…</code></dd>
            <dt>{{ t('tokCreated') }}</dt><dd>{{ when(openKey.createdAt) }}</dd>
            <dt>{{ t('tokUploads') }}</dt><dd>{{ openKey.uploads }}</dd>
            <dt>{{ t('tokLastSeen') }}</dt>
            <dd>{{ openKey.seen ? relTime(openKey.seen.at) : openKey.lastUsedAt ? when(openKey.lastUsedAt) : t('tokNever') }}</dd>
          </dl>
          <template v-if="openKey.seen">
            <div class="seenfull small">{{ seenTitle(openKey.seen) }}</div>
            <div v-if="twoClients(openKey.seen)" class="seenfull small">{{ twoTitle(openKey.seen) }}</div>
            <p class="muted small">{{ t('seenApprox') }}</p>
          </template>

          <form v-if="!openKey.revokedAt" class="issue" @submit.prevent="save">
            <label class="small namefield"><b>{{ t('tokName') }}</b>
              <input v-model="editName" maxlength="80" :disabled="saving" /></label>
            <b class="small">{{ t('tokPerms') }}</b>
            <ScopePicker v-model="editScopes" :disabled="saving" />
            <p class="muted small">{{ t('tokEditHint') }}</p>
            <p v-if="editErr" class="small err-text">{{ editErr }}</p>
            <div class="row">
              <button class="primary" type="submit" :disabled="!dirty || !editName.trim() || !editScopes.length || saving">{{ saving ? t('tokSaving') : t('tokSave') }}</button>
            </div>
          </form>

          <div v-if="!openKey.revokedAt" class="dangerzone">
            <b class="small">{{ t('tokRevoke') }}</b>
            <p class="muted small">{{ t('tokRevokeHint') }}</p>
            <div v-if="revoking" class="row">
              <span class="small">{{ t('tokRevokeConfirm') }}</span>
              <button class="danger small" @click="revoke">{{ t('tokRevokeYes') }}</button>
              <button class="ghost small" @click="revoking = false">{{ t('cancel') }}</button>
            </div>
            <button v-else class="danger small" @click="revoking = true">{{ t('tokRevoke') }}</button>
          </div>
        </template>

        <!-- a built-in identity: read-only -->
        <template v-else-if="openBuiltin">
          <div class="row">
            <h3>{{ openBuiltin.name }} <span class="chip">{{ t('builtinChip') }}</span></h3>
            <button class="ghost" :aria-label="t('close')" @click="closePanel">✕</button>
          </div>
          <div class="small">{{ t('tokPerms') }}: <ScopeChips :scopes="openBuiltin.scopes" /></div>
          <p class="muted small">{{ t('builtinsHint') }}</p>
          <template v-if="openBuiltin.seen">
            <div class="seenfull small">{{ seenTitle(openBuiltin.seen) }}</div>
            <div v-if="twoClients(openBuiltin.seen)" class="seenfull small">{{ twoTitle(openBuiltin.seen) }}</div>
          </template>
          <p v-else class="muted small">{{ t('seenNever') }}</p>
        </template>
      </aside>
    </div>
  </div>
</template>
