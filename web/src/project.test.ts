import { describe, expect, it } from 'vitest'
import { buildProject, parseHours, projectName, vmapHours, PROJECT_SCHEMA } from './project'
import type { Dataset, Place, Review } from './types'

const shop: Place = {
  id: 's1-01', name: 'Bún Bò Huế Hương Cố Đô', category: 'restaurant', status: 'open', confidence: 'high',
  fields: {
    name: { value: 'Bún Bò Huế Hương Cố Đô', photo: 37 },
    phone: { value: '0886673868', photo: 37 },
    houseNumber: { value: '106', photo: 37, sure: 'high' },
    street: { value: 'Nguyễn Thanh Sơn', photo: 37 },
    openingHours: { value: 'Thứ Hai: 08:00–12:0013:00–17:00; Thứ Ba: 08:00–17:00', source: 'google' },
    website: { value: 'shop.vn', source: 'google' },
    signText: { value: 'BÚN BÒ', photo: 37, sure: 'low' },
  },
  photos: [{ file: '01_x/COVER.jpg', cover: true, index: 37 }, { file: '01_x/b.jpg', index: 38 },
    { file: '01_x/web.png', source: 'website', url: 'https://shop.vn/a.png' }],
  pins: {
    algorithm: { lat: 10.7769, lon: 106.7578 },
    agent: { lat: 10.77695, lon: 106.75775, why: 'shopfront' },
    google: { lat: 10.7771, lon: 106.7576, name: 'Google name', distanceM: 24, url: 'https://maps.google.com/?cid=1' },
  },
  google: { name: 'Google name', phone: '+84 999', address: 'Google address', openingHours: ['Thứ Hai: 08:00–12:00'] },
}
const gone: Place = { ...shop, id: 's1-02', name: 'Old shop', status: 'gone', fields: {}, photos: [{ file: '02_x/a.jpg', cover: true }], google: null,
  pins: { algorithm: { lat: 10.1, lon: 106.1 } } }
const junk: Place = { ...shop, id: 's1-03', name: 'Test place', fields: {}, google: null, pins: { algorithm: { lat: 10.2, lon: 106.2 } } }
const later: Place = { ...shop, id: 's1-04', name: 'Not reviewed', fields: {}, google: null, pins: { algorithm: { lat: 10.3, lon: 106.3 } } }
const dataset = { id: 's1', source: 'result', session: { id: 's1', name: 'NTS', startedAt: '2026-11-15T04:41:59Z', kind: 'sweep' },
  places: [shop, gone, junk, later], tasks: [
    { type: 'removal', place: null, name: 'Closed café', lat: 10.77, lon: 106.75, why: 'for rent', photo: 'removed/1.jpg' },
    { type: 'visit', place: 's1-01', name: 'Visit me', lat: 10.1, lon: 106.1, why: 'sign unreadable' },
    { type: 'removal', place: null, name: 'Maybe gone', lat: 10.6, lon: 106.6, why: 'shutter down' },
  ] } as unknown as Dataset

const reviews: Record<string, Review> = {
  's1-01': { decision: 'confirmed', reviewer: 'A', at: '2026-11-16T09:00:00Z', comment: 'nice',
    category: 'restaurant',
    fields: {
      name: { state: 'ok', value: 'Bún Bò Huế Hương Cố Đô' }, phone: { state: 'edited', value: '0886673869' },
      houseNumber: { state: 'rejected', value: '106' }, street: { state: 'ok', value: 'Nguyễn Thanh Sơn' },
      openingHours: { state: 'ok', value: 'Thứ Hai: 08:00–12:0013:00–17:00; Thứ Ba: 08:00–17:00; Thứ Tư: 08:00–17:00; Thứ Năm: 08:00–17:00; Thứ Sáu: 08:00–17:00; Thứ Bảy: 08:00–12:00; Chủ Nhật: Đóng cửa; 5: ; 4: ' },
      website: { state: 'ok', value: 'shop.vn' },
    },
    pin: { lat: 10.777, lon: 106.758, source: 'manual' },
    photos: { removed: ['01_x/b.jpg'], added: ['added/s1-01/k-0.png'], crops: { '01_x/COVER.jpg': { rect: [0, 0, 0.5, 0.5], file: 'crop/s1-01/c.jpg' } } },
  },
  's1-02': { decision: 'confirmed', fields: {} },
  's1-03': { decision: 'rejected', fields: {}, comment: 'test' },
}

