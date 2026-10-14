import type { ActionPermission as ServerPermission, GamePlayerView, GameView } from './restTypes'
import type { DemoSeat } from '@/features/lobby/types'
import type { AppliedEvent } from './gameStream'
import { actionNames, finishReasonNames, phaseNames } from '@/features/game/types'
import type {
  ActionPermission,
  DisplayPhase,
  GameActionType,
  GamePlayer,
  GamePresentation,
  GameRole,
} from '@/features/game/types'

/**
 * 后端 `GameView` → 前端展示模型 `GamePresentation` 的映射。
 *
 * **这里最容易犯的错是"编造服务端没给的字段"。** `GamePresentation` 有 24 个字段，
 * 而后端视图只提供其中一部分——余下的是展示层概念（气围、发言顺序、记录列表）或
 * **尚未接入的能力**。每一处缺口都在下面显式注明并给出保守默认值，因为编一个值
 * 比留空更糟：界面会显示一个用户以为来自服务端的假信息，而对局界面上的假信息
 * （比如"还剩 3 人"）会直接导致错误决策。
 *
 * **特别注意 `permissions[]`**：后端已经把它投影好了（api-spec.md §5.2），
 * 而前端的 `ActionPermission` 要求一个 `label` 字段——后端没有。这里用动作类型名兜底，
 * **不猜中文标签**：标签属于展示层词汇表，应该由界面维护一份映射，
 * 而不是从服务端字段里凭空长出来。
 */

/** 后端阶段 → 展示阶段。 */
const PHASE_MAP: Record<string, DisplayPhase> = {
  NIGHT_WOLF: 'NIGHT_ACTION',
  NIGHT_SEER: 'NIGHT_ACTION',
  NIGHT_WITCH: 'NIGHT_ACTION',
  NIGHT_SETTLE: 'NIGHT_RESOLVING',
  DAY_ANNOUNCE: 'DAYBREAK',
  LAST_WORDS: 'LAST_WORDS',
  HUNTER_SHOOT: 'ROLE_REACTION',
  DAY_DISCUSS: 'DAY_DISCUSSION',
  DAY_VOTE: 'DAY_VOTING',
  VOTE_SETTLE: 'VOTE_RESULT',
  GAME_OVER: 'GAME_OVER',
}

/** 夜晚阶段集合；用于推导 `atmosphere`。 */
const NIGHT_PHASES = new Set([
  'NIGHT_WOLF',
  'NIGHT_SEER',
  'NIGHT_WITCH',
  'NIGHT_SETTLE',
])

/** 后端角色 → 展示角色。 */
const ROLE_MAP: Record<string, GameRole> = {
  VILLAGER: 'VILLAGER',
  WEREWOLF: 'WEREWOLF',
  SEER: 'SEER',
  WITCH: 'WITCH',
  HUNTER: 'HUNTER',
  // 后端经典九人没有守卫；保留映射是为了将来扩展时不至于静默丢失。
  GUARD: 'GUARD',
}

/** 座位头像分配；与大厅用同一套，保证用户在不同页面看到同一张脸。 */
const AVATARS: DemoSeat['avatar'][] = ['green', 'blue', 'red', 'wolf', 'owl', 'fox']

function avatarFor(seatNo: number): DemoSeat['avatar'] {
  return AVATARS[(seatNo - 1) % AVATARS.length]!
}

/**
 * `permissions[]` 的动作类型白名单。
 *
 * 后端的 `type` 是动作名，而前端的 `GameActionType` 还包含**仅用于预览**的 `GUARD_PREVIEW`，
 * 它不是可提交动作。因此这里过滤而不是直接转换：把预览类型当成可提交动作会让按钮出现却点了报错。
 */
const SUBMITTABLE: Record<string, GameActionType> = {
  SPEAK: 'SPEAK',
  VOTE: 'VOTE',
  WOLF_KILL: 'WOLF_KILL',
  SEER_INSPECT: 'SEER_INSPECT',
  WITCH_SAVE: 'WITCH_SAVE',
  WITCH_POISON: 'WITCH_POISON',
  WITCH_PASS: 'WITCH_PASS',
  HUNTER_SHOOT: 'HUNTER_SHOOT',
  SKIP: 'SKIP',
  WOLF_CHAT: 'WOLF_CHAT',
}

/**
 * 后端权限 → 前端权限。
 *
 * `label` 从展示层的 {@link actionNames} 查表：后端不下发标签，而**中文文案不属于这一层**——
 * 它是展示词汇，和 `roleNames` / `phaseNames` 是同一类东西。表定义在
 * `features/game/types.ts`，因此"改文案"与"改接口适配"是两件互不影响的事。
 */
