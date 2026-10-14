import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createRealObservationAdapter } from '../src/features/observation/realObservationAdapter'
import { setSessionToken } from '../src/api/config'
import { useObservationStore } from '../src/stores/observation'
import { installStorage } from './support/jsdomStubs'
import type { GameSocketOptions } from '../src/api/gameSocket'
import type { GameView } from '../src/api/restTypes'

const sockets = vi.hoisted(() => [] as { options: GameSocketOptions; close: ReturnType<typeof vi.fn>; resume: ReturnType<typeof vi.fn> }[])
vi.mock('../src/api/gameSocket', () => ({ GameSocket: class {
  close = vi.fn(); resume = vi.fn(); ack = vi.fn(); unsubscribe = vi.fn()
  constructor(public options: GameSocketOptions) { sockets.push(this) }
  connect() { this.options.onState?.('connecting') }
} }))
vi.mock('../src/api/adapters', async () => {
  const { createRealObservationAdapter } = await import('../src/features/observation/realObservationAdapter')
  return { observationAdapter: () => createRealObservationAdapter() }
})
const view: GameView = { gameId: 'g_live', roomId: 'r_live', status: 'RUNNING', version: 12, phase: 'NIGHT_SEER', round: 3,
  players: [{ playerId: 'p_1', seatNo: 1, role: null, camp: null, alive: true, controller: { type: 'HUMAN', refId: 'u_1' } }], permissions: [] }
const event = { eventId: 'public', version: 12, type: 'PHASE_CHANGED', visibility: 'PUBLIC', occurredAt: 1000, phase: 'DAY_DISCUSS', round: 1 }
function response(value: unknown) { return { ok: true, status: 200, text: async () => JSON.stringify(value) } as Response }
function stub(current = view) {
  const fetcher = vi.fn(async (url: string) => response(url.includes('/replay') ? { events: [event] } : current))
  vi.stubGlobal('fetch', fetcher)
  return fetcher
}
beforeEach(() => { installStorage(); setSessionToken('spectator'); sockets.length = 0; setActivePinia(createPinia()) })
afterEach(() => { useObservationStore().dispose(); vi.useRealTimers(); vi.unstubAllGlobals() })

