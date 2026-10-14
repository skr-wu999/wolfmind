import { afterEach, describe, expect, test, vi } from 'vitest'
import {
  MAX_EVENTS,
  applyView,
  emptyState,
  markResyncRequired,
  reduceFrame,
  type GameStreamState,
  type ReducibleFrame,
} from '../src/api/gameStream'
import type { GameView } from '../src/api/restTypes'

/**
 * 事件归约层。
 *
 * 这一层守两条**容易搞反**的规则：
 * 1. **重复帧必须丢弃**（投递是至少一次语义）。
 * 2. **seq 不连续不是错误**——服务端按可见性过滤后，客户端收到的本来就是被跳过版本的序列。
 *    把不连续当错误会让村民每隔几帧就触发一次不必要的全量重建，而这正是"隐藏信息保护"
 *    生效（因为他看不到那些事件）时的正常表现。
 */

function eventFrame(seq: number, type: string, inner: Record<string, unknown> = {}): ReducibleFrame {
  return {
    type: 'GAME_EVENT',
    seq,
    ts: 1_000 + seq,
    payload: {
      eventId: `e_${seq}`,
      type,
      phase: 'NIGHT_WOLF',
      round: 1,
      occurredAt: 1_000 + seq,
      actorId: 'SYSTEM',
      payload: inner,
    },
  }
}

function view(version: number): GameView {
  return {
    gameId: 'g_1',
    roomId: 'r_1',
    status: 'RUNNING',
    phase: 'NIGHT_WOLF',
    round: 1,
    version,
    players: [],
    permissions: [],
  }
}

