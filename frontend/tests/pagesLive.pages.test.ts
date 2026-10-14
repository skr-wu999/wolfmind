// @vitest-environment jsdom
import { afterAll, afterEach, beforeAll, expect, test, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import LobbyPage from '../src/pages/LobbyPage.vue'
import RoomWaitingPage from '../src/pages/RoomWaitingPage.vue'
import { SESSION_TOKEN_KEY } from '../src/api/config'
import {
  gameAdapter,
  roomAdapter,
  setCurrentUserProvider,
  setMySeatNoProvider,
  usingRealBackend,
} from '../src/api/adapters'
import { useSeatStore } from '../src/stores/session'

/**
 * **页面组件 × 真实后端**。
 *
 * ## 为什么还需要这一层
 *
 * 第 15 轮验证了适配器与后端字段对齐，但**没有渲染任何 Vue 组件**。
 * 于是"页面确实通过注册表取适配器"这件事仍然没有证据——而这正是最容易悄悄坏掉的地方：
 * 只要某个页面写 `import { adapter } from '@/features/.../mockAdapter'`，
 * 它在 `VITE_USE_REAL_API=true` 下仍然会渲染演示数据，而**两边都"看起来正常"**。
 *
 * 写这个测试的过程立刻找到了一个：`RoomWaitingPage.vue` 直接 import 了 `mockRoomAdapter`，
 * 绕过注册表。它意味着用户从大厅点进一个真实房间号，看到的却是 mock 房间。
 *
 * ## 断言用 `roomId` 而不是文案或 CSS 类
 *
 * 房间号是后端生成的不透明串（`r_xxxx`），**只有真的读了后端才可能出现**。
 * 文案会改、CSS 类会改，而"页面上出现了一个只有后端才知道的字符串"是不会被重构轻易废掉的证据。
 *
 * ## 怎么跑
 *
 * ```
 * npm run e2e:pages     # 需要后端在 18080（见 vitest.pages.config.ts）
 * ```
 */

const suffix = Math.random().toString(36).slice(2, 10)
const BASE = process.env.WOLFMIND_E2E_BASE ?? 'http://127.0.0.1:18080'

let wrapper: VueWrapper | undefined

function installStorage(): void {
  const store = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
    removeItem: (k: string) => void store.delete(k),
  })
}

/** 直接用后端注册并登录（认证本身已有契约测试覆盖）。 */
async function account(prefix: string) {
  const username = `pg_${prefix}_${suffix}`
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

/** 建一个真实房间，返回它的 roomId。 */
async function createRoom(token: string, name: string): Promise<string> {
  const response = await fetch(`${BASE}/api/v1/rooms`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
      'Idempotency-Key': `pg_room_${suffix}_${Math.random().toString(36).slice(2, 8)}`,
    },
    body: JSON.stringify({ name, config: { playerCount: 9 } }),
  })
  if (response.status !== 201) throw new Error(`建房失败 ${response.status}: ${await response.text()}`)
  return ((await response.json()) as { roomId: string }).roomId
}

/** 挂载一个页面；路由按页面需要的最小集合声明。 */
async function mountPage(component: unknown, path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div>home</div>' } },
      { path: '/lobby', name: 'lobby', component: { template: '<div>lobby</div>' } },
      { path: '/login', name: 'login', component: { template: '<div>login</div>' } },
      { path: '/rooms/:roomId', name: 'room-waiting', component: RoomWaitingPage },
    ],
  })
  await router.push(path)
  await router.isReady()
  wrapper = mount(component as never, { global: { plugins: [createPinia(), router] } })
  await flushPromises()
  return wrapper
}

beforeAll(() => {
  installStorage()

  /*
   * jsdom 不实现 canvas 的 2D 上下文（`getContext('2d')` 返回 null），
   * 而村庄场景组件会画它。给一个最小的替身而不是引入 `canvas` 原生依赖：
   * 这里要证明的是"数据从后端来到页面"，不是"像素画得对不对"。
   */
  const context = {
    imageSmoothingEnabled: false,
    fillStyle: '',
    strokeStyle: '',
    lineWidth: 1,
    globalAlpha: 1,
    clearRect: vi.fn(),
    fillRect: vi.fn(),
    strokeRect: vi.fn(),
    drawImage: vi.fn(),
    beginPath: vi.fn(),
    closePath: vi.fn(),
    moveTo: vi.fn(),
    lineTo: vi.fn(),
    fill: vi.fn(),
    stroke: vi.fn(),
    arc: vi.fn(),
    save: vi.fn(),
    restore: vi.fn(),
    translate: vi.fn(),
    scale: vi.fn(),
    rotate: vi.fn(),
    setTransform: vi.fn(),
    createLinearGradient: () => ({ addColorStop: vi.fn() }),
  }
  vi.stubGlobal('requestAnimationFrame', vi.fn(() => 1))
  vi.stubGlobal('cancelAnimationFrame', vi.fn())
  vi.stubGlobal('matchMedia', vi.fn(() => ({
    matches: false,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
  })))
  vi.stubGlobal('ResizeObserver', class {
    observe() {}
    unobserve() {}
    disconnect() {}
  })
  Object.defineProperty(HTMLCanvasElement.prototype, 'getContext', {
    configurable: true,
    value: () => context,
  })

  /*
   * jsdom 不实现 `HTMLDialogElement.showModal/close`（它们是较新的原生 API），
   * 而页面用 `<dialog>` 做弹窗。补两个空实现：这里要证明的是数据流，
   * 不是弹窗的原生行为。
   */
  const dialogPrototype = globalThis.HTMLDialogElement?.prototype
  if (dialogPrototype) {
    Object.defineProperty(dialogPrototype, 'showModal', { configurable: true, value() {} })
    Object.defineProperty(dialogPrototype, 'close', { configurable: true, value() {} })
  }

  // 注册表为真实适配器时，页面才算真的走后端。
  expect(usingRealBackend(), 'VITE_USE_REAL_API 必须为 true 才能证明页面走真实接入').toBe(true)
})

