import type { VillagePhase } from './village'

/*
 * 首页雾隐村场景。整幅画在低分辨率像素缓冲里逐像素合成，再按整数倍放大显示，
 * 因此所有渐变、雾与光晕都用 4x4 有序抖动表达，画面里不出现半透明混色。
 */

const BAYER = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5]
const bayer = (x: number, y: number) => (BAYER[(y & 3) * 4 + (x & 3)] + 0.5) / 16
const clamp = (v: number, a = 0, b = 1) => Math.min(b, Math.max(a, v))
const lerp = (a: number, b: number, t: number) => a + (b - a) * t
const smooth = (t: number) => t * t * (3 - 2 * t)

function hash(n: number) {
  const x = Math.sin(n * 127.1 + 311.7) * 43758.5453
  return x - Math.floor(x)
}
const hash2 = (x: number, y: number) => hash(x * 157.31 + y * 113.97)

function noise1(x: number, seed: number) {
  const i = Math.floor(x)
  return lerp(hash(i + seed * 1013), hash(i + 1 + seed * 1013), smooth(x - i))
}

function noise2(x: number, y: number, seed: number) {
  const xi = Math.floor(x), yi = Math.floor(y)
  const u = smooth(x - xi), v = smooth(y - yi)
  const s = seed * 917
  const a = hash2(xi + s, yi), b = hash2(xi + 1 + s, yi)
  const c = hash2(xi + s, yi + 1), d = hash2(xi + 1 + s, yi + 1)
  return lerp(lerp(a, b, u), lerp(c, d, u), v)
}

function rng(seed: number) {
  let a = seed >>> 0
  return () => {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

function packColor(hex: string) {
  const n = parseInt(hex.slice(1), 16)
  return ((0xff << 24) | ((n & 0xff) << 16) | (n & 0xff00) | ((n >> 16) & 0xff)) >>> 0
}

const DAY = {
  sky0: '#6aa3c4', sky1: '#83b5cd', sky2: '#a2c8d4', sky3: '#c3dbd6', sky4: '#e2e5cd',
  orb: '#fff5d4', orbRim: '#f6dc96', halo: '#f0ecd0', star: '#ffffff', starDim: '#ffffff',
  cloud: '#fbf8ee', cloudShade: '#dbe5e3', cloudDeep: '#bcd0d6',
  mtn1: '#9dbdc4', mtn1Lit: '#b6d1d3', snow: '#eef3ee', mtn2: '#7ea4a6', mtn2Lit: '#95b8b3', haze: '#d2e1dc',
  pineFar: '#5f8b7d', pineFarLit: '#739c87', pine: '#3e6c5b', pineLit: '#588968', pineDark: '#2e5647',
  rock: '#8f938a', rockLit: '#aeb1a3', rockDark: '#6c7068',
  grass: '#8cb369', grassLit: '#a3c479', grassDark: '#759f59', grassDeep: '#5d894b',
  dirt: '#d8c596', dirtLit: '#e8d8ac', dirtDark: '#bea779',
  stone: '#a9a693', stoneLit: '#c6c2ab', stoneDark: '#86836f',
  wallF: '#d6bf91', wallS: '#ecdaad', wallDark: '#b59c6f', timber: '#6e4a33', timberDark: '#4f3423',
  roofR: '#c05b3b', roofRLit: '#d8774f', roofRDark: '#8c3c29',
  roofB: '#6c8593', roofBLit: '#87a0ab', roofBDark: '#4d6371',
  roofT: '#b08f54', roofTLit: '#c9a868', roofTDark: '#7f6439',
  glass: '#5a7c8b', glassLit: '#a7c6cc', winLit: '#ffd889', winCore: '#fff3c8',
  glowSoft: '#d6bf91', glowGround: '#8cb369',
  door: '#7a4a2c', doorDark: '#58351f', brass: '#d0a546', brassDark: '#9a7430',
  leaf: '#5e9952', leafLit: '#80b462', leafDark: '#3e7040', trunk: '#6a4a34',
  fg: '#2d5440', fgLit: '#3f6b4c', fgDark: '#1f3d30',
  fog: '#eef1e4', fogThin: '#dce7de', smoke: '#eef0ea', smokeShade: '#cfd8d4',
  fire: '#ffc452', fireCore: '#fff2b0', fireRed: '#e0642f', ember: '#b2452a',
  flowerA: '#f6f2e2', flowerB: '#f2c94c', flowerC: '#e58d86',
  bird: '#3c4c55', firefly: '#e9f7a0', fireflyDim: '#8cb369', wolf: '#2e2b35', wolfEye: '#ffd23a',
  ink: '#2a2230', bubble: '#fffaf0',
}
type PaletteDef = typeof DAY

const NIGHT: PaletteDef = {
  sky0: '#0a0f1f', sky1: '#0f1830', sky2: '#152341', sky3: '#1d2f52', sky4: '#2a3e63',
  orb: '#f4efd9', orbRim: '#c9c5ae', halo: '#24375b', star: '#eef2ff', starDim: '#7d88b2',
  cloud: '#2a3a5d', cloudShade: '#22314f', cloudDeep: '#1b2844',
  mtn1: '#1b2949', mtn1Lit: '#26385c', snow: '#3b4d74', mtn2: '#142038', mtn2Lit: '#1c2c48', haze: '#283a5e',
  pineFar: '#101b31', pineFarLit: '#17253e', pine: '#0c1527', pineLit: '#152239', pineDark: '#080e1b',
  rock: '#1c263d', rockLit: '#2b3956', rockDark: '#121a2c',
  grass: '#22374a', grassLit: '#2c4658', grassDark: '#1b2e3e', grassDeep: '#142433',
  dirt: '#434d67', dirtLit: '#525c77', dirtDark: '#353d55',
  stone: '#414a63', stoneLit: '#535c78', stoneDark: '#323950',
  wallF: '#3b425c', wallS: '#525a77', wallDark: '#2d3349', timber: '#201e2b', timberDark: '#16151e',
  roofR: '#4c2e45', roofRLit: '#643f5a', roofRDark: '#361e32',
  roofB: '#283653', roofBLit: '#384a6b', roofBDark: '#1d273f',
  roofT: '#443d4b', roofTLit: '#585063', roofTDark: '#312b38',
  glass: '#192136', glassLit: '#26314c', winLit: '#ffc35a', winCore: '#fff0b4',
  glowSoft: '#a27c58', glowGround: '#5d5a5a',
  door: '#2a2030', doorDark: '#1b1521', brass: '#7c6a4a', brassDark: '#4f4434',
  leaf: '#15273a', leafLit: '#1f344a', leafDark: '#0e1b2a', trunk: '#1d1925',
  fg: '#070c16', fgLit: '#0e1828', fgDark: '#04070e',
  fog: '#3c5079', fogThin: '#2d3f67', smoke: '#3c4c6d', smokeShade: '#2d3b5b',
  fire: '#ffb547', fireCore: '#ffe9a0', fireRed: '#d6582c', ember: '#a43c25',
  flowerA: '#6c7aa0', flowerB: '#5c6a8c', flowerC: '#5e5d84',
  bird: '#000000', firefly: '#e9f7a0', fireflyDim: '#4e6a4e', wolf: '#05070d', wolfEye: '#ffd23a',
  ink: '#0a0c14', bubble: '#f4efd9',
}

type Palette = { [K in keyof PaletteDef]: number }
function compile(def: PaletteDef): Palette {
  const out = {} as Palette
  for (const key of Object.keys(def) as (keyof PaletteDef)[]) out[key] = packColor(def[key])
  return out
}

class Pix {
  readonly data: Uint32Array
  constructor(readonly w: number, readonly h: number) { this.data = new Uint32Array(w * h) }
  set(x: number, y: number, c: number) {
    x = Math.floor(x); y = Math.floor(y)
    if (x < 0 || y < 0 || x >= this.w || y >= this.h) return
    this.data[y * this.w + x] = c
  }
  get(x: number, y: number) {
    x = Math.floor(x); y = Math.floor(y)
    if (x < 0 || y < 0 || x >= this.w || y >= this.h) return 0
    return this.data[y * this.w + x]
  }
  rect(x: number, y: number, w: number, h: number, c: number) {
    const x0 = Math.max(0, Math.floor(x)), y0 = Math.max(0, Math.floor(y))
    const x1 = Math.min(this.w, Math.floor(x + w)), y1 = Math.min(this.h, Math.floor(y + h))
    for (let yy = y0; yy < y1; yy++) this.data.fill(c, yy * this.w + x0, yy * this.w + Math.max(x0, x1))
  }
  dither(x: number, y: number, w: number, h: number, c: number, t: number | ((x: number, y: number) => number)) {
    const x0 = Math.max(0, Math.floor(x)), y0 = Math.max(0, Math.floor(y))
    const x1 = Math.min(this.w, Math.floor(x + w)), y1 = Math.min(this.h, Math.floor(y + h))
    for (let yy = y0; yy < y1; yy++) for (let xx = x0; xx < x1; xx++) {
      const v = typeof t === 'number' ? t : t(xx, yy)
      if (bayer(xx, yy) < v) this.data[yy * this.w + xx] = c
    }
  }
  ellipse(cx: number, cy: number, rx: number, ry: number, paint: (x: number, y: number, d: number) => number | null) {
    for (let y = Math.floor(cy - ry); y <= Math.ceil(cy + ry); y++) for (let x = Math.floor(cx - rx); x <= Math.ceil(cx + rx); x++) {
      const d = ((x + 0.5 - cx) / rx) ** 2 + ((y + 0.5 - cy) / ry) ** 2
      if (d > 1) continue
      const c = paint(x, y, d)
      if (c !== null) this.set(x, y, c)
    }
  }
  poly(pts: [number, number][], paint: (x: number, y: number) => number | null) {
    let minY = Infinity, maxY = -Infinity
    for (const [, y] of pts) { minY = Math.min(minY, y); maxY = Math.max(maxY, y) }
    for (let y = Math.floor(minY); y <= Math.ceil(maxY); y++) {
      const sy = y + 0.5, xs: number[] = []
      for (let i = 0; i < pts.length; i++) {
        const [ax, ay] = pts[i], [bx, by] = pts[(i + 1) % pts.length]
        if ((ay <= sy && by > sy) || (by <= sy && ay > sy)) xs.push(ax + ((sy - ay) / (by - ay)) * (bx - ax))
      }
      xs.sort((a, b) => a - b)
      for (let i = 0; i + 1 < xs.length; i += 2) for (let x = Math.ceil(xs[i] - 0.5); x <= Math.floor(xs[i + 1] - 0.5); x++) {
        const c = paint(x, y)
        if (c !== null) this.set(x, y, c)
      }
    }
  }
  line(x0: number, y0: number, x1: number, y1: number, c: number, w = 1) {
    const steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0), 1)
    for (let i = 0; i <= steps; i++) {
      const x = Math.round(lerp(x0, x1, i / steps)), y = Math.round(lerp(y0, y1, i / steps))
      this.rect(x - Math.floor((w - 1) / 2), y - Math.floor((w - 1) / 2), w, w, c)
    }
  }
  remap(x: number, y: number, w: number, h: number, map: Map<number, number>, t: number | ((x: number, y: number) => number)) {
    const x0 = Math.max(0, Math.floor(x)), y0 = Math.max(0, Math.floor(y))
    const x1 = Math.min(this.w, Math.floor(x + w)), y1 = Math.min(this.h, Math.floor(y + h))
    for (let yy = y0; yy < y1; yy++) for (let xx = x0; xx < x1; xx++) {
      const v = typeof t === 'number' ? t : t(xx, yy)
      if (v <= 0 || bayer(xx, yy) >= v) continue
      const i = yy * this.w + xx, next = map.get(this.data[i])
      if (next !== undefined) this.data[i] = next
    }
  }
  blit(src: Pix, dx = 0, dy = 0, alpha = 1) {
    dx = Math.floor(dx); dy = Math.floor(dy)
    if (dx === 0 && dy === 0 && src.w === this.w && src.h === this.h && alpha >= 1) {
      const s = src.data, d = this.data
      for (let i = 0; i < s.length; i++) if (s[i]) d[i] = s[i]
      return
    }
    for (let y = 0; y < src.h; y++) {
      const ty = y + dy
      if (ty < 0 || ty >= this.h) continue
      for (let x = 0; x < src.w; x++) {
        const c = src.data[y * src.w + x], tx = x + dx
        if (!c || tx < 0 || tx >= this.w) continue
        if (alpha < 1 && bayer(tx, ty) >= alpha) continue
        this.data[ty * this.w + tx] = c
      }
    }
  }
}

