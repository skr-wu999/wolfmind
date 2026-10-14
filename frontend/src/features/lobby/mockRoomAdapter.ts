import { LobbyError } from './types'
import { demoRules } from '@/features/game/rules'
import type { CreateDemoRoom, DemoRoom, DemoRoomSummary, DemoSeat, RoomAdapter } from './types'

const storageKey = 'wolfmind.demo.rooms.v1'
function seats(occupied: number): DemoSeat[] {
  const names = ['小林', 'Moon', '阿泽', '推理猫', 'AI-001', 'AI-夜枭', 'AI-白狐', '北巷', '小麦']
  const avatars: DemoSeat['avatar'][] = ['green', 'blue', 'red', 'fox', 'wolf', 'owl', 'fox', 'blue', 'green']
  return Array.from({ length: 9 }, (_, i) => ({ seatNo: i + 1, name: i < occupied ? names[i]! : null, isMe: false,
    controller: i >= occupied ? null : i >= 4 && i <= 6 ? 'AI' : 'HUMAN', ready: i < occupied && i !== 2,
    avatar: avatars[i]! }))
}
function seedRooms(): DemoRoom[] {
  const definitions = [
    ['雾隐村 1 号房', '新手友好，一起享受推理的乐趣。', 7, 'WAITING', 'red'],
    ['新月夜话', '听完每一段发言，再做出你的判断。', 8, 'WAITING', 'grey'],
    ['推理练习局', '适合新手，友好讨论。', 4, 'PLAYING', 'trees'],
    ['AI 自对局观战房', '观战场景示例，尚未连接真实 AI。', 9, 'PLAYING', 'red'],
    ['深夜推理社', '欢迎喜欢思考的你。', 7, 'WAITING', 'grey'],
    ['轻松娱乐局', '快乐推理，友好氛围。', 3, 'WAITING', 'trees'],
  ] as const
  return definitions.map(([name, description, occupied, status, illustration], i) => ({
    roomId: String(101001 + i), name, description, modeLabel: i === 3 ? '观战示例' : '经典九人',
    rules: { ...demoRules },
    occupied, readyCount: occupied, capacity: 9, status, visibility: 'PUBLIC', canJoin: status === 'WAITING', illustration,
    seats: seats(occupied), mySeatNo: null, ownerSeatNo: 1,
  }))
}
function summary(room: DemoRoom): DemoRoomSummary {
  const { seats: _seats, mySeatNo: _mySeat, ownerSeatNo: _owner, ...item } = room
  return item
}
function validateRooms(value: unknown): value is DemoRoom[] {
  return Array.isArray(value) && value.every((room) => room && typeof room.roomId === 'string'
    && typeof room.name === 'string' && typeof room.description === 'string' && typeof room.modeLabel === 'string'
    && room.capacity === 9 && ['WAITING', 'PLAYING'].includes(room.status)
    && ['PUBLIC', 'INVITE'].includes(room.visibility) && typeof room.canJoin === 'boolean'
    && ['red', 'grey', 'trees'].includes(room.illustration) && Number.isInteger(room.occupied)
    && room.occupied >= 0 && room.occupied <= 9 && (room.mySeatNo === null || Number.isInteger(room.mySeatNo) && room.mySeatNo >= 1 && room.mySeatNo <= 9)
    && (room.ownerSeatNo === undefined || room.ownerSeatNo === null || Number.isInteger(room.ownerSeatNo) && room.ownerSeatNo >= 1 && room.ownerSeatNo <= 9)
    && Array.isArray(room.seats) && room.seats.length === 9
    && room.seats.every((seat: DemoSeat, i: number) => seat && seat.seatNo === i + 1 && (seat.name === null || typeof seat.name === 'string') && typeof seat.isMe === 'boolean'
      && (seat.controller === undefined || seat.controller === null || ['HUMAN', 'AI'].includes(seat.controller))
      && (seat.ready === undefined || typeof seat.ready === 'boolean')
      && (seat.avatar === undefined || ['green', 'blue', 'red', 'wolf', 'owl', 'fox'].includes(seat.avatar)))
    && room.seats.filter((seat: DemoSeat) => seat.name !== null).length === room.occupied
    && room.seats.every((seat: DemoSeat) => seat.isMe === (seat.seatNo === room.mySeatNo) && (!seat.isMe || seat.name !== null)))
}

