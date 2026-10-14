// @vitest-environment jsdom
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import LobbyPage from '../src/pages/LobbyPage.vue'
import RoomWaitingPage from '../src/pages/RoomWaitingPage.vue'
import { LobbyError } from '../src/features/lobby/types'
import type { DemoRoom, DemoRoomSummary } from '../src/features/lobby/types'

const service = vi.hoisted(() => ({ list: vi.fn(), get: vi.fn(), create: vi.fn(), join: vi.fn(), leave: vi.fn(), takeSeat: vi.fn(), ready: vi.fn(), setAi: vi.fn() }))
vi.mock('../src/features/lobby/mockRoomAdapter', () => ({ roomAdapter: service }))
let wrapper: VueWrapper | undefined
function room(overrides: Partial<DemoRoom> = {}): DemoRoom {
  return {
    roomId: '101001', name: '雾隐村 1 号房', description: '本地示例', modeLabel: '经典九人',
    occupied: 6, readyCount: 6, capacity: 9, status: 'WAITING', visibility: 'PUBLIC', canJoin: true,
    illustration: 'red', mySeatNo: null, ownerSeatNo: 1,
    seats: Array.from({ length: 9 }, (_, i) => ({ seatNo: i + 1, name: i < 6 ? `脚本村民 ${i + 1}` : null, isMe: false, controller: i < 6 ? 'HUMAN' : null, ready: i < 6, avatar: 'green' })),
    ...overrides,
  }
}
function summary(value: DemoRoom): DemoRoomSummary {
  const { seats: _seats, mySeatNo: _me, ownerSeatNo: _owner, ...rest } = value
  return rest
}
beforeEach(() => {
  vi.resetAllMocks()
  service.list.mockResolvedValue([
    summary(room()),
    summary(room({ roomId: '101002', name: '已开始的房间', status: 'PLAYING', canJoin: false })),
    summary(room({ roomId: '101003', name: '凭号房间', visibility: 'INVITE' })),
  ])
  service.get.mockResolvedValue(room())
  HTMLDialogElement.prototype.showModal = function () { this.open = true }
  HTMLDialogElement.prototype.close = function () { if (this.open) { this.open = false; this.dispatchEvent(new Event('close')) } }
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; document.body.innerHTML = '' })
async function render(page: typeof LobbyPage | typeof RoomWaitingPage = LobbyPage, path = '/lobby') {
  const placeholder = { template: '<div>路由目标</div>' }
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/', component: placeholder }, { path: '/lobby', component: placeholder },
    { path: '/rooms/:roomId', name: 'room-waiting', component: placeholder },
    { path: '/lab', component: placeholder },
  ] })
  await router.push(path)
  wrapper = mount(page, { attachTo: document.body, global: { plugins: [createPinia(), router], stubs: { FogVillageScene: true, PixelTitle: true, VillageScene: true, VillageHud: true, VillageIllustration: true, PixelAvatar: true } } })
  await flushPromises()
  return router
}

test('filters update the room list and started rooms cannot be joined', async () => {
  await render()
  expect(wrapper!.findAll('.room-row')).toHaveLength(3)
  expect(wrapper!.get('[aria-label="已开始的房间已开始"]').attributes('disabled')).toBeDefined()
  await wrapper!.findAll('.room-filters button')[1]!.trigger('click')
  expect(wrapper!.findAll('.room-row')).toHaveLength(2)
  await wrapper!.findAll('.room-filters button')[2]!.trigger('click')
  expect(wrapper!.findAll('.room-row')).toHaveLength(1)
  expect(wrapper!.get('.room-row').text()).toContain('已开始的房间')
})

test('creation validates the name, preserves invite settings and routes to the new room', async () => {
  const router = await render()
  await wrapper!.get('.create-room-button').trigger('click')
  expect(document.activeElement?.id).toBe('room-name')
  await wrapper!.get('.framed-dialog form').trigger('submit')
  expect(wrapper!.get('#room-name-error').text()).toBe('给房间起个名字吧。')
  await wrapper!.get('#room-name').setValue('朋友们的房间')
  await wrapper!.get('input[value="INVITE"]').setValue()
  service.create.mockResolvedValue(room({ roomId: '101007', name: '朋友们的房间', visibility: 'INVITE', mySeatNo: 1 }))
  await wrapper!.get('.framed-dialog form').trigger('submit')
  await flushPromises()
  expect(service.create).toHaveBeenCalledWith({ name: '朋友们的房间', visibility: 'INVITE' })
  expect(router.currentRoute.value.path).toBe('/rooms/101007')
})

