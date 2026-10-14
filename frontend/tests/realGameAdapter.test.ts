import { afterEach, describe, expect, test, vi } from 'vitest'
import { toGamePresentation, toPermission } from '../src/api/mapGame'
import { createRealGameAdapter, SUBMITTABLE_ACTIONS } from '../src/api/realGameAdapter'
import { SESSION_TOKEN_KEY, setSessionToken } from '../src/api/config'
import type { GameView } from '../src/api/restTypes'
import type { AppliedEvent } from '../src/api/gameStream'

/**
 * 对局视图映射与真实对局适配器。
 *
 * 这一层最容易出的问题有两类，各有一组断言守着：
 * 1. **编造服务端没给的字段**：对局界面上的假信息（比如"还剩 3 人"、一个假的倒计时）
 *    会让用户做出错误决策，比留空糟得多。
 * 2. **前端自算权限**：后端已经把 `permissions[]` 投影好了，前端再算一遍会在阶段边界上
 *    与服务端不一致，那时按钮可点却报错。
 */

function gameView(overrides: Partial<GameView> = {}): GameView {
  return {
    gameId: 'g_r_1',
    roomId: 'r_1',
    status: 'RUNNING',
    phase: 'DAY_VOTE',
    round: 2,
    version: 42,
    players: [
      { playerId: 'p_1', seatNo: 1, role: 'WEREWOLF', camp: 'WOLF', alive: true, controller: { type: 'HUMAN', refId: 'u_1' } },
      { playerId: 'p_2', seatNo: 2, role: null, camp: null, alive: true, controller: { type: 'HUMAN', refId: 'u_2' } },
      { playerId: 'p_3', seatNo: 3, role: null, camp: null, alive: false, controller: { type: 'AI', refId: 'agent_03' } },
    ],
    permissions: [
      { type: 'VOTE', targets: [1, 2], allowEmpty: false, remaining: null },
      { type: 'SKIP', targets: [], allowEmpty: true, remaining: null },
    ],
    ...overrides,
  }
}

test('chat projection orders authorized public phase, turn, speech and death facts by event version', () => {
  const event = (seq: number, type: string, payload: Record<string, unknown>, round = 2): AppliedEvent => ({ seq, type, payload, round, phase: 'DAY_DISCUSS', occurredAt: seq * 1000, actorId: '' })
  const phaseEvent = { ...event(1, 'PHASE_CHANGED', { from: 'DAY_ANNOUNCE', to: 'DAY_DISCUSS' }), phase: 'DAY_ANNOUNCE' }
  const projected = toGamePresentation(gameView(), 1, [event(4, 'DEATH_ANNOUNCED', { deaths: [{ seatNo: 3 }] }), event(2, 'TURN_GRANTED', { seatNo: 1 }), event(3, 'PLAYER_SPOKE', { seatNo: 1, speechId: 'accepted', content: '公开发言' }), phaseEvent])
  expect(projected.messages[0]).toMatchObject({ id: 'accepted', order: 3, round: 2 })
  expect(projected.records.map(record => record.order)).toEqual([1, 2, 4])
  expect(projected.records.map(record => record.text)).toEqual(['白天讨论', '轮到 1 号发言', '3 号已出局'])
})
test('future events, unrevealed night actions and another seer result never become public chat records', () => {
  const event = (seq: number, type: string, payload: Record<string, unknown>, phase = 'NIGHT_SEER'): AppliedEvent => ({ seq, type, payload, round: 2, phase, occurredAt: seq, actorId: '' })
  const projected = toGamePresentation(gameView({ version: 4 }), 1, [event(1, 'TURN_GRANTED', { seatNo: 2 }), event(2, 'SEER_INSPECTED', { seerSeatNo: 2, targetSeatNo: 3, result: 'WOLF' }), event(3, 'PLAYER_KILLED', { seatNo: 3, cause: 'WOLF_KILL' }), event(5, 'PLAYER_SPOKE', { seatNo: 1, content: '未来发言' })])
  expect(projected.records).toEqual([]); expect(projected.privateRecords).toEqual([]); expect(projected.messages).toEqual([])
})
test('authorized WOLF_CHAT_SENT events become wolf messages, never public chat', () => {
  const event = (seq: number, type: string, payload: Record<string, unknown>): AppliedEvent => ({ seq, type, payload, round: 2, phase: 'NIGHT_WOLF', occurredAt: seq, actorId: '' })
  const projected = toGamePresentation(gameView({ phase: 'NIGHT_WOLF', version: 3 }), 1, [
    event(2, 'WOLF_CHAT_SENT', { wolfId: 'p_1', wolfSeatNo: 1, chatId: 'g_r_1:2', content: '刀 2 号' }),
    event(4, 'WOLF_CHAT_SENT', { wolfId: 'p_1', wolfSeatNo: 1, chatId: 'g_r_1:4', content: '未来消息' }),
  ])
  expect(projected.wolfMessages).toEqual([{ id: 'g_r_1:2', seatNo: 1, content: '刀 2 号', scope: 'WOLF', round: 2, order: 2 }])
  expect(projected.messages).toEqual([]); expect(projected.records).toEqual([])
})
test('the authorized personal seer result carries timeline metadata only in the private projection', () => {
  const projected = toGamePresentation(gameView(), 1, [{ seq: 4, type: 'SEER_INSPECTED', payload: { seerSeatNo: 1, targetSeatNo: 2, result: 'WOLF' }, round: 2, phase: 'NIGHT_SEER', occurredAt: 1000, actorId: '' }])
  expect(projected.privateRecords[0]).toMatchObject({ order: 4, round: 2, scope: 'PRIVATE', text: '2 号的阵营：狼人' })
  expect(projected.records).toEqual([])
})

