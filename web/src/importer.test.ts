import { describe, expect, it } from 'vitest'
import { _test } from './importer'

const result = (o: Record<string, unknown> = {}, places: unknown[] = []) => ({
  schema: 'poi-result/1', session: { id: 's1', name: 'NTS', ...o }, track: [], shots: [], tasks: [], places,
})
const place = (o: Record<string, unknown>) => ({ id: 'p', name: 'x', status: 'open', fields: {}, photos: [], pins: {}, ...o })

describe('importer: sweep targets and layers', () => {
  it('keeps the target, the layers and the sign / address parts of result.json', () => {
    const d = _test.fromResult(result({ target: 'signs' }, [
      place({ id: 'a', layer: 'address', category: 'address', placeRef: 'Bún Bò', fields: { houseNumber: { value: '106' } } }),
      place({ id: 's', layer: 'sign', category: 'traffic_sign', sign: { code: 'P.131a', group: 'prohibitory', facingDeg: 92.4, side: 'right' },
        fields: { signCode: { value: 'P.131a' } } }),
    ]), new Map()).dataset
    expect(d.session.target).toBe('signs')
    expect(d.places.map((p) => p.layer)).toEqual(['address', 'sign'])
    expect(d.places[0].placeRef).toBe('Bún Bò')
    expect(d.places[1].sign).toEqual({ code: 'P.131a', group: 'prohibitory', facingDeg: 92.4, side: 'right' })
    expect(d.places[1].fields.signCode?.value).toBe('P.131a')
  })

  it('an older result: no target = shops, no layer = place (an unknown one too)', () => {
    const d = _test.fromResult(result({}, [place({ id: 'a' }), place({ id: 'b', layer: 'tree' })]), new Map()).dataset
    expect(d.session.target).toBe('shops')
    expect(d.places.map((p) => p.layer)).toEqual(['place', 'place'])
  })

  it('a raw phone export keeps its target', async () => {
    const m = { session: { id: 'r1', kind: 'sweep', target: 'addresses' }, items: [] }
    expect((await _test.fromRaw(m, new Map())).dataset.session.target).toBe('addresses')
    expect((await _test.fromRaw({ session: { id: 'r2' }, items: [] }, new Map())).dataset.session.target).toBe('shops')
  })
})

describe('importer: floor plans of an indoor walk', () => {
  const tm = { origin: [10.8, 106.74], scaleMPerPx: 0.05, rotationDeg: 12 }
  const r = {
    ...result({ kind: 'indoor' }), levels: ['2'],
    plans: [
      { level: '2', file: '_plans/plan_2_1.jpg', size: [2048, 1365], toMap: tm, placedBy: 'footprint' },
      { level: '3', file: '_plans/plan_3_1.jpg', size: [0, 10], toMap: tm },                 // no size: not drawable
      { level: '3', file: '_plans/plan_3_2.jpg', size: [10, 10], toMap: { origin: [1], scaleMPerPx: 1 } },
    ],
    planUnits: [{ level: '2', unit: 'L2-15', polygon: [[10.8, 106.74], [10.8001, 106.74], [10.8001, 106.7401]], place: null }],
  }
  const img = () => new Blob([new Uint8Array([1, 2, 3])])

  it('reads the plans and their units, and keeps the plan images as images', () => {
    const files = new Map([['_plans/plan_2_1.jpg', img()], ['_plans/plan_3_1.jpg', img()], ['_plans/evil.html', img()]])
    const { dataset, files: kept } = _test.fromResult(r, files)
    expect(dataset.plans!.map((p) => p.file)).toEqual(['_plans/plan_2_1.jpg'])
    expect(dataset.plans![0].toMap).toEqual(tm)
    expect(dataset.planUnits![0].unit).toBe('L2-15')
    expect([...kept.keys()]).toEqual(['_plans/plan_2_1.jpg'])
    expect(kept.get('_plans/plan_2_1.jpg')!.type).toBe('image/jpeg')
    expect(dataset.files).toContain('_plans/plan_2_1.jpg')
  })

  it('outdoors, or an older result: no plans', () => {
    expect(_test.fromResult({ ...r, session: { id: 's1' } }, new Map()).dataset.plans).toBeUndefined()
    const { plans: _p, planUnits: _u, ...older } = r
    const d = _test.fromResult(older, new Map()).dataset
    expect(d.plans).toEqual([])
    expect(d.planUnits).toEqual([])
  })
})