/** 字符画转精灵：`.` 透明，其余字符查 colors，并自动描一圈 1px 外轮廓。 */
function sprite(rows: string[], colors: Record<string, number>, outline: number, mirror = false): Pix {
  const h = rows.length, w = rows[0].length
  const out = new Pix(w + 2, h + 2)
  const at = (x: number, y: number) => {
    if (x < 0 || y < 0 || x >= w || y >= h) return '.'
    return rows[y][mirror ? w - 1 - x : x]
  }
  for (let y = -1; y <= h; y++) for (let x = -1; x <= w; x++) {
    const ch = at(x, y)
    if (ch !== '.') { out.set(x + 1, y + 1, colors[ch] ?? outline); continue }
    if (at(x - 1, y) !== '.' || at(x + 1, y) !== '.' || at(x, y - 1) !== '.' || at(x, y + 1) !== '.') out.set(x + 1, y + 1, outline)
  }
  return out
}

const VILLAGER = [
  '.HHH.',
  'HHHHH',
  'HSSSH',
  '.ESE.',
  '.SSS.',
  'CCCCC',
  'SCCCS',
  '.PPP.',
]
const LEGS_STILL = ['.P.P.', '.B.B.']
const LEGS_STEP = ['P...P', 'B...B']
const NIGHT_WOLF = [
  'K...K',
  'KK.KK',
  'KKKKK',
  'KYKYK',
  '.KKK.',
  'KKKKK',
  'KKKKK',
  '.KKK.',
  '.K.K.',
  '.K.K.',
]
const CRAG_WOLF = [
  '.......K.',
  '......KKK',
  '.....KKKKK',
  '....KKKK..',
  '...KKKK...',
  '..KKKKK...',
  '.KKKKKK...',
  'KKKKKKK...',
  'K.KK.KK...',
].map((row) => row.padEnd(10, '.'))
const CRAG_WOLF_HOWL = [
  '........K.',
  '.......KK.',
  '......KKK.',
  '.....KKK..',
  '....KKKK..',
  '..KKKKK...',
  '.KKKKKK...',
  'KKKKKKK...',
  'K.KK.KK...',
]

const LOOKS = [
  { hair: '#5a3a26', shirt: '#b8573a' },
  { hair: '#2f2a2a', shirt: '#4f7aa0' },
  { hair: '#a8683a', shirt: '#d9a441' },
  { hair: '#3d2a1e', shirt: '#6f9a5a' },
  { hair: '#c7b49a', shirt: '#8a5f8f' },
  { hair: '#6b4a32', shirt: '#4e8a86' },
]

interface HouseSpec { x: number; base: number; fw: number; sw: number; wh: number; roof: 'R' | 'B' | 'T'; chimney?: boolean }
interface Win { x: number; y: number; w: number; h: number; side?: { x0: number; base: number; sw: number; d: number } }
interface HouseGeom extends HouseSpec {
  d: number; top: number; gh: number
  apex: [number, number]; eaveR: [number, number]; eaveL: [number, number]; depth: [number, number]
  door: { x: number; y: number; w: number; h: number }
  windows: Win[]
  chimneyTop?: [number, number]
}

