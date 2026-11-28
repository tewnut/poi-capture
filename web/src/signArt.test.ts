import { describe, expect, it } from 'vitest'
import { signSvg } from './signArt'

describe('signSvg', () => {
  it('draws each group with its own shape and colour', () => {
    expect(signSvg('P.104')).toContain('#d0021b')          // red ring
    expect(signSvg('R.301d')).toContain('#1257b8')         // blue disc
    expect(signSvg('W.207b')).toContain('#ffd21f')         // yellow triangle
    expect(signSvg('I.414')).toContain('<rect')
  })
  it('puts the speed on a speed limit and keeps only digits', () => {
    expect(signSvg('P.127', '60')).toContain('>60<')
    expect(signSvg('P.127', '<b>70</b> km/h')).toContain('>70<')
    expect(signSvg('P.127', '<script>')).not.toContain('<script')
  })
  it('escapes unknown codes and copes with nothing', () => {
    expect(signSvg('<img src=x>')).not.toContain('<img')
    expect(signSvg(undefined)).toContain('<svg')
    expect(signSvg('P.999')).toContain('999')
  })
})
