<script setup lang="ts">
// Admin, "Access keys": issue a key with its permissions (a secret shown once, with the phone's setup QR code drawn
// here, never by a web service), change a key's permissions, revoke it; the built-in identities. Two views of the
// same filtered keys: a list, or a map of where each was last seen (IP lookup: approximate).
import { computed, ref } from 'vue'
import QRCode from 'qrcode'
import { t, relTime } from '../i18n'
import {
  issueToken, updateToken, revokeToken, setupLink, endpoint, twoClients, CloudError, PRESETS,
  type Builtin, type CloudConfig, type Scope, type Token,
} from '../cloud'
import { when, short, seenLine, seenTitle, twoTitle, since, within, colours, seenPopup, approxNote, type Period } from '../adminfmt'
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

/** The key (or built-in) whose full last-seen record is spelled out: a tap, as touch screens have no tooltips. */
const seenOpen = ref<string | null>(null)
const toggleSeen = (id: string) => (seenOpen.value = seenOpen.value === id ? null : id)

const tokErr = ref('')
const revoking = ref<string | null>(null)
async function revoke(id: string) {
  tokErr.value = ''
  try { await revokeToken(props.cfg, id); revoking.value = null; emit('reload') } catch (e) { failed(e, tokErr) }
}

// change a key's permissions: one at a time, in a full-width row below it (a table) or inside its card
const editing = ref<string | null>(null)
const editScopes = ref<Scope[]>([])
const editErr = ref('')
const saving = ref(false)
function startEdit(k: Token) {
  editing.value = k.id; editScopes.value = [...k.scopes]; editErr.value = ''; revoking.value = null
}
function cancelEdit() { editing.value = null; editErr.value = '' }
async function saveEdit(k: Token) {
  editErr.value = ''
  if (!editScopes.value.length) return
  if (editScopes.value.length === k.scopes.length && editScopes.value.every((x) => k.scopes.includes(x))) { cancelEdit(); return }
  saving.value = true
  try {
    await updateToken(props.cfg, k.id, { scopes: editScopes.value })
    cancelEdit()
    emit('reload')
  } catch (e) {
    if (e instanceof CloudError && e.status === 409) { editErr.value = t('tokEditRevoked'); emit('reload') }
    else failed(e, editErr)
  } finally {
    saving.value = false
  }
}

// issue: the secret lives only in `issued` until dismissed
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
defineExpose({ forget: () => { issued.value = null; editing.value = null } })
</script>