describe('toPermission', () => {
  test('可提交动作被转换，label 查展示层的文案表', () => {
    const permission = toPermission({ type: 'VOTE', targets: [1, 2], allowEmpty: false, remaining: 1 })

    expect(permission!.type).toBe('VOTE')
    // 后端只给动作名，中文文案由展示层的 `actionNames` 提供。
    // 文案与 mock 适配器保持一致（"确认投票"而不是"投票"）——同一个动作在两种接入模式下
    // 必须是同一个词，否则用户会以为切换后端后功能变了。
    expect(permission!.label).toBe('确认投票')
    expect(permission!.targets).toEqual([1, 2])
    expect(permission!.remaining).toBe(1)
  })

  test('每个可提交动作都有文案（新增动作时编译期就会报缺项）', () => {
    // 逐条断言，把问题挡在测试阶段；同时确认文案与动作名不同——
    // 相同就说明查表没生效（回退成了用动作名当标签）。
    for (const type of SUBMITTABLE_ACTIONS) {
      const permission = toPermission({ type, targets: [], allowEmpty: null, remaining: null })
      expect(permission!.label, `动作 ${type} 缺少文案`).toBeTruthy()
      expect(permission!.label, `动作 ${type} 的文案回退成了动作名`).not.toBe(type)
    }
  })

  test('仅预览的类型被过滤掉（它们不可提交）', () => {
    // 前端的 GameActionType 含 GUARD_PREVIEW，它只用于预览。
    // 不过滤的话界面会出现一个点了就报错的按钮。
    expect(toPermission({ type: 'GUARD_PREVIEW', targets: [], allowEmpty: null, remaining: null })).toBeNull()
    expect(toPermission({ type: 'WOLF_CHAT_PREVIEW', targets: [], allowEmpty: null, remaining: null })).toBeNull()
  })

  test('WOLF_CHAT 是可提交动作，remaining 为本夜剩余条数', () => {
    expect(toPermission({ type: 'WOLF_CHAT', targets: [], allowEmpty: null, remaining: 20 })).toMatchObject({ type: 'WOLF_CHAT', label: '发送密谈', remaining: 20 })
  })

  test('目录外的动作类型被过滤，不猜成别的动作', () => {
    expect(toPermission({ type: 'TELEPORT', targets: [], allowEmpty: null, remaining: null })).toBeNull()
  })

  test('SUBMITTABLE_ACTIONS 与后端 §5.3 的动作表一致', () => {
    // api-spec.md §5.3 列出 10 个动作。多一个会造出后端不认的动作，少一个会让功能点不出来。
    expect([...SUBMITTABLE_ACTIONS].sort()).toEqual(
      ['HUNTER_SHOOT', 'SEER_INSPECT', 'SKIP', 'SPEAK', 'VOTE', 'WITCH_PASS', 'WITCH_POISON', 'WITCH_SAVE', 'WOLF_CHAT', 'WOLF_KILL'].sort(),
    )
  })
})

