// The reviewer works from the project's server (server/README.md; its address is the build's, cloud.ts
// DEFAULT_CLOUD_URL): every session comes from it, nothing is imported by hand. This keeps the access key (in this
// browser), who the key belongs to (GET /me,
// also the name saved with each review) and the server's sessions (index.json), and downloads a processed session
// into this browser (IndexedDB, store.ts) the first time it is opened or when the server has a newer one.
import { computed, reactive } from 'vue'
import {
  loadCloud, saveCloud, me, fetchIndex, downloadItem, endpoint, CloudError,
  listReviews, checkout, releaseCheckout, getReview, putReviewPackage, CheckedOut,
  type CloudConfig, type CloudItem, type Me, type ReviewInfo, restoreReview } from './cloud'
import { build, parse, collect, reviewFiles, progress, REVIEW_SCHEMA } from './reviewpkg'
import type { Dataset } from './types'
import { importFiles } from './importer'
import { state, add, setReviewer, applyReview, photoBlob, saveReviews, setSynced } from './store'
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
  /** Per session: who has it checked out, and the last review saved on the server (GET /reviews). */
  reviews: {} as Record<string, ReviewInfo>,
  reviewBusy: '' as string,            // the session whose review is being checked out / saved / checked in
})

/** The sessions this reviewer holds: the only ones the store lets change (store.ts state.editable). */
function syncEditable() {
  const mine = new Set<string>()
  for (const r of Object.values(conn.reviews)) if (r.lock && conn.me && r.lock.by.id === conn.me.id) mine.add(r.sessionId)
  state.editable = mine
}
function setInfo(info: ReviewInfo) {
  if (!info.sessionId) return
  conn.reviews = { ...conn.reviews, [info.sessionId]: info }
  syncEditable()
}

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
    const [items, reviews] = await Promise.all([fetchIndex(conn.cfg, f, ''), listReviews(conn.cfg, f).catch(() => null)])
    conn.items = items
    if (reviews) { conn.reviews = Object.fromEntries(reviews.map((r) => [r.sessionId, r])); syncEditable() }
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
  conn.reviews = {}; syncEditable()
}

// ---- reviews: check out → edit in this browser → save / check in (server/README.md "Reviews")

/**
 * The server's saved review of [d] replaces this browser's, when it is newer than the one loaded here. Never over
 * changes not saved to the server (state.dirty) unless [force]: a reviewer whose checkout ended keeps their work until
 * they choose (ReviewBar: check out again and keep it, or discard it).
 */
export async function loadSaved(d: Dataset, force = false, f: typeof fetch = fetch) {
  const saved = conn.reviews[d.id]?.saved
  if (!force && state.dirty.has(d.id)) return
  if (!saved || (!force && state.synced[d.id] && state.synced[d.id] >= saved.at)) return
  const zip = await getReview(conn.cfg, d.id, f)
  if (!zip) return
  const { doc, files } = parse(zip, d.id)
  const blobs = new Map([...files].map(([p, b]) => [p, new Blob([b as BlobPart], { type: p.endsWith('.png') ? 'image/png' : p.endsWith('.webp') ? 'image/webp' : 'image/jpeg' })]))
  await applyReview(d.id, doc.reviews, doc.removals, blobs, saved.at)
}

/**
 * Take the session: the server's latest review comes into this browser first; then it can be edited. [keepMine]: this
 * browser's unsaved changes (from a checkout that ended) are kept instead, and replace the server's on the next save.
 */
export async function checkOut(d: Dataset, keepMine = false, f: typeof fetch = fetch) {
  conn.reviewBusy = d.id
  try {
    const info = await checkout(conn.cfg, d.id, f)
    // a review saved on the server is the one to go on from; none yet: this browser's (older work) stays
    if (!keepMine) { state.dirty.delete(d.id); await loadSaved(d, false, f) }
    setInfo(info)
  } finally {
    conn.reviewBusy = ''
  }
}

/** Upload this browser's review of [d]; [checkin] hands the session back so another reviewer can take it. */
export async function saveToServer(d: Dataset, checkin: boolean, f: typeof fetch = fetch) {
  conn.reviewBusy = d.id
  try {
    const { reviews, removals } = collect(d.id, state.reviews, state.removals)
    const files = new Map<string, Uint8Array>()
    for (const p of reviewFiles(reviews)) {
      const b = await photoBlob(d.id, p)
      if (b) files.set(p, new Uint8Array(await b.arrayBuffer()))
    }
    const savedAt = new Date().toISOString()
    const zip = build({ schema: REVIEW_SCHEMA, sessionId: d.id, reviewer: state.reviewer, savedAt, reviews, removals }, files)
    let info: ReviewInfo
    try {
      info = await putReviewPackage(conn.cfg, d.id, zip, progress(d, reviews, removals), checkin, f)
    } catch (e) {
      // the checkout ended meanwhile (released, expired, taken): show it at once, the changes stay here (ReviewBar)
      if (e instanceof CheckedOut && e.info.sessionId) setInfo(e.info)
      throw e
    }
    state.dirty.delete(d.id)
    await saveReviews()
    if (info.saved) await setSynced(d.id, info.saved.at)
    setInfo(info)
  } finally {
    conn.reviewBusy = ''
  }
}

/** Give up the checkout without uploading: this browser's unsaved changes are replaced by the server's review. */
export async function giveUp(d: Dataset, f: typeof fetch = fetch) {
  conn.reviewBusy = d.id
  try {
    await releaseCheckout(conn.cfg, d.id, f)
    const cur = conn.reviews[d.id]
    setInfo({ sessionId: d.id, lock: null, saved: cur?.saved ?? null })
    state.dirty.delete(d.id)
    await saveReviews()
    await loadSaved(d, true, f).catch(() => {})
  } finally {
    conn.reviewBusy = ''
  }
}

/** Throw away this browser's unsaved changes of [d] (after its checkout ended): the server's review comes back. */
export async function discardMine(d: Dataset, f: typeof fetch = fetch) {
  conn.reviewBusy = d.id
  try {
    state.dirty.delete(d.id)
    await saveReviews()
    if (conn.reviews[d.id]?.saved) await loadSaved(d, true, f)
  } finally {
    conn.reviewBusy = ''
  }
}

/** An admin makes an earlier version the review of [d]; this browser loads it at once. */
export async function restoreVersion(d: Dataset, version: string, f: typeof fetch = fetch) {
  conn.reviewBusy = d.id
  try {
    setInfo(await restoreReview(conn.cfg, d.id, version, f))
    await loadSaved(d, true, f)
  } finally {
    conn.reviewBusy = ''
  }
}

/** An admin frees another reviewer's checkout. */
export async function forceRelease(sessionId: string, f: typeof fetch = fetch) {
  await releaseCheckout(conn.cfg, sessionId, f)
  const cur = conn.reviews[sessionId]
  setInfo({ sessionId, lock: null, saved: cur?.saved ?? null })
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
