// @vitest-environment node
import { afterAll, beforeAll, describe, expect, test, vi } from 'vitest'
import { createRealRoomAdapter, startGame } from '../src/api/realRoomAdapter'
import { createRealGameAdapter } from '../src/api/realGameAdapter'
import { SESSION_TOKEN_KEY } from '../src/api/config'
import { ApiError } from '../src/api/apiClient'
import { LobbyError } from '../src/features/lobby/types'

/**
 * **前端适配器 × 真实后端**的集成测试。
 *
 * ## 为什么必须有这一层
 *
 * 第 14 轮的端到端冒烟用 Node 的 `fetch` 直接打后端，**没有加载任何前端代码**。
 * 因此它能证明"后端自洽"，但不能证明"前端适配器读的字段是对的"——
 * 而后者恰恰是第 11–13 轮交付的 `realRoomAdapter` / `realGameAdapter` 里最容易错的部分：
 * 字段名、嵌套形状、`ready` 路由的特殊响应体、错误码映射。
 * 这些在 fetch 替身测试里全部由我手写的样本数据决定，**样本错了测试就一起错**。
 *
 * ## 怎么跑
 *
 * 它需要一个真实后端，因此**默认跳过**（`npm test` 不依赖服务）。显式开启：
 * ```
 * WOLFMIND_E2E=1 npm test -- tests/realAdapterIntegration.test.ts
 * ```
 * 后端地址取自 `VITE_API_BASE_URL`（默认 `http://127.0.0.1:8080`），
 * 也可以用 `WOLFMIND_E2E_BASE` 覆盖（例如冒烟脚本用的 18080）。
 */

const BASE = process.env.WOLFMIND_E2E_BASE ?? 'http://127.0.0.1:18080'
vi.stubEnv('VITE_API_BASE_URL', BASE)

/** 目前登录账号；适配器的身份提供者从这里取，与 `main.ts` 的注入方式一致。 */
let currentUserId: string | null = null

const suffix = Math.random().toString(36).slice(2, 10)

/** 安装一个内存 localStorage（node 环境没有），并返回它的入口。 */
function installStorage(): { getItem: (k: string) => string | null; setItem: (k: string, v: string) => void } {
  const store = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
    removeItem: (k: string) => void store.delete(k),
  })
  return {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
  }
}

/**
 * 把会话令牌写进**真实的存储路径**。
 *
 * 不能自己维护一个 Map：`apiClient` 读的是 `globalThis.localStorage`，
 * 两处各存一份时适配器会以未登录身份发请求——而失败信息是"房间操作需要登录"，
 * 看起来像授权问题，实际是测试装了两份存储。
 */
function loginAs(token: string): void {
  localStorage.setItem(SESSION_TOKEN_KEY, token)
}

