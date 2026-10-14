import { afterEach, describe, expect, test, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { phaseOf, realObservationAdapter } from '../src/features/observation/realObservationAdapter'
import { unknownPhase } from '../src/features/observation/types'
import { avatarFor } from '../src/api/mapRoom'

/**
 * 真实观战适配器的映射。
 *
 * ## 它守什么
 *
 * 这个适配器把两个后端响应（`GET /games/{id}` 与 `GET /games/{id}/replay`）
 * 映射成观战页模型。**它最容易错的地方不是解析，而是"补上后端没有的字段"**：
 * 一旦补了，观战页会显示看起来可信、实际是编造的信息。
 * 因此下面的断言有一半是"**某个字段没有被填**"。
 *
 * 另一半是**可见性**：未结束的对局不许披露角色。观战页是公开路由，
 * 披露正在进行的对局身份就是把隐藏信息泄漏给看直播的人（AGENTS.md §8）。
 */

function stubFetch(view: unknown, replay: unknown) {
  const fetcher = vi.fn(async (url: string) => {
    const body = String(url).includes('/replay') ? replay : view
    return {
      ok: true,
      status: 200,
      headers: new Headers({ 'content-type': 'application/json' }),
      text: async () => JSON.stringify(body),
      json: async () => body,
    } as unknown as Response
  })
  vi.stubGlobal('fetch', fetcher)
  return fetcher
}

const runningView = {
  gameId: 'g_1',
  roomId: 'r_1',
  status: 'RUNNING',
  phase: 'DAY_DISCUSS',
  round: 2,
  version: 12,
  players: [
    { playerId: 'p_1', seatNo: 1, role: null, camp: null, alive: true, controller: { type: 'AI', refId: 'p_1' } },
    { playerId: 'p_2', seatNo: 2, role: null, camp: null, alive: false, controller: { type: 'HUMAN', refId: 'u_2' } },
  ],
}

const finishedView = { ...runningView, status: 'FINISHED', phase: 'GAME_OVER', players: [
  { playerId: 'p_1', seatNo: 1, role: 'WEREWOLF', camp: 'WOLF', alive: true, controller: { type: 'AI', refId: 'p_1' } },
  { playerId: 'p_2', seatNo: 2, role: 'SEER', camp: 'VILLAGE', alive: false, controller: { type: 'HUMAN', refId: 'u_2' } },
] }

const replayWithEvents = {
  gameId: 'g_1',
  events: [
    { eventId: 'e_1', version: 1, type: 'GAME_STARTED', occurredAt: 1_000, phase: 'GAME_INIT', round: 1, visibility: 'PUBLIC', payload: {} },
    { eventId: 'e_2', version: 2, type: 'PLAYER_SPOKE', occurredAt: 3_000, phase: 'DAY_DISCUSS', round: 1, visibility: 'PUBLIC',
      payload: { seatNo: 3, content: '我怀疑 5 号', speechId: 's_1' } },
    { eventId: 'e_3', version: 3, type: 'WOLF_KILL_SELECTED', occurredAt: 5_000, phase: 'NIGHT_WOLF', round: 1, visibility: 'WOLF',
      payload: { actorSeatNo: 9, targetSeatNo: 5 } },
    { eventId: 'e_4', version: 4, type: 'GAME_FINISHED', occurredAt: 9_000, phase: 'GAME_OVER', round: 2, visibility: 'PUBLIC',
      payload: { winnerCamp: 'WOLF', finishReason: 'OUTNUMBER' } },
  ],
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('观战适配器：映射', () => {
  test('事件被映射成观战模型，且按 version 升序', async () => {
    stubFetch(finishedView, { ...replayWithEvents, events: [...replayWithEvents.events].reverse() })
    const match = await realObservationAdapter.get('g_1')

    expect(match.gameId).toBe('g_1')
    expect(match.events.map((e) => e.version)).toEqual([1, 2, 3, 4])
    expect(match.events[1]!.content).toBe('3 号：我怀疑 5 号')
    expect(match.events[2]!.content).toBe('狼人阵营选择袭击 5 号')
    expect(match.events[3]!.content).toContain('狼人阵营获胜')
  })

  test('可见性映射到 scope，不用前端自己裁剪', async () => {
    stubFetch(finishedView, replayWithEvents)
    const match = await realObservationAdapter.get('g_1')

    // 服务端已经过滤过；客户端只把 visibility 如实映射成 scope。
    expect(match.events.map((e) => e.scope)).toEqual(['PUBLIC', 'PUBLIC', 'WOLF', 'PUBLIC'])
  })

  test('时长与初始位置来自事件时间，而不是猜', async () => {
    stubFetch(finishedView, replayWithEvents)
    const match = await realObservationAdapter.get('g_1')

    // 1_000 → 9_000 毫秒 = 8 秒。
    expect(match.duration).toBe(8)
    // 播放器默认停在最后一个事件：观战从"当前"开始看。
    expect(match.initialPosition).toBe(8)
    expect(match.events.map(event => event.at)).toEqual([0, 2, 4, 8])
    expect(match.startedAt).not.toBe('')
  })

  test('阶段变化写进 change.phase，且淘汰座位来自 payload', async () => {
    stubFetch(finishedView, replayWithEvents)
    const match = await realObservationAdapter.get('g_1')

    expect(match.events[0]!.change?.phase).toBe('等待开局')
    expect(match.events[1]!.change?.phase).toBe('白天讨论')
    expect(match.events[3]!.change?.phase).toBe('游戏结束')
    expect(match.events[3]!.change?.winner).toBe('狼人阵营获胜')
  })

  test('玩家头像与座位号派生一致（与等待房同一约定）', async () => {
    stubFetch(finishedView, replayWithEvents)
    const match = await realObservationAdapter.get('g_1')

    expect(match.players.map((p) => p.seatNo)).toEqual([1, 2])
    expect(match.players.map((p) => p.controller)).toEqual(['AI', 'HUMAN'])
    /*
     * 头像是**展示决定**：同一座位在任何页面都应得到同一张脸。
     *
     * 断言用 `avatarFor` 而不是写死 `'green'`：
     * 写死的话，一旦调色板顺序变了，这个测试会失败而**约定其实仍然成立**——
     * 那时它守的是"某个具体颜色"，而不是"两处用同一个派生规则"。
     * 真正该守的是后者，所以直接与派生函数比对。
     */
    for (const player of match.players) {
      expect(player.avatar).toBe(avatarFor(player.seatNo))
    }
  })

  test('空事件流不崩，且不给虚假时值', async () => {
    stubFetch(runningView, { gameId: 'g_1', events: [] })
    const match = await realObservationAdapter.get('g_1')

    expect(match.events).toEqual([])
    expect(match.duration).toBe(0)
    expect(match.initialPosition).toBe(0)
    expect(match.startedAt).toBe('')
  })
})

describe('观战适配器：不编造后端没有的信息', () => {
  test('rationale（决策摘要）**不被填**——后端不投影它', async () => {
    stubFetch(finishedView, replayWithEvents)
    const match = await realObservationAdapter.get('g_1')

    // 填一个"示例决策摘要"会让观战者以为看到了模型的推理，
    // 而实际上什么都没看到（AGENTS.md §14）。
    for (const event of match.events) {
      expect(event.rationale, `事件 ${event.type} 不该有 rationale`).toBeUndefined()
    }
  })

  test('玩家名用 playerId，而不是生成代号', async () => {
    stubFetch(finishedView, replayWithEvents)
    const match = await realObservationAdapter.get('g_1')

    // 后端只下发 playerId，没有显示名。生成 "AI-白狐" 之类会让它看起来像后端数据。
    expect(match.players.map((p) => p.name)).toEqual(['p_1', 'p_2'])
  })

  test('房间名用 roomId，而不是编一个名字', async () => {
    stubFetch(finishedView, replayWithEvents)
    const match = await realObservationAdapter.get('g_1')

    expect(match.roomName).toBe('r_1')
  })
})

describe('观战适配器：未结束的对局不披露角色', () => {
  test('RUNNING 时不下发 disclosedRole', async () => {
    stubFetch(runningView, { ...replayWithEvents, events: replayWithEvents.events.filter(event => event.visibility === 'PUBLIC') })
    const match = await realObservationAdapter.get('g_1')

    for (const player of match.players) {
      // 关键：**不给一个中性角色值**。给 'VILLAGER' 会让观战者以为那是事实，
      // 而它只是"没披露"。
      expect(player.disclosedRole, `座位 ${player.seatNo} 不该有角色`).toBeUndefined()
    }
  })

  test('FINISHED 时披露真实角色', async () => {
    stubFetch(finishedView, replayWithEvents)
    const match = await realObservationAdapter.get('g_1')

    expect(match.players[0]!.disclosedRole).toBe('WEREWOLF')
    expect(match.players[1]!.disclosedRole).toBe('SEER')
  })
})

/** `?atVersion=N` 返回的 `snapshot`：与 `GET /games/{id}` 同形状，但状态是**那个版本**的。 */
const boardAtNightOne = {
  gameId: 'g_1', roomId: 'r_1', status: 'RUNNING', phase: 'NIGHT_WOLF', round: 1, version: 11,
  currentTurn: null, winner: null,
  players: [
    { playerId: 'p_1', seatNo: 1, role: null, camp: null, alive: true, controller: { type: 'AI', refId: 'p_1' } },
    { playerId: 'p_2', seatNo: 2, role: null, camp: null, alive: true, controller: { type: 'HUMAN', refId: 'u_2' } },
  ],
  permissions: [],
}

describe('观战适配器：按版本读取权威棋盘', () => {
  test('atVersion 快照映射成棋盘：服务端说 9 人存活就是 9 人存活', async () => {
    const fetcher = stubFetch(finishedView, { events: [], atVersion: 11, snapshot: boardAtNightOne })
    const board = await realObservationAdapter.boardAt!('g_1', 11)

    // 棋盘由服务端重建：请求里必须带 atVersion，而不是读回放事件自己重演（AP-6）。
    expect(String(fetcher.mock.calls[0]?.[0])).toContain('atVersion=11')
    expect(board?.version).toBe(11)
    expect(board?.phase).toBe('狼人行动')
    expect(board?.round).toBe(1)
    expect(board?.speaker).toBeNull()
    expect(board?.winner).toBeNull()
    expect(board?.players.filter(player => player.alive).map(player => player.seatNo)).toEqual([1, 2])
  })

  test('未结束的历史版本按当时状态脱敏：不披露角色', async () => {
    stubFetch(finishedView, { events: [], snapshot: boardAtNightOne })
    const board = await realObservationAdapter.boardAt!('g_1', 11)

    for (const player of board!.players) expect(player.disclosedRole).toBeUndefined()
  })

  test('非法版本号一律不发请求', async () => {
    const fetcher = stubFetch(finishedView, { events: [], snapshot: boardAtNightOne })

    expect(await realObservationAdapter.boardAt!('g_1', Number.NaN)).toBeNull()
    expect(await realObservationAdapter.boardAt!('g_1', 0)).toBeNull()
    expect(await realObservationAdapter.boardAt!('g_1', -3)).toBeNull()
    expect(await realObservationAdapter.boardAt!('g_1', 1.5)).toBeNull()
    expect(fetcher).not.toHaveBeenCalled()
  })

  test('服务端没有历史快照时返回 null，而不是空棋盘', async () => {
    stubFetch(finishedView, { events: [] })
    expect(await realObservationAdapter.boardAt!('g_1', 11)).toBeNull()
  })
})

const here = dirname(fileURLToPath(import.meta.url))
const GAME_PHASE_PATH = resolve(here, '../../backend/engine/src/main/java/com/wolfmind/domain/game/model/GamePhase.java')

/** 从后端源码里抽出 `GamePhase` 的枚举取值名（形如 `NIGHT_SEER(2),`）。 */
function backendGamePhases(): string[] {
  const source = readFileSync(GAME_PHASE_PATH, 'utf8')
  return [...source.matchAll(/^\s{4}([A-Z][A-Z0-9_]*)\(\d+\)[,;]?$/gm)].map(match => match[1]!)
}

/**
 * 阶段文案的**跨模块契约**。
 *
 * 前端自己列一份"我以为的阶段"是发现不了漏项的：漏掉的阶段不会报错，只会在下拉框里
 * 变成"第 3 天 · undefined"（QA P2）。所以这里直接读后端的 `GamePhase`，逐个对照。
 */
describe('阶段文案：与领域 GamePhase 逐项对照', () => {
  test('每个领域阶段都有中文文案，且夜间子阶段彼此不同', () => {
    const names = backendGamePhases()
    expect(names.length, `未能从 ${GAME_PHASE_PATH} 解析阶段枚举`).toBeGreaterThanOrEqual(12)

    for (const name of names) {
      const label = phaseOf(name)
      expect(label, `阶段 ${name} 没有文案`).toBeTruthy()
      expect(label, `阶段 ${name} 落到了未知占位`).not.toBe(unknownPhase)
    }
    // 每个阶段一个专属文案：折叠（例如把 NIGHT_SEER/NIGHT_WITCH 都写成"黑夜行动"）会在这里失败。
    const labels = names.map(name => phaseOf(name))
    expect(new Set(labels).size).toBe(names.length)

    const night = names.filter(name => name.startsWith('NIGHT_'))
    expect(night.length).toBeGreaterThanOrEqual(4)
    expect(new Set(night.map(name => phaseOf(name))).size).toBe(night.length)
  })

  test('自动阶段与结算阶段也有文案（它们同样产生 PHASE_CHANGED）', () => {
    expect(phaseOf('DAY_ANNOUNCE')).toBe('公布死讯')
    expect(phaseOf('VOTE_SETTLE')).toBe('投票结算')
    expect(phaseOf('NIGHT_SETTLE')).toBe('夜间结算')
  })

  test('未知阶段给出明确占位，而不是 undefined', () => {
    expect(phaseOf('SOMETHING_NEW')).toBe(unknownPhase)
    expect(phaseOf(undefined)).toBe(unknownPhase)
  })

  test('回放的章节阶段读载荷 to（信封 phase 是跳转前的阶段）', async () => {
    stubFetch(finishedView, { events: [
      { eventId: 'e_1', version: 1, type: 'PHASE_CHANGED', occurredAt: 1_000, phase: 'NIGHT_SETTLE', round: 1,
        visibility: 'PUBLIC', payload: { from: 'NIGHT_SETTLE', to: 'DAY_ANNOUNCE', round: 1 } },
    ] })
    const match = await realObservationAdapter.get('g_1')

    // 用信封 phase 会得到"夜间结算"，而这条事件讲的是"进入公布死讯"。
    expect(match.events[0]!.change?.phase).toBe('公布死讯')
    expect(match.events[0]!.content).toBe('进入公布死讯')
  })
})
