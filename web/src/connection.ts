// The reviewer works from the project's server (server/README.md; its address is the build's, cloud.ts
// DEFAULT_CLOUD_URL): every session comes from it, nothing is imported by hand. This keeps the access key (in this
// browser), who the key belongs to (GET /me,
// also the name saved with each review) and the server's sessions (index.json), and downloads a processed session
// into this browser (IndexedDB, store.ts) the first time it is opened or when the server has a newer one.
import { computed, reactive } from 'vue'
import {
  loadCloud, saveCloud, me, fetchIndex, downloadItem, endpoint, CloudError,
  type CloudConfig, type CloudItem, type Me,
} from './cloud'
import { importFiles } from './importer'
import { state, add, setReviewer } from './store'
export { rows, type Row } from './sessions'

export type Status = 'none' | 'checking' | 'ok' | 'error'

export const conn = reactive({
  cfg: loadCloud() as CloudConfig,
  me: null as Me | null,
  status: 'none' as Status,
  error: '',
  /** 401 / 403 on /me: the key itself is wrong or revoked (not the network). */
  keyRefused: false,
  items: null as CloudItem[] | null,   // every upload the key may list, newest first
  listing: false,
  listError: '',
  pulling: '' as string,               // the session id being downloaded
})

export const connected = computed(() => conn.status === 'ok' && !!conn.me)
/** The key may list and download (View & download, or Process rides). */
export const canRead = computed(() => !!conn.me && (conn.me.admin || conn.me.scopes.includes('read') || conn.me.scopes.includes('process')))

/** Ask the server who the key is, then list its sessions. Without a key: 'none'. */
export async function connect(f: typeof fetch = fetch) {
  conn.error = ''; conn.keyRefused = false; conn.listError = ''
  if (!conn.cfg.token.trim() || !endpoint(conn.cfg.url)) { conn.status = 'none'; conn.me = null; conn.items = null; return }
  conn.status = 'checking'
  try {
    conn.me = await me(conn.cfg, f)
    conn.status = 'ok'
    if (conn.me.name && state.reviewer !== conn.me.name) setReviewer(conn.me.name)
  } catch (e: any) {
    conn.me = null
    conn.status = 'error'
    conn.keyRefused = e instanceof CloudError && (e.status === 401 || e.status === 403 || e.status === 409)
    conn.error = e?.message || String(e)
    return
  }
  await refresh(f)
}

export async function refresh(f: typeof fetch = fetch) {
  if (!connected.value || !canRead.value) { conn.items = null; return }
  conn.listing = true; conn.listError = ''
  try {
    conn.items = await fetchIndex(conn.cfg, f, '')
  } catch (e: any) {
    conn.listError = e?.message || String(e)
  } finally {
    conn.listing = false
  }
}

/** A new access key (Settings): saved in this browser, then connect. */
export async function setKey(token: string, f: typeof fetch = fetch) {
  saveCloud({ token })
  conn.cfg = loadCloud()
  await connect(f)
}

/** Forget the key in this browser (the downloaded sessions and reviews stay). */
export function signOut() {
  conn.cfg = { ...conn.cfg, token: '' }
  saveCloud(conn.cfg)
  conn.me = null; conn.items = null; conn.status = 'none'; conn.error = ''; conn.keyRefused = false
}

/** Download a processed session into this browser (first open, or the server's newer version). */
export async function pull(item: CloudItem, f: typeof fetch = fetch): Promise<string> {
  conn.pulling = item.sessionId || item.id
  try {
    const file = await downloadItem(conn.cfg, item, f)
    const got = await importFiles([file])
    if (!got.length) throw new Error('the download holds no session')
    await add(got)
    return got[0].dataset.id
  } finally {
    conn.pulling = ''
  }
}
