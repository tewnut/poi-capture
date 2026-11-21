import { describe, expect, it } from 'vitest'
import { blocksSwipe, neighbour, swipeStep, SWIPE_MIN_PX, type SwipeEl } from './swipe'

describe('swipeStep', () => {
  it('reads a left swipe as next and a right swipe as previous', () => {
    expect(swipeStep(-120, 10, 200)).toBe(1)
    expect(swipeStep(120, -10, 200)).toBe(-1)
  })
  it('ignores short moves', () => {
    expect(swipeStep(-(SWIPE_MIN_PX - 1), 0, 100)).toBeNull()
    expect(swipeStep(SWIPE_MIN_PX, 0, 100)).toBe(-1)
  })
  it('ignores mostly vertical moves (scrolling)', () => {
    expect(swipeStep(-100, 80, 200)).toBeNull()
    expect(swipeStep(-100, 300, 200)).toBeNull()
    expect(swipeStep(-150, 90, 200)).toBe(1)
  })
  it('ignores slow drags and bad numbers', () => {
    expect(swipeStep(-200, 0, 2000)).toBeNull()
    expect(swipeStep(NaN, 0, 100)).toBeNull()
  })
})

describe('neighbour', () => {
  const ids = ['a', 'b', 'c']
  it('steps without wrapping', () => {
    expect(neighbour(ids, 'b', 1)).toBe('c')
    expect(neighbour(ids, 'b', -1)).toBe('a')
    expect(neighbour(ids, 'c', 1)).toBeNull()
    expect(neighbour(ids, 'a', -1)).toBeNull()
  })
  it('is null for an item not in the list', () => {
    expect(neighbour(ids, 'z', 1)).toBeNull()
    expect(neighbour([0, 1, 2], 0, 1)).toBe(1)
  })
})

describe('blocksSwipe', () => {
  type Fake = SwipeEl & { tag: string; ox?: string }
  const el = (tag: string, parent: Fake | null, extra: Partial<Fake> = {}): Fake => ({
    tag, parentElement: parent, scrollWidth: 100, clientWidth: 100,
    matches: (sel: string) => sel.split(',').map((s) => s.trim()).includes(tag), ...extra,
  })
  const ox = (e: SwipeEl) => (e as Fake).ox || 'visible'
  const root = el('root', null)

  it('lets a swipe start on plain content', () => {
    const p = el('p', el('div', root))
    expect(blocksSwipe(p, root, ox)).toBe(false)
  })
  it('blocks fields, the crop editor and marked elements', () => {
    expect(blocksSwipe(el('input', root), root, ox)).toBe(true)
    expect(blocksSwipe(el('img', el('.crop', root)), root, ox)).toBe(true)
    expect(blocksSwipe(el('span', el('[data-noswipe]', root)), root, ox)).toBe(true)
  })
  it('blocks inside a strip that scrolls sideways, not one that fits', () => {
    const strip = el('div', root, { ox: 'auto', scrollWidth: 600, clientWidth: 300 })
    expect(blocksSwipe(el('img', strip), root, ox)).toBe(true)
    const fits = el('div', root, { ox: 'auto', scrollWidth: 300, clientWidth: 300 })
    expect(blocksSwipe(el('img', fits), root, ox)).toBe(false)
  })
  it('stops at the panel itself', () => {
    const panel = el('input', null)   // even if the root matched, it is not checked
    expect(blocksSwipe(panel, panel, ox)).toBe(false)
    expect(blocksSwipe(null, root, ox)).toBe(false)
  })
})
