// Project package (Publish → Project (.zip)): one reviewed session as a self-describing folder for the next stage,
// the agentic VMAP edit (poi-contribution). Schema poi-project/1, see docs/pipeline.md.
//
//   project.json             everything the reviewer decided: every reviewed place (with its decision, so the agent
//                            knows what to skip), each field with its reviewed value, state and provenance, the final
//                            pin, the kept photos; removal tasks with their decision; warnings
//   tasks.jsonl              one line per confirmed place, for poi-contribution/tools/tasks.py (id, name, address, …)
//   places/<place id>/<n>.*  the kept photos of the confirmed places (cropped where cropped), cover first
//   removals/<n>.*           the photo of a removal task, when it has one
//   README.md                the layout, and an overview table to check before handing the package on
//
// Pure: App.vue reads the photo files (photoBlob) and zips. The Google match is in only as a duplicate-check hint,
// marked not for publishing (Google data never goes to OpenStreetMap; publish.ts header).
import type { Dataset, Decision, FieldKey, Place, RemovalReview, Review, Task } from './types'
import { FIELD_KEYS } from './types'
import { keptPhotos, exportNames } from './photos'
import { record } from './publish'

export const PROJECT_SCHEMA = 'poi-project/1'

// ---------- opening hours ----------

export const DAYS = ['mo', 'tu', 'we', 'th', 'fr', 'sa', 'su'] as const
export type Day = (typeof DAYS)[number]
export type Slot = [string, string]   // ["08:00", "12:00"]; a whole day is ["00:00", "24:00"]
export type Hours = {
  days: Record<Day, Slot[]>           // every day listed; [] = closed
  vmap: string | null                 // the same as poi-contribution's --hours spec, when it fits its presets
  ignored?: string[]                  // empty rows left out (e.g. Google scraper junk "5: ")
}

const fold = (s: string) => s.normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/đ/g, 'd').replace(/Đ/g, 'D').toLowerCase()

// one day name -> index (folded, no accents)
const DAY_WORDS: [RegExp, number][] = [
  [/^(thu (hai|2)|t2|monday|mon|mo)$/, 0], [/^(thu (ba|3)|t3|tuesday|tue|tu)$/, 1], [/^(thu (tu|4)|t4|wednesday|wed|we)$/, 2],
  [/^(thu (nam|5)|t5|thursday|thu|th)$/, 3], [/^(thu (sau|6)|t6|friday|fri|fr)$/, 4], [/^(thu (bay|7)|t7|saturday|sat|sa)$/, 5],
  [/^(chu nhat|cn|sunday|sun|su)$/, 6],
]
function dayIndex(w: string): number | null {
  const f = fold(w).replace(/\./g, '').replace(/\s+/g, ' ').trim()
  for (const [re, i] of DAY_WORDS) if (re.test(f)) return i
  return null
}

/** "Thứ Hai - Thứ Sáu", "T2-T7", "Mo-Fr", "Mo,We", "Monday" -> day indexes; null when it is not a day list. */
function daySpec(s: string): number[] | null {
  const f = fold(s).trim()
  if (/^(hang ngay|moi ngay|ca tuan|daily|every ?day|mo-su)$/.test(f)) return [0, 1, 2, 3, 4, 5, 6]
  const out: number[] = []
  for (const part of s.split(/\s*,\s*|\s+(?:và|and)\s+/i)) {
    const r = part.split(/\s*-\s*/)
    if (r.length > 2) return null
    const a = dayIndex(r[0]), b = r.length === 2 ? dayIndex(r[1]) : a
    if (a == null || b == null) return null
    for (let i = a; ; i = (i + 1) % 7) { out.push(i); if (i === b) break }
  }
  return out.length ? [...new Set(out)] : null
}

const hhmm = (h: number, m: number) => `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`

