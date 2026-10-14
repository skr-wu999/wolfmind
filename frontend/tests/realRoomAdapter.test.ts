import { afterEach, describe, expect, test, vi } from 'vitest'
import { toDemoRoom, toDemoRoomSummary, toDemoStatus } from '../src/api/mapRoom'
import { createRealRoomAdapter, startGame } from '../src/api/realRoomAdapter'
import { SESSION_TOKEN_KEY } from '../src/api/config'
import { LobbyError } from '../src/features/lobby/types'
import type { RoomView } from '../src/api/restTypes'

/**
 * 映射层与真实房间适配器。
 *
 * 这一层最容易出的两类问题，各有一组断言守着：
 * 1. **映射撒谎**：后端没有的字段被编出一个值，界面上显示成"服务端给的"假信息。
 * 2. **错误码被改写**：界面按 `LobbyError.code` 分支（mock 适配器就是这么写的），
 *    如果真实适配器把 `NOT_READY` 翻成别的字，同一件事会有两种叫法，
 *    而界面逻辑迟早按错的那套分支。
 */

const OWNER = 'u_owner'

/** 一个后端房间视图样本；字段与 api-spec.md §4 一致。 */
function roomView(overrides: Partial<RoomView> = {}): RoomView {
  return {
    roomId: 'r_1',
    name: '测试房',
    status: 'WAITING',
    ownerId: OWNER,
    ownerSeatNo: 1,
    occupied: 2,
    capacity: 9,
    readyCount: 1,
    seats: [
      { seatNo: 1, playerId: 'p_1', controller: { type: 'HUMAN', refId: OWNER }, ready: true },
      { seatNo: 2, playerId: 'agent_02', controller: { type: 'AI', refId: 'agent_02' }, ready: true },
      { seatNo: 3, playerId: null, controller: null, ready: false },
    ],
    config: {},
    gameId: null,
    closedReason: null,
    ...overrides,
  }
}

describe('toDemoStatus', () => {
  test('后端的 RUNNING 映射为展示层的 PLAYING', () => {
    // 后端用 RUNNING、前端用 PLAYING，这是刻意分开的两个词：
    // 展示层不该因为接口改了一个枚举名就跟着改。
    expect(toDemoStatus('RUNNING')).toBe('PLAYING')
    expect(toDemoStatus('WAITING')).toBe('WAITING')
    expect(toDemoStatus('CLOSED')).toBe('CLOSED')
  })
})

describe('toDemoRoom', () => {
  test('resolved server rules reach the waiting room presentation', () => {
    const rules = { winCondition: 'WOLVES_OUTNUMBER', witchSelfSave: false }
    expect(toDemoRoom(roomView({ rules }), OWNER).rules).toEqual(rules)
  })
  test('按 controller.refId 算出哪个座位是我', () => {
    const room = toDemoRoom(roomView(), OWNER)

    // playerId 是座位标识（p_1..p_9），不是账号——靠 refId 反查才对。
    expect(room.mySeatNo).toBe(1)
    expect(room.seats[0]!.isMe).toBe(true)
    expect(room.seats[1]!.isMe).toBe(false)
  })

  test('非参与者时 mySeatNo 为 null，且所有座位都不是我的', () => {
    const room = toDemoRoom(roomView(), 'u_outsider')

    expect(room.mySeatNo).toBeNull()
    expect(room.seats.every((seat) => !seat.isMe)).toBe(true)
  })

  test('未登录（null）时不把任何座位当成我的', () => {
    const room = toDemoRoom(roomView(), null)

    expect(room.mySeatNo).toBeNull()
  })

  test('空座不显示名字、不显示控制者', () => {
    const room = toDemoRoom(roomView(), OWNER)

    const empty = room.seats.find((seat) => seat.seatNo === 3)!
    expect(empty.name).toBeNull()
    expect(empty.controller).toBeNull()
    expect(empty.ready).toBe(false)
  })

  test('AI 座位标记为 AI 而不是 HUMAN', () => {
    const room = toDemoRoom(roomView(), OWNER)

    expect(room.seats.find((seat) => seat.seatNo === 2)!.controller).toBe('AI')
  })

  test('可加入的判据是"未开始 + 未满 + 我不在房里"', () => {
    expect(toDemoRoom(roomView({ occupied: 2 }), null).canJoin).toBe(true)
    // 我已入座时不该再显示"加入"。
    expect(toDemoRoom(roomView({ occupied: 2 }), OWNER).canJoin).toBe(false)
    // 已开局。
    expect(toDemoRoom(roomView({ status: 'RUNNING' }), null).canJoin).toBe(false)
    // 已满。
    expect(toDemoRoom(roomView({ occupied: 9 }), null).canJoin).toBe(false)
  })

  test('后端没有的展示字段留空，不编造', () => {
    const room = toDemoRoom(roomView(), OWNER)

    // api-spec.md §4 的房间视图没有 description 与 visibility。
    // 编一个"经典九人局"式的描述会让用户以为那是服务端信息。
    expect(room.description).toBe('')
    expect(room.visibility).toBe('PUBLIC')
    // 名字缺失时回落到 roomId，而不是显示空白。
    expect(toDemoRoom(roomView({ name: null }), OWNER).name).toBe('r_1')
  })

  test('头像按座位号固定分配，同一座位每次渲染相同', () => {
    const first = toDemoRoom(roomView(), OWNER)
    const second = toDemoRoom(roomView(), OWNER)

    // 随机会让每次渲染换脸，而头像的作用是让用户认出"这是刚才那个人"。
    expect(first.seats.map((s) => s.avatar)).toEqual(second.seats.map((s) => s.avatar))
  })

  test('插画按房间号固定分配', () => {
    expect(toDemoRoom(roomView(), OWNER).illustration)
      .toBe(toDemoRoom(roomView(), OWNER).illustration)
  })
})