function upgradeRoom(room: DemoRoom): DemoRoom {
  room.rules ??= { ...demoRules }
  const defaults = seats(9)
  room.seats = room.seats.map((seat, i) => {
    const legacy = seat.controller === undefined
    const replacement = legacy && /^脚本村民 \d+$/.test(seat.name || '') ? defaults[i] : null
    const controller = seat.name === null ? null : seat.isMe ? 'HUMAN' : replacement?.controller || seat.controller || 'HUMAN'
    return { ...seat, name: replacement?.name || seat.name, controller, avatar: seat.avatar || replacement?.avatar || 'green',
      ready: controller === 'AI' || (seat.name !== null && (seat.ready ?? (seat.isMe ? false : defaults[i]!.ready))) }
  })
  if (room.ownerSeatNo === undefined) room.ownerSeatNo = room.seats.find((seat) => seat.name && seat.controller === 'HUMAN')?.seatNo ?? null
  return room
}

export function createMockRoomAdapter(storage?: Pick<Storage, 'getItem' | 'setItem'>, latency = 180): RoomAdapter {
  let memory = seedRooms()
  let storageAvailable = !!storage
  function read(): DemoRoom[] {
    if (!storage || !storageAvailable) return structuredClone(memory)
    let raw: string | null
    try { raw = storage.getItem(storageKey) } catch { storageAvailable = false; return structuredClone(memory) }
    if (!raw) return structuredClone(memory)
    try {
      const data: unknown = JSON.parse(raw)
      if (!validateRooms(data)) throw new Error('Invalid demo data')
      return data.map(upgradeRoom)
    } catch { throw new LobbyError('DEMO_DATA_INVALID', '本地房间数据无法读取，请清除此站点的会话数据后重试。') }
  }
  function save(rooms: DemoRoom[]) {
    for (const room of rooms) {
      room.occupied = room.seats.filter((seat) => seat.name !== null).length
      room.canJoin = room.status === 'WAITING' && room.occupied < 9
    }
    memory = structuredClone(rooms)
    if (storageAvailable) {
      try { storage?.setItem(storageKey, JSON.stringify(rooms)) } catch { storageAvailable = false }
    }
  }
  const wait = () => latency > 0 ? new Promise<void>((resolve) => setTimeout(resolve, latency)) : Promise.resolve()
  function find(rooms: DemoRoom[], roomId: string) {
    const room = rooms.find((room) => room.roomId === roomId)
    if (!room) throw new LobbyError('ROOM_NOT_FOUND', '没有找到这个房间，请检查房间号。')
    return room
  }
  function ensureWaiting(room: DemoRoom) {
    if (room.status !== 'WAITING') throw new LobbyError('GAME_ALREADY_STARTED', '这个房间已经开始了，请选择等待中的房间。')
  }
  function findSeat(room: DemoRoom, seatNo: number) {
    if (!Number.isInteger(seatNo) || seatNo < 1 || seatNo > 9) throw new LobbyError('INVALID_SEAT', '请选择 1 至 9 号座位。')
    return room.seats[seatNo - 1]!
  }
  function occupy(room: DemoRoom, seatNo: number) {
    ensureWaiting(room)
    const seat = findSeat(room, seatNo)
    if (room.mySeatNo !== null) {
      if (room.mySeatNo === seatNo) return
      throw new LobbyError('ALREADY_SEATED', '你已经入座，请先离开房间再选择座位。')
    }
    if (seat.name !== null) throw new LobbyError('ALREADY_SEATED', '这个座位已有人，请选择空位。')
    Object.assign(seat, { name: '你', isMe: true, controller: 'HUMAN', ready: false, avatar: 'blue' })
    room.mySeatNo = seatNo
    if (room.ownerSeatNo === null) room.ownerSeatNo = seatNo
  }
  return {
    async list() { await wait(); return read().map(summary) },
    async get(roomId) { await wait(); return find(read(), roomId) },
    async create(input: CreateDemoRoom) {
      await wait()
      const name = input.name.trim()
      if (!name || name.length > 24) throw new LobbyError('VALIDATION_ERROR', '房间名称需为 1 至 24 个字符。')
      const rooms = read()
      const roomId = String(Math.max(101006, ...rooms.map((room) => Number(room.roomId) || 0)) + 1)
      const room: DemoRoom = {
        rules: { ...demoRules },
        roomId, name, description: input.visibility === 'PUBLIC' ? '欢迎来到你的新房间。' : '凭房间号加入，一起聊聊今晚的线索。',
        modeLabel: '经典九人', occupied: 1, readyCount: 0, capacity: 9, status: 'WAITING', visibility: input.visibility,
        canJoin: true, illustration: 'red', seats: seats(0), mySeatNo: 1, ownerSeatNo: 1,
      }
      room.seats[0] = { seatNo: 1, name: '你', isMe: true, controller: 'HUMAN', ready: false, avatar: 'green' }
      rooms.push(room)
      save(rooms)
      return structuredClone(room)
    },
    async join(roomId) {
      await wait()
      const rooms = read()
      const room = find(rooms, roomId)
      if (room.mySeatNo !== null) return structuredClone(room)
      ensureWaiting(room)
      const seat = room.seats.find((seat) => seat.name === null)
      if (!room.canJoin || !seat) throw new LobbyError('ROOM_FULL', '房间已满，请选择其他房间。')
      occupy(room, seat.seatNo)
      save(rooms)
      return structuredClone(room)
    },
    async leave(roomId) {
      await wait()
      const rooms = read()
      const room = find(rooms, roomId)
      ensureWaiting(room)
      if (room.mySeatNo === null) return
      const seat = room.seats[room.mySeatNo - 1]!
      Object.assign(seat, { name: null, isMe: false, controller: null, ready: false })
      if (room.ownerSeatNo === room.mySeatNo) room.ownerSeatNo = room.seats.find((seat) => seat.name && seat.controller === 'HUMAN')?.seatNo ?? null
      room.mySeatNo = null
      room.occupied -= 1
      room.canJoin = room.status === 'WAITING' && room.occupied < 9
      save(rooms)
    },
    async takeSeat(roomId, seatNo) {
      await wait()
      const rooms = read()
      const room = find(rooms, roomId)
      occupy(room, seatNo)
      save(rooms)
      return structuredClone(room)
    },
    async ready(roomId) {
      await wait()
      const rooms = read()
      const room = find(rooms, roomId)
      ensureWaiting(room)
      if (room.mySeatNo === null) throw new LobbyError('NOT_SEATED', '请先入座，再准备。')
      findSeat(room, room.mySeatNo).ready = true
      save(rooms)
      return structuredClone(room)
    },
    async setAi(roomId, seatNo, enabled) {
      await wait()
      const rooms = read()
      const room = find(rooms, roomId)
      ensureWaiting(room)
      if (room.mySeatNo === null || room.mySeatNo !== room.ownerSeatNo) throw new LobbyError('NOT_OWNER', '只有房主可以管理 AI 座位。')
      const seat = findSeat(room, seatNo)
      if (seat.name && seat.controller !== 'AI') throw new LobbyError('ALREADY_SEATED', '玩家已在这个座位，不能更改。')
      if (enabled) Object.assign(seat, { name: `AI-${String(seatNo).padStart(3, '0')}`, isMe: false, controller: 'AI', ready: true, avatar: 'wolf' })
      else Object.assign(seat, { name: null, isMe: false, controller: null, ready: false })
      save(rooms)
      return structuredClone(room)
    },
  }
}

let session: Storage | undefined
try { session = window.sessionStorage } catch { /* The local adapter also works without browser storage. */ }
export const roomAdapter = createMockRoomAdapter(session)