/** The slots in one day's text ("08:00-12:0013:00-17:00", "7:30 AM - 5 PM", "Đóng cửa"); null if anything else is in it. */
function slots(text: string): Slot[] | null {
  const f = fold(text).trim()
  if (/^(dong cua|closed|nghi|off)$/.test(f)) return []
  if (/^(mo cua ca ngay|mo ca ngay|ca ngay|open 24 hours|24\/7|24 ?h|24 gio)$/.test(f)) return [['00:00', '24:00']]
  const t = String.raw`(\d{1,2})(?::(\d{2}))?\s*(am|pm|sa|ch)?`
  const re = new RegExp(`${t}\\s*-\\s*${t}`, 'g')
  const out: Slot[] = []
  const conv = (h: string, m: string | undefined, p: string | undefined) => {
    let x = +h
    if (p === 'pm' || p === 'ch') { if (x < 12) x += 12 } else if ((p === 'am' || p === 'sa') && x === 12) x = 0
    return [x, m ? +m : 0]
  }
  let rest = f
  for (const m of f.matchAll(re)) {
    const [h1, m1] = conv(m[1], m[2], m[3]), [h2, m2] = conv(m[4], m[5], m[6])
    if (!m[2] && !m[3] && !m[5] && !m[6]) return null           // "8 - 17": bare numbers, not clearly a time
    if (h1 > 24 || h2 > 24 || m1 > 59 || m2 > 59) return null
    out.push([hhmm(h1, m1), hhmm(h2 === 0 && m2 === 0 ? 24 : h2, m2)])
    rest = rest.replace(m[0], ' ')
  }
  if (!out.length || rest.replace(/[\s,;/&+]|\b(va|and)\b/g, '') !== '') return null
  return out
}

/** Opening hours text -> per-day slots, when it parses cleanly (every day of the week accounted for).
 *  Reads "07:00–22:00", "7h30 - 21h", "07:30-11:30, 13:00-17:00" (every day), Google's weekday lists
 *  ("Thứ Hai: 08:00–12:0013:00–17:00; …", junk rows "5: " ignored), "T2-T6: 7:00-17:00; T7-CN: Đóng cửa",
 *  OpenStreetMap style "Mo-Fr 08:00-17:00; Sa 08:00-12:00" (days not named are closed). */
export function parseHours(v: string | null | undefined): Hours | null {
  if (!v || !v.trim()) return null
  const norm = v.replace(/[–—−~]/g, '-').replace(/\s+(đến|den|to)\s+/gi, ' - ')
    .replace(/(\d{1,2})\s*(?:h|giờ|g)\s*(\d{2})?(?![\d:])/gi, (_m, h, m) => `${h}:${m || '00'}`)
  const days: (Slot[] | undefined)[] = Array(7).fill(undefined)
  const ignored: string[] = []
  let osmStyle = false
  const segs = norm.split(/\s*[;\n]\s*/).filter((s) => s.trim())
  for (const seg of segs) {
    // "<days>: <slots>" or "<days> <slots>" (OSM) or "<slots>" (every day)
    const all = slots(seg)
    if (all) {
      if (segs.length > 1) return null           // a dayless part among day lists: unclear
      for (let i = 0; i < 7; i++) days[i] = all
      continue
    }
    const m = seg.match(/^\s*([A-Z][a-z](?:\s*[-,]\s*[A-Z][a-z])*)\s+(\d.*)$/) || seg.match(/^\s*([^:\d]+?|[^:]{1,12}?)\s*:\s*(.*)$/)
    if (!m) return null
    if (!m[2].trim()) { ignored.push(seg.trim()); continue }    // "5: ", "Thứ Hai:" with nothing after
    const ds = daySpec(m[1]), sl = slots(m[2])
    if (!ds || !sl) return null
    if (/^[A-Z][a-z](\s*[-,]\s*[A-Z][a-z])*$/.test(m[1].trim())) osmStyle = true
    for (const i of ds) days[i] = sl
  }
  if (osmStyle) for (let i = 0; i < 7; i++) days[i] ??= []
  if (days.some((d) => d === undefined)) return null
  const out = Object.fromEntries(DAYS.map((d, i) => [d, days[i]!])) as Record<Day, Slot[]>
  return { days: out, vmap: vmapHours(out), ...(ignored.length ? { ignored } : {}) }
}