export function toPermission(permission: ServerPermission): ActionPermission | null {
  const type = SUBMITTABLE[permission.type]
  if (!type) return null
  return {
    type,
    // 查展示层的文案表；后端只给动作名。
    label: actionNames[type],
    targets: permission.targets,
    allowEmpty: permission.allowEmpty ?? undefined,
    remaining: permission.remaining ?? undefined,
  }
}

function toPlayer(player: GamePlayerView): GamePlayer {
  return {
    seatNo: player.seatNo,
    name: `玩家 ${player.seatNo}`,
    avatar: avatarFor(player.seatNo),
    // 后端只有 HUMAN / AI / BOT；展示层区分 HUMAN 与 AI，BOT 归到 AI
    // （两者都是程序控制，对用户而言是同一件事）。
    controller: player.controller.type === 'HUMAN' ? 'HUMAN' : 'AI',
    alive: player.alive,
    // 只有可见时才下发角色；不可见时为 undefined 而不是编一个"村民"。
    disclosedRole: player.role ? ROLE_MAP[player.role] : undefined,
  }
}

/**
 * 视图里的"我"。
 *
 * 优先使用服务端授权的 `me`；只有旧视图未包含此字段时才读取本地座位。
 *
 * 找不到自己的座位时返回 `{seatNo: 0, role: 'VILLAGER', alive: false}` 而不是抛异常：
 * 观战者与未入座的用户本就没有座位，而**把"没有座位"当成错误会让观战页崩掉**。
 * `seatNo: 0` 是一个明确的"无座位"标记，界面据此隐藏"我的操作"区域。
 */
function toMe(view: GameView, mySeatNo: number | null) {
  if (view.me === null) return { seatNo: 0, role: 'VILLAGER' as GameRole, alive: false }
  if (view.me) {
    return { seatNo: view.me.seatNo, role: ROLE_MAP[view.me.role ?? ''] ?? 'VILLAGER' as GameRole, alive: view.me.alive }
  }
  if (mySeatNo === null) {
    return { seatNo: 0, role: 'VILLAGER' as GameRole, alive: false }
  }
  const mine = view.players.find((player) => player.seatNo === mySeatNo)
  if (!mine) {
    return { seatNo: 0, role: 'VILLAGER' as GameRole, alive: false }
  }
  return {
    seatNo: mySeatNo,
    // 看不到自己的角色时（例如刚开局还没收到 ROLE_ASSIGNED）默认村民：
    // 这是显示层的占位，而 `disclosedRole` 缺省才是"服务端没给"的真实表达。
    role: (mine.role ? ROLE_MAP[mine.role] : undefined) ?? 'VILLAGER',
    alive: mine.alive,
  }
}

/**
 * 把后端对局视图转成展示模型。
 *
 * @param view      后端视图（api-spec.md §5.2）
 * @param mySeatNo  我的座位号；观战者传 null
 */