test('join-by-code handles empty and missing rooms before navigating successfully', async () => {
  const router = await render()
  await wrapper!.get('.join-room-panel form').trigger('submit')
  expect(wrapper!.get('#room-code-error').text()).toBe('请输入房间号。')
  service.join.mockRejectedValueOnce(new LobbyError('ROOM_NOT_FOUND', '没有找到这个房间'))
  await wrapper!.get('.room-code-field input').setValue('missing')
  await wrapper!.get('.join-room-panel form').trigger('submit')
  await flushPromises()
  expect(wrapper!.get('#room-code-error').text()).toContain('没有找到')
  expect(document.activeElement).toBe(wrapper!.get('.room-code-field input').element)
  expect(router.currentRoute.value.path).toBe('/lobby')
  service.join.mockResolvedValue(room({ mySeatNo: 7 }))
  await wrapper!.get('.room-code-field input').setValue(' 101001 ')
  await wrapper!.get('.join-room-panel form').trigger('submit')
  await flushPromises()
  expect(service.join).toHaveBeenLastCalledWith('101001')
  expect(router.currentRoute.value.path).toBe('/rooms/101001')
})

test('empty and error list states can recover through refresh', async () => {
  service.list.mockRejectedValueOnce(new Error('offline'))
  await render()
  expect(wrapper!.get('[role="alert"]').text()).toContain('重试')
  service.list.mockResolvedValue([])
  await wrapper!.get('.lobby-error button').trigger('click')
  await flushPromises()
  expect(wrapper!.get('.empty-rooms').text()).toContain('当前没有房间')
  expect(wrapper!.find('[role="alert"]').exists()).toBe(false)
})

test('waiting room shows all seats and players, and leaving returns to the lobby', async () => {
  const joined = room({ occupied: 7, mySeatNo: 7 })
  joined.seats[6] = { seatNo: 7, name: '你', isMe: true, controller: 'HUMAN', ready: false, avatar: 'blue' }
  service.get.mockResolvedValue(joined)
  service.leave.mockResolvedValue(undefined)
  const router = await render(RoomWaitingPage, '/rooms/101001')
  expect(wrapper!.findAll('.waiting-seat')).toHaveLength(9)
  expect(wrapper!.findAll('.waiting-player-row')).toHaveLength(9)
  expect(wrapper!.get('.my-waiting-seat').text()).toContain('7号')
  expect(wrapper!.get('.seat-feedback').text()).toContain('你已加入房间')
  await wrapper!.get('.waiting-leave').trigger('click')
  await flushPromises()
  expect(service.leave).toHaveBeenCalledWith('101001')
  expect(router.currentRoute.value.path).toBe('/lobby')
})

test('missing room URLs show an error with a working return action', async () => {
  service.get.mockRejectedValue(new LobbyError('ROOM_NOT_FOUND', '没有找到这个房间'))
  const router = await render(RoomWaitingPage, '/rooms/missing')
  expect(wrapper!.get('[role="alert"]').text()).toContain('没有找到')
  await wrapper!.get('.waiting-leave').trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/lobby')
})

test('ready updates both the board and player list from the refreshed snapshot', async () => {
  const joined = room({ mySeatNo: 1 })
  joined.seats[0]!.isMe = true
  joined.seats[0]!.ready = false
  service.get.mockResolvedValue(joined)
  await render(RoomWaitingPage, '/rooms/101001')
  expect(wrapper!.get('.ready-button').attributes('disabled')).toBeUndefined()
  const prepared = structuredClone(joined)
  prepared.seats[0]!.ready = true
  service.ready.mockResolvedValue(prepared)
  service.get.mockResolvedValue(prepared)
  await wrapper!.get('.ready-button').trigger('click')
  await flushPromises()
  expect(service.ready).toHaveBeenCalledWith('101001')
  expect(wrapper!.get('.my-waiting-seat').text()).toContain('已准备')
  expect(wrapper!.get('.waiting-player-row').text()).toContain('已准备')
  expect(wrapper!.get('.ready-button').attributes('disabled')).toBeDefined()
  expect(wrapper!.get('.start-game-button').attributes('disabled')).toBeDefined()
})