/** poi-contribution's --hours spec (tools/vmap_forms.py parse_hours: "24/7" | "HH:MM-HH:MM" |
 *  "mon-fri=…;sat-sun=…", one slot per group): null when the hours need more (split shifts, other day groups). */
export function vmapHours(d: Record<Day, Slot[]>): string | null {
  const one = (s: Slot[]) => (s.length === 0 ? 'closed' : s.length > 1 ? null : s[0][0] === '00:00' && s[0][1] === '24:00' ? '24/7' : `${s[0][0]}-${s[0][1]}`)
  const key = (s: Slot[]) => JSON.stringify(s)
  const wk = DAYS.slice(0, 5).map((x) => key(d[x])), we = [key(d.sa), key(d.su)]
  if (new Set([...wk, ...we]).size === 1) { const v = one(d.mo); return v === 'closed' ? null : v }
  if (new Set(wk).size === 1 && new Set(we).size === 1) {
    const a = one(d.mo), b = one(d.sa)
    return a && b ? `mon-fri=${a};sat-sun=${b}` : null
  }
  return null
}

// ---------- the package ----------

export type ProjectInput = {
  dataset: Dataset
  reviews: Record<string, Review>              // place id -> review (places never opened: decision todo)
  removals: Record<number, RemovalReview>      // task index -> decision
  reviewer: string                             // who exported
  exportedAt: string                           // ISO time
  missing?: Set<string>                        // stored photo paths that could not be read (marked, and warned)
}

type Src = 'photo' | 'google' | 'website' | 'ride'
export type ProjectField = {
  value: string | null                         // the reviewed value; null when the reviewer rejected it
  state: 'ok' | 'edited' | 'rejected'
  source: Src | 'reviewer'                     // where the value comes from now (reviewer = typed by the reviewer)
  edited: boolean
  readValue?: string                           // what the processing read, when the reviewer changed or rejected it
  readFrom?: Src                               // where that came from, when the reviewer typed over it
  photo?: number | string                      // photo the value was read on (sweep index or photo id)
  clarified?: boolean                          // an unclear photo reading made clear by Google / the website
  hours?: Hours | null                         // openingHours only: parsed (null: did not parse cleanly, use the text)
}
export type ProjectPhoto = {
  file: string                                 // path in the zip
  role: 'cover' | 'photo'
  source: 'street' | 'website' | 'reviewer'    // street photo from the capture, the place's website, added by the reviewer
  cropped: boolean
  index: number | null                         // the capture's photo index (street photos)
  original: string                             // path in the result bundle / browser store
  url?: string                                 // website photos: where it was taken from
  missing?: true                               // could not be read from the browser store: not in the zip
}
export type Action = 'add_or_update' | 'mark_closed' | 'skip'

export type ProjectPlace = {
  id: string; decision: Exclude<Decision, 'todo'>; action: Action; name: string; category: string | null
  status: Place['status']; address: string; pin: { lat: number; lon: number; source: string }
  fields: Partial<Record<FieldKey, ProjectField>>; notChecked?: FieldKey[]; fromGoogle: string[]
  comment: string | null; reviewer: string | null; reviewedAt: string | null; photos: ProjectPhoto[]; agentNote: string | null
  googleMatchHint?: { notForPublishing: true; use: string; name: string; distanceM: number | null; url: string | null }
}
export type ProjectRemoval = {
  id: string; type: Task['type']; name: string; lat: number; lon: number; why: string; place: string | null
  decision: RemovalReview['decision']; action: Action; reviewer: string | null; reviewedAt: string | null; photo: string | null
}
export type Project = {
  schema: typeof PROJECT_SCHEMA; exportedAt: string; exportedBy: string | null
  session: { id: string; name: string | null; startedAt: string | null; kind: string | null; street: string | null }
  counts: { places: number; confirmed: number; visit: number; rejected: number; todo: number; photos: number
    removals: { total: number; confirmed: number; verify: number; dismissed: number; todo: number } }
  rules: string[]; warnings: string[]; places: ProjectPlace[]; removals: ProjectRemoval[]
}