const build = (missing?: Set<string>) => buildProject({ dataset, reviews, removals: { 0: { decision: 'confirmed', reviewer: 'A' }, 2: { decision: 'verify' } },
  reviewer: 'A', exportedAt: '2026-11-16T10:05:00.000Z', missing })

describe('project package', () => {
  const pj = build()
  const p = pj.project

  it('describes the session and counts', () => {
    expect(p.schema).toBe(PROJECT_SCHEMA)
    expect(p.session).toMatchObject({ id: 's1', name: 'NTS', kind: 'sweep' })
    expect(p.counts).toMatchObject({ places: 4, confirmed: 2, rejected: 1, todo: 1, visit: 0, photos: 4 })
    expect(p.counts.removals).toEqual({ total: 2, confirmed: 1, verify: 1, dismissed: 0, todo: 0 })
    expect(projectName('s1', '2026-11-16T10:05:00.000Z')).toBe('poi-project-s1-202611161005.zip')
  })

  it('lists every reviewed place with its decision and what the VMAP step does with it', () => {
    expect(p.places.map((x) => [x.id, x.decision, x.action])).toEqual([
      ['s1-01', 'confirmed', 'add_or_update'], ['s1-02', 'confirmed', 'mark_closed'], ['s1-03', 'rejected', 'skip']])
    expect(p.warnings[0]).toContain('1 place(s) not reviewed yet, left out: s1-04')
  })

  it('keeps each field with its reviewed value, state and provenance', () => {
    const f = p.places[0].fields
    expect(f.phone).toMatchObject({ value: '0886673869', state: 'edited', source: 'reviewer', edited: true, readValue: '0886673868', readFrom: 'photo' })
    expect(f.houseNumber).toMatchObject({ value: null, state: 'rejected', readValue: '106' })
    expect(f.name).toMatchObject({ source: 'photo', photo: 37, edited: false })
    expect(f.website).toMatchObject({ value: 'shop.vn', source: 'google' })
    expect(p.places[0].fromGoogle).toEqual(['openingHours', 'website'])
    expect(p.places[0].notChecked).toEqual(['signText'])      // unsure reading never confirmed: left out
    expect(p.places[0].address).toBe('Nguyễn Thanh Sơn')     // house number rejected
    expect(p.places[0].pin).toEqual({ lat: 10.777, lon: 106.758, source: 'manual' })
  })

  it('parses the reviewer\'s opening hours, keeping the text', () => {
    const oh = p.places[0].fields.openingHours!
    expect(oh.value).toContain('12:0013:00')
    expect(oh.hours!.days.mo).toEqual([['08:00', '12:00'], ['13:00', '17:00']])
    expect(oh.hours!.days.su).toEqual([])
    expect(oh.hours!.ignored).toEqual(['5:', '4:'])
    expect(oh.hours!.vmap).toBeNull()                         // a split shift: the VMAP tool cannot set it
  })

  it('photos: kept and cropped, cover first, with their source', () => {
    expect(p.places[0].photos).toEqual([
      { file: 'places/s1-01/1.jpg', role: 'cover', source: 'street', cropped: true, index: 37, original: '01_x/COVER.jpg' },
      { file: 'places/s1-01/2.png', role: 'photo', source: 'website', cropped: false, index: null, original: '01_x/web.png', url: 'https://shop.vn/a.png' },
      { file: 'places/s1-01/3.png', role: 'photo', source: 'reviewer', cropped: false, index: null, original: 'added/s1-01/k-0.png' },
    ])
    expect(pj.files).toContainEqual({ path: 'places/s1-01/1.jpg', from: 'crop/s1-01/c.jpg' })
    expect(pj.files).toContainEqual({ path: 'removals/1.jpg', from: 'removed/1.jpg' })
    expect(pj.files.some((f) => f.from === '01_x/b.jpg')).toBe(false)      // removed by the reviewer
    expect(p.places[2].photos).toEqual([])                                  // rejected: no photos
  })

  it('Google is only a duplicate-check hint, marked not for publishing', () => {
    expect(p.places[0].googleMatchHint).toEqual({ notForPublishing: true, use: 'duplicate check only', name: 'Google name', distanceM: 24, url: 'https://maps.google.com/?cid=1' })
    expect(pj.json).not.toContain('Google address')
    expect(pj.json).not.toContain('+84 999')
    expect(pj.json).not.toContain('10.7771')                 // Google's pin
  })

  it('lists removals with their decision', () => {
    expect(p.removals.map((r) => [r.name, r.decision, r.action, r.photo])).toEqual([
      ['Closed café', 'confirmed', 'mark_closed', 'removals/1.jpg'], ['Maybe gone', 'verify', 'skip', null]])
  })

  it('flags what to check: comments, test leftovers, added photos', () => {
    const w = p.warnings.join('\n')
    expect(w).toContain('s1-03 Test place: looks like a test leftover')
    expect(w).toContain('reviewer comment "test"')
    expect(w).toContain('added by the reviewer')
    expect(pj.readme).toContain('## Check before handing on')
    expect(pj.readme).toContain('| rejected | skip | s1-03 | Test place |')
  })

  it('tasks.jsonl has one line per confirmed place, as tools/tasks.py reads it', () => {
    const rows = pj.tasksJsonl.trim().split('\n').map((l) => JSON.parse(l))
    expect(rows).toHaveLength(2)
    expect(rows[0]).toEqual({ id: 's1-01', name: 'Bún Bò Huế Hương Cố Đô', address: 'Nguyễn Thanh Sơn', lat: 10.777, lon: 106.758,
      category: 'restaurant', status: 'open', action: 'add_or_update', photos: 'places/s1-01/' })
  })

  it('marks photos missing from the browser and leaves them out of the zip', () => {
    const m = build(new Set(['crop/s1-01/c.jpg']))
    expect(m.project.places[0].photos[0].missing).toBe(true)
    expect(m.files.some((f) => f.from === 'crop/s1-01/c.jpg')).toBe(false)
    expect(m.project.warnings.join('\n')).toContain('missing from this browser')
  })
})