function houseGeom(s: HouseSpec): HouseGeom {
  const d = Math.round(s.sw * 0.45)
  const top = s.base - 2 - s.wh
  const gh = Math.round(s.fw / 2) + 1
  const o = 2
  const apex: [number, number] = [s.x + s.fw / 2, top - gh]
  const eaveR: [number, number] = [s.x + s.fw + o, top + o - 1]
  const eaveL: [number, number] = [s.x - o, top + o - 1]
  const dw = s.fw >= 24 ? 5 : 4
  const door = { x: s.x + Math.floor(s.fw / 2) - Math.floor(dw / 2), y: s.base - 2 - 8, w: dw, h: 8 }
  const wy = top + 3
  const windows: Win[] = [
    { x: s.x + 2, y: wy, w: 3, h: 4 },
    { x: s.x + s.fw - 5, y: wy, w: 3, h: 4 },
  ]
  if (s.sw >= 12) windows.push({ x: 3, y: 3, w: 3, h: 4, side: { x0: s.x + s.fw, base: s.base, sw: s.sw, d } })
  if (s.sw >= 16) windows.push({ x: s.sw - 7, y: 3, w: 3, h: 4, side: { x0: s.x + s.fw, base: s.base, sw: s.sw, d } })
  const g: HouseGeom = { ...s, d, top, gh, apex, eaveR, eaveL, depth: [s.sw, -d], door, windows }
  if (s.chimney) {
    const px = lerp(apex[0], eaveR[0], 0.35) + s.sw * 0.62, py = lerp(apex[1], eaveR[1], 0.35) - d * 0.62
    g.chimneyTop = [Math.round(px), Math.round(py) - 6]
  }
  return g
}

export interface VillageLayout {
  W: number; H: number; g: number; cx: number
  orb: [number, number]
  square: { x: number; y: number; rx: number; ry: number }
  houses: HouseGeom[]
  tower: { x: number; base: number }
  crag: { x: number; top: number }
  slots: [number, number][]
  storyHouse: number
  wolfPath: [number, number][]
  lanterns: [number, number][]
}

export function createLayout(W: number, H: number): VillageLayout {
  const g = Math.round(H * 0.6)
  const cx = W < 300 ? Math.round(W * 0.5) : Math.round(W * 0.6)
  const square = { x: cx, y: g + Math.round((H - g) * 0.47), rx: 34, ry: 9 }
  const houses = [
    { x: cx - 118, base: g + 14, fw: 22, sw: 14, wh: 13, roof: 'R', chimney: true },
    { x: cx - 68, base: g + 8, fw: 18, sw: 12, wh: 11, roof: 'B', chimney: true },
    { x: cx + 26, base: g + 9, fw: 20, sw: 14, wh: 12, roof: 'T' },
    { x: cx + 74, base: g + 15, fw: 24, sw: 16, wh: 14, roof: 'R', chimney: true },
    { x: cx - 172, base: g + 47, fw: 28, sw: 18, wh: 16, roof: 'B', chimney: true },
    { x: cx + 104, base: g + 52, fw: 26, sw: 16, wh: 15, roof: 'T', chimney: true },
  ].map((spec) => houseGeom(spec as HouseSpec))
  const slots: [number, number][] = []
  for (let i = 0; i < 6; i++) {
    const a = Math.PI * (0.15 + i / 3)
    slots.push([Math.round(square.x + Math.cos(a) * 22), Math.round(square.y + Math.sin(a) * 6.5)])
  }
  const story = houses[1]
  const gapX = Math.round((houses[0].x + houses[0].fw + houses[0].sw + story.x) / 2)
  const doorX = story.door.x + Math.floor(story.door.w / 2)
  let cragX = Math.round(W * 0.9), best = Infinity
  for (let x = Math.round(W * 0.84); x < Math.round(W * 0.96); x++) {
    const y = ridge2(x, H, g)
    if (y < best) { best = y; cragX = x }
  }
  return {
    W, H, g, cx,
    orb: [Math.round(W < 300 ? W * 0.78 : W * 0.78), Math.round(H * 0.17)],
    square,
    houses,
    tower: { x: cx - 8, base: g + 5 },
    crag: { x: cragX, top: Math.min(best - 2, g - 40) },
    slots,
    storyHouse: 1,
    wolfPath: [[gapX, g + 1], [gapX, g + 18], [doorX, g + 18], [doorX, story.base]],
    lanterns: [[square.x - square.rx - 5, square.y + 2], [square.x + square.rx + 4, square.y - 1]],
  }
}

function ridge1(x: number, H: number, g: number) {
  const r = 1 - Math.abs(2 * noise1(x * 0.018, 3) - 1)
  return Math.round(g - H * 0.2 - H * 0.15 * r * r - 5 * noise1(x * 0.07, 4))
}
function ridge2(x: number, H: number, g: number) {
  return Math.round(g - H * 0.09 - H * 0.08 * noise1(x * 0.026, 5) - 3 * noise1(x * 0.09, 6))
}

interface Layers { sky: Pix; far: Pix; near: Pix; front: Pix; clouds: Pix[]; fog: Uint8Array[] }

function shadowMap(p: Palette) {
  return new Map<number, number>([
    [p.grass, p.grassDark], [p.grassLit, p.grass], [p.grassDark, p.grassDeep],
    [p.dirt, p.dirtDark], [p.dirtLit, p.dirt], [p.stone, p.stoneDark], [p.stoneLit, p.stone],
  ])
}

function drawSky(px: Pix, L: VillageLayout, p: Palette, phase: VillagePhase) {
  const bands = [p.sky0, p.sky1, p.sky2, p.sky3, p.sky4]
  for (let y = 0; y < L.g; y++) {
    const pos = (y / L.g) * (bands.length - 1)
    const i = Math.min(bands.length - 2, Math.floor(pos)), f = clamp((pos - i - 0.55) / 0.45)
    for (let x = 0; x < L.W; x++) px.data[y * L.W + x] = bayer(x, y) < f ? bands[i + 1] : bands[i]
  }
  const [ox, oy] = L.orb
  if (phase === 'night') {
    const r = rng(41)
    const count = Math.round(L.W * L.g * 0.0045)
    for (let i = 0; i < count; i++) {
      const x = Math.floor(r() * L.W), y = Math.floor(r() ** 1.7 * L.g * 0.8)
      px.set(x, y, r() < 0.3 ? p.star : p.starDim)
    }
  }
  const r0 = phase === 'day' ? 9 : 10, r1 = phase === 'day' ? 17 : 24
  px.ellipse(ox, oy, r1, r1, (x, y) => {
    const d = Math.hypot(x + 0.5 - ox, y + 0.5 - oy)
    if (d < r0 + 2.5) return p.halo
    return bayer(x, y) < (1 - (d - r0 - 2.5) / (r1 - r0 - 2.5)) * 0.5 ? p.halo : null
  })
  px.ellipse(ox, oy, r0, r0, (x, y) => {
    const dx = x + 0.5 - ox, dy = y + 0.5 - oy
    if (phase === 'day') return dx + dy > r0 * 0.9 ? p.orbRim : p.orb
    const crater = Math.hypot(dx + 3, dy + 2) < 2.3 || Math.hypot(dx - 3, dy - 3) < 1.6 || Math.hypot(dx - 2.5, dy + 4) < 1.2
    return crater || dx - dy < -r0 * 1.05 ? p.orbRim : p.orb
  })
}

function drawPine(px: Pix, x: number, base: number, h: number, c: number, lit: number, dark: number, seed: number) {
  const tiers = Math.max(3, Math.round(h / 7))
  px.rect(x, base - 2, 1, 3, dark)
  for (let y = base - h; y < base - 1; y++) {
    const p = (y - (base - h)) / (h - 1)
    const tp = (p * tiers) % 1
    const hw = Math.round((0.35 + 0.65 * tp) * (0.8 + p * h * 0.27)) + (hash(seed + y) < 0.25 ? 1 : 0)
    for (let xx = x - hw; xx <= x + hw; xx++) {
      let col = xx > x ? lit : c
      if (xx === x - hw || (tp > 0.82 && xx < x)) col = dark
      px.set(xx, y, col)
    }
  }
}

