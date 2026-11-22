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
