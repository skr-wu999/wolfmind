import type { GamePlayer, GameRole } from '@/features/game/types'

export type EventScope = 'PUBLIC' | 'WOLF' | 'PRIVATE' | 'JUDGE'
/**
 * 棋盘上的阶段文案，取值与领域 `GamePhase` 一一对应
 * （`backend/engine/.../domain/game/model/GamePhase.java`）。
 *
 * 夜间子阶段必须分开写：`PHASE_CHANGED` 是 PUBLIC 事件，"现在轮到谁行动"本身就是公开信息，
 * 把狼人/预言家/女巫折叠成同一个"黑夜行动"会让回放的阶段下拉框无法分辨。
 *
 * `unknownPhase` 是**明确的未知占位**：未知阶段渲染成 `undefined` 会让页面出现
 * "第 3 天 · undefined"（QA P2）——那不是"没有信息"，而是一个缺陷。
 */
export const unknownPhase = '未知阶段'
export type ObservationPhase =
  | '等待开局' | '狼人行动' | '预言家查验' | '女巫用药' | '夜间结算'
  | '公布死讯' | '遗言' | '白天讨论' | '放逐投票' | '投票结算' | '猎人开枪' | '游戏结束'
  | typeof unknownPhase
const nightPhases: readonly ObservationPhase[] = ['狼人行动', '预言家查验', '女巫用药', '夜间结算']
/** 是否处于夜间。棋盘配色与棋子动画只认这个判断，不认具体是哪个夜间子阶段。 */
export function isNightPhase(phase?: ObservationPhase): boolean {
  return phase !== undefined && nightPhases.includes(phase)
}
export type ObservationConnection = 'connecting' | 'syncing' | 'live' | 'reconnecting' | 'closed' | 'login-required' | 'finished'
// Local presentation records; the production adapter must map authorized server events.
export interface ObservationEvent {
  id: string
  version: number
  at: number
  type: string
  title: string
  content: string
  scope: EventScope
  seatNo?: number
  recipient?: number
  change?: { phase?: ObservationPhase; round?: number; eliminated?: number; winner?: string }
  rationale?: string
}
export interface ObservationMatch {
  gameId: string
  roomName: string
  startedAt: string
  duration: number
  initialPosition: number
  /**
   * 玩家列表。
   *
   * `disclosedRole` **是可选的**：对局未结束时后端不下发角色，
   * 而观战页是公开路由——披露未结束对局的身份就是把隐藏信息泄漏给正在看直播的人。
   *
   * 声明为必填会迫使实现填一个中性值（例如 `VILLAGER`），那是**伪造信息**：
   * 观战者会以为他看到的村民是事实，而那只是"没披露"。
   */
  players: (GamePlayer & { disclosedRole?: GameRole })[]
  events: ObservationEvent[]
  snapshot?: { version: number; phase: ObservationPhase; round: number; speaker: number | null; winner: string | null; finished: boolean }
}
/**
 * 某个领域版本上的权威棋盘（`GET /games/{id}/replay?atVersion=N` 的 `snapshot`）。
 *
 * 历史存活与阶段必须由服务端重建（api-spec.md §6、AP-6）。前端拿终局视图加事件增量自己重演一
 * 遍会有两个必然错误：当前视角看不见的淘汰事件根本不在手里，而终局的 `alive` 会被当成开局状态
 * （QA P1：回到开局后 1、2、3、4、7、8、9 号仍是已淘汰）。
 */
export interface ObservationBoard {
  version: number
  phase: ObservationPhase
  round: number
  speaker: number | null
  winner: string | null
  finished: boolean
  players: (GamePlayer & { disclosedRole?: GameRole })[]
}
export interface ObservationAdapter {
  get(gameId: string, signal?: AbortSignal): Promise<ObservationMatch>
  /**
   * 读取某个领域版本的棋盘；服务端没有该版本的历史时为 `null`。
   *
   * **可选**：只有能给出权威历史状态的真实适配器才实现它。纯前端 fixture 适配器省略它，
   * store 会退回本地投影——省略是合法状态，不是"还没实现"。
   */
  boardAt?(gameId: string, version: number, signal?: AbortSignal): Promise<ObservationBoard | null>
  subscribe?(gameId: string, version: number, callbacks: {
    onMatch: (match: ObservationMatch) => void
    onState: (state: ObservationConnection) => void
    onError: (message: string) => void
  }): () => void
}
export const scopeNames: Record<EventScope, string> = { PUBLIC: '公开', WOLF: '狼人阵营', PRIVATE: '角色私有', JUDGE: '裁判' }
export function formatTime(seconds: number) {
  const value = Math.max(0, Math.floor(seconds))
  return `${Math.floor(value / 60).toString().padStart(2, '0')}:${(value % 60).toString().padStart(2, '0')}`
}
