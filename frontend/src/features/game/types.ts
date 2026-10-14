import type { DemoSeat } from '@/features/lobby/types'
import type { RuleSettings } from './rules'

export type GameRole = 'VILLAGER' | 'WEREWOLF' | 'SEER' | 'WITCH' | 'HUNTER' | 'GUARD'
export type DisplayPhase = 'DAYBREAK' | 'DAY_DISCUSSION' | 'DAY_VOTING' | 'VOTE_RESULT' | 'NIGHT_TRANSITION' | 'NIGHT_ACTION' | 'NIGHT_WAITING' | 'NIGHT_RESOLVING' | 'LAST_WORDS' | 'ROLE_REACTION' | 'GAME_OVER'
export type GameActionType = 'SPEAK' | 'VOTE' | 'WOLF_KILL' | 'SEER_INSPECT' | 'WITCH_SAVE' | 'WITCH_POISON' | 'WITCH_PASS' | 'HUNTER_SHOOT' | 'SKIP' | 'WOLF_CHAT' | 'GUARD_PREVIEW'
export interface GamePlayer {
  seatNo: number
  name: string
  avatar: DemoSeat['avatar']
  controller: 'HUMAN' | 'AI'
  alive: boolean
  disclosedRole?: GameRole
}
export interface GameMessage { id: string; seatNo: number; content: string; scope: 'PUBLIC' | 'WOLF'; round: number; phase?: DisplayPhase; order?: number }
export interface GameRecord { id: string; text: string; scope: 'PUBLIC' | 'PRIVATE' | 'WOLF'; round?: number; order?: number }
export interface ActionPermission { type: GameActionType; label: string; targets: number[]; allowEmpty?: boolean; remaining?: number }

// Presentation projection for the local fixture adapter, not a new server DTO.
export interface GamePresentation {
  gameId: string
  version: number
  scenario: string
  roomName: string
  rules?: RuleSettings
  phase: DisplayPhase
  round: number
  atmosphere: 'day' | 'night'
  deadline: number | null
  windowMs: number
  serverNow: number
  players: GamePlayer[]
  me: { seatNo: number; role: GameRole; alive: boolean }
  speakerSeatNo: number | null
  turnOrder: number[]
  completedSpeakers: number[]
  messages: GameMessage[]
  preview: { speechId: string; seatNo: number; content: string } | null
  permissions: ActionPermission[]
  records: GameRecord[]
  privateRecords: GameRecord[]
  wolfTeam: number[]
  wolfMessages: GameMessage[]
  aliveCount: number
  eliminatedCount: number
  votedCount: number | null
  submitted: string | null
  result: { title: string; content: string; tally?: { seatNo: number; count: number }[] } | null
  privateResult: { title: string; content: string } | null
}
/**
 * 动作类型 → 按钮文案。
 *
 * **这份表必须在展示层，而不是从服务端字段里取。** 后端的 `permissions[].type` 是动作名
 * （`VOTE`、`WOLF_KILL`），它属于接口契约；文案属于展示词汇，和 `roleNames`、`phaseNames`
 * 是同一类东西。映射层（`api/mapGame.ts`）因此查这张表来填 `label`——
 * 若把中文写进映射层，"改一句文案要动接口适配代码"，而接口适配本该只关心字段形状。
 *
 * 用 `Record<GameActionType, string>` 而不是宽松索引：新增动作类型时**编译期**就会报缺项，
 * 而不是等界面上出现一个空白按钮。
 */
export const actionNames: Record<GameActionType, string> = {
  SPEAK: '提交发言',
  VOTE: '确认投票',
  WOLF_KILL: '确认袭击',
  SEER_INSPECT: '确认查验',
  WITCH_SAVE: '使用解药',
  WITCH_POISON: '确认使用毒药',
  WITCH_PASS: '今晚不用药',
  HUNTER_SHOOT: '确认开枪',
  SKIP: '跳过本回合',
  WOLF_CHAT: '发送密谈',
  // **仅预览**类型：不是可提交动作，保留文案是为了演示模式能显示它。
  GUARD_PREVIEW: '确认守护',
}
export interface GameCommand { commandId: string; expectedVersion: number; actionType: GameActionType; params: { targetSeatNo?: number; abstain?: boolean; giveUp?: boolean; content?: string } }
export type GameConnection = 'local' | 'disconnected' | 'syncing'
export interface GameAdapter {
  get(gameId: string): Promise<GamePresentation>
  scenario(gameId: string, scenario: string): Promise<GamePresentation>
  submit(gameId: string, command: GameCommand): Promise<GamePresentation>
  subscribe(gameId: string, receive: (view: GamePresentation) => void, connection?: (state: GameConnection) => void): () => void
}
export const roleNames: Record<GameRole, string> = { VILLAGER: '村民', WEREWOLF: '狼人', SEER: '预言家', WITCH: '女巫', HUNTER: '猎人', GUARD: '守卫' }
export const roleDescriptions: Record<GameRole, string> = {
  VILLAGER: '倾听发言，找出隐藏的狼人。', WEREWOLF: '与同伴共同选择今晚的袭击目标。', SEER: '夜晚查验一名玩家的阵营。', WITCH: '掌握一瓶解药与一瓶毒药。', HUNTER: '获得开枪资格时，发动最后一枪。', GUARD: '守护目标的扩展职业 UI 预览。',
}
export const phaseNames: Record<DisplayPhase, string> = {
  DAYBREAK: '天亮了', DAY_DISCUSSION: '白天讨论', DAY_VOTING: '放逐投票', VOTE_RESULT: '投票结算', NIGHT_TRANSITION: '天黑了', NIGHT_ACTION: '夜间行动', NIGHT_WAITING: '夜间等待', NIGHT_RESOLVING: '等待天亮', LAST_WORDS: '遗言', ROLE_REACTION: '猎人行动', GAME_OVER: '游戏结束',
}
export const finishReasonNames: Record<string, string> = {
  ALL_WOLVES_ELIMINATED: '所有狼人已出局。',
  WOLVES_OUTNUMBER_VILLAGE: '存活狼人数量已不少于好人阵营。',
  ALL_VILLAGERS_ELIMINATED: '所有平民已出局。',
  ALL_GODS_ELIMINATED: '所有神职已出局。',
  STALLED: '连续多轮无人出局，对局已中止，不计胜负。',
  NO_HUMANS: '所有真人已离开，本局中止，不计胜负。',
  ROOM_CLOSED: '房间已关闭，本局中止，不计胜负。',
}
export const gameScenarios = [
  ['discussion', '白天 · AI 发言'], ['my-turn', '白天 · 你的发言'], ['voting', '白天 · 放逐投票'], ['daybreak', '天亮 · 昨夜消息'], ['vote-result', '白天 · 投票结算'],
  ['transition', '天黑 · 昼夜过渡'], ['seer', '黑夜 · 预言家'], ['wolf', '黑夜 · 狼人'], ['witch', '黑夜 · 女巫'], ['villager', '黑夜 · 村民等待'], ['hunter-wait', '黑夜 · 猎人等待'], ['resolving', '黑夜 · 等待结算'],
  ['hunter', '触发 · 猎人开枪'], ['game-over', '结束 · 胜负结算'], ['guard', '扩展预览 · 守卫'],
] as const