<template>
  <div class="adminpane">
    <!-- the new secret, shown once -->
    <div v-if="issued" class="issued">
      <b>{{ t('issuedTitle', { name: issued.name }) }}</b>
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
      <button class="primary small" @click="issued = null">{{ t('issuedDone') }}</button>
    </div>

    <form v-else-if="view === 'list'" class="issue" @submit.prevent="issue">
      <b class="small">{{ t('issueTitle') }}</b>
      <div class="issuerow">
        <input v-model="newName" maxlength="80" :placeholder="t('issueNamePh')" :aria-label="t('tokName')" />
        <button class="primary small" type="submit" :disabled="!newName.trim() || !newScopes.length || issuing">{{ t('issueBtn') }}</button>
      </div>
      <ScopePicker v-model="newScopes" presets :disabled="issuing" />
      <p class="muted small">{{ t('issueHint') }}</p>
      <p v-if="issueErr" class="small err-text">{{ issueErr }}</p>
    </form>

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
      <button class="ghost small" type="button" @click="emit('reload')">{{ t('cloudRefresh') }}</button>
    </div>
    <p v-if="tokErr" class="small err-text">{{ tokErr }}</p>

    <template v-if="view === 'map'">
      <p v-if="!markers.length" class="empty soft small">{{ t('mapEmptyKeys') }}</p>
      <LayerMap v-else :markers="markers" :legend="legend" :popup-footer="approxNote()" />
      <p class="muted small">{{ unlocated ? t('mapNoPlace', { n: unlocated }) + ' ' : '' }}{{ t('seenApprox') }}</p>
    </template>

    <template v-else>
      <p v-if="tokens && !tokens.length" class="muted small">{{ t('tokNone') }}</p>
      <p v-else-if="tokens && !shownTokens.length" class="muted small">{{ t('noneMatch') }}</p>
      <div v-else-if="tokens" class="tablewrap">
        <!-- the "wide" columns give way below ~980 px of room (their values move under the name, "sub");
             below ~720 px the table becomes cards (style.css, container queries on .adminpane) -->
        <table class="atable cards tokens">
          <thead><tr>
            <th>{{ t('tokName') }}</th><th>{{ t('tokPerms') }}</th><th class="wide">{{ t('tokPrefix') }}</th><th class="wide">{{ t('tokCreated') }}</th>
            <th>{{ t('tokLastSeen') }}</th><th class="num">{{ t('tokUploads') }}</th><th>{{ t('tokStatus') }}</th><th></th>
          </tr></thead>
          <tbody>
            <template v-for="k in shownTokens" :key="k.id">
              <tr :class="{ revoked: k.revokedAt, open: (seenOpen === k.id && k.seen) || editing === k.id }">
                <td class="name"><b>{{ k.name }}</b>
                  <div class="sub muted small"><code>{{ k.prefix }}…</code> · <span :title="when(k.createdAt)">{{ short(k.createdAt) }}</span></div></td>
                <td class="perms" :data-label="t('tokPerms')">
                  <ScopeChips :scopes="k.scopes" />
                  <!-- cards: the editor inside the card; a table: in a full-width row below (editrow) -->
                  <div v-if="editing === k.id" class="scopeedit incell">
                    <ScopePicker v-model="editScopes" :disabled="saving" />
                    <div class="editbtns">
                      <button class="primary small" :disabled="!editScopes.length || saving" @click="saveEdit(k)">{{ saving ? t('tokSaving') : t('tokSave') }}</button>
                      <button class="ghost small" :disabled="saving" @click="cancelEdit">{{ t('cancel') }}</button>
                    </div>
                    <p class="muted small">{{ t('tokEditHint') }}</p>
                    <p v-if="editErr" class="small err-text">{{ editErr }}</p>
                  </div>
                </td>
                <td class="wide nw" :data-label="t('tokPrefix')"><code>{{ k.prefix }}…</code></td>
                <td class="wide nw" :data-label="t('tokCreated')" :title="when(k.createdAt)">{{ short(k.createdAt) }}</td>
                <td class="seen" :data-label="t('tokLastSeen')">
                  <template v-if="k.seen">
                    <span class="nw" :title="seenTitle(k.seen)">{{ relTime(k.seen.at) }}</span>
                    <span v-if="twoClients(k.seen)" class="chip visit" :title="twoTitle(k.seen)">{{ t('seenTwo') }}</span>
                    <button type="button" class="ghost small more" :aria-expanded="seenOpen === k.id" @click="toggleSeen(k.id)">{{ seenOpen === k.id ? t('seenLess') : t('seenMore') }}</button>
                    <div class="muted small" :title="seenTitle(k.seen)">{{ seenLine(k.seen) }}</div>
                    <!-- cards: the details inside the card; a table: in a full-width row below (seenrow) -->
                    <div v-if="seenOpen === k.id" class="seenfull incell small">{{ seenTitle(k.seen) }}</div>
                    <div v-if="seenOpen === k.id && twoClients(k.seen)" class="seenfull incell small">{{ twoTitle(k.seen) }}</div>
                  </template>
                  <span v-else-if="k.lastUsedAt" class="nw" :title="t('tokLastUsed') + ': ' + when(k.lastUsedAt)">{{ short(k.lastUsedAt) }}</span>
                  <span v-else class="muted">{{ t('tokNever') }}</span>
                </td>
                <td class="num" :data-label="t('tokUploads')">{{ k.uploads }}</td>
                <td class="status" :data-label="t('tokStatus')">
                  <span v-if="k.revokedAt" class="chip rm" :title="when(k.revokedAt)">{{ t('tokRevoked', { date: short(k.revokedAt) }) }}</span>
                  <span v-else class="chip ok">{{ t('tokActive') }}</span>
                </td>
                <td class="act">
                  <template v-if="!k.revokedAt">
                    <span v-if="revoking === k.id" class="confirm">
                      <span class="small">{{ t('tokRevokeConfirm') }}</span>
                      <button class="danger small" @click="revoke(k.id)">{{ t('tokRevokeYes') }}</button>
                      <button class="ghost small" @click="revoking = null">{{ t('cancel') }}</button>
                    </span>
                    <span v-else class="btns">
                      <button v-if="editing !== k.id" class="secondary small" @click="startEdit(k)">{{ t('tokEdit') }}</button>
                      <button class="danger small" @click="revoking = k.id; cancelEdit()">{{ t('tokRevoke') }}</button>
                    </span>
                  </template>
                </td>
              </tr>
              <tr v-if="editing === k.id" class="seenrow editrow">
                <td colspan="8"><div class="scopeedit">
                  <b class="small">{{ t('tokEditTitle', { name: k.name }) }}</b>
                  <ScopePicker v-model="editScopes" :disabled="saving" />
                  <div class="editbtns">
                    <button class="primary small" :disabled="!editScopes.length || saving" @click="saveEdit(k)">{{ saving ? t('tokSaving') : t('tokSave') }}</button>
                    <button class="ghost small" :disabled="saving" @click="cancelEdit">{{ t('cancel') }}</button>
                    <span class="muted small">{{ t('tokEditHint') }}</span>
                  </div>
                  <p v-if="editErr" class="small err-text">{{ editErr }}</p>
                </div></td>
              </tr>
              <tr v-if="seenOpen === k.id && k.seen" class="seenrow">
                <td colspan="8"><div class="seenwrap">
                  <div class="seenfull small">{{ seenTitle(k.seen) }}</div>
                  <div v-if="twoClients(k.seen)" class="seenfull small">{{ twoTitle(k.seen) }}</div>
                </div></td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>

      <template v-if="shownBuiltins.length">
        <b class="small">{{ t('builtinsTitle') }}</b>
        <p class="muted small">{{ t('builtinsHint') }}</p>
        <ul class="builtins small">
          <li v-for="b in shownBuiltins" :key="b.id">
            <b>{{ b.name }}</b> <ScopeChips :scopes="b.scopes" />
            <template v-if="b.seen">
              · <span :title="seenTitle(b.seen)">{{ relTime(b.seen.at) }}</span>
              <span v-if="twoClients(b.seen)" class="chip visit" :title="twoTitle(b.seen)">{{ t('seenTwo') }}</span>
              <span class="muted" :title="seenTitle(b.seen)"> · {{ seenLine(b.seen) }}</span>
              <button type="button" class="ghost small more" :aria-expanded="seenOpen === b.id" @click="toggleSeen(b.id)">{{ seenOpen === b.id ? t('seenLess') : t('seenMore') }}</button>
              <div v-if="seenOpen === b.id" class="seenfull">{{ seenTitle(b.seen) }}</div>
              <div v-if="seenOpen === b.id && twoClients(b.seen)" class="seenfull">{{ twoTitle(b.seen) }}</div>
            </template>
            <span v-else class="muted"> · {{ t('seenNever') }}</span>
          </li>
        </ul>
      </template>
    </template>
  </div>
</template>
