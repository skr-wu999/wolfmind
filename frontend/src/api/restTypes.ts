/**
 * 后端响应的类型契约（与 docs/backend/api-spec.md 对应）。
 *
 * **这些类型是服务端契约的镜像**，因此：
 * - 只写后端**确实返回**的字段，不预先添加"将来可能会有"的可选字段；
 * - 后端没有的字段不在这里编造（例如房间视图没有 `visibility`），
 *   需要展示时由映射层给默认值并注明；
 * - 字段名与 JSON 一致（后端用 camelCase，因此这里也是）。
 *
 * 生成式共享类型是更好的长期方案（AGENTS.md §4 提到"prefer generated or shared
 * TypeScript types"），当前后端还没有 OpenAPI 输出，因此这份是手工镜像。
 * 它比"页面直接用 any"好在：字段改名会在 `npm run typecheck` 阶段暴露，而不是运行时白屏。
 */

/** 分页信封（api-spec.md §1）。 */
export interface Page<T> {
  items: T[]
  page: number
  pageSize: number
  /** 过滤后的**全量**条数，不是当前页条数。 */
  total: number
}

/** 座位上的控制者（api-spec.md §4）。 */
export interface ControllerView {
  type: 'HUMAN' | 'AI' | 'BOT'
  refId: string
}

/** 座位视图。空座也在列表里，`playerId` 为 null。 */
export interface RoomSeatView {
  seatNo: number
  playerId: string | null
  controller: ControllerView | null
  ready: boolean
}

/** 房间视图（api-spec.md §4）。 */
export interface RoomView {
  roomId: string
  name: string | null
  /** WAITING | RUNNING | CLOSED（后端用这三个值，前端展示层映射为 WAITING | PLAYING）。 */
  status: string
  ownerId: string | null
  ownerSeatNo: number | null
  occupied: number
  humanCount?: number
  aiCount?: number
  capacity: number
  readyCount: number
  seats: RoomSeatView[]
  config: Record<string, unknown>
  rules?: import('@/features/game/rules').RuleSettings | null
  gameId: string | null
  closedReason: string | null
}

/** 开局响应（api-spec.md §5.1）。 */
export interface StartGameResponse {
  gameId: string
  status: string
  version: number
  phase: string
  agentCount: number
  roomId: string
}

/** 注册响应（api-spec.md §3.1）；**不含令牌**。 */
export interface RegisterResponse {
  userId: string
  username: string
  displayName: string
}

/** 登录响应（api-spec.md §3.2）；`sessionToken` 是唯一一次下发明文令牌的地方。 */
export interface LoginResponse {
  userId: string
  sessionToken: string
  expiresAt: number
}

/** 当前账号（api-spec.md §3.3）。 */
export interface CurrentUserResponse {
  userId: string
  username: string
  displayName: string
  /** 该账号当前控制的对局；尚未接入时为 null。 */
  activeGameId: string | null
  /** 该账号订阅中的对局；尚未接入时为空数组。 */
  spectatingGameIds: string[]
}

/** `permissions[]` 的一项（api-spec.md §5.2 的服务端投影字段）。 */
export interface ActionPermission {
  type: string
  targets: number[]
  allowEmpty: boolean | null
  remaining: number | null
}

/** 玩家视图。 */
export interface GamePlayerView {
  playerId: string
  seatNo: number
  /** 只有可见时才下发；不可见时为 null。 */
  role: string | null
  camp: string | null
  alive: boolean
  controller: ControllerView
}

/** 对局视图（api-spec.md §5.2）。 */
export interface GameView {
  gameId: string
  roomId: string | null
  status: string
  phase: string
  round: number
  version: number
  players: GamePlayerView[]
  me?: GamePlayerView | null
  turnIndex?: number
  turnOrder?: number[]
  currentTurn?: { seatNo: number; phase: string; turnIndex: number; deadline: number } | null
  rules?: import('@/features/game/rules').RuleSettings & { turnDeadlineMs?: number; agentTurnDeadlineMs?: number; voteDeadlineMs?: number }
  votes?: { voterSeat: number; targetSeatNo: number | null; abstain: boolean }[]
  winner?: 'WOLF' | 'VILLAGER' | null
  finishReason?: string | null
  /** **服务端投影的可用动作**：前端不得自行推断权限（api-spec.md §5.2）。 */
  permissions: ActionPermission[]
}