test('AI management is available to the owner and updates occupied counts', async () => {
  const owned = room({ mySeatNo: 1 })
  owned.seats[0]!.isMe = true
  service.get.mockResolvedValue(owned)
  await render(RoomWaitingPage, '/rooms/101001')
  await wrapper!.get('[data-seat="9"]').trigger('click')
  const ai = structuredClone(owned)
  ai.seats[8] = { seatNo: 9, name: 'AI-009', isMe: false, controller: 'AI', ready: true, avatar: 'wolf' }
  ai.occupied = 7
  service.setAi.mockResolvedValue(ai)
  service.get.mockResolvedValue(ai)
  await wrapper!.findAll('.seat-detail-actions button')[0]!.trigger('click')
  await flushPromises()
  expect(service.setAi).toHaveBeenCalledWith('101001', 9, true)
  expect(wrapper!.get('[data-seat="9"]').text()).toContain('AI-009')
  expect(wrapper!.get('.waiting-player-summary').text()).toContain('AI 1')
  expect(wrapper!.get('.waiting-info').text()).toContain('7 / 9')
})

test('non-members cannot ready and can select an empty seat to join', async () => {
  await render(RoomWaitingPage, '/rooms/101001')
  expect(wrapper!.get('.ready-button').attributes('disabled')).toBeDefined()
  expect(wrapper!.find('.start-game-button').exists()).toBe(false)
  await wrapper!.get('[data-seat="8"]').trigger('click')
  expect(wrapper!.get('.seat-detail-actions').text()).not.toContain('添加 AI')
  const joined = room({ occupied: 7, mySeatNo: 8 })
  joined.seats[7] = { seatNo: 8, name: '你', isMe: true, controller: 'HUMAN', ready: false, avatar: 'blue' }
  service.takeSeat.mockResolvedValue(joined)
  service.get.mockResolvedValue(joined)
  await wrapper!.get('.seat-detail-actions .lobby-button').trigger('click')
  await flushPromises()
  expect(service.takeSeat).toHaveBeenCalledWith('101001', 8)
  expect(wrapper!.get('.my-waiting-seat').text()).toContain('8号')
})

test('failed preparation preserves the snapshot, shows feedback and allows retry', async () => {
  const joined = room({ mySeatNo: 1 })
  joined.seats[0]!.isMe = true
  joined.seats[0]!.ready = false
  service.get.mockResolvedValue(joined)
  service.ready.mockRejectedValueOnce(new LobbyError('NOT_READY', '准备请求失败，请重试'))
  await render(RoomWaitingPage, '/rooms/101001')
  await wrapper!.get('.ready-button').trigger('click')
  await flushPromises()
  expect(wrapper!.get('[role="alert"]').text()).toContain('准备请求失败')
  expect(wrapper!.get('.my-waiting-seat').text()).toContain('未准备')
  expect(wrapper!.get('.ready-button').attributes('disabled')).toBeUndefined()
  expect(service.get).toHaveBeenCalledTimes(2)
})

test('keyboard arrow navigation moves between seats and wraps', async () => {
  await render(RoomWaitingPage, '/rooms/101001')
  await wrapper!.get('[data-seat="9"]').trigger('keydown', { key: 'ArrowRight' })
  expect((document.activeElement as HTMLElement).dataset.seat).toBe('1')
  await wrapper!.get('[data-seat="1"]').trigger('keydown', { key: 'ArrowLeft' })
  expect((document.activeElement as HTMLElement).dataset.seat).toBe('9')
})

test('a late snapshot from another room cannot replace the current room', async () => {
  let finish!: (value: DemoRoom) => void
  service.get.mockImplementationOnce(() => new Promise((resolve) => { finish = resolve }))
  const router = await render(RoomWaitingPage, '/rooms/101001')
  expect(wrapper!.findAll('.seat-loading')).toHaveLength(9)
  service.get.mockResolvedValue(room({ roomId: '101002', name: '新房间' }))
  await router.push('/rooms/101002')
  await flushPromises()
  finish(room())
  await flushPromises()
  expect(wrapper!.get('.waiting-name-line h2').text()).toBe('新房间')
  expect(wrapper!.get('.waiting-info').text()).toContain('101002')
})

test('the lobby always offers the AI lab entry and keeps the demo entries in demo mode', async () => {
  await render()
  const hrefs = wrapper!.findAll('a').map(link => link.attributes('href'))
  // 实验室入口是无条件的：真实模式下没有它，用户就只能靠手输 URL 进实验室（QA 报告 P1-4）。
  expect(hrefs, '大厅必须有 AI 实验室入口').toContain('/lab')
  // 示例入口只在 mock 模式出现——真实模式把它们指向 demo 会把用户带到一个不存在的对局。
  expect(hrefs).toContain('/games/demo')
})
