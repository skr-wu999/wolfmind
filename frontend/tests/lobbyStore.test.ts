import { expect, test, vi } from 'vitest'
import { createLobbyState } from '../src/stores/lobby'
import { createMockRoomAdapter } from '../src/features/lobby/mockRoomAdapter'
import { LobbyError } from '../src/features/lobby/types'
import type { DemoRoomSummary } from '../src/features/lobby/types'

test('filters show waiting, playing and public rooms consistently', async () => {
  const adapter = createMockRoomAdapter(undefined, 0)
  await adapter.create({ name: '凭号房间', visibility: 'INVITE' })
  const state = createLobbyState(adapter)
  await state.refresh()
  expect(state.filteredRooms.value).toHaveLength(7)
  expect(state.visibleRooms.value).toHaveLength(6)
  expect(state.pageCount.value).toBe(2)
  state.page.value = 2
  expect(state.visibleRooms.value[0]?.name).toBe('凭号房间')
  state.filter.value = 'PUBLIC'
  expect(state.page.value).toBe(1)
  expect(state.visibleRooms.value).toHaveLength(6)
  state.filter.value = 'WAITING'
  expect(state.visibleRooms.value).toHaveLength(5)
  state.filter.value = 'PLAYING'
  expect(state.visibleRooms.value).toHaveLength(2)
})

test('a failed refresh preserves the previous list and supports retry', async () => {
  const adapter = createMockRoomAdapter(undefined, 0)
  const state = createLobbyState(adapter)
  await state.refresh()
  const list = vi.spyOn(adapter, 'list').mockRejectedValueOnce(new Error('offline'))
  await state.refresh()
  expect(state.rooms.value).toHaveLength(6)
  expect(state.error.value).toContain('重试')
  expect(state.loading.value).toBe(false)
  list.mockRestore()
  await state.refresh()
  expect(state.error.value).toBe('')
})

test('a late list response cannot overwrite a more recent refresh', async () => {
  const adapter = createMockRoomAdapter(undefined, 0)
  let finish!: (value: DemoRoomSummary[]) => void
  vi.spyOn(adapter, 'list').mockImplementationOnce(() => new Promise((resolve) => { finish = resolve }))
  const state = createLobbyState(adapter)
  const earlier = state.refresh()
  await state.refresh()
  finish([])
  await earlier
  expect(state.rooms.value).toHaveLength(6)
})

test('duplicate clicks cannot submit two concurrent joins', async () => {
  const adapter = createMockRoomAdapter(undefined, 0)
  let reject!: (reason: unknown) => void
  const join = vi.spyOn(adapter, 'join').mockImplementation(() => new Promise((_, rejectPromise) => { reject = rejectPromise }))
  const state = createLobbyState(adapter)
  const pending = state.join('101001')
  expect(await state.join('101002')).toBeNull()
  expect(join).toHaveBeenCalledOnce()
  reject(new LobbyError('ROOM_FULL', '房间已满'))
  expect(await pending).toBeNull()
  expect(state.actionError.value).toBe('房间已满')
  expect(state.pendingRoomId.value).toBeNull()
})