function drawFar(px: Pix, L: VillageLayout, p: Palette, phase: VillagePhase) {
  const { W, H, g } = L
  for (let x = 0; x < W; x++) {
    const y0 = ridge1(x, H, g), slope = ridge1(x + 1, H, g) - ridge1(x - 1, H, g)
    const peak = y0 < g - H * 0.31
    for (let y = y0; y < g; y++) {
      let c = p.mtn1
      if (slope > 0 && y - y0 < 3 + slope * 2) c = p.mtn1Lit
      if (peak && y - y0 < 3 && bayer(x, y) < (phase === 'day' ? 0.9 : 0.6)) c = p.snow
      const hz = clamp((y - (g - H * 0.2)) / (H * 0.2)) * 0.7
      if (bayer(x, y) < hz) c = p.haze
      px.set(x, y, c)
    }
  }
  for (let x = 0; x < W; x++) {
    const y0 = ridge2(x, H, g), slope = ridge2(x + 1, H, g) - ridge2(x - 1, H, g)
    for (let y = y0; y < g; y++) {
      let c = slope > 0 && y - y0 < 2 + slope * 2 ? p.mtn2Lit : p.mtn2
      if (bayer(x, y) < clamp((y - (g - H * 0.07)) / (H * 0.1)) * 0.45) c = p.haze
      px.set(x, y, c)
    }
  }
  const crag = L.crag, ct = crag.top
  px.poly([[crag.x - 16, g - 8], [crag.x - 10, ct + 9], [crag.x - 6, ct + 4], [crag.x - 2, ct], [crag.x + 3, ct], [crag.x + 6, ct + 5], [crag.x + 9, ct + 7], [crag.x + 15, g - 8]], (x, y) => {
    const strata = (y - ct) % 5 === 4 && hash2(Math.floor(x / 3), y) > 0.35
    const face = x - crag.x > (y - ct) * 0.35 - 1
    let c = strata ? p.rockDark : face ? p.rockLit : p.rock
    if (y - ct < 1) c = p.rockLit
    if (bayer(x, y) < clamp((y - ct - 8) / 30) * 0.5) c = p.haze
    return c
  })
  for (let y = Math.round(g - H * 0.13); y < g - H * 0.04; y += 2) {
    for (let x = 0; x < W; x++) if (noise2(x * 0.025, y * 0.3, 19) > 0.62) px.set(x, y, p.haze)
  }
  let r = rng(7)
  for (let x = -4; x < W + 6; x += 3 + Math.floor(r() * 4)) {
    drawPine(px, x, g - 2 + Math.floor(r() * 3), 10 + Math.floor(noise1(x * 0.08, 9) * 10 + r() * 4), p.pineFar, p.pineFarLit, p.pineFar, x)
  }
  r = rng(11)
  for (let x = -6; x < W + 8; x += 6 + Math.floor(r() * 5)) {
    const h = 15 + Math.floor(noise1(x * 0.05, 12) * 14 + r() * 6)
    drawPine(px, x, g + 1 + Math.floor(r() * 3), h, p.pine, p.pineLit, p.pineDark, x * 3)
  }
}

function drawGround(px: Pix, L: VillageLayout, p: Palette, phase: VillagePhase) {
  const { W, H, g } = L
  for (let y = g - 1; y < H; y++) for (let x = 0; x < W; x++) {
    const n = noise2(x * 0.07, y * 0.16, 7)
    let c = p.grass
    if (n > 0.58 && bayer(x, y) < (n - 0.58) * 4) c = p.grassLit
    else if (n < 0.36 && bayer(x, y) < (0.36 - n) * 4) c = p.grassDark
    if (y < g + 2 && bayer(x, y) < 0.3) c = p.grassLit
    if (y > H - 26 && bayer(x, y) < (y - (H - 26)) / 50) c = p.grassDark
    px.data[y * W + x] = c
  }
  const r = rng(23)
  const tufts = Math.round((W * (H - g)) / 55)
  for (let i = 0; i < tufts; i++) {
    const x = Math.floor(r() * W), y = g + 3 + Math.floor(r() * (H - g - 3))
    px.set(x, y, p.grassDeep); px.set(x, y - 1, p.grassDark)
    if (r() < 0.5) { px.set(x + 1, y, p.grassDark); px.set(x + 2, y - 1, p.grassDark) }
    if (r() < 0.3) px.set(x - 1, y - 2, p.grassLit)
  }
  const flowers = Math.round((W * (H - g)) / 380)
  const fc = [p.flowerA, p.flowerB, p.flowerC]
  for (let i = 0; i < flowers; i++) {
    const x = Math.floor(r() * W), y = g + 6 + Math.floor(r() * (H - g - 6))
    px.set(x, y, fc[i % 3]); px.set(x, y + 1, p.grassDeep)
  }
  const sq = L.square
  for (let y = sq.y; y < H + 2; y++) {
    const k = y - sq.y
    const mid = sq.x - 8 - k * 0.32 + Math.sin(y * 0.09) * 3
    const hw = 4 + k * 0.17
    for (let x = Math.floor(mid - hw - 2); x <= Math.ceil(mid + hw + 2); x++) {
      const e = Math.abs(x + 0.5 - mid) - hw - (noise1(y * 0.6 + x * 0.1, 31) - 0.5) * 2.4
      if (e > 1) continue
      let c = e > 0 ? p.dirtDark : p.dirt
      if (e <= 0 && hash2(x, y) < 0.06) c = p.dirtLit
      if (e <= 0 && hash2(x + 3, y) < 0.03) c = p.stoneDark
      px.set(x, y, c)
    }
  }
  for (const h of L.houses) {
    const dx = h.door.x + Math.floor(h.door.w / 2)
    const tx = lerp(sq.x, dx, 0.55), ty = h.base < sq.y ? sq.y - sq.ry + 2 : sq.y + 3
    const steps = Math.max(Math.abs(dx - tx), Math.abs(h.base - ty))
    for (let i = 0; i <= steps; i++) {
      const x = Math.round(lerp(dx, tx, i / steps)), y = Math.round(lerp(h.base, ty, i / steps))
      px.rect(x - 1, y, 3, 1, p.dirt)
      px.set(x - 2, y, p.dirtDark); px.set(x + 2, y, p.dirtDark)
    }
  }
  px.ellipse(sq.x, sq.y, sq.rx + 4, sq.ry + 2.5, (x, y, d) => (d > 0.86 ? (bayer(x, y) < 0.5 ? p.dirtDark : null) : p.dirt))
  px.ellipse(sq.x, sq.y, sq.rx, sq.ry, (x, y, d) => {
    if (d > 0.82) return p.stoneDark
    const row = Math.floor(y / 3), off = row % 2 ? 2 : 0
    if (y % 3 === 0 || (x + off) % 4 === 0) return p.stoneDark
    return hash2(Math.floor((x + off) / 4), row) > 0.62 ? p.stoneLit : p.stone
  })
  px.ellipse(sq.x, sq.y, 5, 2.4, (x, y, d) => (d > 0.45 ? (hash2(x, y) < 0.5 ? p.stoneLit : p.stone) : p.stoneDark))
  px.line(sq.x - 3, sq.y + 1, sq.x + 2, sq.y - 1, p.timber)
  px.line(sq.x - 2, sq.y - 1, sq.x + 3, sq.y + 1, p.timberDark)
  if (phase === 'night') {
    for (const h of L.houses) {
      for (const w of h.windows) if (!w.side) px.ellipse(w.x + 1, h.base + 3, 5, 2, (x, y) => (bayer(x, y) < 0.35 ? p.glowGround : null))
    }
  }
}

function drawGarden(px: Pix, x: number, y: number, p: Palette) {
  px.rect(x, y, 20, 8, p.dirtDark)
  for (let r = 0; r < 4; r++) {
    px.rect(x + 1, y + 1 + r * 2, 18, 1, p.dirt)
    for (let c = 0; c < 6; c++) {
      px.set(x + 2 + c * 3, y + r * 2, p.leaf)
      px.set(x + 3 + c * 3, y + r * 2, p.leafLit)
    }
  }
  for (let i = 0; i <= 20; i += 3) {
    px.rect(x - 1 + i, y + 6, 1, 4, p.timber)
    px.set(x - 1 + i, y + 6, p.wallS)
  }
  px.rect(x - 1, y + 7, 22, 1, p.timberDark)
}

