<script setup lang="ts">
// Admin, "Capture sessions": every upload on the server (the phones' raw sessions, the agent's processed results),
// finished or not, with delete. Two views of the same filtered uploads: a list, or a map of the rides (the `track`
// in a raw session's metadata, drawn without downloading the zips), one colour per uploader.
import { computed, ref } from 'vue'
import { t } from '../i18n'
import { deleteUpload, decodePolyline, humanSize, CloudError, PROCESSED, type CloudConfig, type CloudItem } from '../cloud'
import { when, short, since, within, colours, tripPopup, type Period } from '../adminfmt'
import LayerMap, { type MapLine } from './LayerMap.vue'

const props = defineProps<{ cfg: CloudConfig; uploads: CloudItem[] | null; view: 'list' | 'map' }>()
const emit = defineEmits<{ reload: []; auth: [e: unknown] }>()

function failed(e: unknown, into: { value: string }) {
  if (e instanceof CloudError && (e.status === 401 || e.status === 403)) emit('auth', e)
  else into.value = (e as any)?.message || String(e)
}

const all = computed(() => props.uploads || [])
const byName = (u: CloudItem) => u.by?.name ?? t('mapUnknown')

// filters, shared by both views: uploader ('' = unknown), period, type
const who = ref<string | null>(null)
const period = ref<Period>('all')
const upType = ref('')
const uploaders = computed(() => {
  const m = new Map<string, string>()
  for (const u of all.value) if (!m.has(u.by?.id ?? '')) m.set(u.by?.id ?? '', byName(u))
  return [...m].map(([id, name]) => ({ id, name })).sort((a, b) => (a.id === '' ? 1 : b.id === '' ? -1 : a.name.localeCompare(b.name)))
})
const upTypes = computed(() => [...new Set(all.value.map((u) => u.type))].sort())
const at = (u: CloudItem) => u.trip?.startedAt || u.createdAt
const shown = computed(() => {
  const from = since(period.value)
  return all.value.filter((u) => (who.value == null || (u.by?.id ?? '') === who.value) && (!upType.value || u.type === upType.value) && within(at(u), from))
})
const filtered = computed(() => who.value != null || period.value !== 'all' || !!upType.value)

// the map: the rides with a track, a colour per uploader
const colour = computed(() => colours(all.value.map((u) => u.by?.id ?? '')))
const tracked = computed(() => shown.value.map((u) => ({ u, pts: u.trip?.track ? decodePolyline(u.trip.track) : [] })).filter((x) => x.pts.length))
const lines = computed<MapLine[]>(() => tracked.value.map(({ u, pts }) => ({
  id: u.id, pts, colour: colour.value.get(u.by?.id ?? '')!, popup: tripPopup(u, byName(u)),
})))
const legend = computed(() => {
  const ids = new Set(tracked.value.map(({ u }) => u.by?.id ?? ''))
  return uploaders.value.filter((p) => ids.has(p.id)).map((p) => ({ label: p.name, colour: colour.value.get(p.id)! }))
})
const untracked = computed(() => shown.value.length - tracked.value.length)

const upErr = ref('')
const deleting = ref<string | null>(null)
async function del(id: string) {
  upErr.value = ''
  try { await deleteUpload(props.cfg, id); deleting.value = null; emit('reload') } catch (e) { failed(e, upErr) }
}
const typeLabel = (ty: string) => (ty === PROCESSED ? t('typeProcessed') : ty === 'raw-session' ? t('typeRaw') : ty)
const pct = (u: CloudItem) => (u.size ? Math.floor((u.offset / u.size) * 100) : 0)
</script>

<template>
  <div class="adminpane">
    <div class="row adminfilters">
      <label class="small">{{ t('mapUploader') }}
        <select v-model="who">
          <option :value="null">{{ t('mapAllUploaders') }}</option>
          <option v-for="p in uploaders" :key="p.id" :value="p.id">{{ p.name }}</option>
        </select>
      </label>
      <label class="small">{{ t('mapPeriod') }}
        <select v-model="period">
          <option value="7">{{ t('mapPeriod7') }}</option>
          <option value="30">{{ t('mapPeriod30') }}</option>
          <option value="all">{{ t('mapPeriodAll') }}</option>
        </select>
      </label>
      <label class="small">{{ t('upType') }}
        <select v-model="upType">
          <option value="">{{ t('upAllTypes') }}</option>
          <option v-for="ty in upTypes" :key="ty" :value="ty">{{ typeLabel(ty) }}</option>
        </select>
      </label>
      <span class="muted small">{{ filtered ? t('shownOf', { n: shown.length, total: all.length }) : t('sessionsCount', { n: all.length }) }}</span>
      <button class="ghost small" type="button" @click="emit('reload')">{{ t('cloudRefresh') }}</button>
    </div>
    <p v-if="upErr" class="small err-text">{{ upErr }}</p>

    <template v-if="view === 'map'">
      <p v-if="!lines.length" class="empty soft small">{{ t('mapEmptySessions') }}</p>
      <LayerMap v-else :lines="lines" :legend="legend" />
      <p v-if="untracked" class="muted small">{{ t('mapNoTrack', { n: untracked }) }}</p>
    </template>

    <template v-else>
      <p v-if="uploads && !all.length" class="muted small">{{ t('upNone') }}</p>
      <p v-else-if="uploads && !shown.length" class="muted small">{{ t('noneMatch') }}</p>
      <div v-else-if="uploads" class="tablewrap">
        <table class="atable cards ups">
          <thead><tr>
            <th>{{ t('tokName') }}</th><th>{{ t('upType') }}</th><th class="num">{{ t('upSize') }}</th><th class="wide">{{ t('tokCreated') }}</th>
            <th class="wide">{{ t('upBy') }}</th><th></th>
          </tr></thead>
          <tbody>
            <tr v-for="u in shown" :key="u.id">
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
    </template>
  </div>
</template>
