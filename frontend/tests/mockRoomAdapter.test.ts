import { expect, test } from 'vitest'
import { createMockRoomAdapter } from '../src/features/lobby/mockRoomAdapter'

test('summaries contain nine-player presentation fields and no seats or secret roles', async () => {
  const items = await createMockRoomAdapter(undefined, 0).list()
  expect(items).toHaveLength(6)
  expect(items.every((room) => room.capacity === 9)).toBe(true)
  expect(items.every((room) => !('seats' in room) && !('role' in room) && !('mySeatNo' in room))).toBe(true)
})

test('joining fills the first empty seat once, and leaving releases it', async () => {
  const adapter = createMockRoomAdapter(undefined, 0)
  const room = await adapter.join('101001')
  expect([room.mySeatNo, room.occupied]).toEqual([8, 8])
  expect((await adapter.join('101001')).occupied).toBe(8)
  await adapter.leave('101001')
  expect((await adapter.get('101001')).occupied).toBe(7)
  expect((await adapter.get('101001')).mySeatNo).toBeNull()
})

test('unknown and started rooms reject joining with usable errors', async () => {
  const adapter = createMockRoomAdapter(undefined, 0)
  await expect(adapter.join('missing')).rejects.toMatchObject({ code: 'ROOM_NOT_FOUND' })
  await expect(adapter.join('101003')).rejects.toMatchObject({ code: 'GAME_ALREADY_STARTED' })
})

test('taking the last seat closes joining and leaving opens it again', async () => {
  const adapter = createMockRoomAdapter(undefined, 0)
  const joined = await adapter.join('101002')
  expect([joined.occupied, joined.canJoin, joined.mySeatNo]).toEqual([9, false, 9])
  expect((await adapter.join('101002')).occupied).toBe(9)
  await adapter.leave('101002')
  expect((await adapter.get('101002')).canJoin).toBe(true)
})

test('creation persists an invite room and its creator seat across adapter instances', async () => {
  const data = new Map<string, string>()
  const storage = { getItem: (key: string) => data.get(key) ?? null, setItem: (key: string, value: string) => { data.set(key, value) } }
  const first = createMockRoomAdapter(storage, 0)
  const room = await first.create({ name: '  今晚来推理  ', visibility: 'INVITE' })
  expect([room.name, room.visibility, room.mySeatNo, room.occupied]).toEqual(['今晚来推理', 'INVITE', 1, 1])
  const restored = await createMockRoomAdapter(storage, 0).get(room.roomId)
  expect(restored).toEqual(room)
  await expect(first.create({ name: ' ', visibility: 'PUBLIC' })).rejects.toMatchObject({ code: 'VALIDATION_ERROR' })
})

test('unreadable stored data produces feedback rather than silently replacing it', async () => {
  const adapter = createMockRoomAdapter({ getItem: () => '{broken', setItem: () => {} }, 0)
  await expect(adapter.list()).rejects.toMatchObject({ code: 'DEMO_DATA_INVALID' })
})

test('preparation requires a seat, is idempotent and persists', async () => {
  const data = new Map<string, string>()
  const storage = { getItem: (key: string) => data.get(key) ?? null, setItem: (key: string, value: string) => { data.set(key, value) } }
  const adapter = createMockRoomAdapter(storage, 0)
  await expect(adapter.ready('101001')).rejects.toMatchObject({ code: 'NOT_SEATED' })
  const joined = await adapter.takeSeat('101001', 9)
  expect([joined.mySeatNo, joined.seats[8]!.ready]).toEqual([9, false])
  await adapter.ready('101001')
  await adapter.ready('101001')
  const restored = await createMockRoomAdapter(storage, 0).get('101001')
  expect(restored.seats[8]!.ready).toBe(true)
  expect(restored.occupied).toBe(8)
  await adapter.leave('101001')
  expect((await adapter.get('101001')).seats[8]!.ready).toBe(false)
})

