import type { RuleSettings } from '@/features/game/rules'

export type LobbyFilter = 'ALL' | 'PUBLIC' | 'PLAYING' | 'WAITING'
export type DemoRoomStatus = 'WAITING' | 'PLAYING' | 'CLOSED'
export type DemoRoomVisibility = 'PUBLIC' | 'INVITE'

// Local presentation models; GET /rooms item fields are still awaiting a shared contract.
export interface DemoRoomSummary {
  roomId: string
  gameId?: string | null
  name: string
  description: string
  modeLabel: string
  occupied: number
  humanCount?: number
  aiCount?: number
  /**
   * 已就绪座位数；等待房据此显示"3/9 已准备"。
   *
   * 后端 `RoomView.readyCount` 一直有，但展示模型此前漏了它——后果是等待房只能显示
   * "几人入座"，而开局条件是**全员就绪**（INV-R2）：用户看到"9/9 入座"却点不动开始，
   * 不知道还差什么。
   */
  readyCount: number
  capacity: 9
  status: DemoRoomStatus
  visibility: DemoRoomVisibility
  canJoin: boolean
  illustration: 'red' | 'grey' | 'trees'
}
export interface DemoSeat {
  seatNo: number
  name: string | null
  isMe: boolean
  controller: 'HUMAN' | 'AI' | null
  ready: boolean
  avatar: 'green' | 'blue' | 'red' | 'wolf' | 'owl' | 'fox'
}
export interface DemoRoom extends DemoRoomSummary {
  rules?: RuleSettings
  seats: DemoSeat[]
  mySeatNo: number | null
  ownerSeatNo: number | null
  isOwner?: boolean
  gameId?: string | null
}
export interface CreateDemoRoom {
  name: string
  visibility: DemoRoomVisibility
}
export interface RoomAdapter {
  list(): Promise<DemoRoomSummary[]>
  get(roomId: string): Promise<DemoRoom>
  create(input: CreateDemoRoom): Promise<DemoRoom>
  join(roomId: string): Promise<DemoRoom>
  leave(roomId: string): Promise<void>
  takeSeat(roomId: string, seatNo: number): Promise<DemoRoom>
  ready(roomId: string): Promise<DemoRoom>
  setAi(roomId: string, seatNo: number, enabled: boolean): Promise<DemoRoom>
}
export class LobbyError extends Error {
  constructor(public code: string, message: string) { super(message) }
}