const STATUS_ACTION: Record<Place['status'], Action> = {
  open: 'add_or_update', unknown: 'add_or_update', closed: 'mark_closed', gone: 'mark_closed', for_rent: 'mark_closed',
  under_construction: 'skip',
}
const TESTY = /\b(test|testing|asdf|qwerty|lorem|xxx+|dummy)\b|\bthử\b/i

/** Where a removal task's photo goes in the zip. */
const removalPhoto = (i: number, path: string) => `removals/${i + 1}.${(path.match(/\.(\w+)$/)?.[1] || 'jpg').toLowerCase()}`

export function buildProject(x: ProjectInput): { project: Project; files: { path: string; from: string }[]; json: string; tasksJsonl: string; readme: string } {
  const { dataset: d, reviews, removals, missing = new Set<string>() } = x
  const warnings: string[] = []
  const files: { path: string; from: string }[] = []     // zip path <- stored photo path
  const counts = { places: d.places.length, confirmed: 0, visit: 0, rejected: 0, todo: 0, photos: 0,
    removals: { total: 0, confirmed: 0, verify: 0, dismissed: 0, todo: 0 } }

  const places: ProjectPlace[] = []
  const todo: string[] = []
  for (const p of d.places) {
    const r = reviews[p.id] || { decision: 'todo' as Decision, fields: {} }
    counts[r.decision]++
    if (r.decision === 'todo') { todo.push(p.id); continue }
    const rec = record({ dataset: d, place: p, review: r })
    const fields: Partial<Record<FieldKey, ProjectField>> = {}
    const notChecked: FieldKey[] = []
    for (const k of FIELD_KEYS) {
      const f = p.fields[k], rv = r.fields[k]
      if (!rv) { if (f) notChecked.push(k); continue }
      const from: Src = f?.source || 'photo'
      const edited = rv.state === 'edited'
      const e: ProjectField = {
        value: rv.state === 'rejected' ? null : rv.value || null, state: rv.state,
        source: edited || !f ? 'reviewer' : from, edited,
      }
      if (f && rv.state !== 'ok') e.readValue = f.value
      if (f && edited) e.readFrom = from
      if (f?.photo != null && from === 'photo') e.photo = f.photo
      if (f?.clarified && !edited) e.clarified = true
      if (k === 'openingHours' && e.value) e.hours = parseHours(e.value)
      fields[k] = e
      if (TESTY.test(e.value || '')) warnings.push(`${p.id}: ${k} "${e.value}" looks like a test leftover`)
    }
    const confirmed = r.decision === 'confirmed'
    const photos: ProjectPhoto[] = []
    if (confirmed) {
      const names = exportNames(p, r)
      keptPhotos(p, r).forEach((s, k) => {
        const file = `places/${names[k]}`
        const meta = p.photos.find((q) => q.file === s.file)
        const ph: ProjectPhoto = { file, role: s.cover ? 'cover' : 'photo', source: s.added ? 'reviewer' : s.source === 'website' ? 'website' : 'street',
          cropped: !!s.cropped, index: s.index ?? null, original: s.file, ...(meta?.url ? { url: meta.url } : {}) }
        if (missing.has(s.show)) { ph.missing = true; warnings.push(`${p.id}: photo ${s.show} is missing from this browser; not in the zip`) }
        else files.push({ path: file, from: s.show })
        photos.push(ph)
      })
      counts.photos += photos.filter((q) => !q.missing).length
      if (!photos.length) warnings.push(`${p.id} ${rec.name}: confirmed without any photo`)
      const added = photos.filter((q) => q.source === 'reviewer').length
      if (added) warnings.push(`${p.id} ${rec.name}: ${added} photo(s) added by the reviewer, check they belong to this place`)
      if (notChecked.length) warnings.push(`${p.id} ${rec.name}: ${notChecked.join(', ')} not checked by the reviewer (left out)`)
      if (rec.fromGoogle) warnings.push(`${p.id} ${rec.name}: ${rec.fromGoogle.split(' ').join(', ')} still Google's value (keep out of OpenStreetMap)`)
      const oh = fields.openingHours
      if (oh?.value && !oh.hours) warnings.push(`${p.id} ${rec.name}: opening hours "${oh.value}" did not parse; enter them by hand or skip`)
    }
    if (r.comment) warnings.push(`${p.id} ${rec.name}: reviewer comment "${r.comment}"`)
    if (TESTY.test(`${rec.name} ${r.comment || ''}`)) warnings.push(`${p.id} ${rec.name}: looks like a test leftover`)

    const gp = p.pins.google, g = p.google
    const gname = gp?.name || g?.name
    places.push({
      id: p.id,
      decision: r.decision,
      action: confirmed ? STATUS_ACTION[p.status] : 'skip',
      name: rec.name,
      category: rec.category,
      status: p.status,
      address: address(rec.houseNumber, rec.street),
      pin: { lat: rec.lat, lon: rec.lon, source: rec.pinSource },
      fields,
      ...(notChecked.length ? { notChecked } : {}),
      fromGoogle: rec.fromGoogle ? rec.fromGoogle.split(' ') : [],
      comment: r.comment || null,
      reviewer: r.reviewer || null,
      reviewedAt: r.at || null,
      photos,
      agentNote: p.note || null,
      ...(gname ? { googleMatchHint: {
        notForPublishing: true, use: 'duplicate check only',
        name: gname, distanceM: gp?.distanceM ?? g?.distanceM ?? null, url: gp?.url || g?.url || null,
      } } : {}),
    })
  }
  if (todo.length) warnings.unshift(`${todo.length} place(s) not reviewed yet, left out: ${todo.join(', ')}`)

  const removalsOut: ProjectRemoval[] = []
  d.tasks.forEach((t: Task, i) => {
    if (t.type === 'visit') return
    const rv = removals[i]
    const decision = rv?.decision || 'todo'
    counts.removals.total++
    counts.removals[decision]++
    let photo: string | null = null
    if (t.photo) {
      if (missing.has(t.photo)) warnings.push(`removal ${i + 1} ${t.name}: photo ${t.photo} is missing from this browser`)
      else { photo = removalPhoto(i, t.photo); files.push({ path: photo, from: t.photo }) }
    }
    removalsOut.push({
      id: `${d.session.id}-removal-${i}`, type: t.type, name: t.name, lat: t.lat, lon: t.lon, why: t.why, place: t.place,
      decision, action: decision === 'confirmed' ? 'mark_closed' : 'skip',
      reviewer: rv?.reviewer || null, reviewedAt: rv?.at || null, photo,
    })
  })
  if (counts.removals.todo) warnings.push(`${counts.removals.todo} removal task(s) not reviewed yet`)

  const project: Project = {
    schema: PROJECT_SCHEMA,
    exportedAt: x.exportedAt,
    exportedBy: x.reviewer || null,
    session: { id: d.session.id, name: d.session.name || null, startedAt: d.session.startedAt || null, kind: d.session.kind || null,
      street: d.session.street || null },
    counts,
    rules: [
      'Act on places with action "add_or_update" (fill missing fields or create) and "mark_closed"; skip the rest.',
      'Use field values as given; a field with value null was rejected by the reviewer: do not fill it from elsewhere.',
      'googleMatchHint and fields listed in fromGoogle come from Google Maps: never publish them to OpenStreetMap.',
      'Photos: places/<id>/1.* is the cover; source tells where each photo came from.',
    ],
    warnings: [...new Set(warnings)],
    places,
    removals: removalsOut,
  }
  const tasks = places.filter((p) => p.decision === 'confirmed').map((p) => JSON.stringify({
    id: p.id, name: p.name, address: p.address, lat: p.pin.lat, lon: p.pin.lon, category: p.category,
    status: p.status, action: p.action, photos: `places/${p.id}/`,
  }))
  return {
    project,
    files,
    json: JSON.stringify(project, null, 1) + '\n',
    tasksJsonl: tasks.length ? tasks.join('\n') + '\n' : '',
    readme: readme(project),
  }
}

