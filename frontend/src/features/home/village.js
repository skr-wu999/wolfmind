const W = 960
const H = 600
const mix = (a, b, t) => a.map((v, i) => Math.round(v + (b[i] - v) * t))
const rgb = (color) => `rgb(${color.join(',')})`
const ease = (t) => t * t * (3 - 2 * t)
const rand = (seed) => {
  const value = Math.sin(seed * 127.1 + 311.7) * 43758.5453
  return value - Math.floor(value)
}

const names = ['橡果', '松果', '南瓜', '阿栗', '小麦', '青苔']
const colors = ['#b96e50', '#738da0', '#dfb462', '#819969', '#a88098', '#648d86']
const positions = [[334, 453], [361, 478], [416, 479], [443, 454], [391, 423], [289, 442]]
const homes = [[151, 399], [327, 383], [484, 427], [512, 422], [346, 384], [222, 404]]
const visitHouse = { x: 291, y: 364, width: 76, height: 66 }
const doorstep = [visitHouse.x + visitHouse.width / 2, visitHouse.y + 5]
const inside = [doorstep[0], visitHouse.y - 34]
const progress = (value, start, end) => Math.min(Math.max((value - start) / (end - start), 0), 1)

function followPath(points, amount) {
  const lengths = points.slice(1).map(([x, y], i) => Math.hypot(x - points[i][0], y - points[i][1]))
  let distance = ease(amount) * lengths.reduce((sum, length) => sum + length, 0)
  for (let i = 0; i < lengths.length; i++) {
    if (distance <= lengths[i]) {
      const ratio = distance / lengths[i]
      return points[i].map((value, axis) => value + (points[i + 1][axis] - value) * ratio)
    }
    distance -= lengths[i]
  }
  return points.at(-1)
}

export function getNightVisit(phaseTime) {
  const openFor = (start, close) => progress(phaseTime, start, start + .4) * (1 - progress(phaseTime, close, close + .4))
  return {
    doorOpen: Math.max(openFor(1.7, 2.9), openFor(4.6, 6.8), openFor(10.2, 12.4)),
    windowLight: phaseTime >= 8.6 ? 0 : 1 - .45 * Math.sin(Math.PI * progress(phaseTime, 7.3, 8.6)),
    house: visitHouse,
  }
}

export function getVillageActors(phase, phaseTime, time = 0, reducedMotion = false) {
  const gathering = ease(Math.min(Math.max(phaseTime / 4, 0), 1))
  return names.flatMap((name, id) => {
    if (phase === 'day') {
      const walking = phaseTime < 4 && !reducedMotion
      return [{ id, name, x: homes[id][0] + (positions[id][0] - homes[id][0]) * gathering,
        y: homes[id][1] + (positions[id][1] - homes[id][1]) * gathering + (walking ? Math.round(Math.sin(time * 9 + id)) : 0),
        color: colors[id], step: walking ? Math.round(Math.sin(time * 9 + id) * 2) : 0, wolf: false,
        raising: phaseTime > 4 && Math.floor(phaseTime / 2) % 6 === id }]
    }
    if (id === 0 || id === 3) {
      const enter = id === 0 ? 5.1 : 6
      const leave = id === 0 ? 10.7 : 11.6
      if (phaseTime >= enter + .8 && phaseTime < leave) return []
      const approach = id === 0 ? [positions[id], [309, 428], [304, 395], [321, 382], doorstep]
        : [positions[id], [401, 465], [364, 451], [345, 427], [339, 395], [337, 382], doorstep]
      const retreat = id === 0 ? [doorstep, [314, 395], [304, 429], [279, 452]]
        : [doorstep, [335, 395], [339, 438], [364, 451], [407, 459]]
      const throughDoor = phaseTime >= enter && phaseTime < leave + .8
      const [x, y] = phaseTime < enter ? followPath(approach, progress(phaseTime, id === 0 ? .4 : 1.1, enter))
        : phaseTime < enter + .8 ? followPath([doorstep, inside], progress(phaseTime, enter, enter + .8))
          : phaseTime < leave + .8 ? followPath([inside, doorstep], progress(phaseTime, leave, leave + .8))
            : followPath(retreat, progress(phaseTime, leave + .8, 15))
      return [{ id, name, x, y, color: colors[id], step: reducedMotion ? 0 : Math.round(Math.sin(time * 8 + id) * 2),
        wolf: true, raising: false, throughDoor, interactive: !throughDoor }]
    }
    if (id === 1 && phaseTime < 2.9) {
      const throughDoor = phaseTime >= 2.2
      const [x, y] = throughDoor ? followPath([doorstep, inside], progress(phaseTime, 2.2, 2.9))
        : followPath([positions[id], [352, 436], [338, 390], doorstep], progress(phaseTime, 0, 2.2))
      return [{ id, name, x, y, color: colors[id], step: reducedMotion ? 0 : Math.round(Math.sin(time * 9) * 2),
        wolf: false, raising: false, throughDoor, interactive: !throughDoor }]
    }
    return []
  })
}