describe('toGamePresentation', () => {
  test('the displayed rules come from this game', () => {
    const rules = { winCondition: 'WOLVES_OUTNUMBER', witchSelfSave: false }
    expect(toGamePresentation(gameView({ rules }), null).rules).toEqual(rules)
  })
  test.each(['ABORTED', 'FINISHED'] as const)('terminal %s shows results even when server keeps the last phase', status => {
    const view = toGamePresentation(gameView({ status, phase: 'NIGHT_WOLF', permissions: [],
      finishReason: 'NO_HUMANS', winner: null }), null)
    expect(view.phase).toBe('GAME_OVER')
    expect(view.deadline).toBeNull()
    expect(view.speakerSeatNo).toBeNull()
    expect(view.atmosphere).toBe('day')
    if (status === 'ABORTED') expect(view.result?.content).toContain('所有真人已离开')
  })
  test('阶段映射覆盖后端全部十个阶段', () => {
    const phases: Record<string, string> = {
      NIGHT_WOLF: 'NIGHT_ACTION',
      NIGHT_SEER: 'NIGHT_ACTION',
      NIGHT_WITCH: 'NIGHT_ACTION',
      NIGHT_SETTLE: 'NIGHT_RESOLVING',
      DAY_ANNOUNCE: 'DAYBREAK',
      LAST_WORDS: 'LAST_WORDS',
      DAY_DISCUSS: 'DAY_DISCUSSION',
      DAY_VOTE: 'DAY_VOTING',
      VOTE_SETTLE: 'VOTE_RESULT',
      GAME_OVER: 'GAME_OVER',
    }
    for (const [server, display] of Object.entries(phases)) {
      expect(toGamePresentation(gameView({ phase: server }), null).phase).toBe(display)
    }
  })

  test('未知阶段保守地映射为等待，而不是"白天讨论"', () => {
    // 映射成"白天讨论"会让界面显示成可以发言，而实际上阶段未知。
    expect(toGamePresentation(gameView({ phase: 'FUTURE_PHASE' }), null).phase).toBe('NIGHT_WAITING')
  })

  test('夜晚阶段给出 night 气氛，白天阶段给出 day', () => {
    expect(toGamePresentation(gameView({ phase: 'NIGHT_WOLF' }), null).atmosphere).toBe('night')
    expect(toGamePresentation(gameView({ phase: 'DAY_VOTE' }), null).atmosphere).toBe('day')
    expect(toGamePresentation(gameView({ phase: 'GAME_OVER' }), null).atmosphere).toBe('day')
  })

  test('角色只按服务端下发的披露，不从存活状态推断', () => {
    const view = toGamePresentation(gameView(), null)

    // 座位 1 的角色对当前读取者可见。
    expect(view.players.find((p) => p.seatNo === 1)!.disclosedRole).toBe('WEREWOLF')
    // 座位 2 的 role 为 null 表示"服务端没给我看"。**不编一个"村民"**——
    // 猜错等于把隐藏信息当成公开信息展示。
    expect(view.players.find((p) => p.seatNo === 2)!.disclosedRole).toBeUndefined()
  })

  test('狼队只由"谁的角色对我可见"推导', () => {
    // 能看到 WEREWOLF 就说明我是狼；不做额外的角色判断。
    expect(toGamePresentation(gameView(), null).wolfTeam).toEqual([1])
  })

  test('存活数用 alive 计数，而不是总人数', () => {
    const view = toGamePresentation(gameView(), null)

    expect(view.aliveCount).toBe(2)
    expect(view.eliminatedCount).toBe(1)
  })

  test('permissions 直接用服务端投影，前端不追加', () => {
    const view = toGamePresentation(gameView(), null)

    expect(view.permissions.map((p) => p.type)).toEqual(['VOTE', 'SKIP'])
    // 服务端说只有这两个可做，前端就只显示这两个。
  })

  test('me 按 mySeatNo 定位；观战者为"无座位"', () => {
    const asSeat1 = toGamePresentation(gameView(), 1)
    expect(asSeat1.me.seatNo).toBe(1)
    expect(asSeat1.me.role).toBe('WEREWOLF')
    expect(asSeat1.me.alive).toBe(true)

    // 观战者：seatNo 为 0 是明确的"无座位"标记，界面据此隐藏"我的操作"。
    const asSpectator = toGamePresentation(gameView(), null)
    expect(asSpectator.me.seatNo).toBe(0)
    expect(asSpectator.me.alive).toBe(false)
  })

  test('mySeatNo 指向不存在的座位时也降级为"无座位"', () => {
    expect(toGamePresentation(gameView(), 9).me.seatNo).toBe(0)
  })

  test('后端未投影的字段留空，不编造', () => {
    const view = toGamePresentation(gameView(), 1)

    // 服务端权威计时（AGENTS.md §9）尚未通过视图暴露：编一个倒计时会让用户按错误时间行动。
    expect(view.deadline).toBeNull()
    expect(view.windowMs).toBe(0)
    // 发言与记录尚未投影。
    expect(view.messages).toEqual([])
    expect(view.records).toEqual([])
    // 已投票人数未知时留 null，界面据此显示"进行中"而不是"0/9"。
    expect(view.votedCount).toBeNull()
    // 结算展示尚未投影：编一个空壳结算会让用户以为结算已完成。
    expect(view.result).toBeNull()
    expect(view.privateResult).toBeNull()
  })

  test('头像按座位号固定分配', () => {
    const first = toGamePresentation(gameView(), null)
    const second = toGamePresentation(gameView(), null)

    expect(first.players.map((p) => p.avatar)).toEqual(second.players.map((p) => p.avatar))
  })

  test('BOT 归到 AI（对用户而言是同一件事）', () => {
    const withBot = gameView({
      players: [
        { playerId: 'p_1', seatNo: 1, role: null, camp: null, alive: true, controller: { type: 'BOT', refId: 'bot_1' } },
      ],
    })

    expect(toGamePresentation(withBot, null).players[0]!.controller).toBe('AI')
  })
})

