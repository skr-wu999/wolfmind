import { afterAll, afterEach, beforeAll, describe, expect, test, vi } from 'vitest'
import { createRealGameAdapter } from '../src/api/realGameAdapter'
import { createRealRoomAdapter, startGame } from '../src/api/realRoomAdapter'
import { SESSION_TOKEN_KEY } from '../src/api/config'

/** 规则模式验证自动推进；九名真人按授权动作提交，验证已提交发言进入订阅记录。 */

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

/**
 * 异步轮询到条件成立或超时。
 *
 * **谓词必须是 async**：我第一版复用了别处的同步 `until`，而它用
 * `if (predicate())` 判断 Promise 的真值——**Promise 恒为真**，于是循环立刻返回
 * "成立"。那样断言看起来通过，实际什么都没等。这个坑值得单独写一条注释：
 * 把一个同步工具用在异步场景上，失败方式是**静默通过**而不是报错。
 */
async function untilAsync(
  predicate: () => Promise<boolean>,
  timeoutMs: number,
): Promise<boolean> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await predicate()) return true
    await wait(100)
  }
  return predicate()
}

async function account(prefix: string) {
  const username = `pv_${prefix}_${uniqueSuffix()}`
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

/**
 * 建一个**九个座位全是 AI** 的对局。
 *
 * <p>房主必须先离开房间：只要房主坐在 1 号座，那个座位就是 `HUMAN`，
 * 而角色是**服务端随机分配**的——房主若是狼，`NIGHT_WOLF` 就必须等**真人提交**，
 * 驱动会（正确地）一直等下去。
 *
 * <p>我第一版没有离开房间，于是这个测试的通过与否取决于
 * **房主恰好不是狼**，而它不通过时的现象是"游戏不动"——
 * 一个看起来像产品缺陷的现象，实际是测试的隐含假设。
 * 因此这里先离开，并在断言前确认 9 个座位都是 AI。
 */
async function startedGame(prefix: string) {
  const owner = await account(prefix)
  localStorage.setItem(SESSION_TOKEN_KEY, owner.token)
  const roomAdapter = createRealRoomAdapter(() => owner.userId)
  const room = await roomAdapter.create({ name: 'preview 端到端房', visibility: 'PUBLIC' })
  // 离开房主座位，让 9 个座位全部由 AI 补位。
  await roomAdapter.leave(room.roomId).catch(() => {
    // 某些实现里房主离开会关闭房间；那种情况下由 agentCount 断言兜住。
  })
  const started = await startGame(room.roomId, true)
  return { ...owner, gameId: started.gameId, roomId: room.roomId, agentCount: started.agentCount }
}

/** 读一次对局视图的原始 JSON。 */
async function rawView(gameId: string) {
  return (await fetch(`${BASE}/api/v1/games/${gameId}`).then((r) => r.json())) as {
    phase: string
    version: number
    status: string
  }
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



describe('preview 端到端（驱动已能提交行动）', () => {
  test('AI 自动推进游戏：从 NIGHT_WOLF 走到白天（或直接结束）', async () => {
  const session = await startedGame('advance')

  const opening = await rawView(session.gameId)
  expect(opening.status).toBe('RUNNING')
  expect(opening.phase, '开局阶段应为 NIGHT_WOLF').toBe('NIGHT_WOLF')
  /*
   * 前置断言：9 个座位必须全是 AI。
   *
   * 没有它，下面"AI 应把游戏推离夜晚"会在**房主恰好是狼**时失败，
   * 而现象是"游戏不动"——看起来像产品缺陷，实际是测试的隐含假设。
   * 这条断言让那种情况以明确的原因失败。
   */
  expect(session.agentCount, '九个座位都应由 AI 补位（房主需先离开座位）').toBe(9)

  /*
   * 等 AI 推完夜晚。这是**规则模式才能通过**的断言：LLM 模式下 9 个 Agent
   * 各要几秒到几十秒，而且可能超时走兜底。
   *
   * 上界 120 秒只用于让"卡住"以明确的失败呈现，而不是挂到全局超时。
   */
  let phase = opening.phase
  const advanced = await untilAsync(async () => {
    phase = (await rawView(session.gameId)).phase
    return phase !== 'NIGHT_WOLF'
  }, 120_000)

  expect(advanced, `AI 应把游戏推离夜晚（当前阶段 ${phase}）。`
    + '若后端未以 -Dwolfmind.agent.force-rule=true 启动，Agent 会调真实 LLM 而不确定。')
    .toBe(true)
})

  // 不要求 AI 策略必选发言；使用真人的合法命令验证确定的链路。
test('真人已提交发言经真实订阅进入记录，不恢复为预览', async () => {
  async function json(path: string, token: string, body?: unknown) {
    const response = await fetch(`${BASE}/api/v1${path}`, {
      method: body === undefined ? 'GET' : 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    const value = await response.json()
    if (!response.ok) throw new Error(`${path}: ${response.status} ${JSON.stringify(value)}`)
    return value
  }
  const owner = await account('speech')
  localStorage.setItem(SESSION_TOKEN_KEY, owner.token)
  const rooms = createRealRoomAdapter(() => owner.userId)
  const room = await rooms.create({ name: '已提交发言联调', visibility: 'PUBLIC' })
  const participants = [owner]
  for (let seat = 2; seat <= 9; seat++) {
    const participant = await account(`speech${seat}`)
    await json(`/rooms/${room.roomId}/join`, participant.token, {})
    participants.push(participant)
  }
  for (const participant of participants) await json(`/rooms/${room.roomId}/ready`, participant.token, {})
  const session = await startGame(room.roomId, false)
  expect(session.agentCount).toBe(0)
  expect(await untilAsync(async () => {
    const view = await json(`/games/${session.gameId}`, owner.token)
    if (view.phase === 'DAY_DISCUSS') return true
    for (const participant of participants) {
      const own = await json(`/games/${session.gameId}`, participant.token)
      const permission = own.permissions.find((candidate: { type: string }) => ['WOLF_KILL', 'SEER_INSPECT', 'WITCH_PASS'].includes(candidate.type))
      if (permission) {
        const params = permission.type === 'SEER_INSPECT' ? { targetSeatNo: permission.targets[0] } : {}
        await json(`/games/${session.gameId}/actions`, participant.token,
          { actionType: permission.type === 'WOLF_KILL' ? 'SKIP' : permission.type, params, expectedVersion: own.version })
        break
      }
    }
    return false
  }, 15000)).toBe(true)
  const adapter = createRealGameAdapter({ mySeatNo: () => 1 })
  const received: import('../src/features/game/types').GamePresentation[] = []
  unsubscribe = adapter.subscribe(session.gameId, view => received.push(view))

  const gotFirst = await untilAsync(async () => received.length > 0, 10_000)
  expect(gotFirst, 'subscribe 应立刻给一次视图').toBe(true)

  expect(received.at(-1)!.permissions.some(permission => permission.type === 'SPEAK')).toBe(true)
  const content = '这条发言应从已提交事件进入公开记录。'
  await json(`/games/${session.gameId}/actions`, owner.token,
    { actionType: 'SPEAK', params: { content }, expectedVersion: received.at(-1)!.version })
  expect(await untilAsync(async () => received.some(view => view.messages.some(message => message.content === content)), 10000),
    '原始 REST 提交之后，适配器应由 WebSocket 事件触发刷新').toBe(true)
  expect(received.at(-1)!.messages.filter(message => message.content === content)).toHaveLength(1)
  expect(received.at(-1)!.preview).toBeNull()
})
})