export function projectVillageActors(actors, { width, height, crop = .5, anchorY = 1 }) {
  if (width <= 0 || height <= 0) return []
  const scale = Math.max(width / W, height / H)
  const offsetX = (width - W * scale) * crop
  const offsetY = (height - H * scale) * anchorY
  return actors.flatMap((actor) => {
    const screenX = Math.round(actor.x * scale + offsetX)
    const screenY = Math.round((actor.y - 13) * scale + offsetY)
    if (screenX < 0 || screenX > width || screenY < 0 || screenY > height || actor.opacity < .35 || actor.interactive === false) return []
    return [{ ...actor, screenX, screenY }]
  })
}

export function createVillage(canvas) {
  const ctx = canvas.getContext('2d', { alpha: false })
  canvas.width = W
  canvas.height = H
  ctx.imageSmoothingEnabled = false
  let darkness = 0
  let lastFrame = performance.now()
  let lastPhase = 'day'
  let phaseBlend = 1
  let previousActors = []
  let currentActors = []
  let bubbleBounds = { left: 0, right: W }
  const rect = (x, y, w, h, c) => {
    ctx.fillStyle = c
    ctx.fillRect(Math.round(x), Math.round(y), Math.round(w), Math.round(h))
  }
  const polygon = (points, color) => {
    ctx.fillStyle = color
    ctx.beginPath()
    points.forEach(([x, y], i) => i ? ctx.lineTo(Math.round(x), Math.round(y)) : ctx.moveTo(Math.round(x), Math.round(y)))
    ctx.closePath()
    ctx.fill()
  }
  const palette = (day, night) => rgb(mix(day, night, darkness))

  function cloud(x, y, size = 1) {
    const color = palette([248, 250, 221], [107, 135, 163])
    ;[[0, 8, 68, 10], [8, 2, 45, 8], [19, -5, 24, 8], [55, 12, 22, 6]].forEach(([a, b, w, h]) => rect(x + a * size, y + b * size, w * size, h * size, color))
    rect(x + 10 * size, y + 18 * size, 50 * size, 3 * size, palette([203, 225, 211], [82, 106, 139]))
  }

  function pine(x, y, size = 1, distant = false) {
    const trunk = palette([108, 88, 58], [44, 53, 65])
    const shade = distant ? palette([86, 132, 108], [42, 72, 91]) : palette([42, 100, 74], [24, 59, 62])
    const light = distant ? palette([108, 153, 116], [45, 83, 95]) : palette([75, 129, 75], [29, 72, 68])
    rect(x - 3 * size, y - 10 * size, 6 * size, 23 * size, trunk)
    for (let i = 0; i < 4; i++) {
      const yy = y - (64 - i * 13) * size
      const width = (14 + i * 11) * size
      rect(x - width / 2, yy, width, 12 * size, shade)
      rect(x - width / 2, yy, width * .5, 4 * size, light)
      rect(x - width / 2 - 4 * size, yy + 9 * size, width + 8 * size, 7 * size, shade)
    }
    rect(x - 4 * size, y - 74 * size, 8 * size, 12 * size, light)
  }

  function broadTree(x, y, size = 1) {
    rect(x - 5 * size, y - 28 * size, 11 * size, 35 * size, palette([109, 79, 48], [52, 48, 57]))
    rect(x + 4 * size, y - 21 * size, 9 * size, 5 * size, palette([109, 79, 48], [52, 48, 57]))
    const dark = palette([65, 109, 62], [25, 60, 56])
    const mid = palette([94, 143, 76], [32, 78, 62])
    const light = palette([132, 170, 85], [47, 94, 67])
    rect(x - 25 * size, y - 62 * size, 48 * size, 38 * size, dark)
    rect(x - 33 * size, y - 56 * size, 60 * size, 25 * size, mid)
    rect(x - 17 * size, y - 76 * size, 31 * size, 24 * size, mid)
    rect(x - 24 * size, y - 61 * size, 17 * size, 11 * size, light)
    rect(x - 13 * size, y - 72 * size, 21 * size, 8 * size, light)
    rect(x + 7 * size, y - 54 * size, 12 * size, 10 * size, light)
    rect(x - 22 * size, y - 40 * size, 8 * size, 8 * size, dark)
  }

  function window(x, y, width = 15, height = 18, lit = 1) {
    const wood = palette([83, 75, 62], [35, 45, 56])
    rect(x - 2, y - 2, width + 4, height + 4, wood)
    rect(x, y, width, height, palette([156, 199, 196], mix([40, 60, 75], [255, 202, 109], lit)))
    rect(x, y, width, 3, palette([104, 153, 154], mix([35, 53, 65], [229, 149, 80], lit)))
    rect(x + Math.floor(width / 2) - 1, y, 2, height, wood)
    rect(x, y + Math.floor(height / 2), width, 2, wood)
    rect(x - 3, y + height + 2, width + 6, 3, palette([141, 106, 69], [78, 70, 64]))
    if (darkness > .2) {
      ctx.globalAlpha = darkness * .12 * lit
      rect(x - 4, y - 4, width + 8, height + 8, '#ffe3a0')
      ctx.globalAlpha = 1
    }
  }

  function house(x, y, width, height, roofType = 'red', time = 0, visit = null) {
    const outline = palette([75, 81, 62], [28, 41, 55])
    const wall = palette([245, 215, 158], [134, 143, 139])
    const side = palette([210, 181, 128], [101, 117, 119])
    const roof = roofType === 'red' ? palette([177, 91, 62], [104, 68, 73]) : palette([104, 125, 114], [61, 87, 104])
    const roofLight = roofType === 'red' ? palette([205, 124, 75], [127, 84, 80]) : palette([143, 158, 135], [75, 103, 115])
    const roofHeight = Math.round(width * .35)
    rect(x + width * .68, y - height - roofHeight + 8, 10, 24, side)
    rect(x + width * .68 - 2, y - height - roofHeight + 6, 14, 4, outline)
    for (let i = 0; i < 4; i++) {
      const age = (time * 8 + i * 8) % 32
      ctx.globalAlpha = (1 - age / 32) * .3
      rect(x + width * .7 + Math.sin(age / 8) * 3, y - height - roofHeight + 3 - age, 6 + age / 5, 5, palette([248, 248, 225], [126, 148, 163]))
    }
    ctx.globalAlpha = 1
    rect(x - 2, y - height, width + 4, height, outline)
    rect(x, y - height, width, height - 3, wall)
    rect(x + width - 16, y - height, 16, height - 3, side)
    for (let i = 0; i < roofHeight; i += 4) {
      const inset = (roofHeight - i) * 1.15
      rect(x - 11 + inset, y - height - roofHeight + i, width + 22 - inset * 2, 4, i % 8 === 0 ? roofLight : roof)
      if (i > 12) {
        for (let j = 0; j < 5; j++) rect(x + inset + j * 15 - 5, y - height - roofHeight + i, 2, 3, roof)
      }
    }
    rect(x - 12, y - height, width + 24, 5, outline)
    rect(x - 9, y - height, width + 18, 3, roof)
    rect(x + 5, y - height + 6, 5, height - 10, side)
    rect(x + width - 22, y - height + 6, 4, height - 10, outline)
    rect(x + 5, y - height + 38, width - 25, 4, side)
    rect(x + width / 2 - 9, y - 28, 21, 28, outline)
    const opening = visit?.doorOpen ?? 0
    if (opening > 0) rect(x + width / 2 - 6, y - 25, 15, 23, '#15252d')
    rect(x + width / 2 - 6, y - 25, 15 * (1 - opening), 23, palette([141, 98, 60], [66, 68, 71]))
    if (opening < .8) rect(x + width / 2 + 5 - opening * 15, y - 15, 2, 2, '#e9c58c')
    window(x + 16, y - height + 13, 13, 16, visit?.windowLight ?? 1)
    if (width > 70) window(x + width - 43, y - height + 13, 13, 16, visit?.windowLight ?? 1)
    if (height > 65) window(x + 16, y - 29, 12, 15, visit?.windowLight ?? 1)
    rect(x - 5, y, width + 10, 5, palette([131, 139, 111], [72, 88, 93]))
    rect(x + width / 2 - 13, y + 5, 29, 4, palette([212, 204, 166], [106, 120, 124]))
    for (let i = 0; i < 5; i++) rect(x + i * 13 + 6, y - 6, 6, 3, side)
  }

  function fence(x, y, length) {
    const light = palette([231, 206, 143], [113, 131, 127])
    const shade = palette([169, 143, 91], [69, 90, 97])
    rect(x, y - 13, length, 4, light)
    rect(x, y - 5, length, 3, shade)
    for (let i = 0; i < length; i += 12) {
      rect(x + i, y - 20, 4, 25, shade)
      rect(x + i, y - 20, 2, 22, light)
      rect(x + i + 1, y - 22, 2, 2, light)
    }
  }

  function person(x, y, shirt, step, wolf = false, raising = false, opacity = 1) {
    x = Math.round(x)
    y = Math.round(y)
    ctx.globalAlpha = opacity
    rect(x - 7, y + 1, 15, 3, palette([106, 133, 78], [30, 51, 60]))
    if (wolf) {
      const fur = '#57566a'
      rect(x - 6, y - 29, 4, 7, fur)
      rect(x + 3, y - 29, 4, 7, fur)
      rect(x - 7, y - 24, 15, 11, fur)
      rect(x - 6, y - 21, 3, 2, '#edb466')
      rect(x + 3, y - 21, 3, 2, '#edb466')
      rect(x - 3, y - 17, 7, 5, '#929093')
      rect(x, y - 17, 2, 2, '#263745')
      rect(x - 6, y - 12, 13, 11, '#404958')
      rect(x + 7, y - 9, 5, 3, fur)
      rect(x + 10, y - 12, 3, 4, fur)
    } else {
      const skin = palette([248, 200, 144], [186, 163, 137])
      rect(x - 5, y - 25, 11, 11, skin)
      rect(x - 6, y - 28, 13, 6, '#69533c')
      rect(x - 6, y - 24, 3, 7, '#69533c')
      rect(x - 1, y - 21, 2, 2, '#38433f')
      rect(x + 4, y - 21, 2, 2, '#38433f')
      rect(x - 5, y - 14, 11, 13, shirt)
      rect(x - 2, y - 14, 3, 3, '#f1ddae')
      rect(x - 8, y - 13, 3, 9, shirt)
      rect(x + 6, y - (raising ? 20 : 13), 3, 9, shirt)
      rect(x - 8, y - 5, 3, 3, skin)
      rect(x + 6, y - (raising ? 23 : 5), 3, 3, skin)
      if (raising) rect(x + 5, y - 29, 7, 6, '#faf2d4')
    }
    rect(x - 5, y - 1, 4, 5 - step, '#3d4b4b')
    rect(x + 2, y - 1, 4, 5 + step, '#3d4b4b')
    rect(x - 6, y + 3 - step, 5, 2, '#35403e')
    rect(x + 2, y + 3 + step, 5, 2, '#35403e')
    ctx.globalAlpha = 1
  }

  function drawActor(actor, opacity) {
    if (actor.throughDoor) {
      ctx.save()
      ctx.beginPath()
      ctx.rect(doorstep[0] - 6, visitHouse.y - 25, 15, H - visitHouse.y + 25)
      ctx.clip()
    }
    person(actor.x, actor.y, actor.color, actor.step, actor.wolf, actor.raising, opacity)
    if (actor.throughDoor) ctx.restore()
  }

  function bubble(x, y, text, night = false) {
    ctx.font = '10px "Microsoft YaHei", sans-serif'
    const width = Math.ceil(ctx.measureText(text).width) + 16
    const center = Math.round(Math.max(bubbleBounds.left + width / 2 + 3, Math.min(bubbleBounds.right - width / 2 - 3, x)))
    rect(center - width / 2 - 2, y - 2, width + 4, 21, '#455455')
    rect(center - width / 2, y, width, 17, night ? '#e5d9c0' : '#fff8e4')
    rect(x - 2, y + 17, 4, 4, '#455455')
    ctx.fillStyle = '#344746'
    ctx.textAlign = 'center'
    ctx.fillText(text, center, Math.round(y + 12))
  }

  function draw(time, phase, phaseTime, reducedMotion = false, interaction = null, viewport = {}) {
    const interacting = interaction !== null
    if (viewport.width > 0 && viewport.height > 0) {
      const scale = Math.max(viewport.width / W, viewport.height / H)
      const left = (W * scale - viewport.width) * (viewport.crop ?? .5) / scale
      bubbleBounds = { left, right: left + viewport.width / scale }
    }
    const now = performance.now()
    const dt = Math.max((now - lastFrame) / 1000, 0)
    lastFrame = now
    if (phase !== lastPhase) {
      previousActors = currentActors
      phaseBlend = 0
      lastPhase = phase
    }
    phaseBlend = reducedMotion ? 1 : Math.min(phaseBlend + dt / 1.2, 1)
    darkness = reducedMotion ? (phase === 'night' ? 1 : 0) : darkness + ((phase === 'night' ? 1 : 0) - darkness) * (1 - Math.exp(-dt * 1.8))
    const t = reducedMotion ? 0 : time
    rect(0, 0, W, H, palette(viewport.dusk ? [227, 174, 129] : [174, 214, 222], [28, 48, 76]))
    rect(0, 169, W, 160, palette(viewport.dusk ? [230, 193, 147] : [193, 223, 216], [44, 68, 91]))
    const wideScene = canvas.clientWidth / canvas.clientHeight > 1.75
    const sx = wideScene ? 555 : 438
    const sy = viewport.hideActors ? 100 : canvas.clientWidth < 650 ? 78 : wideScene ? 170 : 102
    ctx.globalAlpha = 1 - darkness
    rect(sx + 8, sy - 5, 26, 5, '#f8e5a0')
    rect(sx, sy, 42, 31, '#f8e5a0')
    rect(sx + 8, sy + 31, 26, 5, '#f8e5a0')
    ctx.globalAlpha = darkness
    polygon([[sx + 23, sy - 5], [sx + 34, sy - 5], [sx + 34, sy], [sx + 42, sy], [sx + 42, sy + 31], [sx + 34, sy + 31], [sx + 34, sy + 36], [sx + 8, sy + 36], [sx + 8, sy + 31], [sx, sy + 31], [sx, sy + 20], [sx + 5, sy + 20], [sx + 5, sy + 23], [sx + 28, sy + 23], [sx + 28, sy + 20], [sx + 23, sy + 20]], '#f6e5b4')
    ctx.globalAlpha = 1
    if (darkness > .1) {
      for (let i = 0; i < 52; i++) {
        ctx.globalAlpha = darkness * (.5 + Math.sin(t * 1.1 + i) * .25)
        const x = Math.floor(rand(i + 11) * W)
        const y = Math.floor(rand(i + 219) * 227 + 22)
        rect(x, y, 2, 2, '#f6e8c9')
        if (i % 11 === 0) { rect(x - 2, y, 6, 2, '#f6e8c9'); rect(x, y - 2, 2, 6, '#f6e8c9') }
      }
      ctx.globalAlpha = 1
    }
    cloud(38 + Math.sin(t * .02) * 14, 102, 1.1)
    cloud(240 + Math.sin(t * .018) * 12, 160, .65)
    cloud(536 + Math.sin(t * .016) * 10, 74, .85)
    cloud(826 + Math.sin(t * .02) * 16, 133, 1.3)
    const back = palette([141, 181, 157], [48, 81, 98])
    const front = palette([116, 160, 132], [42, 73, 87])
    polygon([[0, 293], [0, 223], [40, 223], [40, 203], [72, 203], [72, 189], [104, 189], [104, 202], [135, 202], [135, 224], [161, 224], [161, 234], [198, 234], [198, 216], [230, 216], [230, 185], [260, 185], [260, 168], [290, 168], [290, 191], [321, 191], [321, 208], [353, 208], [353, 236], [399, 236], [399, 212], [439, 212], [439, 197], [475, 197], [475, 211], [514, 211], [514, 235], [578, 235], [578, 270], [960, 250], [960, 350]], back)
    polygon([[0, 340], [0, 283], [52, 283], [52, 271], [100, 271], [100, 254], [158, 254], [158, 267], [211, 267], [211, 282], [266, 282], [266, 260], [316, 260], [316, 244], [367, 244], [367, 253], [420, 253], [420, 274], [471, 274], [471, 290], [530, 290], [530, 275], [591, 275], [591, 262], [653, 262], [653, 271], [704, 271], [704, 285], [758, 285], [758, 270], [826, 270], [826, 255], [899, 255], [899, 270], [960, 270], [960, 370]], front)
    for (let i = 0; i < 36; i++) pine(i * 29 - 12, 324 + rand(i + 70) * 19, .55 + rand(i + 7) * .28, true)
    rect(0, 332, W, H - 332, palette([149, 177, 100], [47, 77, 64]))
    rect(0, 352, W, 30, palette([162, 188, 110], [51, 84, 68]))
    polygon([[110, 360], [126, 360], [170, 388], [230, 419], [258, 453], [281, 486], [242, 540], [294, 600], [370, 600], [318, 535], [354, 481], [322, 435], [267, 408], [194, 377]], palette([208, 197, 149], [94, 110, 98]))
    polygon([[294, 440], [438, 434], [512, 453], [512, 469], [478, 488], [328, 492], [281, 474]], palette([216, 204, 158], [99, 113, 102]))
    polygon([[418, 462], [490, 439], [573, 420], [660, 423], [728, 440], [805, 441], [812, 457], [716, 459], [654, 442], [574, 439], [504, 459], [443, 480]], palette([208, 197, 149], [94, 110, 98]))
    for (let i = 0; i < 510; i++) {
      const x = rand(i + 310) * W
      const y = 367 + rand(i + 900) * 240
      rect(x, y, i % 4 === 0 ? 5 : 2, 2, palette(i % 3 === 0 ? [176, 193, 113] : [130, 158, 87], i % 3 === 0 ? [58, 89, 70] : [39, 70, 58]))
      if (i % 7 === 0) rect(x + 2, y - 2, 2, 2, palette([123, 152, 80], [39, 70, 58]))
    }
    fence(66, 376, 109)
    house(105, 382, 86, 71, 'red', t)
    house(visitHouse.x, visitHouse.y, visitHouse.width, visitHouse.height, 'grey', t + 2, phase === 'night' ? getNightVisit(phaseTime) : null)
    broadTree(232, 383, .85)
    house(434, 403, 95, 80, 'red', t + 4)
    house(708, 386, 80, 66, 'grey', t + 1)
    house(844, 424, 92, 77, 'red', t + 3)
    pine(34, 393, 1.15)
    pine(592, 387, .95)
    broadTree(651, 412, 1.1)
    broadTree(64, 452, 1.3)
    fence(99, 435, 83)
    fence(425, 447, 120)
    rect(109, 408, 49, 14, palette([148, 105, 64], [68, 74, 61]))
    for (let i = 0; i < 6; i++) {
      rect(111 + i * 8, 405, 4, 12, palette([110, 143, 68], [55, 92, 61]))
      rect(110 + i * 8, 408, 6, 4, '#cea06a')
    }
    // The clock tower is the village's visual anchor and stays readable in both phases.
    rect(349, 430, 27, 7, palette([143, 148, 121], [69, 88, 93]))
    rect(353, 390, 19, 40, palette([222, 208, 165], [124, 138, 132]))
    rect(350, 386, 25, 5, palette([89, 101, 83], [39, 57, 66]))
    rect(355, 378, 15, 8, palette([180, 104, 65], [110, 76, 71]))
    rect(359, 374, 7, 4, palette([205, 132, 78], [138, 89, 75]))
    rect(357, 396, 11, 12, '#f3e5b7')
    rect(362, 398, 2, 6, '#495b54')
    rect(362, 402, 4, 2, '#495b54')
    rect(360, 416, 6, 14, '#526352')
    rect(383, 455, 16, 14, palette([134, 101, 63], [79, 80, 71]))
    rect(380, 452, 22, 5, palette([181, 134, 80], [108, 100, 79]))
    rect(386, 453, 10, 2, '#374c49')
    if (!viewport.hideActors && phaseBlend < 1) previousActors.forEach((actor) => drawActor(actor, 1 - phaseBlend))
    currentActors = viewport.hideActors ? [] : getVillageActors(phase, phaseTime, time, reducedMotion)
    currentActors.forEach((actor) => drawActor(actor, phaseBlend))
    if (!viewport.hideActors && !interacting && phaseBlend > .95 && phase === 'day' && phaseTime > 5 && (phaseTime - 5) % 3.5 < 1.8) {
      const speaker = Math.floor((phaseTime - 5) / 3.5) % 3
      const [x, y] = positions[speaker]
      bubble(x, y - 49, ['我投 3 号。', '昨晚在哪？', '相信我。'][speaker])
    }
    if (!interacting && phase === 'night' && phaseTime > 3.5 && phaseTime < 4.8) {
      const wolf = currentActors.find((actor) => actor.id === 0)
      if (wolf) bubble(wolf.x, wolf.y - 49, '嘘…这边。', true)
    }
    const speaker = interaction && currentActors.find((actor) => actor.id === interaction.id)
    if (speaker && speaker.interactive !== false && phaseBlend > .35) bubble(speaker.x, speaker.y - 49, interaction.text, phase === 'night')
    // Foreground plants overlap the paths to give the flat pixel scene depth.
    fence(23, 520, 114)
    broadTree(21, 542, 1.6)
    pine(562, 553, 1.5)
    broadTree(854, 568, 1.6)
    rect(0, 565, W, 35, palette([95, 133, 73], [32, 62, 52]))
    for (let i = 0; i < 40; i++) {
      const x = i * 25 + rand(i) * 8
      rect(x, 561 - rand(i + 1) * 7, 5, 10, palette([95, 133, 73], [32, 62, 52]))
      rect(x + 3, 565, 6, 5, palette([95, 133, 73], [32, 62, 52]))
    }
    if (darkness > .1) {
      for (let i = 0; i < 16; i++) {
        ctx.globalAlpha = darkness * (.4 + Math.sin(t * 2 + i) * .35)
        rect(70 + rand(i + 21) * 790 + Math.sin(t + i) * 5, 380 + rand(i + 61) * 167 + Math.cos(t * .7 + i) * 4, 2, 2, '#f4dd86')
      }
      ctx.globalAlpha = 1
    }
    return currentActors.map((actor) => ({ ...actor, opacity: phaseBlend }))
  }
  function drawIllustration(variant = 'red') {
    darkness = 0
    rect(0, 0, W, H, '#f7f0df')
    cloud(96, 286, .75)
    cloud(178, 296, .6)
    rect(78, 382, 152, 11, '#8daf79')
    rect(83, 393, 141, 9, '#759951')
    rect(90, 402, 131, 6, '#55773e')
    if (variant === 'trees') {
      pine(119, 385, .95)
      pine(174, 385, .8)
    } else {
      pine(202, 380, .8)
      broadTree(93, 370, .65)
      house(115, 383, 75, 68, variant, 0)
      fence(100, 395, 101)
    }
    for (let i = 0; i < 22; i++) rect(82 + rand(i) * 140, 387 + rand(i + 22) * 17, 3, 2, i % 3 ? '#a4bb6d' : '#658642')
  }
  function drawPortrait(color = '#588861') {
    canvas.width = 32
    canvas.height = 36
    darkness = 0
    rect(0, 0, 32, 36, '#f7f0df')
    person(16, 30, color, 0)
  }
  function drawGameTable(phase = 'day') {
    darkness = phase === 'night' ? 1 : 0
    rect(0, 0, W, H, palette([119, 156, 81], [39, 68, 56]))
    for (let i = 0; i < 48; i++) {
      const x = rand(i + 701) * W
      const y = rand(i + 810) * H
      if (x < 160 || x > 800 || y < 65 || y > 540) {
        if (i % 3) pine(x, y + 75, .8 + rand(i) * .5)
        else broadTree(x, y + 60, .7)
      }
    }
    polygon([[360, 56], [600, 56], [600, 86], [720, 86], [720, 125], [790, 125], [790, 440], [715, 440], [715, 504], [610, 504], [610, 558], [350, 558], [350, 514], [235, 514], [235, 460], [167, 460], [167, 148], [236, 148], [236, 93], [360, 93]], palette([216, 192, 128], [144, 133, 96]))
    for (let i = 0; i < 380; i++) {
      const x = rand(i + 80) * W, y = rand(i + 230) * H
      rect(x, y, 3 + i % 4, 2, palette([149, 164, 90], [72, 94, 65]))
      if (i % 9 === 0) { rect(x, y - 5, 3, 7, '#708a4e'); rect(x + 4, y - 2, 3, 4, '#829756') }
      if (i % 17 === 0) { rect(x, y - 7, 4, 4, '#eee1ad'); rect(x + 4, y - 5, 4, 4, '#c89c4e') }
    }
    const seats = [[480, 144], [701, 204], [816, 324], [768, 325], [605, 440], [355, 440], [192, 325], [144, 324], [259, 204]]
    seats.forEach(([x, y]) => { rect(x - 20, y, 40, 27, '#775333'); rect(x - 24, y - 5, 48, 13, '#ab8351'); rect(x - 16, y - 3, 30, 5, '#c39760'); rect(x - 16, y + 11, 4, 19, '#654b30') })
    for (const [x, y] of [[55, 300], [870, 276], [720, 50], [224, 66]]) {
      rect(x, y, 8, 68, '#65492f'); rect(x - 7, y + 6, 22, 28, '#4b4b35'); rect(x - 3, y + 10, 14, 20, '#efbd65'); rect(x + 1, y + 12, 6, 15, '#ffe2a4')
    }
    const stones = [[440, 308], [477, 295], [514, 308], [527, 339], [511, 367], [473, 376], [434, 362], [425, 331]]
    stones.forEach(([x, y]) => { rect(x, y, 30, 21, '#696b58'); rect(x + 2, y, 24, 10, '#a3a188') })
    rect(454, 335, 56, 10, '#60452d'); rect(474, 322, 10, 43, '#60452d')
    polygon([[473, 260], [482, 260], [482, 281], [495, 281], [495, 298], [512, 298], [512, 337], [497, 355], [454, 355], [445, 332], [458, 301], [466, 301], [466, 277], [473, 277]], '#df7035')
    polygon([[473, 286], [483, 286], [483, 311], [496, 311], [496, 340], [479, 349], [459, 335], [467, 311], [473, 311]], '#f3a942')
    rect(474, 319, 10, 25, '#ffe08a')
  }
  return { draw, drawIllustration, drawPortrait, drawGameTable }
}
