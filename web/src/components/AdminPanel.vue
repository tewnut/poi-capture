<script setup lang="ts">
// Admin: the cloud server's personal access tokens (issue, list, revoke) and its uploads (delete). Needs the admin
// password (the server's ADMIN_TOKEN), kept in sessionStorage only (cloud.ts) and separate from the reviewer token.
// A new token's secret is shown once, with a QR code of the phone setup link drawn here (never by a web service).
import { computed, onMounted, ref } from 'vue'
import QRCode from 'qrcode'
import { t, lang, relTime } from '../i18n'
import {
  me, adminTokens, issueToken, revokeToken, deleteUpload, fetchIndex, setupLink, endpoint, humanSize,
  loadAdminToken, saveAdminToken, CloudError, PROCESSED, seenPlace, shortClient, twoClients,
  type Builtin, type CloudItem, type Me, type Seen, type Token,
} from '../cloud'
import AdminMap from './AdminMap.vue'

const props = defineProps<{ url: string }>()

const pw = ref(loadAdminToken())
const who = ref<Me | null>(null)
const authErr = ref('')
const checking = ref(false)
const tab = ref<'tokens' | 'uploads' | 'map'>('tokens')
const cfg = computed(() => ({ url: props.url, token: pw.value }))
const urlOk = computed(() => endpoint(props.url) != null)

function message(e: any): string {
  if (e instanceof CloudError && e.status === 401) return t('adminWrong')
  if (e instanceof CloudError && e.status === 403) return t('adminForbidden')
  return e?.message || String(e)
}

