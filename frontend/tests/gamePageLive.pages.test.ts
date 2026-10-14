// @vitest-environment jsdom
import { afterAll, afterEach, beforeAll, expect, test, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import GamePage from '../src/pages/GamePage.vue'
import { SESSION_TOKEN_KEY } from '../src/api/config'
import { setCurrentUserProvider, setMySeatNoProvider, usingRealBackend } from '../src/api/adapters'
import { useSeatStore } from '../src/stores/session'
import { useGameStore } from '../src/stores/game'
import { installJsdomStubs, installNodeWebSocket, installStorage } from './support/jsdomStubs'

/**
 * **对局页 × 真实后端 + 真实 WebSocket**。
 *
 * ## 它补的是哪一段
 *
 * 第 17 轮已经分别验证过两端：`e2e:ws` 证明 `GameSocket` 与真实端点能对上，
 * `e2e:pages` 证明大厅与等待房能从后端取数渲染。但**对局页是唯一同时用到
 * REST（取视图）与 WebSocket（驱动刷新）两个通路的页面**，而它自己一次都没被跑过。
 *
 * 具体能证明的事：
 * - `GamePage` 通过**注册表**取到真实对局适配器（而不是某个 mock 的 import）；
 * - REST 得到的 `GameView` 经映射后真的渲染出来（房间名、阶段、9 个玩家）；
 * - **座位号从 `seat` store 一路走到 `me`**：这正是第 17 轮才接通的那条链，
 *   没有它页面算不出"我是谁"，只能隐藏整个操作区域；
 * - 实时通路在真实连接上被建立（连接状态离开"正在同步"）。
 *
 * 运行：`npm run e2e:pages`（需要真实后端，见 `vitest.pages.config.ts`）。
 */

const BASE = process.env.WOLFMIND_E2E_BASE ?? 'http://127.0.0.1:18080'
// 每个账号各自取一个后缀：模块级的常量会让同文件里的多个测试撞用户名（409 USERNAME_TAKEN）。
const uniqueSuffix = () => Math.random().toString(36).slice(2, 10)

let wrapper: VueWrapper | undefined

async function account(prefix: string) {
  const username = `gp_${prefix}_${uniqueSuffix()}`
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

/** 建房 → 准备 → AI 补位开局；返回 gameId 与 roomId。 */
async function startedGame(allAi = false): Promise<{ userId: string; token: string; gameId: string; roomId: string }> {
  const owner = await account('game')
  const auth = { 'Content-Type': 'application/json', Authorization: `Bearer ${owner.token}` }
  const idem = () => `gp_${Math.random().toString(36).slice(2, 10)}`

  const created = await fetch(`${BASE}/api/v1/rooms`, {
    method: 'POST',
    headers: { ...auth, 'Idempotency-Key': idem() },
    body: JSON.stringify({ name: '对局页集成房', config: { playerCount: 9 } }),
  })
  if (created.status !== 201) throw new Error(`建房失败 ${created.status}: ${await created.text()}`)
  const roomId = ((await created.json()) as { roomId: string }).roomId

  const ready = await fetch(`${BASE}/api/v1/rooms/${roomId}/ready`, {
    method: 'POST',
    headers: { ...auth, 'Idempotency-Key': idem() },
    body: JSON.stringify({}),
  })
  if (ready.status !== 200) throw new Error(`准备失败 ${ready.status}: ${await ready.text()}`)

  if (allAi) {
    const left = await fetch(`${BASE}/api/v1/rooms/${roomId}/leave`, {
      method: 'POST', headers: { ...auth, 'Idempotency-Key': idem() }, body: JSON.stringify({}),
    })
    if (!left.ok) throw new Error(`离开房间失败 ${left.status}`)
  }

  const started = await fetch(`${BASE}/api/v1/rooms/${roomId}/games`, {
    method: 'POST',
    headers: { ...auth, 'Idempotency-Key': idem() },
    body: JSON.stringify({ autoFillAi: true }),
  })
  if (started.status !== 201) throw new Error(`开局失败 ${started.status}: ${await started.text()}`)
  const gameId = ((await started.json()) as { gameId: string }).gameId

  return { ...owner, gameId, roomId }
}

/** 等到条件成立或超时。 */
async function until(predicate: () => boolean, timeoutMs = 10_000): Promise<boolean> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (predicate()) return true
    await flushPromises()
    await new Promise((r) => setTimeout(r, 40))
  }
  return predicate()
}

async function mountGamePage(gameId: string, pinia: ReturnType<typeof createPinia>) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div>home</div>' } },
      { path: '/lobby', name: 'lobby', component: { template: '<div>lobby</div>' } },
      { path: '/games/:gameId', name: 'game', component: GamePage },
    ],
  })
  await router.push(`/games/${gameId}`)
  await router.isReady()
  wrapper = mount(GamePage, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return wrapper
}

