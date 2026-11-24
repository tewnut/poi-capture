// A traffic sign drawn from its QCVN 41:2019 code (an SVG string), for the map pins and the review panel.
// Simplified drawings: the shape and colours of the sign's group and a plain pictogram for the common codes, so a
// reviewer recognises the sign at a glance; the code itself is always shown next to it. Codes not known here get
// their group's shape with the code inside. All text that ends up in the SVG is escaped or reduced to digits.

const RED = '#d0021b'
const BLUE = '#1257b8'
const YELLOW = '#ffd21f'
const K = '#111'

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!)

// ---------- pictograms, drawn in a 100 x 100 box around (50, 50), in colour c ----------

const wheel = (x: number, y: number, r: number, c: string) => `<circle cx="${x}" cy="${y}" r="${r}" fill="none" stroke="${c}" stroke-width="5"/>`
const P = {
  motorbike: (c: string) => wheel(31, 62, 9, c) + wheel(69, 62, 9, c) +
    `<path d="M31 62 L44 50 L60 50 L69 62 M60 50 L56 38 L49 38 M44 50 L38 43 L28 43" fill="none" stroke="${c}" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/>`,
  car: (c: string) => `<path d="M22 60 L26 46 Q28 39 35 39 L65 39 Q72 39 74 46 L78 60 Z" fill="${c}"/><rect x="19" y="54" width="62" height="11" rx="3" fill="${c}"/>` +
    `<circle cx="32" cy="66" r="6.5" fill="${c}"/><circle cx="68" cy="66" r="6.5" fill="${c}"/>`,
  truck: (c: string) => `<rect x="17" y="34" width="44" height="27" fill="${c}"/><path d="M63 42 L75 42 L83 52 L83 61 L63 61 Z" fill="${c}"/>` +
    `<circle cx="30" cy="65" r="6.5" fill="${c}"/><circle cx="72" cy="65" r="6.5" fill="${c}"/>`,
  bus: (c: string) => `<rect x="16" y="33" width="68" height="30" rx="5" fill="${c}"/><rect x="21" y="38" width="58" height="9" fill="#fff" opacity=".9"/>` +
    `<circle cx="30" cy="66" r="6.5" fill="${c}"/><circle cx="70" cy="66" r="6.5" fill="${c}"/>`,
  tricycle: (c: string) => `<rect x="47" y="38" width="32" height="19" fill="${c}"/>` + wheel(27, 63, 8, c) + wheel(55, 65, 6, c) + wheel(73, 65, 6, c) +
    `<path d="M27 63 L38 50 L47 50 M38 50 L34 40 L27 40" fill="none" stroke="${c}" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/>`,
  walker: (c: string) => `<circle cx="52" cy="25" r="7" fill="${c}"/><path d="M50 36 L46 56 L38 76 M46 56 L56 76 M49 40 L38 50 M50 40 L62 48" fill="none" stroke="${c}" stroke-width="7" stroke-linecap="round" stroke-linejoin="round"/>`,
}

/** An arrow: a polyline shaft through pts (100-box), with a head at the last point. */
function arrow(pts: [number, number][], c: string, w = 9) {
  const [x1, y1] = pts[pts.length - 1], [x0, y0] = pts[pts.length - 2]
  const a = Math.atan2(y1 - y0, x1 - x0), h = w * 1.9
  const back = (s: number) => [x1 - Math.cos(a) * h * 1.1 + Math.cos(a + s * Math.PI / 2) * h, y1 - Math.sin(a) * h * 1.1 + Math.sin(a + s * Math.PI / 2) * h]
  const [lx, ly] = back(1), [rx, ry] = back(-1)
  const end = [x1 - Math.cos(a) * h * 0.9, y1 - Math.sin(a) * h * 0.9]
  const shaft = [...pts.slice(0, -1), end].map((p, i) => `${i ? 'L' : 'M'}${p[0].toFixed(1)} ${p[1].toFixed(1)}`).join(' ')
  return `<path d="${shaft}" fill="none" stroke="${c}" stroke-width="${w}" stroke-linejoin="round"/>` +
    `<path d="M${x1} ${y1} L${lx.toFixed(1)} ${ly.toFixed(1)} L${rx.toFixed(1)} ${ry.toFixed(1)} Z" fill="${c}"/>`
}
const A = {
  up: (c: string) => arrow([[50, 82], [50, 18]], c),
  right: (c: string) => arrow([[40, 82], [40, 45], [82, 45]], c),
  left: (c: string) => arrow([[60, 82], [60, 45], [18, 45]], c),
  upRight: (c: string) => arrow([[42, 84], [42, 16]], c) + arrow([[42, 58], [80, 58]], c, 8),
  upLeft: (c: string) => arrow([[58, 84], [58, 16]], c) + arrow([[58, 58], [20, 58]], c, 8),
  leftRight: (c: string) => arrow([[50, 84], [50, 46], [16, 46]], c, 8) + arrow([[50, 46], [84, 46]], c, 8),
  downRight: (c: string) => arrow([[28, 28], [72, 72]], c),
  downLeft: (c: string) => arrow([[72, 28], [28, 72]], c),
}
// a U-turn: up on the right, round the top, down on the left
const uturn = (c: string, x = 0) => `<path d="M${64 + x} 82 L${64 + x} 42 A14 14 0 0 0 ${36 + x} 42 L${36 + x} 56" fill="none" stroke="${c}" stroke-width="9"/>` +
  `<path d="M${36 + x} 74 L${24 + x} 54 L${48 + x} 54 Z" fill="${c}"/>`