describe('opening hours', () => {
  it('reads the common forms', () => {
    const every = (s: string) => parseHours(s)?.days.we
    expect(every('07:00–22:00')).toEqual([['07:00', '22:00']])
    expect(every('7h30 - 21h')).toEqual([['07:30', '21:00']])
    expect(every('07:30-11:30, 13:00-17:00')).toEqual([['07:30', '11:30'], ['13:00', '17:00']])
    expect(parseHours('07:00–22:00')!.vmap).toBe('07:00-22:00')
    expect(parseHours('Mở cửa cả ngày')!.vmap).toBe('24/7')
    expect(parseHours('Monday: 8:00 AM – 5:00 PM; Tuesday-Sunday: 8 AM – 5 PM')!.days.su).toEqual([['08:00', '17:00']])
  })
  it('day groups, and the VMAP spec when it fits', () => {
    const h = parseHours('T2-T6: 7:00-17:00; T7-CN: Đóng cửa')!
    expect(h.days.fr).toEqual([['07:00', '17:00']])
    expect(h.vmap).toBe('mon-fri=07:00-17:00;sat-sun=closed')
    const o = parseHours('Mo-Fr 08:00-17:00; Sa 08:00-12:00')!
    expect(o.days.su).toEqual([])                             // OSM style: days not named are closed
    expect(o.vmap).toBeNull()                                 // Saturday and Sunday differ
  })
  it('gives up rather than guess', () => {
    expect(parseHours('sáng 7 giờ')).toBeNull()
    expect(parseHours('Thứ Hai: 08:00–17:00')).toBeNull()     // the other days unknown
    expect(parseHours('8 - 17')).toBeNull()
    expect(parseHours('07:00-22:00; Thứ Bảy: 08:00-12:00')).toBeNull()
    expect(vmapHours({ mo: [], tu: [], we: [], th: [], fr: [], sa: [], su: [] })).toBeNull()
  })
})