describe('createRealGameAdapter', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  function stubFetch(handler: (url: string, init?: RequestInit) => { status: number; body: unknown }) {
    // mock 的形参必须显式声明：`vi.fn(async () => ...)` 会被推断成零参，
    // 于是 `spy.mock.calls.at(-1)` 的类型是空元组，取 url/init 会编译失败。
    const spy = vi.fn(async (url: string, init?: RequestInit) => {
      const { status, body } = handler(url, init)
      return {
        ok: status >= 200 && status < 300,
        status,
        text: async () => JSON.stringify(body),
        headers: new Headers(),
      } as unknown as Response
    })
    vi.stubGlobal('fetch', spy)
    const store = new Map<string, string>([[SESSION_TOKEN_KEY, 'u_1.s_1.secret']])
    vi.stubGlobal('localStorage', {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => void store.set(k, v),
      removeItem: (k: string) => void store.delete(k),
    })
    return spy
  }

  test('get 读 REST 视图并按 mySeatNo 算出 me', async () => {
    stubFetch(() => ({ status: 200, body: gameView() }))

    const presentation = await createRealGameAdapter({ mySeatNo: () => 1 }).get('g_r_1')

    expect(presentation.gameId).toBe('g_r_1')
    expect(presentation.me.seatNo).toBe(1)
    expect(presentation.phase).toBe('DAY_VOTING')
  })

  test('换账号后迟到的旧视图不能覆盖新账号的角色和权限', async () => {
    stubFetch(() => ({ status: 200, body: {} }))
    const oldView = gameView({ version: 45, me: gameView().players[0], permissions: [] })
    const newView = gameView({ me: { ...gameView().players[1]!, role: 'VILLAGER' } })
    let resolveOld!: (response: Response) => void
    const response = (body: unknown) => ({ ok: true, status: 200, text: async () => JSON.stringify(body) }) as Response
    vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
      if (url.endsWith('/g_r_1') && new Headers(init?.headers).get('Authorization') === 'Bearer u_1.s_1.secret') {
        return new Promise<Response>(resolve => { resolveOld = resolve })
      }
      return Promise.resolve(response(url.includes('/replay?') ? { events: [] } : newView))
    }))
    const adapter = createRealGameAdapter()
    const oldRequest = adapter.get('g_r_1').catch(error => error)
    setSessionToken('u_2.s_2.secret')
    expect((await adapter.get('g_r_1')).me.seatNo).toBe(2)
    resolveOld(response(oldView))
    expect(await oldRequest).toBeInstanceOf(Error)
    const latest = await adapter.get('g_r_1')
    expect(latest.me).toMatchObject({ seatNo: 2, role: 'VILLAGER' })
    expect(latest.permissions.some(permission => permission.type === 'VOTE')).toBe(true)
  })

  test('submit 带 Idempotency-Key 与 expectedVersion', async () => {
    const spy = stubFetch(() => ({ status: 200, body: gameView({ version: 43 }) }))
    const adapter = createRealGameAdapter({ mySeatNo: () => 1 })

    await adapter.submit('g_r_1', {
      commandId: 'cmd_1',
      expectedVersion: 42,
      actionType: 'VOTE',
      params: { targetSeatNo: 2 },
    })

    // 注意不能取 `at(-1)`：submit 之后还会刷新一次视图，最后一条请求是 GET。
    // 要按方法筛选出真正的提交请求。
    const call = spy.mock.calls.find(
      ([, init]) => (init as RequestInit | undefined)?.method === 'POST',
    )! as [string, RequestInit]
    const [url, init] = call
    expect(url).toContain('/api/v1/games/g_r_1/actions')
    expect(init.method).toBe('POST')
    const headers = init.headers as Record<string, string>
    expect(headers['Idempotency-Key']).toBe('cmd_1')
    const body = JSON.parse(init.body as string)
    expect(body.actionType).toBe('VOTE')
    expect(body.params).toEqual({ targetSeatNo: 2 })
    // expectedVersion 必须来自最近一次成功读取的视图（api-spec.md §5.3）：
    // 用本地猜测的值会被服务端拒绝，而错误信息是"版本冲突"，用户看不出怎么办。
    expect(body.expectedVersion).toBe(42)
  })

  test('submit 没有 commandId 时生成幂等键', async () => {
    const spy = stubFetch(() => ({ status: 200, body: gameView() }))

    await createRealGameAdapter().submit('g_r_1', {
      commandId: '',
      expectedVersion: 1,
      actionType: 'SKIP',
      params: {},
    })

    const postCall = spy.mock.calls.find(
      ([, init]) => (init as RequestInit | undefined)?.method === 'POST',
    )! as [string, RequestInit]
    const headers = postCall[1].headers as Record<string, string>
    expect(headers['Idempotency-Key']).toMatch(/^action_/)
  })

  test('提交后立刻重新读取视图（不停在上一个阶段）', async () => {
    let version = 42
    const spy = stubFetch(() => ({ status: 200, body: gameView({ version }) }))
    const adapter = createRealGameAdapter()

    version = 43
    const result = await adapter.submit('g_r_1', {
      commandId: 'cmd_1',
      expectedVersion: 42,
      actionType: 'SKIP',
      params: {},
    })

    // 用户刚点了按钮，界面必须马上反映"已提交"而不是等 WS 事件。
    expect(spy.mock.calls.length).toBeGreaterThanOrEqual(2)
    expect(result.version).toBe(43)
  })

  test('scenario 在真实后端上明确抛错，而不是静默忽略', async () => {
    stubFetch(() => ({ status: 200, body: gameView() }))

    // 静默会让界面上的按钮点了没反应；错误信息能让人立刻明白该功能只存在于演示模式。
    await expect(createRealGameAdapter().scenario('g_r_1', 'nightmare')).rejects.toThrow(/不支持切换剧本/)
  })

  test('REST 失败时 submit 抛出 ApiError（不吞掉错误码）', async () => {
    stubFetch(() => ({ status: 409, body: { code: 'TURN_NOT_YOURS', message: '还没轮到你' } }))

    const error = await createRealGameAdapter()
      .submit('g_r_1', { commandId: 'c', expectedVersion: 1, actionType: 'VOTE', params: {} })
      .catch((e: unknown) => e)

    expect((error as { code?: string }).code).toBe('TURN_NOT_YOURS')
  })

  test('GAME_BUSY 时用同一幂等键退避重试，成功即返回', async () => {
    vi.useFakeTimers()
    try {
      let posts = 0
      const spy = stubFetch((_url, init) => {
        if (init?.method !== 'POST') return { status: 200, body: gameView({ version: 43 }) }
        posts += 1
        return posts < 3
          ? { status: 409, body: { code: 'GAME_BUSY', message: '对局正忙' } }
          : { status: 200, body: { accepted: true } }
      })

      const pending = createRealGameAdapter()
        .submit('g_r_1', { commandId: 'cmd_busy', expectedVersion: 42, actionType: 'VOTE', params: {} })
      await vi.runAllTimersAsync()
      const result = await pending

      expect(result.version).toBe(43)
      const keys = spy.mock.calls
        .filter(([, init]) => (init as RequestInit | undefined)?.method === 'POST')
        .map(([, init]) => ((init as RequestInit).headers as Record<string, string>)['Idempotency-Key'])
      expect(keys).toEqual(['cmd_busy', 'cmd_busy', 'cmd_busy'])
    } finally {
      vi.useRealTimers()
    }
  })

  test('GAME_BUSY 重试用尽后抛出原错误', async () => {
    vi.useFakeTimers()
    try {
      const spy = stubFetch(() => ({ status: 409, body: { code: 'GAME_BUSY', message: '对局正忙' } }))

      const pending = createRealGameAdapter()
        .submit('g_r_1', { commandId: 'c', expectedVersion: 1, actionType: 'VOTE', params: {} })
        .catch((e: unknown) => e)
      await vi.runAllTimersAsync()
      const error = await pending

      expect((error as { code?: string }).code).toBe('GAME_BUSY')
      expect(spy.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === 'POST')).toHaveLength(4)
    } finally {
      vi.useRealTimers()
    }
  })

  test('其他 409 不重试', async () => {
    const spy = stubFetch(() => ({ status: 409, body: { code: 'TURN_NOT_YOURS', message: '还没轮到你' } }))

    await createRealGameAdapter()
      .submit('g_r_1', { commandId: 'c', expectedVersion: 1, actionType: 'VOTE', params: {} })
      .catch(() => undefined)

    expect(spy.mock.calls).toHaveLength(1)
  })
})

