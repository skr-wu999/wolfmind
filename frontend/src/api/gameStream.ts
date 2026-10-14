import type { GameView, RoomView } from './restTypes'
import type { Page } from './restTypes'
import { request } from './apiClient'

/**
 * 事件归约层：把 WebSocket 帧归约成界面状态。
 *
 * **它不重建对局状态。** 一个很容易走错的方向是让前端按事件自己算出"谁死了、谁是什么角色"，
 * 但那是把规则判定搬到客户端（AGENTS.md §9：服务端对计时、回合顺序、权限与结果保持权威）。
 * 前端重建的版本一旦与服务端不同，用户就会看到与服务器不一致的局面，而两边都"有依据"。
 *
 * 这一层做的是另外三件事，它们才是真正需要在前端保证的：
 * 1. **按 seq 去重**——投递是至少一次语义，重复帧必须被丢弃；
 * 2. **区分"缺口"与"这是我看不到的"**——服务端按可见性过滤后本就跳过版本，
 *    因此**客户端绝不能把 seq 不连续当成错误**；
 * 3. **在状态变化时拉取权威视图**，并记录事件流供水位与调试使用。
 */

/** 归约后的状态。 */
export interface GameStreamState {
  /** 权威视图；来自 REST，每次刷新后被替换。 */
  view: GameView | null
  /** 已归约到的最大 seq。重连时把它作为 `lastSeq` 交给 RESUME。 */
  seq: number
  /** 已展示的事件（倒序，最新的在前）；供水位与事件列表使用。 */
  events: AppliedEvent[]
  /** 被丢弃的重复帧数；用于诊断"服务端是否在重发"。 */
  duplicates: number
  /** 收到过 SYNC_REQUIRED，需要一次全量重建。 */
  needsResync: boolean
  /** 需要重新拉取权威视图。 */
  dirty: boolean
  /**
   * 已提交的发言已取代预览：`preview` 不再代表"正在说的那句话"。
   *
   * <p>用**语义信号**而不是超时来清除预览，理由是后端目前**每条增量都带 `final=true`**
   * （Agent 用结构化 JSON 单次返回，没有增量来源），因此"收到 final 就启动倒计时"
   * 会立刻开始计时；而真正的增量源接上之后，那个倒计时又必须在每段到达时重置——
   * 一个只在"当前恰好没有增量源"时正确的实现。
   *
   * <p>语义判据是稳定的：**发言一旦提交，预览就被它取代了**。
   * 无论将来有真增量还是仍是一次性投递，这条都成立。
   */
  previewSuperseded: boolean
}

/** 一条已归约的事件（只保留展示需要的字段）。 */
export interface AppliedEvent {
  seq: number
  type: string
  phase: string
  round: number
  occurredAt: number
  actorId: string
  payload: Record<string, unknown>
}

/** 事件的初始状态。 */
export function emptyState(): GameStreamState {
  return {
    view: null, seq: 0, events: [], duplicates: 0,
    needsResync: false, dirty: false, previewSuperseded: false,
  }
}

/**
 * 哪些事件会让**权威视图**发生变化，从而需要重新拉取。
 *
 * 它是一份显式清单而不是"所有事件都拉"：后者会在夜间每一条狼人行动后都打一次 REST，
 * 而其中大部分不影响界面。漏掉一个的代价是界面滞后一次刷新——比每次都拉轻得多。
 *
 * <b>名字必须与后端 `EventType` 逐字一致。</b>我第一版是按印象写的，里面有
 * `PLAYER_DIED`、`VOTE_RESOLVED`、`SPEECH_COMMITTED`、`REVEAL_PUBLISHED` 四个
 * **后端根本不存在**的事件名，而真正的 `PLAYER_KILLED`、`DEATH_ANNOUNCED`、
 * `VOTE_TALLY_RESOLVED`、`PLAYER_SPOKE` 一个都没写。
 * 后果是**玩家死亡不刷新界面**——玩家列表永远停在开局那一刻。
 *
 * 这个缺陷不会被任何现有测试发现：单测喂的是我自己构造的事件名，
 * 因此它验证的是"清单里的名字能触发刷新"，而不是"清单里的名字是对的"。
 * 真正的保护是下面这份注释里的对照表与 `gameStreamEventNames.test.ts` 的断言。
 *
 * 后端 `EventType` 的全部取值（33 个，见 `engine/.../event/EventType.java`）：
 * GAME_STARTED / ROLE_ASSIGNED / PHASE_CHANGED / TURN_GRANTED / PLAYER_SPOKE /
 * SPEECH_SKIPPED / WOLF_KILL_NOMINATED / WOLF_KILL_SELECTED / WOLF_CHAT_SENT / SEER_INSPECTED /
 * SEER_SKIPPED / WITCH_SAVED / WITCH_POISONED / WITCH_PASSED / HUNTER_SHOT /
 * HUNTER_SKIPPED / PLAYER_VOTED / VOTE_TALLY_RESOLVED / PLAYER_KILLED /
 * DEATH_ANNOUNCED / GAME_FINISHED / GAME_ABORTED / GAME_RECOVERED /
 * PLAYER_CONTROLLER_CHANGED + 房间 6 个 + 账号 3 个。
 */