test('AI changes require ownership, protect human seats and automatically ready AI', async () => {
  const adapter = createMockRoomAdapter(undefined, 0)
  await adapter.join('101001')
  await expect(adapter.setAi('101001', 9, true)).rejects.toMatchObject({ code: 'NOT_OWNER' })
  const own = await adapter.create({ name: 'AI 测试房', visibility: 'PUBLIC' })
  await expect(adapter.setAi(own.roomId, 1, true)).rejects.toMatchObject({ code: 'ALREADY_SEATED' })
  const filled = await adapter.setAi(own.roomId, 4, true)
  expect([filled.seats[3]!.controller, filled.seats[3]!.ready, filled.occupied]).toEqual(['AI', true, 2])
  expect((await adapter.setAi(own.roomId, 4, true)).occupied).toBe(2)
  const cleared = await adapter.setAi(own.roomId, 4, false)
  expect([cleared.occupied, cleared.seats[3]!.name, cleared.seats[3]!.ready]).toEqual([1, null, false])
  await expect(adapter.takeSeat(own.roomId, 2)).rejects.toMatchObject({ code: 'ALREADY_SEATED' })
  await expect(adapter.setAi(own.roomId, 0, true)).rejects.toMatchObject({ code: 'INVALID_SEAT' })
  await expect(adapter.takeSeat('101003', 9)).rejects.toMatchObject({ code: 'GAME_ALREADY_STARTED' })
})

test('legacy session rooms gain presentation fields without losing membership', async () => {
  const adapter = createMockRoomAdapter(undefined, 0)
  const joined = await adapter.join('101001')
  const legacy = JSON.parse(JSON.stringify(joined))
  delete legacy.ownerSeatNo
  legacy.seats.forEach((seat: Record<string, unknown>) => { delete seat.controller; delete seat.ready; delete seat.avatar; if (seat.name && !seat.isMe) seat.name = `脚本村民 ${seat.seatNo}` })
  const migrated = await createMockRoomAdapter({ getItem: () => JSON.stringify([legacy]), setItem: () => {} }, 0).get(joined.roomId)
  expect([migrated.mySeatNo, migrated.occupied, migrated.ownerSeatNo]).toEqual([8, 8, 1])
  expect(migrated.seats[7]!.isMe).toBe(true)
  expect(migrated.seats[7]!.ready).toBe(false)
  expect(migrated.seats[4]!.controller).toBe('AI')
  expect(migrated.seats[4]!.ready).toBe(true)
  const broken = structuredClone(migrated)
  broken.mySeatNo = 10
  await expect(createMockRoomAdapter({ getItem: () => JSON.stringify([broken]), setItem: () => {} }, 0).get(joined.roomId)).rejects.toMatchObject({ code: 'DEMO_DATA_INVALID' })
})

test('unavailable session writes keep later reads in memory', async () => {
  const saved = await createMockRoomAdapter(undefined, 0).list()
  const room = await createMockRoomAdapter(undefined, 0).get(saved[0]!.roomId)
  const adapter = createMockRoomAdapter({ getItem: () => JSON.stringify([room]), setItem: () => { throw new Error('quota') } }, 0)
  await adapter.join(room.roomId)
  await adapter.ready(room.roomId)
  expect((await adapter.get(room.roomId)).seats[7]!.ready).toBe(true)
})

test('a full AI-filled room still requires the human owner to prepare', async () => {
  const adapter = createMockRoomAdapter(undefined, 0)
  const created = await adapter.create({ name: '九人集合', visibility: 'PUBLIC' })
  for (let seatNo = 2; seatNo <= 9; seatNo++) await adapter.setAi(created.roomId, seatNo, true)
  const filled = await adapter.get(created.roomId)
  expect([filled.occupied, filled.canJoin]).toEqual([9, false])
  expect(filled.seats.filter((seat) => seat.ready)).toHaveLength(8)
  const prepared = await adapter.ready(created.roomId)
  expect(prepared.seats.filter((seat) => seat.ready)).toHaveLength(9)
  expect(prepared.status).toBe('WAITING')
})
