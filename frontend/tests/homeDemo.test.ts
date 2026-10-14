import { beforeEach, expect, test } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useHomeDemoStore } from '../src/stores/homeDemo'

beforeEach(() => setActivePinia(createPinia()))

test('manual phase changes clear the demonstration vote and timer', () => {
  const demo = useHomeDemoStore()
  demo.advance(6)
  demo.vote = 3
  demo.setPhase('night')
  expect([demo.phase, demo.elapsed, demo.vote, demo.seconds]).toEqual(['night', 0, null, 15])
})

test('pause freezes time and votes until the next phase', () => {
  const demo = useHomeDemoStore()
  demo.advance(6)
  demo.vote = 3
  demo.paused = true
  expect(demo.advance(20)).toBe(false)
  expect([demo.phase, demo.elapsed, demo.vote]).toEqual(['day', 6, 3])
  demo.paused = false
  expect(demo.advance(10)).toBe(true)
  expect([demo.phase, demo.elapsed, demo.vote]).toEqual(['night', 1, null])
})

test('background time spanning multiple cycles preserves phase parity', () => {
  const demo = useHomeDemoStore()
  demo.advance(31)
  expect([demo.phase, demo.elapsed]).toEqual(['day', 1])
  demo.advance(45)
  expect([demo.phase, demo.elapsed]).toEqual(['night', 1])
})

test('leaving the homepage resets presentation state', () => {
  const demo = useHomeDemoStore()
  demo.setPhase('night')
  demo.paused = true
  demo.vote = 4
  demo.reset()
  expect([demo.phase, demo.elapsed, demo.vote, demo.paused]).toEqual(['day', 0, null, false])
})
