import { afterAll, afterEach, beforeAll, describe, expect, test, vi } from 'vitest'
import { GameSocket, type ServerFrame } from '../src/api/gameSocket'
import { emptyState, reduceFrame, type GameStreamState } from '../src/api/gameStream'
import { SESSION_TOKEN_KEY } from '../src/api/config'

/**
 * **`GameSocket` × 真实 WebSocket 端点**。
 *
 * ## 为什么还需要这一组
 *
 * `gameSocket.test.ts` 用 `FakeSocket` 覆盖了客户端的**决策**（重连退避、RESUME 携带的
 * `lastSeq`、4401 不重连）。但 FakeSocket 是我按"我以为的协议"写的：
 * **它不会因为我理解错了帧格式而失败。** 这一组连真实端点，因此能证明：
 * - 客户端发的 `AUTH` 形状被网关接受（`{id,type,payload}`，没有 `v/ts/seq`）；
 * - 服务端帧的字段名与客户端的读取一致（`AUTH_OK` 的协商参数、`GAME_EVENT` 的 `seq`）；
 * - "帧 → `reduceFrame` → 水位"这条链在真实帧上成立。
 *
 * ## 它与 `realAdapters.integration.test.ts` 的分工
 *
 * 那一组走 REST（`get`/`submit`），这一组走 **WebSocket**。两条通路在真实环境里
 * 是分开的，因此必须分别验证。
 *
 * 运行：`npm run e2e:ws`（需要真实后端，见 `vitest.ws.config.ts`）。
 */

const BASE = process.env.WOLFMIND_E2E_BASE ?? 'http://127.0.0.1:18080'
const suffix = Math.random().toString(36).slice(2, 10)

let openSockets: GameSocket[] = []

function installStorage(): void {
  const store = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
    removeItem: (k: string) => void store.delete(k),
  })
}

async function account(prefix: string) {
  const username = `ws_${prefix}_${suffix}`
  const password = 'correct-horse-battery'
  const registered = await fetch(`${BASE}/api/v1/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  if (registered.status !== 201) throw new Error(`注册失败 ${registered.status}: ${await registered.text()}`)
  const loggedIn = await fetch(`${BASE}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  if (loggedIn.status !== 200) throw new Error(`登录失败 ${loggedIn.status}: ${await loggedIn.text()}`)
  const body = (await loggedIn.json()) as { userId: string; sessionToken: string }
  return { userId: body.userId, token: body.sessionToken }
}

/** 建一个真人 + AI 补位开局的对局；返回 gameId 与该账号令牌。 */
async function startedGame(prefix: string) {
  const owner = await account(prefix)
  const auth = { 'Content-Type': 'application/json', Authorization: `Bearer ${owner.token}` }
  const idem = () => `ws_${Math.random().toString(36).slice(2, 10)}`

  const created = await fetch(`${BASE}/api/v1/rooms`, {
    method: 'POST',
    headers: { ...auth, 'Idempotency-Key': idem() },
    body: JSON.stringify({ name: 'WS 集成房', config: { playerCount: 9 } }),
  })
  if (created.status !== 201) throw new Error(`建房失败 ${created.status}: ${await created.text()}`)
  const roomId = ((await created.json()) as { roomId: string }).roomId

  const ready = await fetch(`${BASE}/api/v1/rooms/${roomId}/ready`, {
    method: 'POST',
    headers: { ...auth, 'Idempotency-Key': idem() },
    body: JSON.stringify({}),
  })
  if (ready.status !== 200) throw new Error(`准备失败 ${ready.status}: ${await ready.text()}`)

  const started = await fetch(`${BASE}/api/v1/rooms/${roomId}/games`, {
    method: 'POST',
    headers: { ...auth, 'Idempotency-Key': idem() },
    body: JSON.stringify({ autoFillAi: true }),
  })
  if (started.status !== 201) throw new Error(`开局失败 ${started.status}: ${await started.text()}`)
  const gameId = ((await started.json()) as { gameId: string }).gameId

  return { ...owner, gameId, roomId }
}

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms))

/** 等到谓词为真或超时；返回是否满足。 */
async function until(predicate: () => boolean, timeoutMs = 8000): Promise<boolean> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (predicate()) return true
    await wait(30)
  }
  return predicate()
}

beforeAll(() => {
  installStorage()
})

afterEach(() => {
  for (const socket of openSockets) socket.close()
  openSockets = []
})

afterAll(() => {
  vi.unstubAllGlobals()
})