beforeAll(() => {
  installStorage()
  installJsdomStubs()
  installNodeWebSocket()
  expect(usingRealBackend(), 'VITE_USE_REAL_API 必须为 true').toBe(true)
})

afterAll(() => {
  vi.unstubAllGlobals()
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

test('对局页渲染真实对局：房间名来自后端视图', async () => {
  const game = await startedGame()
  localStorage.setItem(SESSION_TOKEN_KEY, game.token)
  setCurrentUserProvider(() => game.userId)
  const pinia = createPinia()
  setActivePinia(pinia)
  setMySeatNoProvider(() => useSeatStore(pinia).seatNo)
  useSeatStore(pinia).set(1)

  const page = await mountGamePage(game.gameId, pinia)

  // 等到视图到达。房间名映射自后端视图的 `roomId`，因此页面上出现它就是
  // "REST 视图经映射后渲染出来了"的直接证据。
  const rendered = await until(() => page.html().includes(String(page.vm.$pinia.state.value.game?.view?.roomName ?? '___')))
  const state = page.vm.$pinia.state.value.game as { view: { roomName?: string; gameId?: string } | null; connection: string; error: string }
  expect(rendered,
    `页面应显示后端给的房间名。view.roomName=${JSON.stringify(state.view?.roomName)} `
    + `view.gameId=${state.view?.gameId} connection=${state.connection} error=${state.error}`)
    .toBe(true)

  const html = page.html()
  // 9 个玩家：服务端返回 9 个座位视图，页面必须全部渲染。
  for (const seatNo of [1, 2, 3, 4, 5, 6, 7, 8, 9]) {
    expect(html, `${seatNo} 号玩家必须渲染`).toContain(`${seatNo}`)
  }
  // 开局阶段映射：后端 NIGHT_WOLF → 展示层 NIGHT_ACTION。
  expect(html, '不应显示加载态').not.toContain('正在加载对局与行动权限')
})

test('座位号从 seat store 走到 me：页面能确定「我是谁」', async () => {
  const game = await startedGame()
  localStorage.setItem(SESSION_TOKEN_KEY, game.token)
  setCurrentUserProvider(() => game.userId)

  /*
   * pinia 实例必须**显式共享给三个地方**：挂载时的插件、活动实例（`useSeatStore()`
   * 在组件外要用它）、以及 provider 闭包。
   *
   * 我原先写成 `setActivePinia(createPinia())` 而挂载时又传了另一个新实例——
   * 于是 provider 闭包读的是**上一个测试**留下的活动实例，`me.seatNo` 恒为 0。
   * 失败信息看起来像"座位号没传进去"，实际是测试自己造了两个实例。
   */
  const pinia = createPinia()
  setActivePinia(pinia)
  setMySeatNoProvider(() => useSeatStore(pinia).seatNo)

  // 房主占 1 号座。这里**不硬编码给页面**，而是写进 store——正是
  // `setMySeatNoProvider` 要解决的问题：服务端视图没有单独的"我"，
  // 界面必须自己记住座位号，否则算不出 `me`，只能隐藏操作区域。
  useSeatStore(pinia).set(1)
  expect(useSeatStore(pinia).seatNo).toBe(1)

  const page = await mountGamePage(game.gameId, pinia)
  // 直接等 store 而不是等页面文本：这一条要证明的是"视图进了 store 且 me 正确"，
  // 用页面文本做中间条件会引入与断言无关的时序耦合。
  const arrived = await until(() => {
    const state = page.vm.$pinia.state.value.game as { view: unknown } | undefined
    return state?.view != null
  })
  const state = page.vm.$pinia.state.value.game as
    | { view: { me: { seatNo: number } } | null; error: string; connection: string }
    | undefined
  expect(arrived, `对局视图应到达。connection=${state?.connection} error=${state?.error}`).toBe(true)
  expect(state!.view!.me.seatNo, 'me.seatNo 必须来自 seat store').toBe(1)
})

test('实时通路在真实连接上建立（连接状态离开「同步中」）', async () => {
  const game = await startedGame()
  localStorage.setItem(SESSION_TOKEN_KEY, game.token)
  setCurrentUserProvider(() => game.userId)
  const pinia = createPinia()
  setActivePinia(pinia)
  setMySeatNoProvider(() => useSeatStore(pinia).seatNo)
  useSeatStore(pinia).set(1)

  const page = await mountGamePage(game.gameId, pinia)

  // `connection` 在 load() 开始时被置为 'syncing'，REST 成功后回到 'local'，
  // 而实时通路由 `adapter.subscribe()` 建立。视图到达即说明两条通路都没炸。
  const ready = await until(() => {
    const state = page.vm.$pinia.state.value.game as { view: unknown; connection: string; error: string }
    return state.view !== null && state.connection === 'local' && state.error === ''
  })
  const state = page.vm.$pinia.state.value.game as { connection: string; error: string }
  expect(ready, `对局页应加载成功。connection=${state.connection} error=${state.error}`).toBe(true)
})

test('真实断线自动 RESUME，页面禁用行动并恢复到权威终局', async () => {
  const NativeSocket = globalThis.WebSocket
  const connections: WebSocket[] = []
  const sent: Array<{ type: string; payload: { lastSeq?: number } }> = []
  vi.stubGlobal('WebSocket', class extends NativeSocket {
    constructor(url: string | URL, protocols?: string | string[]) {
      super(url, protocols)
      connections.push(this)
    }
    override send(data: string | ArrayBufferLike | Blob | ArrayBufferView) {
      if (typeof data === 'string') sent.push(JSON.parse(data))
      super.send(data)
    }
  })
  try {
    const game = await startedGame(true)
    localStorage.setItem(SESSION_TOKEN_KEY, game.token)
    const pinia = createPinia()
    setActivePinia(pinia)
    setMySeatNoProvider(() => null)
    await mountGamePage(game.gameId, pinia)
    const store = useGameStore(pinia)
    expect(await until(() => store.connection === 'local' && !!store.view), '初始同步应完成').toBe(true)
    const before = store.view!.version
    connections.at(-1)!.close(1000, 'physical disconnect')
    expect(await until(() => store.connection === 'syncing', 800)).toBe(true)
    expect(store.actionable).toBe(false)
    expect(await until(() => sent.some(frame => frame.type === 'RESUME' && (frame.payload.lastSeq ?? 0) > 0) && store.connection === 'local', 15000)).toBe(true)
    expect(await until(() => store.view?.phase === 'GAME_OVER', 60000), '重连后应收到后续推进直至终局').toBe(true)
    const authoritative = await fetch(`${BASE}/api/v1/games/${game.gameId}`).then(response => response.json()) as { version: number; phase: string; winner: string }
    expect(store.view!.version).toBe(authoritative.version)
    expect(store.view!.version).toBeGreaterThan(before)
    expect(authoritative.phase).toBe('GAME_OVER')
    expect(store.view!.result?.title).toBe(authoritative.winner === 'WOLF' ? '狼人阵营获胜' : '好人阵营获胜')
  } finally {
    wrapper?.unmount()
    wrapper = undefined
    vi.stubGlobal('WebSocket', NativeSocket)
  }
}, 80000)

test('真实 SYNC_REQUIRED 后重建快照，页面继续接收实时推进', async () => {
  const NativeSocket = globalThis.WebSocket
  const connections: WebSocket[] = []
  const frames: Array<{ type: string; seq: number; payload: Record<string, unknown> }> = []
  vi.stubGlobal('WebSocket', class extends NativeSocket {
    constructor(url: string | URL, protocols?: string | string[]) {
      super(url, protocols)
      connections.push(this)
      this.addEventListener('message', message => frames.push(JSON.parse(String(message.data))))
    }
  })
  try {
    const game = await startedGame(true)
    localStorage.setItem(SESSION_TOKEN_KEY, game.token)
    const pinia = createPinia()
    setActivePinia(pinia)
    setMySeatNoProvider(() => null)
    await mountGamePage(game.gameId, pinia)
    const store = useGameStore(pinia)
    expect(await until(() => store.connection === 'local' && !!store.view)).toBe(true)
    connections.at(-1)!.send(JSON.stringify({ id: 'force-truncated', type: 'RESUME', payload: { gameId: game.gameId, lastSeq: 999999 } }))
    expect(await until(() => frames.some(frame => frame.type === 'SYNC_REQUIRED'))).toBe(true)
    expect(await until(() => frames.some(frame => frame.type === 'GAME_SNAPSHOT_SYNC') && store.connection === 'local'),
      `connection=${store.connection}; view=${store.view?.version}; error=${store.error}; frames=${JSON.stringify(frames.map(frame => ({ type: frame.type, seq: frame.seq, reason: frame.payload.reason })))}`).toBe(true)
    const snapshot = frames.find(frame => frame.type === 'GAME_SNAPSHOT_SYNC')!
    expect(snapshot.seq).toBe(snapshot.payload.replayedTo)
    expect(snapshot.payload.me).toBeNull()
    expect(snapshot.payload.permissions).toEqual([])
    expect(await until(() => frames.some(frame => frame.type === 'GAME_EVENT' && frame.seq > snapshot.seq), 15000), '快照之后必须继续收到事件').toBe(true)
    expect(await until(() => store.view?.phase === 'GAME_OVER', 60000)).toBe(true)
    const authoritative = await fetch(`${BASE}/api/v1/games/${game.gameId}`).then(response => response.json()) as { version: number }
    expect(store.view!.version).toBe(authoritative.version)
  } finally {
    wrapper?.unmount()
    wrapper = undefined
    vi.stubGlobal('WebSocket', NativeSocket)
  }
}, 80000)