/**
 * 发言增量 → `preview` 的接线。
 *
 * 用 `GameSocket` 替身而不是真实连接：要验证的是**适配器把回调接上了没有**——
 * 第 24 轮之后后端确实会推 `AGENT_SPEECH_DELTA`，而前端此前没有任何地方消费它，
 * 因此对局页永远不显示"正在说的那句话"。
 */
describe('发言增量接线', () => {
  test('订阅后收到增量帧会更新 preview，且不额外打 REST', async () => {
    const sockets: Array<{ options: Record<string, unknown>; emit: (frame: unknown) => void }> = []
    vi.doMock('../src/api/gameSocket', () => ({
      GameSocket: class {
        options: Record<string, unknown>
        constructor(options: Record<string, unknown>) {
          this.options = options
          sockets.push({
            options,
            emit: (frame: unknown) => {
              ;(options.onFrame as (f: unknown) => void)?.(frame)
            },
          })
        }
        connect() {}
        close() {}
      },
    }))
    vi.resetModules()

    const { createRealGameAdapter: createAdapter } = await import('../src/api/realGameAdapter')
    // 视图先落一次（refresh 会打 REST）。
    const spy = vi.fn(async (url: string) => ({
      ok: true,
      status: 200,
      text: async () => (String(url).includes('/games/') ? JSON.stringify(gameView()) : '{}'),
      headers: new Headers(),
    }) as unknown as Response)
    vi.stubGlobal('fetch', spy)
    const store = new Map<string, string>()
    vi.stubGlobal('localStorage', {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => void store.set(k, v),
      removeItem: (k: string) => void store.delete(k),
    })

    const adapter = createAdapter({ mySeatNo: () => 1 })
    const received: Array<{ preview: unknown }> = []
    const unsubscribe = adapter.subscribe('g_r_1', (view) => received.push(view as never))
    await vi.waitFor(() => expect(received.length).toBeGreaterThan(0))
    expect(sockets.length, '应建了一条 socket').toBe(1)

    const callsBefore = spy.mock.calls.length
    const speechDelta = sockets[0]!.options.onSpeechDelta as ((frame: unknown) => void) | undefined
    expect(speechDelta, '**适配器必须把 onSpeechDelta 接上**——否则 preview 永远是 null')
      .toBeTypeOf('function')

    speechDelta!({
      type: 'AGENT_SPEECH_DELTA',
      seq: 0,
      ts: 0,
      payload: { gameId: 'g_r_1', playerId: 'p_3', seatNo: 3, speechId: 'sp_1', delta: '我怀疑 5 号', index: 0, final: true },
    })

    const latest = received.at(-1)!
    expect(latest.preview, 'preview 必须被填上').toEqual({
      speechId: 'sp_1', seatNo: 3, content: '我怀疑 5 号',
    })
    // 发言文本已经在帧里，为显示它多打一次接口没有必要。
    expect(spy.mock.calls.length, '显示发言不该触发 REST 刷新').toBe(callsBefore)

    unsubscribe()
    vi.doUnmock('../src/api/gameSocket')
    vi.resetModules()
  })
})