function drawTree(px: Pix, x: number, base: number, r: number, p: Palette) {
  px.rect(x - 1, base - r - 2, 2, r + 2, p.trunk)
  px.rect(x - 1, base - r - 2, 1, r + 2, p.timberDark)
  const blobs: [number, number, number][] = [[0, -r * 1.6, r], [-r * 0.7, -r * 1.2, r * 0.8], [r * 0.7, -r * 1.25, r * 0.8], [0, -r * 2.2, r * 0.7]]
  for (const [bx, by, br] of blobs) {
    px.ellipse(x + bx, base + by, br, br * 0.9, (xx, yy) => {
      const lx = xx + 0.5 - x, ly = yy + 0.5 - (base - r * 1.7)
      const light = (lx - ly) / (r * 2.2)
      if (light > 0.45 && bayer(xx, yy) < (light - 0.45) * 3) return p.leafLit
      if (light < -0.35 && bayer(xx, yy) < (-light - 0.35) * 3) return p.leafDark
      return hash2(xx, yy) < 0.06 ? p.leafDark : p.leaf
    })
  }
  px.ellipse(x - 3, base + 1, r + 2, 1.6, (xx, yy) => (bayer(xx, yy) < 0.6 ? p.grassDeep : null))
}

function drawTower(px: Pix, L: VillageLayout, p: Palette) {
  const { x, base } = L.tower
  const top = base - 26
  px.rect(x + 3, top + 2, 1, 26, p.timberDark)
  px.rect(x + 12, top + 2, 1, 26, p.timberDark)
  px.rect(x, top, 1, 29, p.timber)
  px.rect(x + 9, top, 1, 29, p.timber)
  for (let k = 0; k < 2; k++) {
    const y0 = top + 4 + k * 11
    px.line(x, y0, x + 9, y0 + 10, p.timberDark)
    px.line(x + 9, y0, x, y0 + 10, p.timber)
  }
  px.rect(x - 2, top, 17, 2, p.timber)
  px.rect(x - 2, top + 2, 17, 1, p.timberDark)
  px.rect(x - 1, top - 9, 1, 9, p.timber)
  px.rect(x + 10, top - 9, 1, 9, p.timber)
  px.ellipse(x + 5, top - 4, 3, 4, (xx, yy) => (yy > top - 2 ? p.brassDark : xx > x + 5 ? p.brass : p.brassDark))
  px.rect(x + 4, top - 1, 2, 1, p.brass)
  px.poly([[x - 4, top - 9], [x + 5, top - 16], [x + 15, top - 9]], (_xx, yy) => (yy === top - 9 ? p.roofRDark : p.roofR))
  px.line(x + 5, top - 16, x + 15, top - 9, p.roofRLit)
  px.ellipse(x + 4, base + 1, 10, 2, (xx, yy) => (bayer(xx, yy) < 0.55 ? p.grassDeep : null))
}

function roofColors(p: Palette, roof: HouseSpec['roof']) {
  if (roof === 'R') return [p.roofR, p.roofRLit, p.roofRDark]
  if (roof === 'B') return [p.roofB, p.roofBLit, p.roofBDark]
  return [p.roofT, p.roofTLit, p.roofTDark]
}

function sideOff(i: number, sw: number, d: number) { return Math.round((i * d) / sw) }

function paintWindow(px: Pix, h: HouseGeom, w: Win, p: Palette, lit: boolean, frameOnly = false) {
  for (let c = -1; c <= w.w; c++) for (let r = -1; r <= w.h; r++) {
    let x: number, y: number
    if (w.side) {
      const i = w.x + c
      x = w.side.x0 + i
      y = w.side.base - 2 - sideOff(i, w.side.sw, w.side.d) - h.wh + w.y + r
    } else { x = w.x + c; y = w.y + r }
    const edge = c === -1 || c === w.w || r === -1 || r === w.h
    if (edge) { if (!frameOnly) px.set(x, y, p.timber); continue }
    let col = lit ? p.winLit : p.glass
    if (r === 1) col = p.timberDark
    else if (lit && c === 1 && r === 2) col = p.winCore
    else if (!lit && c === 0 && r === 0) col = p.glassLit
    px.set(x, y, col)
  }
}

function drawHouse(px: Pix, h: HouseGeom, p: Palette, phase: VillagePhase) {
  const [rM, rL, rD] = roofColors(p, h.roof)
  const { x, base, fw, sw, wh, d, top } = h
  px.ellipse(x + (fw + sw) / 2 - 7, base + 1, (fw + sw) / 2 + 4, 3, (xx, yy) => (bayer(xx, yy) < 0.7 ? p.grassDeep : null))
  for (let i = 0; i < sw; i++) {
    const X = x + fw + i, off = sideOff(i, sw, d)
    for (let y = base - 2 - off - wh; y < base - off; y++) {
      const rel = y - (base - 2 - off - wh)
      let c = p.wallS
      if (rel >= wh) c = (X + y) % 3 === 0 ? p.stone : p.stoneLit
      else if (rel < 2) c = p.wallDark
      else if (i === sw - 1 || rel === Math.round(wh * 0.5)) c = p.timber
      else if (hash2(X, y) < 0.07) c = p.wallF
      px.set(X, y, c)
    }
  }
  for (let y = top; y < base; y++) for (let X = x; X < x + fw; X++) {
    let c = p.wallF
    if (y >= base - 2) c = (X + (y % 2) * 2) % 4 === 0 || y === base - 1 ? p.stoneDark : p.stone
    else if (X === x || X === x + fw - 1) c = p.timber
    else if (y === top || (wh >= 12 && y === top + Math.round(wh * 0.5) + 1)) c = p.timber
    else if (hash2(X, y) < 0.08) c = p.wallDark
    px.set(X, y, c)
  }
  const [ax, ay] = h.apex
  for (let y = Math.ceil(ay); y < top; y++) {
    const half = ((y - ay) / (top - ay)) * (fw / 2)
    for (let X = Math.ceil(ax - half); X < ax + half; X++) {
      if (X < x || X >= x + fw) continue
      px.set(X, y, (X - x) % 3 === 0 ? p.wallDark : p.wallF)
    }
  }
  if (h.gh >= 10) {
    const gy = Math.round(ay + (top - ay) * 0.55)
    px.rect(Math.round(ax) - 2, gy - 1, 4, 4, p.timber)
    px.rect(Math.round(ax) - 1, gy, 2, 2, phase === 'night' ? p.winLit : p.glass)
  }
  const [ex, ey] = h.eaveR, [dx, dy] = h.depth
  const qx = ex - ax, qy = ey - ay
  const det = qx * dy - qy * dx
  const rows = Math.max(3, Math.round(Math.hypot(qx, qy) / 2.6))
  const cols = Math.max(3, Math.round(sw / 3))
  px.poly([[ax, ay], [ex, ey], [ex + dx, ey + dy], [ax + dx, ay + dy]], (X, Y) => {
    const vx = X + 0.5 - ax, vy = Y + 0.5 - ay
    const s = (vx * dy - vy * dx) / det, t = (qx * vy - qy * vx) / det
    const rf = s * rows, row = Math.floor(rf)
    if (row === 0 && rf % 1 < 0.6) return rL
    if (rf % 1 > 0.7) return rD
    if (h.roof === 'T') return hash2(X, Math.floor(rf)) < 0.3 ? rL : hash2(X + 1, Y) < 0.15 ? rD : rM
    const cf = t * cols + (row % 2) * 0.5
    if (cf % 1 < 0.16) return rD
    return hash2(Math.floor(cf), row) > 0.82 ? rL : rM
  })
  px.line(Math.round(ax), Math.round(ay), Math.round(ax + dx), Math.round(ay + dy), rL)
  px.line(Math.round(ex), Math.round(ey), Math.round(ex + dx), Math.round(ey + dy), rD)
  const [lx, ly] = h.eaveL
  px.line(Math.round(lx), Math.round(ly), Math.round(ax), Math.round(ay), rD, 2)
  px.line(Math.round(ax), Math.round(ay), Math.round(ex), Math.round(ey), rD, 2)
  px.line(Math.round(lx), Math.round(ly) - 1, Math.round(ax), Math.round(ay) - 1, rM)
  px.line(Math.round(ax), Math.round(ay) - 1, Math.round(ex), Math.round(ey) - 1, rL)
  if (h.chimneyTop) {
    const [cx0, cy0] = h.chimneyTop
    for (let y = cy0; y < cy0 + 8; y++) for (let X = cx0; X < cx0 + 3; X++) {
      px.set(X, y, y === cy0 ? p.stoneLit : (X + y) % 3 === 0 ? p.stoneDark : X === cx0 + 2 ? p.stoneLit : p.stone)
    }
  }
  const dr = h.door
  px.rect(dr.x - 1, dr.y - 1, dr.w + 2, dr.h + 1, p.timberDark)
  for (let X = dr.x; X < dr.x + dr.w; X++) px.rect(X, dr.y, 1, dr.h, (X - dr.x) % 2 ? p.doorDark : p.door)
  px.set(dr.x + dr.w - 1, dr.y + 4, p.brass)
  px.rect(dr.x - 1, base, dr.w + 2, 1, p.stoneLit)
  const lit = phase === 'night'
  for (const w of h.windows) {
    if (lit) {
      const cx0 = w.side ? w.side.x0 + w.x + 1 : w.x + 1
      const cy0 = w.side ? w.side.base - 2 - sideOff(w.x, w.side.sw, w.side.d) - h.wh + w.y + 2 : w.y + 2
      px.ellipse(cx0, cy0, 5, 5, (xx, yy) => {
        const c = px.get(xx, yy)
        return (c === p.wallF || c === p.wallS || c === p.wallDark) && bayer(xx, yy) < 0.4 ? p.glowSoft : null
      })
    }
    paintWindow(px, h, w, p, lit)
    if (!w.side && !lit && h.fw >= 22) {
      px.rect(w.x - 1, w.y + w.h + 1, w.w + 2, 1, p.timberDark)
      for (let i = 0; i < w.w + 2; i++) px.set(w.x - 1 + i, w.y + w.h, i % 2 ? p.flowerC : p.leafLit)
    }
  }
}

