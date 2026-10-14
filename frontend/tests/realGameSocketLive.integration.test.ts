import { afterAll, afterEach, beforeAll, expect, test, vi } from 'vitest'
import { createRealGameAdapter } from '../src/api/realGameAdapter'
import { createRealRoomAdapter, startGame } from '../src/api/realRoomAdapter'
import { SESSION_TOKEN_KEY } from '../src/api/config'

/**
 * **`realGameAdapter` × 真实后端 × 真实 WebSocket**。
 *
 * ## 它补的是哪一段
 *
 * 到第 26 轮为止，实时通路有"两端各自的证据"：
 * - 后端侧（`SpeechDeltaDeliveryTest`、`RateLimitLiveTest`…）证明**服务端会发什么**；
 * - 前端侧（`realGameAdapter.test.ts` 用 `GameSocket` 替身）证明**收到帧之后怎么处理**。
 *
 * 而**中间那一段从未跑过**：真实后端推的帧，真实的前端适配器能不能收到并处理。
 * 替身测试无法发现"两端对帧/URL/握手形状的理解不一致"——
 * 第 14 轮 `GAME_EVENT.seq` 恒为 0 的缺陷就是这类问题，当时是端到端冒烟才抓到的。
 *
 * ## 它验证什么、不验证什么
 *
 * **验证**：适配器用真实 `GameSocket` 连上真实 `/ws`、完成 AUTH 与 SUBSCRIBE、
 * 收到开局的真实事件并通过 `subscribe` 回调传给调用方、视图经真实 REST 映射后可用。
 *
 * **不验证**：`preview` 的完整链路。`PLAYER_SPOKE` 只在 `DAY_DISCUSS` 产生，
 * 而开局是 `NIGHT_WOLF`——要走到白天需要 AI 推完整个夜晚，而 Agent 用真实 LLM
 * （成本与时延都不可接受，AGENTS.md §10 要求测试默认不依赖付费调用）。
 * 因此 `preview` 仍只有替身测试覆盖，这一点记在 TASKS.md 里。
 *
 * 运行：`npm run e2e:adapters`（需要真实后端）。
 */

const BASE = process.env.WOLFMIND_E2E_BASE ?? 'http://127.0.0.1:18080'
const uniqueSuffix = () => Math.random().toString(36).slice(2, 10)

function installStorage(): void {
  const store = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
    removeItem: (k: string) => void store.delete(k),
  })
}

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms))

async function until(predicate: () => boolean, timeoutMs = 10_000): Promise<boolean> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (predicate()) return true
    await wait(30)
  }
  return predicate()
}

async function account(prefix: string) {
  const username = `ga_${prefix}_${uniqueSuffix()}`
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

/** 建一个 AI 补位开局的对局；返回 gameId 与账号。 */
async function startedGame(prefix: string) {
  const owner = await account(prefix)
  localStorage.setItem(SESSION_TOKEN_KEY, owner.token)
  const roomAdapter = createRealRoomAdapter(() => owner.userId)
  const room = await roomAdapter.create({ name: '适配器实时房', visibility: 'PUBLIC' })
  await roomAdapter.ready(room.roomId)
  const started = await startGame(room.roomId, true)
  return { ...owner, gameId: started.gameId, roomId: room.roomId }
}

let unsubscribe: (() => void) | undefined

beforeAll(() => {
  installStorage()
})

afterEach(() => {
  unsubscribe?.()
  unsubscribe = undefined
})

afterAll(() => {
  vi.unstubAllGlobals()
})

test('适配器经真实 WebSocket 订阅真实对局并把事件传给调用方', async () => {
  const session = await startedGame('live')
  localStorage.setItem(SESSION_TOKEN_KEY, session.token)

  const adapter = createRealGameAdapter({ mySeatNo: () => 1 })
  const received: Array<{ gameId: string; version: number; players: unknown[] }> = []
  unsubscribe = adapter.subscribe(session.gameId, (view) => received.push(view as never))

  // 订阅会先给一次 REST 视图，随后 WebSocket 上的开局事件会推动刷新。
  const gotView = await until(() => received.length > 0)
  expect(gotView, '适配器应立刻给一次视图（subscribe 内部先拉 REST）').toBe(true)

  const first = received[0]!
  expect(first.gameId).toBe(session.gameId)
  expect(first.players, '视图应含 9 个玩家').toHaveLength(9)

  // 关键：WebSocket 上的真实事件必须推动出**第二次**推送。
  // 只有这一次推送才能证明"订阅真的建立了"——只靠 REST 那一次的话，
  // 一个从未连上 WS 的适配器也会"通过"。
  const pushedAgain = await until(() => received.length > 1, 15_000)
  expect(pushedAgain,
    `真实 WebSocket 上的事件应推动刷新。收到的推送次数=${received.length}，`
    + `版本=${received.map((v) => v.version).join(',')}`).toBe(true)
  expect(received.at(-1)!.version, '版本应不比首次更旧')
    .toBeGreaterThanOrEqual(first.version)
})

test('适配器在真实后端上取到的视图与 REST 一致（同一份权威数据）', async () => {
  const session = await startedGame('rest')

  const adapter = createRealGameAdapter({ mySeatNo: () => 1 })
  const presentation = await adapter.get(session.gameId)

  // 直接问后端拿同一份视图，比对映射结果——两条路径必须给出同一个 gameId 与版本。
  const raw = await fetch(`${BASE}/api/v1/games/${session.gameId}`).then((r) => r.json()) as {
    gameId: string
    version: number
    players: unknown[]
  }
  expect(presentation.gameId).toBe(raw.gameId)
  expect(presentation.version).toBe(raw.version)
  expect(presentation.players).toHaveLength(raw.players.length)
  // 开局是夜晚：映射层必须给出 night 气氛（`NIGHT_WOLF` → `NIGHT_ACTION`）。
  expect(presentation.phase).toBe('NIGHT_ACTION')
  expect(presentation.atmosphere).toBe('night')
})
