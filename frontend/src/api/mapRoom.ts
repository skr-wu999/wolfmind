import type { RoomSeatView, RoomView } from './restTypes'
import type { DemoRoom, DemoRoomStatus, DemoRoomSummary, DemoRoomVisibility, DemoSeat } from '@/features/lobby/types'

/**
 * 后端 `RoomView` → 前端展示模型 `DemoRoom` 的映射。
 *
 * **为什么需要这一层**：前端的 `DemoRoom*` 有自己的字段（`description`、`modeLabel`、
 * `illustration`、`avatar`），它们是**展示**决定而不是服务端契约。让页面直接吃后端字段
 * 会把"配色方案"和"接口字段"绑在一起——改一次视觉就要动接口层的类型。
 *
 * 映射里每一处"后端没有对应字段"都显式给默认值并注明，**不猜**：
 * 猜一个 `description` 比留空更糟，因为界面会显示一个用户以为来自服务端的假信息。
 */

/** 后端状态 → 展示状态。后端用 RUNNING，前端用 PLAYING，这是刻意分开的两个词。 */
export function toDemoStatus(status: string): DemoRoomStatus {
  return status === 'RUNNING' ? 'PLAYING' : status === 'CLOSED' ? 'CLOSED' : 'WAITING'
}

/**
 * 座位头像与插画的分配。
 *
 * 按座位号取模分配，因此**同一座位在任何时候看到同一个头像**——随机会让每次渲染都换脸，
 * 而头像的作用恰恰是让用户认出"这是刚才那个人"。
 */
const AVATARS: DemoSeat['avatar'][] = ['green', 'blue', 'red', 'wolf', 'owl', 'fox']
const ILLUSTRATIONS: DemoRoomSummary['illustration'][] = ['red', 'grey', 'trees']

/**
 * 按座位号取模分配头像。
 *
 * 导出是为了让**观战页与等待房对同一个座位给出同一个头像**：
 * 两处各自实现一套取模迟早会漂移，而头像的作用正是让人认出是同一个人。
 */
export function avatarFor(seatNo: number): DemoSeat['avatar'] {
  return AVATARS[(seatNo - 1) % AVATARS.length]!
}

function illustrationFor(roomId: string): DemoRoomSummary['illustration'] {
  let hash = 0
  for (let i = 0; i < roomId.length; i++) hash = (hash + roomId.charCodeAt(i)) % ILLUSTRATIONS.length
  return ILLUSTRATIONS[hash]!
}

function seatToDemo(seat: RoomSeatView, mySeatNo: number | null): DemoSeat {
  const controller = seat.controller?.type === 'AI' ? 'AI' : seat.playerId ? 'HUMAN' : null
  return {
    seatNo: seat.seatNo,
    // 后端只给不透明 id，没有昵称字段；这里显示座位号，而不是编一个名字。
    name: seat.playerId ? `玩家 ${seat.seatNo}` : null,
    isMe: mySeatNo !== null && seat.seatNo === mySeatNo,
    controller,
    ready: seat.ready,
    avatar: avatarFor(seat.seatNo),
  }
}

/**
 * 把后端房间转成展示模型。
 *
 * @param myUserId 当前登录账号；用于算出 `mySeatNo` 与 `isMe`。服务端返回的 `playerId`
 *                 是座位标识，因此这里靠 `controller.refId` 反查"哪个座位是我"。
 */
export function toDemoRoom(view: RoomView, myUserId: string | null): DemoRoom {
  const mySeat = myUserId
    ? view.seats.find((seat) => seat.controller?.type === 'HUMAN' && seat.controller.refId === myUserId)
    : undefined
  const mySeatNo = mySeat ? mySeat.seatNo : null
  const status = toDemoStatus(view.status)

  return {
    ...toDemoSummary(view, mySeatNo, status),
    rules: view.rules ?? undefined,
    seats: view.seats.map((seat) => seatToDemo(seat, mySeatNo)),
    mySeatNo,
    ownerSeatNo: view.ownerSeatNo,
    isOwner: myUserId !== null && view.ownerId === myUserId,
    gameId: view.gameId,
  }
}

function toDemoSummary(
  view: RoomView,
  mySeatNo: number | null,
  status: DemoRoomStatus,
): DemoRoomSummary {
  // 后端目前不返回 visibility 与 description（api-spec.md §4 的房间视图没有这两个字段）。
  // 这里统一按 PUBLIC + 空描述处理，并在界面上不强调它们，而不是猜一个值。
  const visibility: DemoRoomVisibility = 'PUBLIC'
  return {
    roomId: view.roomId,
    gameId: view.gameId,
    name: view.name ?? view.roomId,
    description: '',
    modeLabel: `${view.capacity} 人 · 经典局`,
    occupied: view.occupied,
    humanCount: view.humanCount ?? view.seats?.filter(seat => seat.playerId && seat.controller?.type === 'HUMAN').length,
    aiCount: view.aiCount ?? view.seats?.filter(seat => seat.playerId && seat.controller?.type !== 'HUMAN').length,
    // 直接透传后端计数，不从前端自己数 ready 座位：列表项根本不含 seats，
    // 自己数会得到 0，而界面会显示"0/9 已准备"——比不显示更糟。
    readyCount: view.readyCount,
    capacity: 9,
    status,
    visibility,
    canJoin: status === 'WAITING' && view.occupied < view.capacity && mySeatNo === null,
    illustration: illustrationFor(view.roomId),
  }
}

/** 房间列表项 → 展示摘要（列表不含座位明细，因此 `mySeatNo` 未知）。 */
export function toDemoRoomSummary(view: RoomView): DemoRoomSummary {
  return toDemoSummary(view, null, toDemoStatus(view.status))
}
