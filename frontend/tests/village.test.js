import { test } from 'vitest'
import assert from 'node:assert/strict'
import { createVillage, getNightVisit, getVillageActors, projectVillageActors } from '../src/features/home/village.js'

test('mobile hit areas follow the same cover crop as the scene', () => {
  const actor = getVillageActors('day', 6).find((actor) => actor.id === 2)
  const [projected] = projectVillageActors([actor], { width: 390, height: 620, crop: .36 })
  assert.deepEqual([projected.screenX, projected.screenY], [213, 482])
  assert.equal(projected.id, 2)
})

test('wide cover crop anchors the scene to the bottom', () => {
  const [projected] = projectVillageActors([{ x: 480, y: 613 }], { width: 1366, height: 704 })
  assert.deepEqual([projected.screenX, projected.screenY], [683, 704])
})

test('short wide windows preserve the sky without shifting NPC hit areas', () => {
  const [projected] = projectVillageActors([{ x: 334, y: 453 }], { width: 1920, height: 836, anchorY: .55 })
  assert.deepEqual([projected.screenX, projected.screenY], [668, 680])
})

test('hidden and cropped characters do not retain interactive targets', () => {
  const projected = projectVillageActors([
    { x: 0, y: 450 }, { x: 960, y: 450 }, { x: 400, y: 450, opacity: .1 },
  ], { width: 390, height: 620, crop: .36 })
  assert.deepEqual(projected, [])
  assert.deepEqual(projectVillageActors(getVillageActors('day', 6), { width: 0, height: 0 }), [])
})

test('villager returns home and wolves disappear indoors before leaving', () => {
  assert.deepEqual(getVillageActors('night', 1).map((actor) => actor.id), [0, 1, 3])
  assert.deepEqual(getVillageActors('night', 4).map((actor) => actor.id), [0, 3])
  assert.deepEqual(getVillageActors('night', 9), [])
  assert.deepEqual(getVillageActors('night', 13).map((actor) => actor.id), [0, 3])
  assert.equal(getVillageActors('day', 0).length, 6)
})

test('wolves use the actual house doorway and cannot be clicked during entry', () => {
  const { house, doorOpen } = getNightVisit(5.5)
  const entering = getVillageActors('night', 5.5).find((actor) => actor.id === 0)
  assert.equal(doorOpen, 1)
  assert.equal(entering.x, house.x + house.width / 2)
  assert.ok(entering.y < house.y + 5 && entering.y > house.y - 34)
  assert.equal(entering.throughDoor, true)
  assert.deepEqual(projectVillageActors([entering], { width: 960, height: 600 }), [])
})

test('doors close during the indoor action and reopen for the exit', () => {
  assert.equal(getNightVisit(7.2).doorOpen, 0)
  assert.equal(getNightVisit(8.8).windowLight, 0)
  assert.equal(getNightVisit(11).doorOpen, 1)
  assert.equal(getNightVisit(14).doorOpen, 0)
  const earlyExit = getVillageActors('night', 11).find((actor) => actor.id === 0)
  const lateExit = getVillageActors('night', 11.4).find((actor) => actor.id === 0)
  assert.ok(lateExit.y > earlyExit.y)
  assert.equal(earlyExit.interactive, false)
  for (let phaseTime = 12.4; phaseTime < 15; phaseTime += .1) {
    const wolf = getVillageActors('night', phaseTime).find((actor) => actor.id === 3)
    assert.ok(wolf.y > 434 || wolf.x + 13 < 353, 'exit route avoids the clock tower')
  }
})

test('reduced motion stops walking', () => {
  const a = getVillageActors('day', 2, 0, true)
  const b = getVillageActors('day', 2, 20, true)
  assert.deepEqual(a, b)
})

function recordingScene() {
  const bubbles = []
  const context = {
    beginPath() {}, moveTo() {}, lineTo() {}, closePath() {}, fill() {}, save() {}, restore() {}, rect() {}, clip() {},
    measureText: (text) => ({ width: text.length * 10 }),
    fillRect(x, y, width, height) {
      if (this.fillStyle === '#455455' || this.fillStyle === '#fff8e4') bubbles.push({ x, y, width, height, color: this.fillStyle })
    },
    fillText(text, x, y) { bubbles.push({ text, x, y, color: this.fillStyle, font: this.font }) },
  }
  const canvas = { clientWidth: 960, clientHeight: 600, getContext: () => context }
  return { renderer: createVillage(canvas), bubbles }
}

test('automatic and interactive dialogue use identical pixel drawing', () => {
  const automatic = recordingScene()
  automatic.renderer.draw(6, 'day', 6, true)
  const interactive = recordingScene()
  interactive.renderer.draw(6, 'day', 6, true, { id: 0, text: '我投 3 号。' })
  assert.ok(automatic.bubbles.length > 0)
  assert.deepEqual(interactive.bubbles, automatic.bubbles)
})

test('interactive canvas dialogue remains inside the mobile scene crop', () => {
  const { renderer, bubbles } = recordingScene()
  renderer.draw(6, 'day', 6, true, { id: 5, text: '村里的事，我都听着呢。' }, { width: 320, height: 620, crop: .36 })
  const border = bubbles[0]
  const scale = 620 / 600
  const offset = (320 - 960 * scale) * .36
  assert.ok(border.x * scale + offset >= 0)
  assert.ok((border.x + border.width) * scale + offset <= 320)
})