const roundabout = (c: string) => [0, 120, 240].map((d) => `<g transform="rotate(${d} 50 50)">${arrow([[22, 46], [30, 26], [50, 20]], c, 8)}</g>`).join('')

// ---------- shapes ----------

const svg = (body: string, vb = '0 0 100 100') => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${vb}" role="img">${body}</svg>`
const SLASH = `<path d="M24 24 L76 76" stroke="${RED}" stroke-width="9" stroke-linecap="butt"/>`
const prohibit = (inner: string, slash = true) =>
  svg(`<circle cx="50" cy="50" r="47" fill="#fff"/><g transform="translate(50 50) scale(.84) translate(-50 -50)">${inner}</g>` +
    (slash ? SLASH : '') + `<circle cx="50" cy="50" r="42" fill="none" stroke="${RED}" stroke-width="10"/>`)
const mandatory = (inner: string) => svg(`<circle cx="50" cy="50" r="47" fill="${BLUE}" stroke="#fff" stroke-width="3"/><g transform="translate(50 50) scale(.78) translate(-50 -50)">${inner}</g>`)
const warning = (inner: string) => svg(`<path d="M50 6 L96 88 L4 88 Z" fill="${YELLOW}" stroke="${RED}" stroke-width="8" stroke-linejoin="round"/>` +
  `<g transform="translate(50 62) scale(.42) translate(-50 -50)">${inner}</g>`)
const infoSquare = (inner: string) => svg(`<rect x="4" y="4" width="92" height="92" rx="8" fill="${BLUE}" stroke="#fff" stroke-width="3"/><g transform="translate(50 50) scale(.8) translate(-50 -50)">${inner}</g>`)
const text = (s: string, size: number, c = K, y = 50) =>
  `<text x="50" y="${y}" text-anchor="middle" dominant-baseline="central" font-family="Be Vietnam Pro,Arial,sans-serif" font-weight="800" font-size="${size}" fill="${c}">${esc(s)}</text>`
const fit = (s: string) => (s.length <= 2 ? 46 : s.length === 3 ? 36 : s.length === 4 ? 27 : 20)

/** Lane board (R.415 / I.4xx): a blue panel, one column per lane with its arrow. */
function laneBoard(n: number) {
  const w = 100 / n
  let body = `<rect x="2" y="2" width="96" height="96" rx="6" fill="${BLUE}"/>`
  for (let i = 1; i < n; i++) body += `<path d="M${i * w} 10 V90" stroke="#fff" stroke-width="2" stroke-dasharray="8 6"/>`
  for (let i = 0; i < n; i++) body += `<g transform="translate(${(i + 0.5) * w} 52) scale(${0.55 / Math.max(1, n / 2)}) translate(-50 -50)">${A.up('#fff')}</g>`
  return svg(body)
}

const PICT: Record<string, (c: string) => string> = {
  '103': P.car, '104': P.motorbike, '105': (c) => `<g transform="translate(0 -18) scale(1 .7)">${P.car(c)}</g><g transform="translate(0 32) scale(1 .6)">${P.motorbike(c)}</g>`,
  '106': P.truck, '107': (c) => `<g transform="translate(0 -16) scale(1 .62)">${P.bus(c)}</g><g transform="translate(0 30) scale(1 .62)">${P.truck(c)}</g>`,
  '111': P.tricycle, '112': P.walker,
}