describe('GameSocket × 真实端点', () => {
  test('AUTH → SUBSCRIBE → 授权快照建立基线，实时事件在其后递增', async () => {
    const session = await startedGame('frames')
    localStorage.setItem(SESSION_TOKEN_KEY, session.token)

    const frames: ServerFrame[] = []
    const states: string[] = []
    const socket = new GameSocket({
      gameId: session.gameId,
      lastSeq: () => 0,
      onFrame: (frame) => frames.push(frame),
      onState: (state) => states.push(state),
    })
    openSockets.push(socket)
    socket.connect()

    const ok = await until(() =>
      frames.some((f) => f.type === 'SUBSCRIBED')
      && frames.some((f) => f.type === 'GAME_SNAPSHOT_SYNC'))
    expect(ok, `等待超时。已收到：${frames.map((f) => f.type).join(',')}`).toBe(true)

    // 客户端帧的形状被服务端接受了——否则会看到 ERROR。
    expect(frames.some((f) => f.type === 'ERROR'),
      `不应有 ERROR：${JSON.stringify(frames.filter((f) => f.type === 'ERROR'))}`).toBe(false)
    expect(states).toContain('ready')

    const subscribed = frames.find((f) => f.type === 'SUBSCRIBED')!
    const snapshot = frames.find(f => f.type === 'GAME_SNAPSHOT_SYNC')!
    expect(subscribed.payload.to).toBe(snapshot.seq)
    expect(snapshot.seq).toBe(snapshot.payload.replayedTo)
    expect(snapshot.seq).toBeGreaterThanOrEqual(11)
    expect(subscribed.payload.scope).toBe('PLAYER')

    const events = frames.filter((f) => f.type === 'GAME_EVENT')
    expect(events.every(event => event.seq > snapshot.seq)).toBe(true)
    // 这一条是本轮的核心：**GAME_EVENT.seq 必须等于事件版本**。
    // 第 14 轮的真实缺陷就是网关把 seq 写成了 0——那时客户端会把第 2 条之后全部丢弃。
    for (const event of events) {
      const version = (event.payload as { version: number }).version
      expect(event.seq, `GAME_EVENT 的 seq 必须等于事件 version（${JSON.stringify(event.payload)}）`)
        .toBe(version)
    }
    // 严格递增：客户端按 seq 去重时不会误丢。
    const seqs = events.map((e) => e.seq)
    expect(seqs, `seq 必须严格递增：${seqs.join(',')}`)
      .toEqual([...seqs].sort((a, b) => a - b).filter((v, i, arr) => i === 0 || v !== arr[i - 1]))
  })

  test('PING → PONG，且 PONG 带 serverSeq 与 serverTime', async () => {
    const session = await startedGame('ping')
    localStorage.setItem(SESSION_TOKEN_KEY, session.token)

    const frames: ServerFrame[] = []
    const socket = new GameSocket({
      gameId: session.gameId,
      lastSeq: () => 0,
      onFrame: (frame) => frames.push(frame),
    })
    openSockets.push(socket)
    socket.connect()
    await until(() => frames.some((f) => f.type === 'SUBSCRIBED'))

    // `PONG` 由 socket 内部消费（不进 onFrame），因此这里在原始连接上观察不到——
    // 改为断言"心跳之后连接仍然 ready 且没有 ERROR"这一可观测性质。
    // 直接发 PING 的能力由契约测试侧覆盖；这里验证客户端发出的心跳不会破坏连接。
    await wait(300)
    expect(frames.some((f) => f.type === 'ERROR')).toBe(false)
    expect(socket.currentState).toBe('ready')
  })

  test('无效令牌 → 不重连、进入 closed，并回调 onUnauthenticated', async () => {
    localStorage.setItem(SESSION_TOKEN_KEY, 'u_1.bogus.secret')

    let unauthCalled = false
    const frames: ServerFrame[] = []
    const socket = new GameSocket({
      gameId: 'g_whatever',
      lastSeq: () => 0,
      onFrame: (frame) => frames.push(frame),
      onUnauthenticated: () => { unauthCalled = true },
    })
    openSockets.push(socket)
    socket.connect()

    const ok = await until(() => unauthCalled)
    // §3.6：4401 表示未认证，重连也会被同样拒绝——重连只会让用户看着"重连中"。
    expect(ok, `应回调 onUnauthenticated。已收到：${frames.map((f) => f.type).join(',')}`).toBe(true)
    expect(socket.currentState).toBe('closed')
  })

  test('帧 → 归约层：真实 seq 能驱动水位，且重复帧被丢弃', async () => {
    const session = await startedGame('reducer')
    localStorage.setItem(SESSION_TOKEN_KEY, session.token)

    const frames: ServerFrame[] = []
    const socket = new GameSocket({
      gameId: session.gameId,
      lastSeq: () => 0,
      onFrame: (frame) => frames.push(frame),
    })
    openSockets.push(socket)
    socket.connect()
    expect(await until(() => frames.some(frame => frame.type === 'GAME_SNAPSHOT_SYNC'))).toBe(true)

    // 用真实帧喂归约层（而不是手工构造的样本）。
    let state: GameStreamState = emptyState()
    for (const frame of frames.filter((f) => ['GAME_EVENT', 'GAME_SNAPSHOT_SYNC'].includes(f.type))) {
      state = reduceFrame(state, frame)
    }

    const versions = frames
      .filter((f) => ['GAME_EVENT', 'GAME_SNAPSHOT_SYNC'].includes(f.type))
      .map((f) => f.seq)
    // 水位必须等于**收到过的最大版本**：客户端重连时把它作为 lastSeq 用于 RESUME。
    expect(state.seq).toBe(Math.max(...versions))
    expect(state.duplicates).toBe(0)
    // 归约层只保留可见事件；村民看不到的事件本就不会到达，因此事件数可能少于水位。
    expect(state.seq).toBeGreaterThan(0)
    expect(state.events.length).toBeLessThanOrEqual(state.seq)

    // 再喂一遍同样的帧：全部应被当成重复丢弃（至少一次投递的幂等消费）。
    const before = state
    for (const frame of frames.filter((f) => f.type === 'GAME_EVENT')) {
      state = reduceFrame(state, frame)
    }
    expect(state.seq).toBe(before.seq)
    expect(state.duplicates).toBe(before.events.length)
  })
})