export function toGamePresentation(view: GameView, mySeatNo: number | null, events: AppliedEvent[] = [], serverNow = Date.now()): GamePresentation {
  const terminal = view.status === 'FINISHED' || view.status === 'ABORTED'
  const alive = view.players.filter((player) => player.alive)
  const ordered = events.filter(event => event.seq <= view.version).sort((a, b) => a.seq - b.seq)
  const phaseEvent = ordered.filter(event => event.type === 'PHASE_CHANGED').at(-1)
  const phaseDeadline = phaseEvent?.payload.deadline
  const deadline = view.currentTurn?.deadline ?? (typeof phaseDeadline === 'number' ? phaseDeadline : null)
  const privateEvents = ordered.filter(event => event.type === 'SEER_INSPECTED' && Number(event.payload.seerSeatNo) === (view.me?.seatNo ?? mySeatNo))
  const privateRecords = privateEvents.map(event => ({ id: String(event.seq), scope: 'PRIVATE' as const, round: event.round, order: event.seq,
    text: `${event.payload.targetSeatNo} 号的阵营：${event.payload.result === 'WOLF' ? '狼人' : '好人'}` }))
  return {
    gameId: view.gameId,
    version: view.version,
    // 后端没有"剧本"概念，经典九人是当前唯一规则集。
    scenario: '经典九人',
    roomName: view.roomId ?? '',
    rules: view.rules,
    phase: terminal ? 'GAME_OVER' : PHASE_MAP[view.phase] ?? 'NIGHT_WAITING',
    round: view.round,
    atmosphere: !terminal && NIGHT_PHASES.has(view.phase) ? 'night' : 'day',

    // 回合截止取视图，阶段截止取已授权的 PHASE_CHANGED；缺失时不显示倒计时。
    deadline: terminal ? null : deadline,
    windowMs: view.currentTurn ? (view.players.find(player => player.seatNo === view.currentTurn!.seatNo)?.controller.type === 'AI'
      ? view.rules?.agentTurnDeadlineMs : view.rules?.turnDeadlineMs) ?? 0
      : (view.phase === 'DAY_VOTE' ? view.rules?.voteDeadlineMs : view.rules?.turnDeadlineMs) ?? 0,
    serverNow,
    speakerSeatNo: terminal ? null : view.currentTurn?.seatNo ?? null,
    turnOrder: view.turnOrder ?? [],
    completedSpeakers: (view.turnOrder ?? []).slice(0, view.turnIndex ?? 0),

    players: view.players.map(toPlayer),
    me: toMe(view, mySeatNo),

    // 发言和技能摘要只映射授权回放中的已提交事实。
    messages: ordered.filter(event => event.type === 'PLAYER_SPOKE').map(event => ({
      id: String(event.payload.speechId ?? event.seq), seatNo: Number(event.payload.seatNo),
      content: String(event.payload.content ?? ''), scope: 'PUBLIC', round: event.round,
      phase: PHASE_MAP[String(event.payload.phase ?? event.phase)], order: event.seq,
    })),
    preview: null,
    records: ordered.flatMap(event => {
      const metadata = { round: event.round, order: event.seq, scope: 'PUBLIC' as const }
      if (event.type === 'DEATH_ANNOUNCED') return ((event.payload.deaths ?? []) as { seatNo: number }[]).map(death => ({ ...metadata, id: `${event.seq}:${death.seatNo}`, text: `${death.seatNo} 号已出局` }))
      const phase = PHASE_MAP[String(event.type === 'PHASE_CHANGED' ? event.payload.to ?? event.payload.phase ?? event.phase : event.payload.phase ?? event.phase)]
      let text: string | undefined
      if (event.type === 'PHASE_CHANGED') text = phase ? phaseNames[phase] : '阶段已更新'
      else if (event.type === 'TURN_GRANTED' && (phase === 'DAY_DISCUSSION' || phase === 'LAST_WORDS') && typeof event.payload.seatNo === 'number') text = `轮到 ${event.payload.seatNo} 号发言`
      else if (event.type === 'VOTE_TALLY_RESOLVED') text = '放逐投票已结算'
      else if (event.type === 'GAME_FINISHED') text = '对局已结束'
      else if (event.type === 'GAME_ABORTED') text = '对局已中止'
      return text ? [{ ...metadata, id: String(event.seq), text }] : []
    }),
    privateRecords,

    // **权限直接用服务端的投影**，不在前端推断。api-spec.md §5.2 明确这是服务端字段，
    // 而前端自算"我能不能投票"会在阶段边界上与服务端不一致（那时按钮可点却报错）。
    permissions: view.permissions
      .map(toPermission)
      .filter((permission): permission is ActionPermission => permission !== null),

    // 狼队可见性由服务端的可见性过滤决定：能看到 `WEREWOLF` 角色就说明我是狼，
    // 因此队友列表只由"谁的角色对我可见"推导，不做额外判断。
    wolfTeam: view.players
      .filter((player) => player.role === 'WEREWOLF')
      .map((player) => player.seatNo),
    // WOLF_CHAT_SENT 是 WOLF 可见事件，回放接口只把它发给狼人（以及终局后的全员）。
    wolfMessages: ordered.filter(event => event.type === 'WOLF_CHAT_SENT').map(event => ({
      id: String(event.payload.chatId ?? event.seq), seatNo: Number(event.payload.wolfSeatNo),
      content: String(event.payload.content ?? ''), scope: 'WOLF', round: event.round, order: event.seq,
    })),

    aliveCount: alive.length,
    eliminatedCount: view.players.length - alive.length,
    // 兼容缺少公开票型字段的旧视图。
    votedCount: view.votes?.length ?? null,

    // 不通过权限消失猜测提交结果；胜负使用服务器终局字段。
    submitted: null,
    result: view.status === 'FINISHED' && view.winner ? {
      title: view.winner === 'WOLF' ? '狼人阵营获胜' : '好人阵营获胜', content: finishReasonNames[view.finishReason ?? ''] ?? '对局已由服务端结算。',
    } : view.status === 'ABORTED' ? {
      title: '对局中止', content: finishReasonNames[view.finishReason ?? ''] ?? '对局已由服务端中止。',
    } : null,
    privateResult: privateRecords.length ? { title: '查验结果', content: privateRecords.at(-1)!.text } : null,
  }
}