/**
 * `preview` 的生命周期：发言提交后必须被清除。
 *
 * 不清的话"正在说的那句话"会一直留在界面上——而 REST 刷新刻意保留 `preview`
 * （不保留会让它每收到一个事件就闪一下），因此它只能靠**语义信号**清除。
 */
describe('preview 生命周期', () => {
  test('提交的 PLAYER_SPOKE 到达后 preview 被清掉', async () => {
    const sockets: Array<{ options: Record<string, unknown> }> = []
    vi.doMock('../src/api/gameSocket', () => ({
      GameSocket: class {
        options: Record<string, unknown>
        constructor(options: Record<string, unknown>) {
          this.options = options
          sockets.push({ options })
        }
        connect() {}
        close() {}
      },
    }))
    vi.resetModules()

    const { createRealGameAdapter: createAdapter } = await import('../src/api/realGameAdapter')
    vi.stubGlobal('fetch', vi.fn(async (url: string) => ({
      ok: true,
      status: 200,
      text: async () => (String(url).includes('/games/') ? JSON.stringify(gameView()) : '{}'),
      headers: new Headers(),
    }) as unknown as Response))
    const store = new Map<string, string>()
    vi.stubGlobal('localStorage', {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => void store.set(k, v),
      removeItem: (k: string) => void store.delete(k),
    })

    const adapter = createAdapter({ mySeatNo: () => 1 })
    const received: Array<{ preview: unknown }> = []
    const unsubscribe = adapter.subscribe('g_r_1', (view) => received.push(view as never))
    await vi.waitFor(() => expect(received.length).toBeGreaterThan(0))

    // 1) 收到增量 → preview 出现。
    const speechDelta = sockets[0]!.options.onSpeechDelta as (frame: unknown) => void
    speechDelta({
      type: 'AGENT_SPEECH_DELTA', seq: 0, ts: 0,
      payload: { gameId: 'g_r_1', playerId: 'p_3', seatNo: 3, speechId: 'sp_1', delta: '我怀疑 5 号', index: 0, final: true },
    })
    expect(received.at(-1)!.preview, '增量到达后 preview 应出现').toBeTruthy()

    // 2) 该发言被提交 → preview 必须被清除。
    const onFrame = sockets[0]!.options.onFrame as (frame: unknown) => void
    onFrame({
      type: 'GAME_EVENT', seq: 12, ts: 0,
      payload: { type: 'PLAYER_SPOKE', version: 12, phase: 'DAY_DISCUSS', round: 2, payload: { seatNo: 3, speechId: 'sp_1' } },
    })
    expect(received.at(-1)!.preview, '发言提交后 preview 必须被清除，否则那句话会一直留在界面上')
      .toBeNull()

    speechDelta({ type: 'AGENT_SPEECH_DELTA', seq: 0, ts: 0,
      payload: { speechId: 'sp_1', seatNo: 3, delta: '我怀疑 5 号', final: true } })
    expect(received.at(-1)!.preview, '提交后到达的同条展示帧不能恢复正在发言状态').toBeNull()

    unsubscribe()
    vi.doUnmock('../src/api/gameSocket')
    vi.resetModules()
  })

  test('index 缺号丢弃预览且本条不再显示；重复段忽略；中途加入不显示（websocket-protocol D-2）', async () => {
    const sockets: Array<{ options: Record<string, unknown> }> = []
    vi.doMock('../src/api/gameSocket', () => ({
      GameSocket: class {
        options: Record<string, unknown>
        constructor(options: Record<string, unknown>) {
          this.options = options
          sockets.push({ options })
        }
        connect() {}
        close() {}
      },
    }))
    vi.resetModules()

    const { createRealGameAdapter: createAdapter } = await import('../src/api/realGameAdapter')
    vi.stubGlobal('fetch', vi.fn(async (url: string) => ({
      ok: true,
      status: 200,
      text: async () => (String(url).includes('/games/') ? JSON.stringify(gameView()) : '{}'),
      headers: new Headers(),
    }) as unknown as Response))
    const store = new Map<string, string>()
    vi.stubGlobal('localStorage', {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => void store.set(k, v),
      removeItem: (k: string) => void store.delete(k),
    })

    const adapter = createAdapter({ mySeatNo: () => 1 })
    const received: Array<{ preview: { content: string } | null }> = []
    const unsubscribe = adapter.subscribe('g_r_1', (view) => received.push(view as never))
    await vi.waitFor(() => expect(received.length).toBeGreaterThan(0))
    const speechDelta = sockets[0]!.options.onSpeechDelta as (frame: unknown) => void
    const delta = (speechId: string, index: number, text: string, final = false) => speechDelta({
      type: 'AGENT_SPEECH_DELTA', seq: 0, ts: 0, payload: { speechId, seatNo: 3, delta: text, index, final } })

    delta('sp_a', 0, '第一句。')
    delta('sp_a', 0, '第一句。')
    delta('sp_a', 1, '第二句。')
    expect(received.at(-1)!.preview?.content, '重复的 index 不能把同一段拼两次').toBe('第一句。第二句。')

    delta('sp_a', 3, '第四句。')
    expect(received.at(-1)!.preview, '缺少 index 2 时必须丢弃已累积的预览').toBeNull()
    delta('sp_a', 4, '第五句。', true)
    expect(received.at(-1)!.preview, '缺号之后本条发言余下的增量都不再显示').toBeNull()

    const pushes = received.length
    delta('sp_b', 2, '中途加入的段。')
    expect(received.length, '首段不是 0 说明错过了开头，不能显示残缺的预览').toBe(pushes)

    delta('sp_c', 0, '新的一条。', true)
    expect(received.at(-1)!.preview?.content, '下一条发言不受上一条缺号影响').toBe('新的一条。')

    unsubscribe()
    vi.doUnmock('../src/api/gameSocket')
    vi.resetModules()
  })
})