describe('toDemoRoomSummary', () => {
  test('列表项不含座位明细，因此不假装知道我的座位', () => {
    const summary = toDemoRoomSummary(roomView())

    expect(summary.roomId).toBe('r_1')
    expect(summary.occupied).toBe(2)
    expect((summary as { seats?: unknown }).seats).toBeUndefined()
  })
})

describe('createRealRoomAdapter', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  /** 安装一个返回固定响应的 fetch 替身。 */
  function stubResponse(status: number, body: unknown) {
    const text = body === undefined ? '' : JSON.stringify(body)
    const spy = vi.fn(async (_url: string, _init?: RequestInit) => ({
      ok: status >= 200 && status < 300,
      status,
      text: async () => text,
      headers: new Headers(),
    }) as unknown as Response)
    vi.stubGlobal('fetch', spy)
    const store = new Map<string, string>([[SESSION_TOKEN_KEY, 'u_1.s_1.secret']])
    vi.stubGlobal('localStorage', {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => void store.set(k, v),
      removeItem: (k: string) => void store.delete(k),
    })
    return spy
  }

  function lastRequest(spy: ReturnType<typeof vi.fn>): [string, RequestInit] {
    const call = spy.mock.calls.at(-1)!
    return [call[0] as string, call[1] as RequestInit]
  }

  test('list 走 GET /rooms 的公开分页，不带令牌', async () => {
    const spy = stubResponse(200, { items: [roomView()], page: 1, pageSize: 50, total: 1 })

    const rooms = await createRealRoomAdapter(() => null).list()

    const [url, init] = lastRequest(spy)
    expect(url).toContain('/api/v1/rooms')
    expect(url).toContain('page=1')
    // 只读接口允许匿名（api-spec.md §1），带令牌反而会让公共大厅依赖登录状态。
    expect((init.headers as Record<string, string>).Authorization).toBeUndefined()
    expect(rooms).toHaveLength(1)
    expect(rooms[0]!.roomId).toBe('r_1')
  })

  test('列表保留进行中对局及观战ID，排除已关闭房间', async () => {
    const spy = stubResponse(200, { items: [roomView(), { ...roomView(), roomId: 'r_playing', status: 'RUNNING', gameId: 'g_watch' }, { ...roomView(), roomId: 'r_closed', status: 'CLOSED' }] })
    const rooms = await createRealRoomAdapter(() => null).list()
    expect(lastRequest(spy)[0]).not.toContain('status=WAITING')
    expect(rooms.map(room => room.roomId)).toEqual(['r_1', 'r_playing'])
    expect(rooms[1]).toMatchObject({ gameId: 'g_watch', status: 'PLAYING', canJoin: false })
  })

  test('create 带 Idempotency-Key', async () => {
    const spy = stubResponse(201, roomView())

    await createRealRoomAdapter(() => OWNER).create({ name: '新房', visibility: 'PUBLIC' })

    const [url, init] = lastRequest(spy)
    expect(url).toBe('http://127.0.0.1:8080/api/v1/rooms')
    expect(init.method).toBe('POST')
    // 后端缺这个头会返回 400（api-spec.md §1）。
    expect((init.headers as Record<string, string>)['Idempotency-Key']).toMatch(/^room_/)
  })

  test('每次写操作生成不同的幂等键', async () => {
    const spy = stubResponse(200, roomView())
    const adapter = createRealRoomAdapter(() => OWNER)

    await adapter.join('r_1')
    const first = (lastRequest(spy)[1].headers as Record<string, string>)['Idempotency-Key']
    await adapter.join('r_1')
    const second = (lastRequest(spy)[1].headers as Record<string, string>)['Idempotency-Key']

    // 相同会让后端的重复提交保护把两次用户意图合并，表现是"点了没反应"。
    expect(first).not.toBe(second)
  })

  test('ready 从 {readyCount, allReady, room} 里取 room', async () => {
    // 这个路由的响应形状与其它写路由不同（api-spec.md §4），因此单独一条断言守着它。
    stubResponse(200, { readyCount: 2, allReady: false, room: roomView({ readyCount: 2 }) })

    const room = await createRealRoomAdapter(() => OWNER).ready('r_1')

    expect(room.readyCount).toBe(2)
    expect(room.roomId).toBe('r_1')
  })

  test('setAi(false) 走 DELETE', async () => {
    const spy = stubResponse(200, roomView())

    await createRealRoomAdapter(() => OWNER).setAi('r_1', 3, false)

    expect(lastRequest(spy)[1].method).toBe('DELETE')
  })

  test('后端错误翻成带原始 code 的 LobbyError', async () => {
    stubResponse(409, { code: 'NOT_READY', message: '还有 1 个座位未就绪', details: [] })

    const error = await createRealRoomAdapter(() => OWNER).join('r_1').catch((e) => e)

    expect(error).toBeInstanceOf(LobbyError)
    // code 必须原样保留：界面按它分支，而 mock 适配器用的是同一套码。
    expect(error.code).toBe('NOT_READY')
    // 文案来自后端，前端不该重写它——否则同一个原因会有两种说法。
    expect(error.message).toBe('还有 1 个座位未就绪')
  })

  test('房间不存在翻成 ROOM_NOT_FOUND', async () => {
    stubResponse(404, { code: 'ROOM_NOT_FOUND', message: '房间不存在' })

    const error = await createRealRoomAdapter(() => OWNER).get('r_nope').catch((e) => e)

    expect(error.code).toBe('ROOM_NOT_FOUND')
  })
})