describe('reduceFrame', () => {
  test('GAME_EVENT 推进 seq 并记录事件', () => {
    const next = reduceFrame(emptyState(), eventFrame(1, 'GAME_STARTED'))

    expect(next.seq).toBe(1)
    expect(next.events).toHaveLength(1)
    expect(next.events[0]!.type).toBe('GAME_STARTED')
  })

  test('重复 seq 被丢弃并计数', () => {
    let state = reduceFrame(emptyState(), eventFrame(3, 'PHASE_CHANGED'))
    state = reduceFrame(state, eventFrame(3, 'PHASE_CHANGED'))

    expect(state.seq).toBe(3)
    expect(state.events).toHaveLength(1)
    expect(state.duplicates).toBe(1)
  })

  test('更旧的 seq 也被丢弃（乱序投递）', () => {
    let state = reduceFrame(emptyState(), eventFrame(5, 'PHASE_CHANGED'))
    state = reduceFrame(state, eventFrame(2, 'GAME_STARTED'))

    expect(state.seq).toBe(5)
    expect(state.events.map((e) => e.seq)).toEqual([5])
  })

  test('seq 不连续不报错、不触发重建（服务端已按可见性过滤）', () => {
    // 这是最关键的一条：村民看不到狼人的夜间目标，因此他收到的 seq 本来就是跳着的。
    // 把不连续当错误会让他在整局里反复全量重建。
    let state = reduceFrame(emptyState(), eventFrame(1, 'PHASE_CHANGED'))
    state = reduceFrame(state, eventFrame(7, 'PHASE_CHANGED'))
    state = reduceFrame(state, eventFrame(9, 'PHASE_CHANGED'))

    expect(state.seq).toBe(9)
    expect(state.events.map((e) => e.seq)).toEqual([9, 7, 1])
    expect(state.needsResync).toBe(false)
  })

  test('最新事件排在最前，并限制总条数', () => {
    let state = emptyState()
    for (let seq = 1; seq <= MAX_EVENTS + 20; seq++) {
      state = reduceFrame(state, eventFrame(seq, 'PHASE_CHANGED'))
    }

    expect(state.events).toHaveLength(MAX_EVENTS)
    // 内存不会随局长无界增长，而水位（seq）仍然完整——两者是不同的东西。
    expect(state.seq).toBe(MAX_EVENTS + 20)
    expect(state.events[0]!.seq).toBe(MAX_EVENTS + 20)
  })

  test('只有会影响视图的事件才置脏', () => {
    // 全量拉取会让夜间每条狼人行动都打一次 REST；漏掉一个只会让界面滞后一次刷新。
    const noisy = reduceFrame(emptyState(), eventFrame(1, 'AGENT_DECISION_RECORDED'))
    expect(noisy.dirty).toBe(false)

    const meaningful = reduceFrame(emptyState(), eventFrame(1, 'PLAYER_KILLED'))
    expect(meaningful.dirty).toBe(true)
  })

  test('已经脏的状态不会被后续无影响事件清掉', () => {
    let state = reduceFrame(emptyState(), eventFrame(1, 'PLAYER_KILLED'))
    state = reduceFrame(state, eventFrame(2, 'AGENT_DECISION_RECORDED'))

    expect(state.dirty).toBe(true)
  })

  test('GAME_SNAPSHOT_SYNC 以服务端水位重建并清空旧事件', () => {
    let state = reduceFrame(emptyState(), eventFrame(1, 'PHASE_CHANGED'))
    state = reduceFrame(state, { type: 'GAME_SNAPSHOT_SYNC', seq: 42, ts: 0, payload: {} })

    expect(state.seq).toBe(42)
    // 保留旧事件会让界面把"同步点之前"与"之后"混成一条时间线。
    expect(state.events).toHaveLength(0)
    expect(state.needsResync).toBe(false)
  })

  test('SUBSCRIBED / PONG 等管理帧返回同一对象（调用方据此跳过更新）', () => {
    const state = emptyState()
    // 同一对象（而不是等值的新对象）让 store 能用引用比较跳过重渲染。
    expect(reduceFrame(state, { type: 'SUBSCRIBED', seq: 0, ts: 0, payload: {} })).toBe(state)
    expect(reduceFrame(state, { type: 'PONG', seq: 0, ts: 0, payload: {} })).toBe(state)
  })

  test('事件的 payload 被解包到内层（展示需要的是领域字段）', () => {
    const state = reduceFrame(emptyState(), eventFrame(1, 'PLAYER_KILLED', { seatNo: 4, cause: 'VOTE' }))

    expect(state.events[0]!.payload).toEqual({ seatNo: 4, cause: 'VOTE' })
    // 外层信封字段也保留，便于排查。
    expect(state.events[0]!.round).toBe(1)
    expect(state.events[0]!.phase).toBe('NIGHT_WOLF')
  })

  test('缺少可选字段时不抛异常（坏帧只丢一帧，不断开）', () => {
    const state = reduceFrame(emptyState(), {
      type: 'GAME_EVENT',
      seq: 1,
      ts: 5,
      payload: { type: 'PHASE_CHANGED' },
    })

    expect(state.seq).toBe(1)
    expect(state.events[0]!.round).toBe(0)
    expect(state.events[0]!.occurredAt).toBe(5)
  })
})

describe('markResyncRequired / applyView', () => {
  test('SYNC_REQUIRED 标记需要重建', () => {
    const state = markResyncRequired(emptyState())

    expect(state.needsResync).toBe(true)
    expect(state.dirty).toBe(true)
  })

  test('applyView 替换视图并清除脏标记', () => {
    let state = markResyncRequired(emptyState())
    state = applyView(state, view(11))

    expect(state.view!.version).toBe(11)
    expect(state.dirty).toBe(false)
    expect(state.needsResync).toBe(false)
  })

  test('applyView 不把 seq 抬到 view.version', () => {
    // 视图版本里的事件可能对本身份不可见。把 seq 抬上去会让之后真正收到的那条
    // 被当成重复而丢弃——用户会看到"某个玩家死了"这条永久不出现。
    let state = reduceFrame(emptyState(), eventFrame(3, 'PHASE_CHANGED'))
    state = applyView(state, view(11))

    expect(state.seq).toBe(3)

    const after = reduceFrame(state, eventFrame(5, 'PLAYER_KILLED'))
    expect(after.events[0]!.seq).toBe(5)
    expect(after.duplicates).toBe(0)
  })
})