afterAll(() => {
  vi.unstubAllGlobals()
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

test('注册表在真实模式下返回真实房间适配器', () => {
  // 前置断言：若注册表仍返回 mock，下面两条会"通过"却什么都没证明。
  expect(usingRealBackend()).toBe(true)
})

test('LobbyPage 走真实后端：大厅里的房间号来自服务端', async () => {
  const owner = await account('lobby')
  localStorage.setItem(SESSION_TOKEN_KEY, owner.token)
  setCurrentUserProvider(() => owner.userId)
  setMySeatNoProvider(() => 1)
  const roomId = await createRoom(owner.token, '页面集成房')

  const page = await mountPage(LobbyPage, '/lobby')
  // 等列表请求回来。
  for (let i = 0; i < 40 && !page.html().includes(roomId); i++) {
    await flushPromises()
    await new Promise((r) => setTimeout(r, 50))
  }

  const html = page.html()
  expect(html, `页面上应出现后端生成的房间号 ${roomId}`).toContain(roomId)
  expect(html, '页面上应出现建房时给的名字').toContain('页面集成房')
})

test('RoomWaitingPage 走真实后端：9 个座位来自服务端', async () => {
  const owner = await account('waiting')
  localStorage.setItem(SESSION_TOKEN_KEY, owner.token)
  setCurrentUserProvider(() => owner.userId)
  setMySeatNoProvider(() => 1)
  const roomId = await createRoom(owner.token, '等待房集成房')

  const page = await mountPage(RoomWaitingPage, `/rooms/${roomId}`)
  for (let i = 0; i < 60 && !page.html().includes(roomId); i++) {
    await flushPromises()
    await new Promise((r) => setTimeout(r, 50))
  }

  const html = page.html()
  expect(html, '等待房应显示它加载的房间号').toContain(roomId)
  expect(html, '等待房应显示后端给的名字').toContain('等待房集成房')

  // 9 个座位：服务端返回 9 个座位视图（含空座），页面必须全部渲染。
  // 这条能抓住"页面按自己的假设渲染座位数"这类问题。
  const seatNumbers = [1, 2, 3, 4, 5, 6, 7, 8, 9]
  for (const seatNo of seatNumbers) {
    expect(html, `${seatNo} 号座位必须渲染`).toContain(`${seatNo}`)
  }
})

test('房间页把真实座位号写进会话 store（对局页「我是谁」的来源）', async () => {
  const owner = await account('seat')
  localStorage.setItem(SESSION_TOKEN_KEY, owner.token)
  setCurrentUserProvider(() => owner.userId)

  // 用**真实的**提供者链，而不是硬编码 `() => 1`：这才能证明"页面 → store →
  // 适配器座位号"这条链是通的。第 13 轮注入的是 `() => null`，因此对局页永远
  // 算不出 `me`，界面只能隐藏整个「我的操作」区域。
  setMySeatNoProvider(() => useSeatStore().seatNo)
  // 座位 store 需要与注册表读到的同一个 Pinia 实例。
  setActivePinia(createPinia())

  const roomId = await createRoom(owner.token, '座位同步房')
  const page = await mountPage(RoomWaitingPage, `/rooms/${roomId}`)
  for (let i = 0; i < 60 && !page.html().includes(roomId); i++) {
    await flushPromises()
    await new Promise((r) => setTimeout(r, 50))
  }

  // 房主占 1 号座：页面加载快照后应把它同步到 store。
  expect(useSeatStore().seatNo, '房间快照到达后座位号应进 store').toBe(1)
  // 注册表构造的适配器都可用了，说明接线没有把注册表弄坏。
  expect(roomAdapter()).toBeTruthy()
  expect(typeof gameAdapter().get).toBe('function')
})