test('实时棋桌使用权威快照，不从公开事件猜夜间阶段或存活状态', async () => {
  stub()
  const store = useObservationStore()
  await store.load('g_live', true)
  // 快照说的是 NIGHT_SEER：夜间子阶段必须给出各自的文案，不能折叠成"黑夜行动"（QA P2）。
  expect(store.view?.phase).toBe('预言家查验')
  expect(store.view?.round).toBe(3)
  expect(store.view?.players[0]?.alive).toBe(true)
  store.seek(0); store.toggle()
  expect(store.playing).toBe(false)
  expect(sockets).toHaveLength(1)
})
test('匿名仍可读取公开记录，明确提示登录且不创建未认证连接', async () => {
  setSessionToken(null); stub()
  const store = useObservationStore()
  await store.load('g_live', true)
  expect(store.match?.events).toHaveLength(1)
  expect(store.connection).toBe('login-required')
  expect(sockets).toHaveLength(0)
})
test('参与者不能通过观战页面获取私有历史', async () => {
  const fetcher = stub({ ...view, me: { ...view.players[0]!, role: 'SEER' } })
  await expect(createRealObservationAdapter().get('g_live')).rejects.toMatchObject({ code: 'PARTICIPANT_VIEW' })
  expect(fetcher).toHaveBeenCalledTimes(1)
})
test('可见事件不足500条也继续按领域版本读取后续窗口', async () => {
  const fetcher = vi.fn(async (url: string) => response(url.includes('/replay') ? { events: url.includes('from=1001') ? [{ ...event, version: 1200 }] : [] } : { ...view, version: 1200 }))
  vi.stubGlobal('fetch', fetcher)
  const result = await createRealObservationAdapter().get('g_live')
  expect(fetcher.mock.calls.map(([url]) => url.split('?')[1]).filter(Boolean)).toEqual([
    'from=1&to=500&limit=500', 'from=501&to=1000&limit=500', 'from=1001&to=1200&limit=500',
  ])
  expect(result.events[0]?.version).toBe(1200)
})
test('会话切换后丢弃旧HTTP结果，不继续读取旧身份历史', async () => {
  let resolve!: (value: Response) => void
  const fetcher = vi.fn(() => new Promise<Response>(done => { resolve = done }))
  vi.stubGlobal('fetch', fetcher)
  const reading = createRealObservationAdapter().get('g_live')
  setSessionToken('another-user'); resolve(response(view))
  await expect(reading).rejects.toMatchObject({ name: 'AbortError' })
  expect(fetcher).toHaveBeenCalledTimes(1)
})
test('补齐订阅后刷新到终局，披露完整角色并停止连接', async () => {
  vi.useFakeTimers(); stub()
  const store = useObservationStore()
  await store.load('g_live', true)
  const socket = sockets[0]!
  socket.options.onState?.('ready')
  stub({ ...view, status: 'FINISHED', phase: 'GAME_OVER', version: 20, players: [{ ...view.players[0]!, role: 'SEER' }] })
  socket.options.onFrame({ v: 1, id: 'end', type: 'GAME_EVENT', ts: 0, seq: 20, payload: {} })
  await vi.advanceTimersByTimeAsync(150)
  expect(store.connection).toBe('finished')
  expect(store.match?.snapshot?.version).toBe(20)
  expect(store.match?.players[0]?.disclosedRole).toBe('SEER')
  expect(socket.close).toHaveBeenCalledOnce()
})
test('重连补发与SYNC_REQUIRED使用服务端游标，路由离开后不再提交旧回调', async () => {
  vi.useFakeTimers(); const fetcher = stub()
  const store = useObservationStore()
  await store.load('g_live', true)
  const socket = sockets[0]!
  socket.options.onFrame({ v: 1, id: 'next', type: 'GAME_EVENT', ts: 0, seq: 18, payload: {} })
  expect(socket.options.lastSeq()).toBe(18)
  socket.options.onState?.('reconnecting')
  expect(store.connection).toBe('reconnecting')
  socket.options.onSyncRequired?.('SEQ_GAP')
  expect(socket.options.lastSeq()).toBe(0)
  expect(socket.resume).toHaveBeenCalledOnce()
  store.dispose()
  socket.options.onFrame({ v: 1, id: 'late', type: 'GAME_EVENT', ts: 0, seq: 99, payload: {} })
  await vi.advanceTimersByTimeAsync(500)
  expect(fetcher).toHaveBeenCalledTimes(2)
  expect(store.match).toBeNull()
  expect(store.connection).toBe('closed')
})
test('恢复快照水位倒退时采用新的基线，不能永远等待旧流尾', async () => {
  vi.useFakeTimers(); stub()
  const store = useObservationStore()
  await store.load('g_live', true)
  const socket = sockets[0]!
  socket.options.onState?.('ready')
  socket.options.onFrame({ v: 1, id: 'old-tail', type: 'SUBSCRIBED', ts: 0, seq: 0, payload: { to: 99 } })
  socket.options.onSyncRequired?.('SEQ_GAP')
  stub({ ...view, version: 6 })
  socket.options.onFrame({ v: 1, id: 'baseline', type: 'GAME_SNAPSHOT_SYNC', ts: 0, seq: 6, payload: {} })
  await vi.advanceTimersByTimeAsync(150)
  expect(socket.options.lastSeq()).toBe(6)
  expect(store.match?.snapshot?.version).toBe(6)
  expect(store.connection).toBe('live')
})
test('读取历史期间结束时重读终局基线，完整角色与历史一起交付', async () => {
  let views = 0
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    if (url.includes('/replay')) return response({ events: [{ ...event, type: 'ROLE_ASSIGNED', visibility: 'PRIVATE' }] })
    views++
    return response(views === 1 ? view : { ...view, status: 'FINISHED', phase: 'GAME_OVER', players: [{ ...view.players[0]!, role: 'SEER' }] })
  }))
  const match = await createRealObservationAdapter().get('g_live')
  expect(match.snapshot?.finished).toBe(true)
  expect(match.players[0]?.disclosedRole).toBe('SEER')
  expect(match.events[0]?.scope).toBe('PRIVATE')
})
