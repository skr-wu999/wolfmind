import { afterEach, expect, test, vi } from 'vitest'
import { toGamePresentation } from '../src/api/mapGame'
import { reduceFrame, emptyState } from '../src/api/gameStream'
import type { GameView } from '../src/api/restTypes'
import type { AppliedEvent } from '../src/api/gameStream'
import { createRealGameAdapter } from '../src/api/realGameAdapter'
import { SESSION_TOKEN_KEY } from '../src/api/config'

afterEach(() => vi.unstubAllGlobals())

const view: GameView = {
  gameId: 'g_live', roomId: null, status: 'RUNNING', phase: 'DAY_DISCUSS', round: 1, version: 20,
  players: [{ playerId: 'p_1', seatNo: 1, alive: true, role: 'SEER', camp: 'VILLAGER', controller: { type: 'HUMAN', refId: 'u_1' } }],
  permissions: [], turnIndex: 1, turnOrder: [2, 1],
  currentTurn: { seatNo: 1, phase: 'DAY_DISCUSS', turnIndex: 1, deadline: 50000 },
  rules: { turnDeadlineMs: 30000 }, votes: [],
}
function event(seq: number, type: string, payload: Record<string, unknown>): AppliedEvent {
  return { seq, type, payload, occurredAt: 10000, phase: view.phase, round: 1, actorId: 'p_1' }
}

test('权威回合、发言、本人查验与公开死亡进入展示投影', () => {
  const events = [event(2, 'PHASE_CHANGED', { deadline: 30000 }),
    event(5, 'PLAYER_SPOKE', { speechId: 's_1', seatNo: 2, content: '检查投票记录' }),
    event(7, 'SEER_INSPECTED', { seerSeatNo: 1, targetSeatNo: 2, result: 'WOLF' }),
    event(9, 'DEATH_ANNOUNCED', { deaths: [{ seatNo: 3 }] })]
  const result = toGamePresentation({ ...view, me: view.players[0] }, null, events, 18000)
  expect(result.me.seatNo).toBe(1)
  expect(result.deadline).toBe(50000)
  expect(result.serverNow).toBe(18000)
  expect(result.completedSpeakers).toEqual([2])
  expect(result.messages.map(message => message.content)).toEqual(['检查投票记录'])
  expect(result.privateResult?.content).toContain('2 号的阵营：狼人')
  expect(result.records.map(record => record.text)).toContain('3 号已出局')
})

test('阶段窗口使用已授权事件，未来事件和他人技能记录不能进入本次视图', () => {
  const events = [event(8, 'PHASE_CHANGED', { deadline: 60000 }),
    event(10, 'SEER_INSPECTED', { seerSeatNo: 2, targetSeatNo: 3, result: 'WOLF' }),
    event(21, 'PLAYER_SPOKE', { content: '未来发言' })]
  const result = toGamePresentation({ ...view, currentTurn: null, phase: 'DAY_VOTE', rules: { voteDeadlineMs: 45000 } }, 1, events)
  expect(result.deadline).toBe(60000)
  expect(result.windowMs).toBe(45000)
  expect(result.privateResult).toBeNull()
  expect(result.messages).toEqual([])
})

test('胜负只取服务端终局结果，猎人阶段能映射到行动面板', () => {
  expect(toGamePresentation({ ...view, phase: 'HUNTER_SHOOT' }, 1).phase).toBe('ROLE_REACTION')
  expect(toGamePresentation({ ...view, status: 'FINISHED', phase: 'GAME_OVER', winner: 'WOLF', finishReason: 'WOLVES_OUTNUMBER_VILLAGE' }, 1).result).toMatchObject({ title: '狼人阵营获胜', content: '存活狼人数量已不少于好人阵营。' })
  expect(toGamePresentation({ ...view, winner: 'WOLF' }, 1).result).toBeNull()
})

test('中止的对局显示中止面板而不是胜负', () => {
  expect(toGamePresentation({ ...view, status: 'ABORTED', phase: 'GAME_OVER', winner: null, finishReason: 'STALLED' }, 1).result)
    .toEqual({ title: '对局中止', content: '连续多轮无人出局，对局已中止，不计胜负。' })
})

test('快照同步触发权威刷新并废弃旧预览', () => {
  const state = reduceFrame(emptyState(), { type: 'GAME_SNAPSHOT_SYNC', seq: 10, ts: 10000, payload: {} })
  expect(state.dirty).toBe(true)
  expect(state.previewSuperseded).toBe(true)
  expect(state.seq).toBe(10)
})

test('本人身份以服务端 me 为准，空 me 覆盖过期的本地座位', () => {
  expect(toGamePresentation({ ...view, me: null }, 1).me.seatNo).toBe(0)
})

test('授权视图和历史都附带令牌，超过 500 版的历史分段读取', async () => {
  vi.stubGlobal('localStorage', { getItem: (key: string) => key === SESSION_TOKEN_KEY ? 'session-token' : null })
  const fetcher = vi.fn(async (url: string, init: RequestInit) => {
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer session-token')
    const parsed = new URL(url)
    const body = parsed.pathname.endsWith('/replay') ? { events: [
      { version: Number(parsed.searchParams.get('to')), type: 'PLAYER_SPOKE', phase: 'DAY_DISCUSS', round: 1,
        occurredAt: 10000, actorId: 'p_1', payload: { seatNo: 1, speechId: parsed.searchParams.get('to'), content: '已提交发言' } },
    ] } : { ...view, version: 1002 }
    return { ok: true, status: 200, headers: new Headers(), text: async () => JSON.stringify(body) } as Response
  })
  vi.stubGlobal('fetch', fetcher)
  const adapter = createRealGameAdapter({ mySeatNo: () => 1 })
  const result = await adapter.get(view.gameId)
  expect(result.messages).toHaveLength(3)
  expect(fetcher.mock.calls.filter(([url]) => url.includes('/replay')).map(([url]) => new URL(url).search)).toEqual([
    '?from=1&to=500', '?from=501&to=1000', '?from=1001&to=1002',
  ])
  await adapter.get(view.gameId)
  expect(fetcher.mock.calls.filter(([url]) => url.includes('/replay'))).toHaveLength(3)
})
