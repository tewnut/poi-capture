// Shared by the admin page's sections (AdminKeys.vue, AdminSessions.vue): times, last-seen lines, map colours and
// popups. Popups are Leaflet HTML: everything from the server is escaped.
import { t, lang, relTime } from './i18n'
import { seenPlace, shortClient, type CloudItem, type Seen } from './cloud'

/** A server time (ISO, UTC) in this browser's time zone: "2026-11-21 06:45"; a date-only value as it is. */
export function when(s: string | null): string {
  if (!s) return ''
  const d = new Date(s)
  if (s.length < 16 || Number.isNaN(d.getTime())) return s.slice(0, 16).replace('T', ' ')
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

/** The same moment as `when`, compact for a table cell: "8 Nov, 13:38" (the year only when not this year). */
export function short(s: string | null): string {
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

/** "Thu Duc, VN · Viettel Group · App 0.16.0 (samsung SM-A546E)" */
export const seenLine = (s: Seen) => [seenPlace(s), s.network, shortClient(s.client, s.userAgent)].filter(Boolean).join(' · ')
/** The full last-seen record, one field a line (a tooltip, or spelled out on a tap). */
export const seenTitle = (s: Seen) => [
  `${t('seenAt')}: ${when(s.at)}`,
  `${t('seenPlace')}: ${[s.city, s.region, s.country].filter(Boolean).join(', ') || '–'} (${t('seenApproxShort')})`,
  s.network && `${t('seenNetwork')}: ${s.network}`,
  s.client && `${t('seenClient')}: ${s.client}`,
  s.userAgent && `${t('seenBrowser')}: ${s.userAgent}`,
].filter(Boolean).join('\n')
export const twoTitle = (s: Seen) => t('seenTwoTitle', { client: s.client || s.userAgent || '?', at: when(s.at), prev: s.prevClient, prevAt: when(s.prevAt) })

/** Periods offered by the filters; null = any time. */
export type Period = '7' | '30' | 'all'
export const since = (p: Period) => (p === 'all' ? null : Date.now() - Number(p) * 86400e3)
export const within = (iso: string | null, from: number | null) => from == null || (iso != null && Date.parse(iso) >= from)

/** Distinct on the OSM tiles (no greens or pale tones that sink into parks and roads), then repeated. */
export const PALETTE = ['#d62728', '#1f5fd6', '#e67e00', '#8e24aa', '#00897b', '#c2185b', '#6d4c41', '#3949ab', '#7cb342', '#212121']
export const UNKNOWN = '#78909c'
/** One colour per id, in a fixed order (sorted ids) so a colour does not change with the filters; '' = unknown. */
export function colours(ids: Iterable<string>): Map<string, string> {
  const m = new Map<string, string>()
  let i = 0
  for (const id of [...new Set(ids)].sort()) m.set(id, id === '' ? UNKNOWN : PALETTE[i++ % PALETTE.length])
  return m
}

export const esc = (s: unknown) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!)
const longWhen = (s: string | null) => (s ? new Date(s).toLocaleString(lang.value, { dateStyle: 'medium', timeStyle: 'short' }) : '–')
const km = (m: number | null) => (m == null ? '–' : m < 1000 ? `${Math.round(m)} m` : `${(m / 1000).toFixed(1)} km`)
function duration(a: string | null, b: string | null) {
  const ms = a && b ? Date.parse(b) - Date.parse(a) : NaN
  if (!Number.isFinite(ms) || ms < 0) return '–'
  const min = Math.round(ms / 60000)
  return min < 60 ? `${min} min` : `${Math.floor(min / 60)} h ${String(min % 60).padStart(2, '0')}`
}
const line = (k: string, v: string) => `<div><span class="k">${esc(t(k))}</span> ${esc(v)}</div>`

/** A key's (or built-in's) last-seen place, for a marker popup. */
export function seenPopup(name: string, s: Seen): string {
  return `<div class="mpop"><b>${esc(name)}</b>`
    + line('seenAt', `${relTime(s.at)} (${longWhen(s.at)})`)
    + line('seenPlace', [s.city, s.region, s.country].filter(Boolean).join(', ') || '–')
    + (s.network ? line('seenNetwork', s.network) : '')
    + line('seenClient', s.client || s.userAgent || '–')
    + '</div>'
}
export const approxNote = () => `<div class="mpop muted">${esc(t('seenApproxShort'))}</div>`

/** A ride's popup: session, start, duration, distance, photos, uploader. */
export function tripPopup(u: CloudItem, by: string): string {
  const tp = u.trip!
  return `<div class="mpop"><b>${esc(u.name || u.sessionId || u.filename)}</b>`
    + (u.name && u.sessionId ? line('tripSession', u.sessionId) : '')
    + line('tripStarted', longWhen(tp.startedAt || u.createdAt))
    + (tp.endedAt ? line('tripDuration', duration(tp.startedAt, tp.endedAt)) : '')
    + line('tripDistance', km(tp.distanceM))
    + line('tripPhotos', tp.photos == null ? '–' : String(tp.photos))
    + line('tripBy', by)
    + '</div>'
}