function drawProps(px: Pix, L: VillageLayout, p: Palette, phase: VillagePhase) {
  const barrel = (x: number, y: number) => {
    for (let r = 0; r < 5; r++) for (let c = 0; c < 4; c++) {
      const col = r === 1 || r === 3 ? p.stoneDark : c === 3 ? p.timberDark : c === 0 ? p.door : p.trunk
      px.set(x + c, y - 5 + r, col)
    }
    px.rect(x, y - 6, 4, 1, p.timber)
  }
  const h0 = L.houses[0], h3 = L.houses[3], h4 = L.houses[4]
  barrel(h0.x - 6, h0.base)
  barrel(h0.x - 10, h0.base + 1)
  for (let i = 0; i < 3; i++) for (let j = 0; j <= i; j++) {
    const lx = h3.x + h3.fw + h3.sw + 1 + j * 3 + (2 - i), ly = h3.base - 4 + i * 2
    px.rect(lx, ly, 3, 2, p.trunk)
    px.set(lx, ly, p.wallS)
  }
  drawGarden(px, L.houses[2].x + 4, L.houses[2].base + 6, p)
  for (const [x, y] of L.lanterns) {
    px.rect(x, y - 10, 1, 10, p.timber)
    px.rect(x - 1, y - 13, 3, 3, p.timberDark)
    px.set(x, y - 12, phase === 'night' ? p.winCore : p.glass)
    px.set(x, y - 11, phase === 'night' ? p.winLit : p.glassLit)
    px.rect(x - 1, y - 14, 3, 1, p.timber)
    if (phase === 'night') {
      px.ellipse(x, y - 11, 7, 6, (xx, yy) => {
        const c = px.get(xx, yy)
        return c !== p.timber && c !== p.timberDark && c !== p.winLit && c !== p.winCore && bayer(xx, yy) < 0.18 ? p.glowSoft : null
      })
      px.ellipse(x, y + 1, 5, 1.5, (xx, yy) => (bayer(xx, yy) < 0.45 ? p.glowGround : null))
    }
  }
  const hay = [h4.x + h4.fw + h4.sw + 6, h4.base - 2] as const
  px.ellipse(hay[0], hay[1] - 3, 6, 5, (xx, yy) => (yy > hay[1] - 2 ? p.roofTDark : xx > hay[0] + 1 ? p.roofTLit : hash2(xx, yy) < 0.3 ? p.roofTDark : p.roofT))
}

function drawNear(px: Pix, L: VillageLayout, p: Palette, phase: VillagePhase) {
  drawGround(px, L, p, phase)
  drawTower(px, L, p)
  const trees: [number, number, number][] = [
    [L.houses[1].x - 6, L.g + 6, 5], [L.houses[2].x - 12, L.g + 6, 4], [L.houses[3].x + 46, L.g + 11, 6],
    [L.houses[0].x - 22, L.g + 13, 6], [L.cx + 150, L.g + 30, 7], [L.cx - 205, L.g + 30, 6],
  ]
  const items: { base: number; draw: () => void }[] = [
    ...trees.map(([x, b, r]) => ({ base: b, draw: () => drawTree(px, x, b, r, p) })),
    ...L.houses.map((h) => ({ base: h.base, draw: () => drawHouse(px, h, p, phase) })),
  ]
  items.sort((a, b) => a.base - b.base).forEach((item) => item.draw())
  drawProps(px, L, p, phase)
}

function drawFront(px: Pix, L: VillageLayout, p: Palette) {
  const { W, H } = L
  const clumps: [number, number, number][] = [[-8, H + 6, 24], [14, H + 4, 14], [W + 6, H + 8, 26], [W - 18, H + 6, 13]]
  for (const [cx, cy, r] of clumps) {
    px.ellipse(cx, cy, r, r * 0.8, (x, y) => {
      const lx = x + 0.5 - cx, ly = y + 0.5 - cy
      const n = noise2(x * 0.3, y * 0.3, 51)
      const edge = Math.hypot(lx / r, ly / (r * 0.8))
      if (edge > 0.82 + n * 0.18) return null
      if ((lx - ly) / r > 0.55 && bayer(x, y) < 0.6) return p.fgLit
      return n < 0.3 ? p.fgDark : p.fg
    })
  }
  const r = rng(77)
  for (let x = 0; x < W; x++) {
    const h = Math.floor((noise1(x * 0.15, 61) ** 2) * 9 + r() * 2)
    for (let y = H - h; y < H; y++) px.set(x + (y < H - h + 2 && r() < 0.3 ? 1 : 0), y, y === H - h ? p.fgLit : p.fg)
  }
}

function makeCloud(w: number, h: number, seed: number, p: Palette): Pix {
  const px = new Pix(w, h)
  const r = rng(seed)
  const blobs: [number, number, number][] = []
  for (let i = 0; i < 5; i++) blobs.push([w * (0.18 + i * 0.16), h * (0.6 - Math.sin((i / 4) * Math.PI) * 0.25), h * (0.28 + r() * 0.2)])
  for (const [bx, by, br] of blobs) px.ellipse(bx, by, br * 1.3, br, () => p.cloud)
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    if (!px.get(x, y)) continue
    if (y >= h - 2) { px.set(x, y, y === h - 1 ? 0 : p.cloudShade); continue }
    const below = px.get(x, y + 2) === 0
    if (below && bayer(x, y) < 0.7) px.set(x, y, p.cloudDeep)
    else if (y > h * 0.62 && bayer(x, y) < 0.5) px.set(x, y, p.cloudShade)
  }
  return px
}

