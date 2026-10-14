import { afterEach, expect, test, vi } from 'vitest'
import { createRealGameAdapter } from '../src/api/realGameAdapter'
import { createRealRoomAdapter, startGame } from '../src/api/realRoomAdapter'
import { SESSION_TOKEN_KEY } from '../src/api/config'
import { ApiError } from '../src/api/apiClient'
import type { GameView } from '../src/api/restTypes'
import type { GamePresentation } from '../src/features/game/types'

const BASE = process.env.WOLFMIND_E2E_BASE ?? 'http://127.0.0.1:18080'
const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))
let unsubscribe: (() => void) | undefined

afterEach(() => {
  unsubscribe?.()
  unsubscribe = undefined
  vi.unstubAllGlobals()
})

async function json(path: string, token?: string, body?: unknown) {
  const response = await fetch(`${BASE}/api/v1${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const result = await response.json()
  if (!response.ok) throw new Error(`${path}: ${response.status} ${JSON.stringify(result)}`)
  return result
}

async function match(human: boolean) {
  const storage = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => void storage.set(key, value),
    removeItem: (key: string) => void storage.delete(key),
  })
  const username = `finish_${Math.random().toString(36).slice(2, 10)}`
  const credentials = { username, password: 'correct-horse-battery' }
  await json('/auth/register', undefined, credentials)
  const account = await json('/auth/login', undefined, credentials) as { userId: string; sessionToken: string }
  localStorage.setItem(SESSION_TOKEN_KEY, account.sessionToken)
  const rooms = createRealRoomAdapter(() => account.userId)
  const room = await rooms.create({ name: human ? '人机终局回归' : '全 AI 终局回归', visibility: 'PUBLIC' })
  if (human) await rooms.ready(room.roomId)
  else await rooms.leave(room.roomId)
  const started = await startGame(room.roomId, true)
  expect(started.agentCount).toBe(human ? 8 : 9)
  const adapter = createRealGameAdapter({ mySeatNo: () => human ? 1 : null })
  let latest: GamePresentation | undefined
  let pushes = 0
  unsubscribe = adapter.subscribe(started.gameId, (view) => { latest = view; pushes++ })
  return { ...account, gameId: started.gameId, adapter, latest: () => latest, pushes: () => pushes }
}

test.each([false, true])('真实调度与适配器完成整局：真人参与=%s', async (human) => {
  const session = await match(human)
  const phases = new Set<string>()
  let humanActions = 0
  let current: GameView | undefined
  const deadline = Date.now() + 60_000
  while (Date.now() < deadline) {
    current = await json(`/games/${session.gameId}`, session.sessionToken) as GameView
    phases.add(`${current.round}:${current.phase}`)
    if (current.status === 'FINISHED') break
    if (human && current.permissions.length > 0) {
      const speaking = current.permissions.some((permission) => permission.type === 'SPEAK')
      try {
        await session.adapter.submit(session.gameId, {
          commandId: `finish:${session.gameId}:${current.version}`,
          expectedVersion: current.version,
          actionType: speaking ? 'SPEAK' : 'SKIP',
          params: speaking ? { content: '这是人机混局回归测试的真人发言。' } : {},
        })
        humanActions++
      } catch (error) {
        // AI 在读取与提交之间可能提交；只重取版本，不放宽其他拒绝。
        if (!(error instanceof ApiError) || error.code !== 'GAME_VERSION_CONFLICT') throw error
      }
    }
    await wait(100)
  }
  expect(current?.status, `未终局，已观察阶段 ${[...phases].join(', ')}`).toBe('FINISHED')
  expect(current?.phase).toBe('GAME_OVER')
  expect(current?.permissions).toEqual([])
  if (human) expect(humanActions).toBeGreaterThan(0)
  // 终局必须经订阅回调到达，REST 轮询不向 latest 写入。
  const syncDeadline = Date.now() + 5_000
  while (session.latest()?.phase !== 'GAME_OVER' && Date.now() < syncDeadline) await wait(50)
  expect(session.latest()?.phase).toBe('GAME_OVER')
  expect(session.latest()?.version).toBe(current?.version)
  expect(session.pushes()).toBeGreaterThan(1)
  const replay = await json(`/games/${session.gameId}/replay?from=1&to=${current!.version}`, session.sessionToken) as {
    events: Array<{ type: string; eventId: string }>
  }
  expect(replay.events.filter((event) => event.type === 'GAME_FINISHED')).toHaveLength(1)
  expect(replay.events.filter((event) => event.type === 'HUNTER_SHOT').length).toBeLessThanOrEqual(1)
  expect(new Set(replay.events.map((event) => event.eventId)).size).toBe(replay.events.length)
  console.info('MATCH_FINISHED', {
    gameId: session.gameId, human, round: current!.round, version: current!.version,
    humanActions, pushes: session.pushes(),
  })
})