export const VIEW_MUTATING = new Set([
  // 现状变化：阶段、存活、控制者、结果。
  'PHASE_CHANGED',
  'PLAYER_KILLED',
  'DEATH_ANNOUNCED',
  'PLAYER_CONTROLLER_CHANGED',
  'GAME_FINISHED',
  'GAME_ABORTED',
  'VOTE_TALLY_RESOLVED',
  // 自己的角色与发言权限。
  'ROLE_ASSIGNED',
  'GAME_STARTED',
  // 发言会进 `speeches` 时间线（视图的一部分），而 `DAY_DISCUSS` 里的发言
  // 也可能改变"轮到我了吗"。
  'PLAYER_SPOKE',
  'SPEECH_SKIPPED',
  'TURN_GRANTED',
  'PLAYER_VOTED',
  'WOLF_KILL_NOMINATED',
  // 狼队夜聊：消息与剩余条数（permissions.remaining）都要刷新。
  'WOLF_CHAT_SENT',
  'SEER_INSPECTED',
  'SEER_SKIPPED',
  'WITCH_SAVED',
  'WITCH_POISONED',
  'WITCH_PASSED',
  'HUNTER_SHOT',
  'HUNTER_SKIPPED',
])

/** 帧的最小形状；与 `gameSocket.ServerFrame` 兼容，但不依赖它，便于测试直接构造。 */
export interface ReducibleFrame {
  type: string
  seq: number
  ts: number
  payload: Record<string, unknown>
}

/**
 * 归约一帧。
 *
 * **返回新状态而不是就地修改**：调用方（Pinia store）需要能比较前后，
 * 而就地修改会让"有没有变化"无法判断，store 就会无条件触发重渲染。
 *
 * @param state 当前状态
 * @param frame 服务端帧
 * @returns 归约后的状态；帧被丢弃时返回**同一个对象**（调用方据此跳过更新）
 */
export function reduceFrame(state: GameStreamState, frame: ReducibleFrame): GameStreamState {
  switch (frame.type) {
    case 'GAME_SNAPSHOT_SYNC':
      // 全量同步：以服务端给的水位为准重建，并把之前的事件清掉——
      // 保留旧事件会让界面显示出"同步点之前的"与"同步点之后的"混在一起的时间线。
      return {
        ...state,
        seq: frame.seq,
        events: [],
        needsResync: false,
        dirty: true,
        // 重建之后预览也该空着：它对应的是重建之前的那条发言。
        previewSuperseded: true,
      }

    case 'GAME_EVENT':
      return reduceGameEvent(state, frame)

    default:
      // SUBSCRIBED / AUTH_OK / PONG 等不推进状态：返回同一对象，调用方据此跳过更新。
      return state
  }
}

function reduceGameEvent(state: GameStreamState, frame: ReducibleFrame): GameStreamState {
  // 至少一次投递：重复必须丢弃。
  if (frame.seq <= state.seq) {
    return { ...state, duplicates: state.duplicates + 1 }
  }

  // **seq 不连续是正常的**：服务端按可见性过滤之后，村民收到的本来就是被跳过版本的序列。
  // 把它当错误会导致村民每隔几帧就触发一次不必要的全量重建。
  // 真正需要重建的信号只有 SYNC_REQUIRED，由服务端明确发出。
  const payload = frame.payload
  const inner = (payload.payload ?? {}) as Record<string, unknown>
  const event: AppliedEvent = {
    seq: frame.seq,
    type: String(payload.type ?? ''),
    phase: String(payload.phase ?? ''),
    round: Number(payload.round ?? 0),
    occurredAt: Number(payload.occurredAt ?? frame.ts),
    actorId: String(payload.actorId ?? ''),
    payload: inner,
  }

  return {
    ...state,
    seq: frame.seq,
    // 最新的在前，并限制长度：一局有几百条事件，全留着会让长局的内存持续增长。
    events: [event, ...state.events].slice(0, MAX_EVENTS),
    dirty: state.dirty || VIEW_MUTATING.has(event.type),
    // 发言一经提交，`preview`（正在说的那句话）就被它取代。
    // 与 `dirty` 同一处设置：都由"事件到达"驱动，都不依赖时序。
    previewSuperseded: state.previewSuperseded || SPEECH_COMMITTING.has(event.type),
  }
}

/**
 * 会**提交或跳过发言**的事件。
 *
 * 它们一旦到达，`preview` 就被取代了——`preview` 的语义是"正在说的那句话"，
 * 而已经提交的发言不再是"正在说"。
 *
 * 名字与后端 `EventType` 逐字一致，由 `eventNameContract.test.ts` 守着。
 */
export const SPEECH_COMMITTING = new Set(['PLAYER_SPOKE', 'SPEECH_SKIPPED'])

/** 保留在内存里的事件条数上限。 */
export const MAX_EVENTS = 200

/** 收到 SYNC_REQUIRED：标记需要全量重建。 */
export function markResyncRequired(state: GameStreamState): GameStreamState {
  return { ...state, needsResync: true, dirty: true }
}

/**
 * 用新拉到的权威视图替换状态，并清除脏标记。
 *
 * `view.version` 会成为新的水位下限：它保证"视图比事件新"时不会把旧事件重新放进来。
 */
export function applyView(state: GameStreamState, view: GameView): GameStreamState {
  return {
    ...state,
    view,
    // 不把 seq 抬到 view.version：那个版本的事件可能对本视图不可见，
    // 抬上去会让之后真正收到的那条被当成重复而丢弃。
    dirty: false,
    needsResync: false,
  }
}