async function signIn() {
  authErr.value = ''; who.value = null
  if (!pw.value.trim() || !urlOk.value) return
  checking.value = true
  saveAdminToken(pw.value)
  try {
    const m = await me(cfg.value)
    if (m.role !== 'admin') {
      authErr.value = t('adminNotAdmin', { name: m.name, role: m.role })
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
  who.value = null; tokens.value = null; builtins.value = []; uploads.value = null; issued.value = null; authErr.value = ''
}
onMounted(() => { if (pw.value) signIn() })

/** An admin request failed: a 401/403 means the password stopped working, so go back to the password field. */
function failed(e: any, into: { value: string }) {
  if (e instanceof CloudError && (e.status === 401 || e.status === 403)) { who.value = null; authErr.value = message(e) }
  else into.value = message(e)
}
/** A server time (ISO, UTC) in this browser's time zone: "2026-11-21 06:45"; a date-only value as it is. */
function when(s: string | null): string {
  if (!s) return ''
  const d = new Date(s)
  if (s.length < 16 || Number.isNaN(d.getTime())) return s.slice(0, 16).replace('T', ' ')
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}
/** The same moment as `when`, compact for a table cell: "8 Nov, 13:38" (the year only when not this year). */
function short(s: string | null): string {
  if (!s) return ''
  const timed = s.length >= 16
  const d = new Date(timed ? s : `${s.slice(0, 10)}T00:00:00`)
  if (Number.isNaN(d.getTime())) return when(s)
  return d.toLocaleString(lang.value, {
    day: 'numeric', month: 'short',
    ...(d.getFullYear() !== new Date().getFullYear() ? { year: 'numeric' } : {}),
    ...(timed ? { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' } : {}),
  } as Intl.DateTimeFormatOptions)
}

// tokens
const tokens = ref<Token[] | null>(null)
const builtins = ref<Builtin[]>([])
const tokErr = ref('')
const revoking = ref<string | null>(null)
async function loadTokens() {
  tokErr.value = ''
  try { ({ tokens: tokens.value, builtins: builtins.value } = await adminTokens(cfg.value)) } catch (e) { failed(e, tokErr) }
}

// last seen: "3 hours ago" and "Thu Duc, VN · Viettel Group · App 0.16.0 (samsung SM-A546E)"; the full record in a tooltip
const seenLine = (s: Seen) => [seenPlace(s), s.network, shortClient(s.client, s.userAgent)].filter(Boolean).join(' · ')
const seenTitle = (s: Seen) => [
  `${t('seenAt')}: ${when(s.at)}`,
  `${t('seenPlace')}: ${[s.city, s.region, s.country].filter(Boolean).join(', ') || '–'} (${t('seenApproxShort')})`,
  s.network && `${t('seenNetwork')}: ${s.network}`,
  s.client && `${t('seenClient')}: ${s.client}`,
  s.userAgent && `${t('seenBrowser')}: ${s.userAgent}`,
].filter(Boolean).join('\n')
/** The token (or built-in) whose full last-seen record is spelled out: a tap, as touch screens have no tooltips. */
const seenOpen = ref<string | null>(null)
const toggleSeen = (id: string) => (seenOpen.value = seenOpen.value === id ? null : id)
const twoTitle = (s: Seen) => t('seenTwoTitle', { client: s.client || s.userAgent || '?', at: when(s.at), prev: s.prevClient, prevAt: when(s.prevAt) })
async function refreshAll() { await Promise.all([loadTokens(), loadUploads()]) }
async function revoke(id: string) {
  tokErr.value = ''
  try { await revokeToken(cfg.value, id); revoking.value = null; await loadTokens() } catch (e) { failed(e, tokErr) }
}

// issue: the secret lives only in `issued` until dismissed
const newName = ref('')
const newRole = ref<'uploader' | 'reviewer'>('uploader')
const issuing = ref(false)
const issueErr = ref('')
const issued = ref<{ name: string; role: string; secret: string; link: string; qr: string } | null>(null)
const copied = ref('')
async function issue() {
  issueErr.value = ''
  issuing.value = true
  try {
    const r = await issueToken(cfg.value, newName.value, newRole.value)
    const link = setupLink(endpoint(props.url)!, r.secret, r.token.name)
    const qr = await QRCode.toString(link, { type: 'svg', errorCorrectionLevel: 'M', margin: 2 })
    issued.value = { name: r.token.name, role: r.token.role, secret: r.secret, link, qr }
    copied.value = ''
    newName.value = ''
    await loadTokens()
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

// uploads: every type, finished or not
const uploads = ref<CloudItem[] | null>(null)
const upErr = ref('')
const upType = ref('')
const deleting = ref<string | null>(null)
const shownUploads = computed(() => (uploads.value || []).filter((u) => !upType.value || u.type === upType.value))
const upTypes = computed(() => [...new Set((uploads.value || []).map((u) => u.type))].sort())
async function loadUploads() {
  upErr.value = ''
  try { uploads.value = await fetchIndex(cfg.value, fetch, '') } catch (e) { failed(e, upErr) }
}
async function del(id: string) {
  upErr.value = ''
  try { await deleteUpload(cfg.value, id); deleting.value = null; await loadUploads() } catch (e) { failed(e, upErr) }
}
const typeLabel = (ty: string) => (ty === PROCESSED ? t('typeProcessed') : ty === 'raw-session' ? t('typeRaw') : ty)
const pct = (u: CloudItem) => (u.size ? Math.floor((u.offset / u.size) * 100) : 0)
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
        <nav class="seg">
          <button :class="{ on: tab === 'tokens' }" @click="tab = 'tokens'">{{ t('adminTokens') }} <span class="n">{{ tokens?.length ?? '' }}</span></button>
          <button :class="{ on: tab === 'uploads' }" @click="tab = 'uploads'">{{ t('adminUploads') }} <span class="n">{{ uploads?.length ?? '' }}</span></button>
          <button :class="{ on: tab === 'map' }" @click="tab = 'map'">{{ t('adminMap') }}</button>
        </nav>

        <section v-if="tab === 'tokens'" class="adminpane">
          <!-- the new secret, shown once -->
          <div v-if="issued" class="issued">
            <b>{{ t('issuedTitle', { name: issued.name }) }}</b>
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

          <form v-else class="issue" @submit.prevent="issue">
            <b class="small">{{ t('issueTitle') }}</b>
            <div class="issuerow">
              <input v-model="newName" maxlength="80" :placeholder="t('issueNamePh')" :aria-label="t('tokName')" />
              <select v-model="newRole" :aria-label="t('tokRole')">
                <option value="uploader">{{ t('role_uploader') }}</option>
                <option value="reviewer">{{ t('role_reviewer') }}</option>
              </select>
              <button class="primary small" type="submit" :disabled="!newName.trim() || issuing">{{ t('issueBtn') }}</button>
            </div>
            <p class="muted small">{{ t('issueHint') }}</p>
            <p v-if="issueErr" class="small err-text">{{ issueErr }}</p>
          </form>

          <div class="row">
            <b class="small">{{ t('adminTokens') }}</b>
            <button class="ghost small" @click="loadTokens">{{ t('cloudRefresh') }}</button>
          </div>
          <p v-if="tokErr" class="small err-text">{{ tokErr }}</p>
          <p v-if="tokens && !tokens.length" class="muted small">{{ t('tokNone') }}</p>
          <div v-else-if="tokens" class="tablewrap">
            <!-- the "wide" columns give way below ~980 px of room (their values move under the name, "sub");
                 below ~720 px the table becomes cards (style.css, container queries on .adminpane) -->
            <table class="atable cards tokens">
              <thead><tr>
                <th>{{ t('tokName') }}</th><th>{{ t('tokRole') }}</th><th class="wide">{{ t('tokPrefix') }}</th><th class="wide">{{ t('tokCreated') }}</th>
                <th>{{ t('tokLastSeen') }}</th><th class="num">{{ t('tokUploads') }}</th><th>{{ t('tokStatus') }}</th><th></th>
              </tr></thead>
              <tbody>
                <template v-for="k in tokens" :key="k.id">
                  <tr :class="{ revoked: k.revokedAt, open: seenOpen === k.id && k.seen }">
                    <td class="name"><b>{{ k.name }}</b>
                      <div class="sub muted small"><code>{{ k.prefix }}…</code> · <span :title="when(k.createdAt)">{{ short(k.createdAt) }}</span></div></td>
                    <td class="nw" :data-label="t('tokRole')">{{ t('roleShort_' + k.role) }}</td>
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
                        <button v-else class="danger small" @click="revoking = k.id">{{ t('tokRevoke') }}</button>
                      </template>
                    </td>
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

          <template v-if="builtins.length">
            <b class="small">{{ t('builtinsTitle') }}</b>
            <p class="muted small">{{ t('builtinsHint') }}</p>
            <ul class="builtins small">
              <li v-for="b in builtins" :key="b.id">
                <b>{{ b.name }}</b> <span class="muted">{{ t('roleShort_' + b.role) }}</span>
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
        </section>

        <section v-else-if="tab === 'map'" class="adminpane">
          <AdminMap :tokens="tokens || []" :builtins="builtins" :uploads="uploads || []">
            <template #actions><button class="ghost small" @click="refreshAll">{{ t('cloudRefresh') }}</button></template>
          </AdminMap>
          <p v-if="tokErr" class="small err-text">{{ tokErr }}</p>
          <p v-if="upErr" class="small err-text">{{ upErr }}</p>
        </section>

        <section v-else class="adminpane">
          <div class="row">
            <select v-model="upType" class="small">
              <option value="">{{ t('upAllTypes') }}</option>
              <option v-for="ty in upTypes" :key="ty" :value="ty">{{ typeLabel(ty) }}</option>
            </select>
            <button class="ghost small" @click="loadUploads">{{ t('cloudRefresh') }}</button>
          </div>
          <p v-if="upErr" class="small err-text">{{ upErr }}</p>
          <p v-if="uploads && !shownUploads.length" class="muted small">{{ t('upNone') }}</p>
          <div v-else-if="uploads" class="tablewrap">
            <table class="atable cards ups">
              <thead><tr>
                <th>{{ t('tokName') }}</th><th>{{ t('upType') }}</th><th class="num">{{ t('upSize') }}</th><th class="wide">{{ t('tokCreated') }}</th>
                <th class="wide">{{ t('upBy') }}</th><th></th>
              </tr></thead>
              <tbody>
                <tr v-for="u in shownUploads" :key="u.id">
                  <td class="name"><b>{{ u.name || u.sessionId || u.filename }}</b><div v-if="u.name || u.sessionId" class="muted small">{{ u.filename }}</div>
                    <div class="sub muted small"><span :title="when(u.createdAt)">{{ short(u.createdAt) }}</span> · {{ u.by?.name || '–' }}</div></td>
                  <td :data-label="t('upType')">{{ typeLabel(u.type) }} <span v-if="!u.complete" class="chip visit">{{ t('upUnfinished', { pct: pct(u) }) }}</span></td>
                  <td class="num nw" :data-label="t('upSize')">{{ humanSize(u.size) }}</td>
                  <td class="wide nw" :data-label="t('tokCreated')" :title="when(u.createdAt)">{{ short(u.createdAt) }}</td>
                  <td class="wide" :data-label="t('upBy')">{{ u.by?.name || '–' }}</td>
                  <td class="act">
                    <span v-if="deleting === u.id" class="confirm">
                      <span class="small">{{ t('upDeleteConfirm') }}</span>
                      <button class="danger small" @click="del(u.id)">{{ t('upDeleteYes') }}</button>
                      <button class="ghost small" @click="deleting = null">{{ t('cancel') }}</button>
                    </span>
                    <button v-else class="danger small" @click="deleting = u.id">{{ t('upDelete') }}</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </template>
    </div>
  </div>
</template>