/** 直接打后端的认证辅助（不经适配器：那部分已由 `AuthContractTest` 覆盖）。 */
async function account(prefix: string) {
  const username = `it_${prefix}_${suffix}`
  const password = 'correct-horse-battery'
  const registered = await fetch(`${BASE}/api/v1/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  if (registered.status !== 201) {
    throw new Error(`注册失败 ${registered.status}: ${await registered.text()}`)
  }
  const loggedIn = await fetch(`${BASE}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  if (loggedIn.status !== 200) {
    throw new Error(`登录失败 ${loggedIn.status}: ${await loggedIn.text()}`)
  }
  const body = (await loggedIn.json()) as { userId: string; sessionToken: string }
  return { userId: body.userId, token: body.sessionToken, username }
}

describe('前端适配器 × 真实后端', () => {
  beforeAll(() => {
    installStorage()
  })

  afterAll(() => {
    vi.unstubAllGlobals()
  })

  test('未登录时 list() 能读到真实房间分页信封', async () => {
    installStorage()
    const adapter = createRealRoomAdapter(() => currentUserId)

    const rooms = await adapter.list()

    // 列表项的形状由后端的 `toSummary()` 决定：字段名对不上时这里会立刻暴露
    // （映射函数读到 undefined 会产出 undefined 字段，而不是抛错）。
    expect(Array.isArray(rooms)).toBe(true)
    for (const room of rooms) {
      expect(typeof room.roomId, 'roomId 必须是字符串').toBe('string')
      expect(room.capacity, 'capacity 必须是 9（后端 capacity 字段）').toBe(9)
      expect(typeof room.occupied, 'occupied 必须是数字').toBe('number')
      expect(typeof room.readyCount, 'readyCount 必须来自后端 readyCount 字段').toBe('number')
      expect(['WAITING', 'PLAYING']).toContain(room.status)
    }
  })

  test('create → get：座位与控制器形状与前端模型对齐', async () => {
    installStorage()
    const owner = await account('owner')
    loginAs(owner.token)
    currentUserId = owner.userId
    const adapter = createRealRoomAdapter(() => currentUserId)

    const created = await adapter.create({ name: '适配器集成房', visibility: 'PUBLIC' })

    expect(created.roomId).toBeTruthy()
    expect(created.ownerSeatNo, '后端 ownerSeatNo 必须被映射').toBe(1)
    expect(created.mySeatNo, '靠 controller.refId 反查我的座位').toBe(1)
    expect(created.seats, '必须是 9 个座位（含空座）').toHaveLength(9)
    expect(created.seats[0]!.isMe, '1 号座应标记为我的座位').toBe(true)
    expect(created.seats[0]!.controller, 'HUMAN 控制者必须被识别').toBe('HUMAN')
    expect(created.seats[1]!.controller, '空座的控制者必须是 null').toBeNull()
    expect(created.canJoin, '已在房内不该显示可加入').toBe(false)

    const fetched = await adapter.get(created.roomId)
    expect(fetched.roomId).toBe(created.roomId)
    expect(fetched.occupied).toBe(1)
    expect(fetched.status).toBe('WAITING')
  })

  test('ready 路由的特殊响应体（{readyCount, allReady, room}）被正确解包', async () => {
    // 这条最难靠样本数据发现：别的写路由返回房间本身，而 /ready 返回一个包装对象。
    // fetch 替身测试用的是我手写的样本——它按我以为的样子写，因此"我以为错了"时它也会通过。
    installStorage()
    const owner = await account('ready')
    loginAs(owner.token)
    currentUserId = owner.userId
    const adapter = createRealRoomAdapter(() => currentUserId)

    const created = await adapter.create({ name: 'ready 解包房', visibility: 'PUBLIC' })
    const afterReady = await adapter.ready(created.roomId)

    expect(afterReady.roomId, '必须解包出 room 而不是读包装对象的字段').toBe(created.roomId)
    expect(afterReady.readyCount, 'readyCount 应变成 1').toBe(1)
  })

  test('错误码原样穿透：缺幂等键之外的真实领域错误', async () => {
    installStorage()
    const owner = await account('errors')
    loginAs(owner.token)
    currentUserId = owner.userId
    const adapter = createRealRoomAdapter(() => currentUserId)
    const created = await adapter.create({ name: '错误码房', visibility: 'PUBLIC' })

    // 未坐满就开局 → 409 NOT_READY。适配器必须把它翻成 LobbyError 且**保留 code**。
    const error = await startGame(created.roomId, true).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(LobbyError)
    expect((error as LobbyError).code).toBe('NOT_READY')
  })

  test('不存在的房间给出 ROOM_NOT_FOUND 而不是通用失败', async () => {
    installStorage()
    const adapter = createRealRoomAdapter(() => null)

    const error = await adapter.get('r_does_not_exist_at_all').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(LobbyError)
    expect((error as LobbyError).code).toBe('ROOM_NOT_FOUND')
  })

  test('对局适配器 get()：真实视图 → GamePresentation 的字段映射', async () => {
    installStorage()
    const owner = await account('game')
    loginAs(owner.token)
    currentUserId = owner.userId
    const roomAdapter = createRealRoomAdapter(() => currentUserId)

    const created = await roomAdapter.create({ name: '对局适配器房', visibility: 'PUBLIC' })
    const roomId = created.roomId
    await roomAdapter.ready(roomId)

    const started = await startGame(roomId, true)
    expect(started.version, '开局产生 11 条事件').toBe(11)

    const gameAdapter = createRealGameAdapter({ mySeatNo: () => 1 })
    const presentation = await gameAdapter.get(started.gameId)

    expect(presentation.gameId).toBe(started.gameId)
    expect(presentation.version, 'version 必须来自后端视图').toBeGreaterThanOrEqual(11)
    expect(presentation.phase, 'NIGHT_WOLF 必须映射成展示阶段').toBe('NIGHT_ACTION')
    expect(presentation.atmosphere, '夜间阶段给出 night 气氛').toBe('night')
    expect(presentation.players, 'players 长度必须来自后端').toHaveLength(9)
    expect(presentation.me.seatNo, 'mySeatNo 注入后应能定位我的座位').toBe(1)
    expect(presentation.aliveCount, '存活数用 alive 计数').toBe(9)
    expect(presentation.permissions, '**permissions[] 必须从后端投影过来**').toBeDefined()

    const replay = await fetch(`${BASE}/api/v1/games/${started.gameId}/replay?from=1&to=${presentation.version}`,
      { headers: { Authorization: `Bearer ${owner.token}` } }).then(response => response.json()) as {
      events: Array<{ type: string; payload: { deadline?: number } }>
    }
    const committedDeadline = replay.events.filter(event => event.type === 'PHASE_CHANGED').at(-1)?.payload.deadline
    expect(presentation.deadline, '截止时间必须等于已提交阶段事件').toBe(committedDeadline)
    expect(presentation.messages).toEqual([])
    expect(presentation.result).toBeNull()
  })

  test('对局适配器在真实后端上访问不存在对局时抛 ApiError', async () => {
    installStorage()
    loginAs('u_1.bogus.secret')
    const gameAdapter = createRealGameAdapter()

    const error = await gameAdapter.get('g_does_not_exist').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
  })
})