/** The sign for `code` (e.g. "P.127", "R.301d", "W.207b"); `value` is the number on it (speed, weight, height) if any. */
export function signSvg(code: string | undefined | null, value?: string | null): string {
  const c = (code || '').trim().toUpperCase().replace(/\s+/g, '')
  const m = /^(DP|[PRWIS])\.?(\d+)([A-Z]?)/.exec(c)
  const num = (value || '').replace(/[^\d.,]/g, '').slice(0, 4)
  if (!m) return svg(`<circle cx="50" cy="50" r="46" fill="#eef1ea" stroke="#9aa39a" stroke-width="5"/>${text(c ? c.slice(0, 5) : '?', 26, '#444')}`)
  const [, g, n, suf] = m
  const s = suf.toLowerCase()
  if (g === 'P') {
    if (n === '102') return svg(`<circle cx="50" cy="50" r="47" fill="${RED}"/><rect x="18" y="41" width="64" height="18" fill="#fff"/>`)
    if (n === '101') return prohibit('', false)
    if (n === '130' || n === '131') return svg(`<circle cx="50" cy="50" r="42" fill="${BLUE}" stroke="${RED}" stroke-width="10"/>` +
      `<path d="M24 24 L76 76${n === '130' ? ' M76 24 L24 76' : ''}" stroke="${RED}" stroke-width="9"/>`)
    if (n === '127' || n === '115' || n === '116' || n === '117' || n === '118') {
      const unit = n === '115' || n === '116' ? 't' : n === '117' || n === '118' ? 'm' : ''
      const label = num ? num + unit : n === '127' ? 'km/h' : `${n}`
      return prohibit(text(label, Math.min(62, fit(label) * 1.35)), false)
    }
    if (n === '123') return prohibit(s === 'b' ? A.right(K) : A.left(K))
    if (n === '124') {
      if (s === 'c' || s === 'd') return prohibit(`<g transform="translate(-8 0)">${A.left(K)}</g><g transform="translate(26 4) scale(.6)">${uturn(K)}</g>`)
      return prohibit(uturn(K))
    }
    if (n === '128') return prohibit(`<path d="M30 40 L44 40 L62 26 L62 74 L44 60 L30 60 Z" fill="${K}"/>`)
    if (PICT[n]) return prohibit(PICT[n](K))
    return prohibit(text(`${n}${s}`, 30), false)
  }
  if (g === 'DP') return svg(`<circle cx="50" cy="50" r="46" fill="#fff" stroke="${K}" stroke-width="3"/><path d="M22 70 L70 22 M30 78 L78 30" stroke="${K}" stroke-width="4"/>`)
  if (g === 'R') {
    if (n === '301') {
      const a = { a: A.up, b: A.right, c: A.left, d: A.right, e: A.left, f: A.upRight, g: A.upLeft, h: A.leftRight }[s] || A.up
      return mandatory(a('#fff'))
    }
    if (n === '302') return mandatory((s === 'b' ? A.downLeft : A.downRight)('#fff'))
    if (n === '303') return mandatory(roundabout('#fff'))
    if (n === '304' || n === '305') return mandatory(n === '304' ? P.motorbike('#fff') : P.walker('#fff'))
    if (n === '412') {
      const pic = { a: P.car, b: P.bus, c: P.car, d: P.truck, e: P.truck, f: P.motorbike, g: P.motorbike, h: P.bus }[s] || P.car
      return infoSquare(`<g transform="translate(0 -6)">${pic('#fff')}</g><path d="M14 84 H86" stroke="#fff" stroke-width="4"/>`)
    }
    if (n === '415' || n === '411') return laneBoard(3)
    if (n === '420' || n === '421') return infoSquare(text('KDC', 30, '#fff'))
    return mandatory(text(`${n}${s}`, 30, '#fff'))
  }
  if (g === 'W') {
    const draw: Record<string, string> = {
      '201': s === 'b' || s === 'd' ? `<path d="M40 92 L40 50 Q40 22 70 22" fill="none" stroke="${K}" stroke-width="12"/><path d="M68 6 L92 22 L68 38 Z" fill="${K}"/>`
        : `<path d="M60 92 L60 50 Q60 22 30 22" fill="none" stroke="${K}" stroke-width="12"/><path d="M32 6 L8 22 L32 38 Z" fill="${K}"/>`,
      '205': `<path d="M50 4 V96 M10 50 H90" stroke="${K}" stroke-width="14"/>`,
      '207': `<path d="M50 4 V96" stroke="${K}" stroke-width="16"/><path d="${'bdfhjl'.includes(s) ? 'M50 50 H92' : 'M50 50 H8'}" stroke="${K}" stroke-width="9"/>`,
      '208': `<path d="M50 4 V96" stroke="${K}" stroke-width="9"/><path d="M8 50 H92" stroke="${K}" stroke-width="16"/>`,
      '224': `<g transform="translate(50 46) scale(1.35) translate(-50 -50)">${P.walker(K)}</g>`, '225': `<g transform="translate(50 46) scale(1.35) translate(-50 -50)">${P.walker(K)}</g>`, '233': `<path d="M50 10 V64" stroke="${K}" stroke-width="16"/><circle cx="50" cy="86" r="9" fill="${K}"/>`,
    }
    return warning(draw[n] ?? text(`${n}`, 44))
  }
  if (g === 'I') {
    if (n === '414' || n === '415' || n === '416' || n === '419') return svg(`<rect x="2" y="16" width="96" height="68" rx="6" fill="${BLUE}"/>` +
      `<g transform="translate(50 50) scale(.62) translate(-50 -50)">${A.upRight('#fff')}</g>`)
    if (n === '423') return infoSquare(`<path d="M50 8 L92 84 L8 84 Z" fill="#fff"/><g transform="translate(50 58) scale(.66) translate(-50 -50)">${P.walker(K)}</g>`)
    if (n === '434') return infoSquare(P.bus('#fff'))
    return infoSquare(text('i', 64, '#fff'))
  }
  // S: an extra plate
  return svg(`<rect x="3" y="24" width="94" height="52" rx="4" fill="#fff" stroke="${K}" stroke-width="4"/>${text(num || `S.${n}${s}`, num ? 34 : 22)}`)
}