function address(hn: string | null, street: string | null) {
  return [hn, street].filter(Boolean).join(' ').trim()
}

const cellMd = (s: unknown) => String(s ?? '').replace(/\|/g, '\\|').replace(/\s+/g, ' ')

function readme(p: Project) {
  const c = p.counts
  const L = [
    `# POI project: ${p.session.name || p.session.id}`,
    '',
    `Session \`${p.session.id}\`${p.session.startedAt ? `, captured ${p.session.startedAt}` : ''}. Exported ${p.exportedAt}` +
      `${p.exportedBy ? ` by ${p.exportedBy}` : ''} from Quét Phố Reviewer (schema \`${p.schema}\`).`,
    '',
    `Places: ${c.places} (confirmed ${c.confirmed}, visit ${c.visit}, rejected ${c.rejected}, not reviewed ${c.todo}); ` +
      `photos ${c.photos}; removal tasks ${c.removals.total} (confirmed ${c.removals.confirmed}, on site ${c.removals.verify}, ` +
      `dismissed ${c.removals.dismissed}, not reviewed ${c.removals.todo}).`,
    '',
    '## Layout',
    '',
    '- `project.json`: everything the reviewer decided. Each reviewed place has its `decision` (confirmed / visit /',
    '  rejected) and an `action` for the VMAP step: `add_or_update` (confirmed, open or unknown status),',
    '  `mark_closed` (confirmed as closed, gone or for rent), `skip` (visit, rejected, under construction).',
    '  Fields carry the reviewed `value` (null = rejected: do not fill it from elsewhere), `state` (ok / edited /',
    '  rejected), `source` (photo / google / website / ride = the road the photos were taken on, accepted by the',
    '  reviewer / reviewer) and, for opening hours, `hours`: per-day slots and',
    '  `vmap`, the `--hours` spec for poi-contribution\'s tools when it fits (null: enter by hand).',
    '  `googleMatchHint` is Google Maps\' nearest match, for duplicate checking only: never publish it.',
    '  `removals`: removal tasks with their decision (`confirmed` = the place is gone).',
    '- `tasks.jsonl`: one line per confirmed place (`id, name, address, lat, lon, category, status, action, photos`),',
    '  for `poi-contribution/tools/tasks.py`.',
    '- `places/<place id>/<n>.<ext>`: the kept photos of the confirmed places, cover first (`1.*`), cropped where the',
    '  reviewer cropped them. Places not confirmed have no photos here.',
    '- `removals/<n>.<ext>`: photos of removal tasks.',
    '',
    '## Check before handing on',
    '',
    ...(p.warnings.length ? p.warnings.map((w) => `- ${w}`) : ['- Nothing to flag.']),
    '',
    '## Places',
    '',
    '| decision | action | id | name | address | fields edited / rejected | photos | comment |',
    '|---|---|---|---|---|---|---|---|',
    ...p.places.map((x) => {
      const ed = Object.entries(x.fields).filter(([, f]) => f!.state !== 'ok').map(([k, f]) => `${k} ${f!.state}`).join(', ')
      const ph = x.photos.length ? `${x.photos.length}${x.photos.some((q) => q.source === 'reviewer') ? ` (${x.photos.filter((q) => q.source === 'reviewer').length} added)` : ''}` +
        `${x.photos.some((q) => q.cropped) ? `, ${x.photos.filter((q) => q.cropped).length} cropped` : ''}` : ''
      return `| ${x.decision} | ${x.action} | ${x.id} | ${cellMd(x.name)} | ${cellMd(x.address)} | ${ed} | ${ph} | ${cellMd(x.comment)} |`
    }),
    '',
  ]
  if (p.removals.length) {
    L.push('## Removal tasks', '', '| decision | action | name | why |', '|---|---|---|---|',
      ...p.removals.map((r) => `| ${r.decision} | ${r.action} | ${cellMd(r.name)} | ${cellMd(r.why)} |`), '')
  }
  return L.join('\n')
}

/** poi-project-<session id>-<yyyymmddhhmm>.zip */
export function projectName(sessionId: string, at: string) {
  return `poi-project-${sessionId}-${at.slice(0, 16).replace(/[-:T]/g, '')}.zip`
}
