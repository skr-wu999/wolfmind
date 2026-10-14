import { afterEach, expect, test, vi } from 'vitest'
import { newIdempotencyKey } from '../src/api/apiClient'

afterEach(() => vi.unstubAllGlobals())

test('HTTP generates distinct UUIDs with getRandomValues when randomUUID is absent', () => {
  const getRandomValues = vi.fn(globalThis.crypto.getRandomValues.bind(globalThis.crypto))
  vi.stubGlobal('crypto', { getRandomValues })
  const keys = Array.from({ length: 100 }, () => newIdempotencyKey('action'))
  expect(new Set(keys).size).toBe(100)
  keys.forEach(key => expect(key).toMatch(/^action_[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/))
  expect(getRandomValues).toHaveBeenCalledTimes(100)
})

test('a present but non-callable randomUUID uses the HTTP fallback', () => {
  vi.stubGlobal('crypto', { randomUUID: undefined, getRandomValues: globalThis.crypto.getRandomValues.bind(globalThis.crypto) })
  expect(newIdempotencyKey()).toMatch(/^ui_[0-9a-f]{8}-/)
})

test('native randomUUID is used when available', () => {
  const randomUUID = vi.fn(() => '12345678-1234-4123-8123-123456789abc')
  vi.stubGlobal('crypto', { randomUUID })
  expect(newIdempotencyKey('room')).toBe('room_12345678-1234-4123-8123-123456789abc')
  expect(randomUUID).toHaveBeenCalledOnce()
})