describe('与 GameSocket 的接线形状', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  test('归约后的 seq 正好可作为 RESUME 的 lastSeq', () => {
    // 这一层存在的直接目的之一：GameSocket 的 lastSeq 回调需要"已归约到哪"，
    // 而它必须是不含重复、已跳过不可见版本的连续水位。
    let state: GameStreamState = emptyState()
    for (const seq of [1, 2, 2, 5, 9]) {
      state = reduceFrame(state, eventFrame(seq, 'PHASE_CHANGED'))
    }

    expect(state.seq).toBe(9)
    expect(state.duplicates).toBe(1)
  })
})

/**
 * **全量快照重建**（`GAME_SNAPSHOT_SYNC`，协议 §8）。
 *
 * 这条路径此前完全没测过：`reduceFrame` 有分支、服务端有发送点
 * （`WebSocketGateway` 在客户端 `lastSeq` 超过服务端流尾时发），
 * 但没有任何断言说明客户端收到它之后该变成什么样。
 *
 * 它要保证的事只有三件，而**每一件错了都会让界面显示出错乱的时间线**：
 * 1. 水位换成**同步点版本**（不是连接内自增号）；
 * 2. 旧事件被**清空**——保留会让"同步点之前"与"之后"混在一起；
 * 3. 置 `dirty`，由适配器去拉一次权威视图。
 */
describe('GAME_SNAPSHOT_SYNC：全量重建', () => {
  test('水位换成同步点版本', () => {
    const before = reduceFrame(emptyState(), eventFrame(1, 'GAME_STARTED'))
    expect(before.seq).toBe(1)

    const after = reduceFrame(before, {
      type: 'GAME_SNAPSHOT_SYNC', seq: 42, ts: 2_000, payload: { replayedTo: 42 },
    })

    expect(after.seq, '快照的 seq 是同步点版本').toBe(42)
  })

  test('旧事件被清空，避免同步点前后混在一起', () => {
    let state = emptyState()
    state = reduceFrame(state, eventFrame(1, 'GAME_STARTED'))
    state = reduceFrame(state, eventFrame(2, 'PHASE_CHANGED'))
    expect(state.events.length).toBe(2)

    const after = reduceFrame(state, {
      type: 'GAME_SNAPSHOT_SYNC', seq: 42, ts: 2_000, payload: { replayedTo: 42 },
    })

    expect(after.events, '重建必须清空事件列表').toEqual([])
  })

  test('置 dirty，让适配器去拉权威视图', () => {
    const after = reduceFrame(emptyState(), {
      type: 'GAME_SNAPSHOT_SYNC', seq: 42, ts: 2_000, payload: { replayedTo: 42 },
    })

    // 快照帧本身**不含**权威视图（服务端只给同步点与元数据），
    // 因此客户端必须自己去取——`dirty` 就是这个信号。
    expect(after.dirty, '快照之后必须拉一次权威视图').toBe(true)
    expect(after.needsResync, '拿到快照就算已重建，needsResync 应清掉').toBe(false)
  })

  test('快照会清掉预览（它对应重建之前的那条发言）', () => {
    const after = reduceFrame(emptyState(), {
      type: 'GAME_SNAPSHOT_SYNC', seq: 7, ts: 2_000, payload: { replayedTo: 7 },
    })

    expect(after.previewSuperseded).toBe(true)
  })

  test('快照之后到达的事件按同步点接续，不被当成重复丢弃', () => {
    const afterSnapshot = reduceFrame(emptyState(), {
      type: 'GAME_SNAPSHOT_SYNC', seq: 42, ts: 2_000, payload: { replayedTo: 42 },
    })

    const next = reduceFrame(afterSnapshot, eventFrame(43, 'PHASE_CHANGED'))

    expect(next.seq).toBe(43)
    expect(next.events).toHaveLength(1)
    expect(next.duplicates, '同步点之后的一版不是重复').toBe(0)
  })
})