describe('startGame', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  test('POST /rooms/{id}/games 并返回 gameId 与版本', async () => {
    const spy = vi.fn(async (_url: string, _init?: RequestInit) => ({
      ok: true,
      status: 201,
      text: async () =>
        JSON.stringify({
          gameId: 'g_r_1',
          status: 'RUNNING',
          version: 11,
          phase: 'NIGHT_WOLF',
          agentCount: 8,
          roomId: 'r_1',
        }),
      headers: new Headers(),
    }) as unknown as Response)
    vi.stubGlobal('fetch', spy)
    const store = new Map<string, string>([[SESSION_TOKEN_KEY, 'u_1.s_1.secret']])
    vi.stubGlobal('localStorage', {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => void store.set(k, v),
      removeItem: (k: string) => void store.delete(k),
    })

    const result = await startGame('r_1', true)

    const call = spy.mock.calls.at(-1)!
    expect(call[0]).toBe('http://127.0.0.1:8080/api/v1/rooms/r_1/games')
    expect(JSON.parse((call[1] as RequestInit).body as string)).toEqual({ autoFillAi: true })
    expect(result.gameId).toBe('g_r_1')
    expect(result.agentCount).toBe(8)
  })

  test('开局失败时抛 LobbyError 并保留 code', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: false,
      status: 409,
      text: async () => JSON.stringify({ code: 'GAME_ALREADY_STARTED', message: '已开局' }),
      headers: new Headers(),
    }) as unknown as Response))
    const store = new Map<string, string>([[SESSION_TOKEN_KEY, 'u_1.s_1.secret']])
    vi.stubGlobal('localStorage', {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => void store.set(k, v),
      removeItem: (k: string) => void store.delete(k),
    })

    const error = await startGame('r_1').catch((e) => e)

    expect(error).toBeInstanceOf(LobbyError)
    expect(error.code).toBe('GAME_ALREADY_STARTED')
  })
})
