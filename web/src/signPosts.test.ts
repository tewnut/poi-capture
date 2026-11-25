import { describe, expect, it } from 'vitest'
import { byOrder, markerGroups, postOf, postSigns, trafficSignValue } from './signPosts'
import { csv, geojson, osm } from './publish'
import { DBF_FIELDS } from './exports'
import type { Dataset, Place, Review } from './types'

const sign = (id: string, code: string, extra: Partial<NonNullable<Place['sign']>> = {}, at = { lat: 10.77, lon: 106.76 }): Place => ({
  id, name: code, category: 'traffic_sign', status: 'open', layer: 'sign',
  sign: { code, group: 'prohibitory', facingDeg: 270.5, side: 'left', ...extra },
  fields: { signCode: { value: code, sure: 'high' } }, photos: [], pins: { algorithm: { ...at } },
})
const top = sign('s-03', 'P.104', { post: 'p1', order: 1 })
const under = sign('s-04', 'P.127', { post: 'p1', order: 2 })
const alone = sign('s-05', 'P.130', {}, { lat: 10.771, lon: 106.761 })
const g1 = sign('s-56', 'R.412', { side: 'over', lane: 4, post: 'g1', order: 1 }, { lat: 10.7782, lon: 106.75803 })
const g2 = sign('s-57', 'R.412', { side: 'over', lane: 3, post: 'g1', order: 2 }, { lat: 10.77821, lon: 106.75805 })
const pin = (p: Place) => p.pins.algorithm!

describe('signs on one post', () => {
  it('finds the post and the signs on it, top first', () => {
    expect(postOf(top)).toBe('p1')
    expect(postOf(alone)).toBeNull()
    expect(postOf({ ...top, layer: 'place' })).toBeNull()
    expect(postSigns([alone, under, top], under).map((p) => p.id)).toEqual(['s-03', 's-04'])
    expect(postSigns([alone, under, top], alone)).toEqual([alone])
    expect(byOrder([sign('a', 'X', { post: 'p' }), sign('b', 'Y', { post: 'p', order: 1 })]).map((p) => p.id)).toEqual(['b', 'a'])
  })

  it('one marker for a pole, in order; gantry boards and moved signs apart', () => {
    const groups = markerGroups([under, alone, top, g1, g2], pin)
    expect(groups.map((g) => g.map((p) => p.id))).toEqual([['s-03', 's-04'], ['s-05'], ['s-56'], ['s-57']])
    // the reviewer moved the lower sign away: drawn on its own
    const moved = (p: Place) => (p.id === 's-04' ? { lat: 10.7701, lon: 106.76 } : pin(p))
    expect(markerGroups([top, under], moved).map((g) => g.length)).toEqual([1, 1])
  })

  it('a traffic_sign value: ";" between the signs, the prefix repeated', () => {
    expect(trafficSignValue(['P.104', ' P.127 ', ''])).toBe('VN:P.104;VN:P.127')
    expect(trafficSignValue(['P.130', 'P.130'])).toBe('VN:P.130')
  })
})

describe('publishing signs on one post', () => {
  const dataset = { id: 's', session: { id: 's', name: 'DVC', startedAt: '2026-11-24T09:44:00Z' }, places: [], tasks: [] } as unknown as Dataset
  const ok = (p: Place): Review => ({ decision: 'confirmed', fields: { signCode: { state: 'ok', value: p.fields.signCode!.value } } })
  const pub = (...ps: Place[]) => ps.map((place) => ({ dataset, place, review: ok(place) }))

  it('OSM: a pole is ONE node with its codes top first; a gantry board keeps its own node', () => {
    const x = osm(pub(under, top, alone, g1, g2))
    expect(x.match(/<node /g)!.length).toBe(4)
    expect(x).toContain('<tag k="traffic_sign" v="VN:P.104;VN:P.127"/>')
    expect(x).toContain('<tag k="traffic_sign" v="VN:P.130"/>')
    expect(x.match(/v="VN:R.412"/g)!.length).toBe(2)
    expect(x).toContain('<node id="-1" lat="10.77" lon="106.76"')
    expect(x).toContain('<tag k="direction" v="91"/>')
  })

  it('OSM: a sign of the pole without a confirmed code is left out of the value', () => {
    const noCode: Review = { decision: 'confirmed', fields: { signCode: { state: 'rejected', value: 'P.104' } } }
    const x = osm([{ dataset, place: top, review: noCode }, ...pub(under)])
    expect(x.match(/<node /g)!.length).toBe(1)
    expect(x).toContain('v="VN:P.127"/>')
    // only the lower sign published: a node of its own
    expect(osm(pub(under))).toContain('v="VN:P.127"/>')
  })

  it('GeoJSON / CSV / Shapefile carry the post and the order', () => {
    const props = JSON.parse(geojson(pub(top, alone), [])).features.map((f: any) => f.properties)
    expect([props[0].signPost, props[0].signOrder]).toEqual(['p1', 1])
    expect('signPost' in props[1]).toBe(false)
    const rows = csv(pub(top, alone), []).split('\r\n')
    const cols = rows[0].replace('﻿', '').split(',')
    expect(rows[1].split(',')[cols.indexOf('signPost')]).toBe('p1')
    expect(rows[1].split(',')[cols.indexOf('signOrder')]).toBe('1')
    expect(rows[2].split(',')[cols.indexOf('signPost')]).toBe('')
    expect(DBF_FIELDS.signPost.length).toBeLessThanOrEqual(10)
  })
})
