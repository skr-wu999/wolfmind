import { expect, test } from 'vitest'
import { createLayout, fitViewport, nightStory } from '../src/features/home/fogVillage'

test('viewport fit uses one integer device-pixel scale and covers the container', () => {
  for (const [w, h, dpr] of [[1920, 1080, 1], [1024, 796, 1.25], [390, 844, 3], [2560, 1080, 1]]) {
    const fit = fitViewport(w, h, dpr)
    expect(Number.isInteger(fit.scale)).toBe(true)
    expect(fit.cssWidth).toBeGreaterThanOrEqual(w)
    expect(fit.cssHeight).toBeGreaterThanOrEqual(h)
    expect(fit.H).toBeGreaterThan(180)
    expect(fit.H).toBeLessThan(300)
  }
})

test('night story keeps the wolf outside, then inside, then leaving, and turns the light off once', () => {
  expect(nightStory(1)).toMatchObject({ walkIn: 0, inside: false, light: 1 })
  expect(nightStory(5).walkIn).toBeGreaterThan(0)
  expect(nightStory(8)).toMatchObject({ walkIn: 1, inside: true, light: 1 })
  expect(nightStory(9.5)).toMatchObject({ inside: true, light: 0 })
  expect(nightStory(12).walkOut).toBeGreaterThan(0)
  expect(nightStory(14.9)).toMatchObject({ inside: false, light: 0 })
})

test('layout keeps houses on the ground and gives every villager a slot by the fire', () => {
  const layout = createLayout(384, 216)
  expect(layout.houses).toHaveLength(6)
  expect(layout.slots).toHaveLength(6)
  for (const house of layout.houses) expect(house.base).toBeGreaterThan(layout.g)
  for (const [, y] of layout.slots) expect(Math.abs(y - layout.square.y)).toBeLessThanOrEqual(layout.square.ry)
})