/** 雾用横向条带表达：浓处是实心长条，淡处只留隔行的细丝，避免棋盘格噪点。 */
function makeFog(P: number, hb: number, seed: number) {
  const mask = new Uint8Array(P * hb)
  for (let y = 0; y < hb; y++) for (let x = 0; x < P; x++) {
    const a = noise2(x * 0.03, y * 0.35, seed), b = noise2((x - P) * 0.03, y * 0.35, seed)
    const n = lerp(a, b, x / P)
    const bell = Math.sin(((y + 0.5) / hb) * Math.PI)
    const v = (n - 0.3) * 1.8 * bell
    mask[y * P + x] = v > 0.62 ? 2 : v > 0.3 && y % 2 === 0 ? 1 : 0
  }
  return mask
}

function buildLayers(L: VillageLayout, phase: VillagePhase): Layers {
  const p = compile(phase === 'day' ? DAY : NIGHT)
  const sky = new Pix(L.W, L.H), far = new Pix(L.W, L.H), near = new Pix(L.W, L.H), front = new Pix(L.W, L.H)
  drawSky(sky, L, p, phase)
  drawFar(far, L, p, phase)
  drawNear(near, L, p, phase)
  drawFront(front, L, p)
  const clouds = phase === 'day'
    ? [makeCloud(46, 14, 3, p), makeCloud(30, 10, 5, p), makeCloud(58, 16, 8, p)]
    : [makeCloud(54, 9, 13, p), makeCloud(36, 7, 17, p)]
  return { sky, far, near, front, clouds, fog: [makeFog(320, 16, 91), makeFog(320, 12, 97)] }
}

/** 夜晚剧情：0-2.9 狼人在林边，2.9-6.8 走向屋子，6.8-10.7 在屋内，10.7 之后离开。 */
export function nightStory(t: number) {
  const walkIn = clamp((t - 2.9) / 3.9), walkOut = clamp((t - 10.7) / 3.6)
  const inside = t >= 6.8 && t < 10.7
  const light = t < 8.2 ? 1 : t < 8.9 ? (Math.floor(t * 9) % 2 ? 1 : 0) : 0
  const doorOpen = (t > 6.5 && t < 7.1) || (t > 10.5 && t < 11.1)
  return { walkIn, walkOut, inside, light, doorOpen }
}

/** 白天剧情：村民从各自门口走到广场火堆边，随后轮流发言。 */
export function dayArrival(t: number, i: number) {
  return smooth(clamp((t - 0.3 - i * 0.3) / 2.6))
}

function along(path: [number, number][], t: number): [number, number] {
  const lens: number[] = []
  let total = 0
  for (let i = 1; i < path.length; i++) {
    const l = Math.hypot(path[i][0] - path[i - 1][0], path[i][1] - path[i - 1][1])
    lens.push(l); total += l
  }
  let dist = t * total
  for (let i = 0; i < lens.length; i++) {
    if (dist <= lens[i] || i === lens.length - 1) {
      const f = lens[i] ? clamp(dist / lens[i]) : 1
      return [Math.round(lerp(path[i][0], path[i + 1][0], f)), Math.round(lerp(path[i][1], path[i + 1][1], f))]
    }
    dist -= lens[i]
  }
  return path[path.length - 1]
}

export interface ViewportFit { W: number; H: number; scale: number; cssWidth: number; cssHeight: number }

/** 选一个整数的设备像素倍率，让逻辑画面高度落在 ~225 像素附近，保证每个像素块大小一致。 */
export function fitViewport(cssW: number, cssH: number, dpr: number): ViewportFit {
  const devH = Math.max(1, cssH * dpr), devW = Math.max(1, cssW * dpr)
  const scale = Math.max(2, Math.round(devH / 225))
  const W = Math.ceil(devW / scale), H = Math.ceil(devH / scale)
  return { W, H, scale, cssWidth: (W * scale) / dpr, cssHeight: (H * scale) / dpr }
}

export interface FrameState { time: number; phase: VillagePhase; phaseTime: number; now: number; actors?: boolean }

export interface FogVillageRenderer {
  resize(W: number, H: number): void
  render(state: FrameState): void
}

export function createFogVillage(canvas: HTMLCanvasElement): FogVillageRenderer | null {
  const ctx = canvas.getContext('2d')
  if (!ctx || typeof ctx.putImageData !== 'function') return null
  let L: VillageLayout | null = null
  let layers: Record<VillagePhase, Layers> | null = null
  let palettes: Record<VillagePhase, Palette> = { day: compile(DAY), night: compile(NIGHT) }
  let shadows: Record<VillagePhase, Map<number, number>> = { day: shadowMap(palettes.day), night: shadowMap(palettes.night) }
  let frameA: Pix, frameB: Pix, image: ImageData
  let shown: VillagePhase | null = null
  let from: VillagePhase = 'day'
  let fadeStart = -Infinity
  const looks = LOOKS.map((look) => {
    const colors = (pal: Palette) => ({ H: packColor(look.hair), S: packColor('#f0c69a'), E: pal.ink, C: packColor(look.shirt), P: packColor('#4a4658'), B: packColor('#3a2a22') })
    const make = (legs: string[], mirror: boolean) => sprite([...VILLAGER, ...legs], colors(palettes.day), palettes.day.ink, mirror)
    return { still: [make(LEGS_STILL, false), make(LEGS_STILL, true)], step: [make(LEGS_STEP, false), make(LEGS_STEP, true)] }
  })
  const nightWolf = sprite(NIGHT_WOLF, { K: palettes.night.wolf, Y: palettes.night.wolfEye }, palettes.night.ink)
  const cragWolf = sprite(CRAG_WOLF, { K: palettes.night.wolf }, palettes.night.wolf)
  const cragHowl = sprite(CRAG_WOLF_HOWL, { K: palettes.night.wolf }, palettes.night.wolf)

  function resize(W: number, H: number) {
    if (L && L.W === W && L.H === H) return
    canvas.width = W
    canvas.height = H
    L = createLayout(W, H)
    layers = { day: buildLayers(L, 'day'), night: buildLayers(L, 'night') }
    frameA = new Pix(W, H)
    frameB = new Pix(W, H)
    image = ctx!.createImageData(W, H)
    palettes = { day: compile(DAY), night: compile(NIGHT) }
    shadows = { day: shadowMap(palettes.day), night: shadowMap(palettes.night) }
  }

  function fog(px: Pix, mask: Uint8Array, y0: number, hb: number, off: number, p: Palette, thin: boolean) {
    const P = 320
    for (let y = 0; y < hb; y++) {
      const yy = y0 + y
      if (yy < 0 || yy >= px.h) continue
      for (let x = 0; x < px.w; x++) {
        const v = mask[y * P + (((x + off) % P) + P) % P]
        if (!v || (v === 1 && !thin)) continue
        px.data[yy * px.w + x] = v === 2 ? p.fog : p.fogThin
      }
    }
  }

  function smoke(px: Pix, x: number, y: number, t: number, seed: number, p: Palette) {
    for (let k = 0; k < 6; k++) {
      const age = (t * 0.32 + k / 6 + seed * 0.37) % 1
      const sx = Math.round(x + 1 - age * 7 + Math.sin(age * 5 + seed) * 1.5), sy = Math.round(y - 1 - age * 20)
      const s = 1 + Math.floor(age * 3)
      for (let yy = 0; yy < s; yy++) for (let xx = 0; xx < s; xx++) {
        if (bayer(sx + xx, sy + yy) > 1 - age * 0.9) continue
        px.set(sx + xx, sy + yy, yy === s - 1 && s > 1 ? p.smokeShade : p.smoke)
      }
    }
  }

  function drawFrame(px: Pix, phase: VillagePhase, time: number, pt: number, actors: boolean) {
    const lay = layers![phase], p = palettes[phase], lay0 = L!
    const { W, H, g } = lay0
    px.data.set(lay.sky.data)
    if (phase === 'night') {
      const r = rng(41)
      const count = Math.round(W * g * 0.0045)
      for (let i = 0; i < count; i++) {
        const x = Math.floor(r() * W), y = Math.floor(r() ** 1.7 * g * 0.8)
        const bright = r() < 0.3
        if (!bright || i % 7) continue
        if (Math.sin(time * 1.7 + i * 2.3) > 0.55) {
          px.set(x - 1, y, p.starDim); px.set(x + 1, y, p.starDim); px.set(x, y - 1, p.starDim); px.set(x, y + 1, p.starDim)
        }
      }
    }
    const cloudRows = phase === 'day' ? [0.1, 0.24, 0.36] : [0.2, 0.3]
    lay.clouds.forEach((c, i) => {
      const span = W + c.w + 40
      const x = ((((i * 197 + time * (1.2 + i * 0.5)) % span) + span) % span) - c.w - 20
      px.blit(c, x, Math.round(H * cloudRows[i]))
    })
    if (phase === 'day') {
      const span = W + 80
      const bx = ((time * 9) % span) - 40
      for (let k = 0; k < 3; k++) {
        const x = Math.round(bx - k * 7), y = Math.round(H * 0.22 + k * 3 + Math.sin(time * 2 + k) * 1.5)
        const up = Math.floor(time * 5 + k) % 2 === 0
        px.set(x - 1, y, p.bird); px.set(x, y, p.bird); px.set(x + 1, y, p.bird)
        px.set(x - 2, up ? y - 1 : y + 1, p.bird); px.set(x + 2, up ? y - 1 : y + 1, p.bird)
      }
    }
    px.blit(lay.far)
    if (phase === 'night') {
      const howl = time % 12 > 9.5
      const s = howl ? cragHowl : cragWolf
      px.blit(s, lay0.crag.x - 4, lay0.crag.top - s.h + 2)
    }
    fog(px, lay.fog[0], g - 12, 16, Math.floor(time * 2.2), p, true)
    if (phase === 'night') {
      for (let k = 0; k < 3; k++) {
        const ex = Math.round(lay0.cx + [-150, 128, 64][k] + Math.sin(k * 3) * 6), ey = g - 3 - k
        if ((time * 0.45 + k * 1.3) % 4 > 2.4 || Math.floor(time * 6 + k) % 17 === 0) continue
        px.set(ex, ey, p.wolfEye); px.set(ex + 2, ey, p.wolfEye)
      }
    }
    px.blit(lay.near)
    const sq = lay0.square
    lay0.houses.forEach((h, i) => { if (h.chimneyTop) smoke(px, h.chimneyTop[0], h.chimneyTop[1], time, i, p) })
    if (phase === 'day') {
      for (let c = -2; c <= 2; c++) {
        const fh = 3 - Math.abs(c) + Math.floor(hash(Math.floor(time * 9) * 7 + c) * 3)
        for (let r = 0; r < fh; r++) {
          const y = sq.y - 1 - r
          px.set(sq.x + c, y, r >= fh - 1 ? p.fireRed : Math.abs(c) < 1 && r < fh - 2 ? p.fireCore : p.fire)
        }
      }
      const sparkAge = (time * 0.8) % 1
      px.set(sq.x + Math.round(Math.sin(time * 3) * 2), sq.y - 6 - Math.round(sparkAge * 8), p.fire)
      const villagers = !actors ? [] : lay0.houses.map((h, i) => {
        const start: [number, number] = [h.door.x + Math.floor(h.door.w / 2), h.base]
        const to = lay0.slots[i]
        const k = dayArrival(pt, i)
        const x = Math.round(lerp(start[0], to[0], k)), y = Math.round(lerp(start[1], to[1], k))
        const moving = k > 0 && k < 1
        return { i, x, y, moving, k, faceLeft: moving ? to[0] < start[0] : sq.x < x }
      }).sort((a, b) => a.y - b.y)
      const speaker = pt > 4 ? Math.floor((pt - 4) / 1.8) % 6 : -1
      for (const v of villagers) {
        if (v.k <= 0) continue
        const look = looks[v.i]
        const s = (v.moving && Math.floor(time * 6) % 2 ? look.step : look.still)[v.faceLeft ? 1 : 0]
        px.remap(v.x - 3, v.y, 7, 1, shadows.day, 0.8)
        const bob = !v.moving && Math.floor(time * 2 + v.i) % 4 === 0 ? 1 : 0
        px.blit(s, v.x - 3, v.y - s.h + 1 + bob, v.k < 0.12 ? v.k / 0.12 : 1)
        if (v.i === speaker) {
          const bx = v.x - 4, by = v.y - s.h - 8
          px.rect(bx, by, 9, 6, p.ink)
          px.rect(bx + 1, by + 1, 7, 4, p.bubble)
          px.set(bx + 3, by + 6, p.ink); px.set(bx + 4, by + 6, p.ink); px.set(bx + 4, by + 7, p.ink)
          const dots = 1 + (Math.floor(time * 3) % 3)
          for (let d = 0; d < dots; d++) px.set(bx + 2 + d * 2, by + 3, p.ink)
        }
      }
    } else {
      for (let k = 0; k < 4; k++) if (Math.sin(time * 4 + k * 2.1) > -0.3) px.set(sq.x - 2 + k, sq.y - (k % 2), k % 2 ? p.ember : p.fireRed)
      const story = nightStory(actors ? pt : 0), h = lay0.houses[lay0.storyHouse]
      if (story.doorOpen) px.rect(h.door.x, h.door.y, h.door.w - 1, h.door.h, p.winLit)
      if (!story.light) for (const w of h.windows) paintWindow(px, h, w, p, false, true)
      let pos: [number, number] | null = null
      if (!actors || pt < 2.9) pos = null
      else if (!story.inside && story.walkOut === 0) pos = along(lay0.wolfPath, story.walkIn)
      else if (story.walkOut > 0 && story.walkOut < 1) pos = along([...lay0.wolfPath].reverse(), story.walkOut)
      if (actors && pt < 2.9) {
        const [ex, ey] = lay0.wolfPath[0]
        if (Math.floor(time * 4) % 9) { px.set(ex - 1, ey - 6, p.wolfEye); px.set(ex + 1, ey - 6, p.wolfEye) }
      }
      if (pos) {
        const edge = Math.min(story.walkIn, 1 - story.walkOut)
        px.remap(pos[0] - 3, pos[1], 7, 1, shadows.night, 0.8)
        px.blit(nightWolf, pos[0] - 3, pos[1] - nightWolf.h + 1, edge < 0.1 ? edge / 0.1 : 1)
      }
      for (let k = 0; k < 11; k++) {
        const bx = (hash(k * 3.1) * W) | 0, by = g + 8 + ((hash(k * 7.7) * (H - g - 22)) | 0)
        const x = Math.round(bx + Math.sin(time * 0.6 + k) * 7), y = Math.round(by + Math.sin(time * 1.1 + k * 2) * 3)
        const glow = Math.sin(time * 2.6 + k * 5)
        if (glow < -0.2) continue
        px.set(x, y, p.firefly)
        if (glow > 0.6) { px.set(x - 1, y, p.fireflyDim); px.set(x + 1, y, p.fireflyDim); px.set(x, y - 1, p.fireflyDim); px.set(x, y + 1, p.fireflyDim) }
      }
    }
    fog(px, lay.fog[1], g + 16, 12, -Math.floor(time * 3.4), p, phase === 'night')
    px.blit(lay.front)
  }

  function render({ time, phase, phaseTime, now, actors = true }: FrameState) {
    if (!L || !layers) return
    if (shown === null) shown = phase
    if (phase !== shown) { from = shown; shown = phase; fadeStart = now }
    const k = (now - fadeStart) / 900
    drawFrame(frameA, phase, time, phaseTime, actors)
    if (k < 1) {
      drawFrame(frameB, from, time, phaseTime, actors)
      const { w, h } = frameA, a = frameA.data, b = frameB.data
      const threshold = smooth(clamp(k))
      for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) if (bayer(x, y) >= threshold) a[y * w + x] = b[y * w + x]
    }
    new Uint32Array(image.data.buffer).set(frameA.data)
    ctx!.putImageData(image, 0, 0)
  }

  return { resize, render }
}
