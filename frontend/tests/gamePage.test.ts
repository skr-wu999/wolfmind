// @vitest-environment jsdom
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import GamePage from '../src/pages/GamePage.vue'
import { createMockGameAdapter } from '../src/features/game/mockGameAdapter'
import { useGameStore } from '../src/stores/game'
import type { GameAdapter } from '../src/features/game/types'

const service = vi.hoisted(() => ({ get: vi.fn(), scenario: vi.fn(), submit: vi.fn(), subscribe: vi.fn(), online: false }))
const exits = vi.hoisted(() => ({ request: vi.fn() }))
vi.mock('../src/api/adapters', () => ({ gameAdapter: () => service, usingRealBackend: () => service.online }))
vi.mock('../src/api/apiClient', async original => ({ ...await original<object>(), request: exits.request }))
vi.mock('../src/features/game/mockGameAdapter', async (original) => ({ ...await original<object>(), gameAdapter: service }))
vi.mock('../src/features/home/village', () => ({ createVillage: () => ({ drawGameTable: vi.fn() }) }))
let wrapper: VueWrapper | undefined, adapter: GameAdapter
beforeEach(() => {
  vi.resetAllMocks(); service.online = false; adapter = createMockGameAdapter()
  service.get.mockImplementation(adapter.get); service.scenario.mockImplementation(adapter.scenario); service.submit.mockImplementation(adapter.submit); service.subscribe.mockImplementation(adapter.subscribe)
  HTMLDialogElement.prototype.showModal = function () { this.open = true }
  HTMLDialogElement.prototype.close = function () { if (this.open) { this.open = false; this.dispatchEvent(new Event('close')) } }
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; document.body.innerHTML = ''; vi.useRealTimers(); vi.unstubAllGlobals() })
async function render(scenario = 'discussion') {
  await adapter.scenario('demo', scenario)
  const pinia = createPinia(), router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/games/:gameId', component: GamePage }, { path: '/games/:gameId/replay', component: { template: '<div>回放</div>' } }, { path: '/games/:gameId/analysis', component: { template: '<div>分析</div>' } }, { path: '/lobby', component: { template: '<div>大厅</div>' } }, { path: '/', component: { template: '<div>首页</div>' } }] })
  await router.push('/games/demo')
  wrapper = mount(GamePage, { attachTo: document.body, global: { plugins: [pinia, router], stubs: { FogVillageScene: true, PixelAvatar: true } } })
  await flushPromises()
  return { store: useGameStore(pinia), router }
}
async function button(text: string) { const result = wrapper!.findAll('button').find(b => b.text() === text); expect(result, text).toBeDefined(); await result!.trigger('click'); await flushPromises() }

test('discussion uses nine fixed seats and speech order, with no active voting controls', async () => {
  await render()
  expect(wrapper!.findAll('.game-seat')).toHaveLength(9)
  expect(wrapper!.findAll('.discussion-order li')).toHaveLength(8)
  expect(wrapper!.find('.game-targets').exists()).toBe(false)
  expect(wrapper!.find('.game-confirm').exists()).toBe(false)
  expect(wrapper!.get('#game-speak').attributes('disabled')).toBeDefined()
  expect(wrapper!.find('.seat-role').exists()).toBe(false)
})
test('own speech validates content, sends plain text, and shows the committed message', async () => {
  await render('my-turn'); await button('发言')
  await wrapper!.get('.game-speech-form').trigger('submit')
  expect(wrapper!.get('.form-error').text()).toContain('1 至 500')
  await wrapper!.get('#speech-content').setValue('<script>这是公开发言</script>')
  await wrapper!.get('.game-speech-form').trigger('submit'); await flushPromises()
  expect(service.submit).toHaveBeenCalledWith('demo', expect.objectContaining({ actionType: 'SPEAK', params: { content: '<script>这是公开发言</script>' } }))
  expect(wrapper!.get('.game-chat-list').text()).toContain('<script>这是公开发言</script>')
  expect(wrapper!.find('.game-chat-list script').exists()).toBe(false)
  expect(document.activeElement).toBe(wrapper!.get('.game-chat-list').element)
  expect(wrapper!.get('#game-speak').attributes('disabled')).toBeDefined()
})
test.each([
  ['my-turn', 'SPEAK'], ['voting', 'VOTE'], ['seer', 'SEER_INSPECT'],
] as const)('HTTP without randomUUID can submit %s actions', async (scenario, type) => {
  vi.stubGlobal('crypto', { getRandomValues: globalThis.crypto.getRandomValues.bind(globalThis.crypto) })
  const { store } = await render(scenario)
  const params = type === 'SPEAK' ? { content: '我想听听大家的判断。' }
    : { targetSeatNo: store.view!.permissions.find(p => p.type === type)!.targets[0] }
  expect(await store.submit(type, params)).toBe(true)
  expect(store.error).toBe('')
  expect(service.submit).toHaveBeenCalledWith('demo', expect.objectContaining({
    actionType: type, commandId: expect.stringMatching(/^action_[0-9a-f]{8}-/),
  }))
})
test('previous speech stays readable until the next speaker says something', async () => {
  await render('my-turn')
  const body = wrapper!.get('.game-chat-list').text()
  expect(body).toContain('2 号 · Moon')
  expect(body).toContain('我想听听 7 号对昨天投票的解释。')
  expect(body).not.toContain('等待玩家发言。')
})
test('table and candidate selection synchronize; invalid seats cannot vote; confirmation is required', async () => {
  const { store } = await render('voting')
  expect(wrapper!.get('.game-confirm').attributes('disabled')).toBeDefined()
  await wrapper!.findAll('.game-seat')[5]!.trigger('click'); expect(store.selected).toBeNull()
  await wrapper!.findAll('.game-seat')[3]!.trigger('click')
  expect(wrapper!.get('[aria-label="选择 4 号 AI-寒鸦"]').attributes('aria-pressed')).toBe('true')
  await wrapper!.get('[aria-label="选择 2 号 Moon"]').trigger('click')
  expect(wrapper!.findAll('.game-seat')[1]!.classes()).toContain('seat-selected')
  await wrapper!.get('.game-confirm').trigger('click'); expect(service.submit).not.toHaveBeenCalled()
  await button('确认提交')
  expect(store.view?.submitted).toContain('投票已提交')
  expect(wrapper!.find('.game-targets').exists()).toBe(false)
  expect(wrapper!.find('.vote-tally').exists()).toBe(false)
})
test('seer result appears in a private dialog and stays out of public logs', async () => {
  await render('seer')
  await wrapper!.get('[aria-label="选择 2 号 Moon"]').trigger('click'); await button('确认查验'); await button('确认提交')
  expect(wrapper!.get('.game-private-result').text()).toContain('非狼人阵营')
  await button('我已知晓'); await button('查看日志')
  expect(wrapper!.get('.game-history').text()).not.toContain('阵营')
})
test('witch rescue opens a confirmation for the authorized target and then locks both potions', async () => {
  const { store } = await render('witch')
  await button('使用解药')
  expect(wrapper!.get('.pixel-dialog').text()).toContain('8 号 北巷')
  await button('确认提交')
  expect(store.view?.permissions).toEqual([])
  expect(wrapper!.find('.witch-potions').exists()).toBe(false)
})
test('night waiting roles have no target picker or exposed private records', async () => {
  await render('villager')
  expect(wrapper!.text()).toContain('夜晚没有主动技能')
  expect(wrapper!.find('.game-targets').exists()).toBe(false)
  expect(wrapper!.text()).not.toContain('今晚可救助')
  await button('技能记录'); expect(wrapper!.get('.game-history').text()).toContain('还没有')
})
test('disconnect preserves a speech draft, disables submit, and resync restores eligibility', async () => {
  const { store } = await render('my-turn'); await button('发言')
  await wrapper!.get('#speech-content').setValue('这段文字先保留。')
  store.disconnect(); await flushPromises()
  expect(wrapper!.get('#speech-content').element).toHaveProperty('value', '这段文字先保留。')
  expect(wrapper!.get('.game-speech-form .lobby-button').attributes('disabled')).toBeDefined()
  await store.reconnect(); await flushPromises()
  expect(wrapper!.get('#speech-content').element).toHaveProperty('value', '这段文字先保留。')
  expect(wrapper!.get('.game-speech-form .lobby-button').attributes('disabled')).toBeUndefined()
})
test('failed submit retains text and refreshes the authoritative demo projection', async () => {
  await render('my-turn'); await button('发言'); await wrapper!.get('#speech-content').setValue('我的判断还在这里。')
  service.submit.mockRejectedValueOnce(new Error('示例提交失败'))
  await wrapper!.get('.game-speech-form').trigger('submit'); await flushPromises()
  expect(wrapper!.get('#speech-content').element).toHaveProperty('value', '我的判断还在这里。')
  expect(wrapper!.get('.game-chat-composer').text()).toContain('示例提交失败')
  expect(service.get).toHaveBeenCalledTimes(2)
})
test('seat arrow keys wrap by stable number and Enter has a public detail action', async () => {
  await render()
  const seats = wrapper!.findAll('.game-seat')
  await seats[8]!.trigger('keydown', { key: 'ArrowRight' }); expect(document.activeElement).toBe(seats[0]!.element)
  await seats[0]!.trigger('click'); await flushPromises()
  expect(wrapper!.get('.player-public-detail').text()).toContain('1 号 小林')
  expect(wrapper!.get('.game-history').text()).toContain('昨夜平安')
})
test('loading and failure show actionable feedback; no invented timer is rendered', async () => {
  service.get.mockRejectedValueOnce(new Error('读取失败'))
  await render()
  expect(wrapper!.get('.game-error').text()).toContain('读取失败')
  expect(wrapper!.find('.game-timer').exists()).toBe(false)
  await button('重试'); expect(wrapper!.findAll('.game-seat')).toHaveLength(9)
})
test('chat retains prior-day speech and last words as history without presenting a new stream', async () => {
  const { store } = await render()
  const speaker = store.view!.speakerSeatNo!
  const message = (id: string, round: number, phase: 'DAY_DISCUSSION' | 'LAST_WORDS', content: string) => ({ id, seatNo: speaker, round, phase, scope: 'PUBLIC' as const, content })
  store.view = { ...store.view!, preview: null, round: 3, phase: 'DAY_DISCUSSION', messages: [message('old', 2, 'DAY_DISCUSSION', '前一天的旧发言'), message('lw', 3, 'LAST_WORDS', '同一天的遗言')] }
  await flushPromises()
  expect(wrapper!.get('.game-chat-list').text()).toContain('前一天的旧发言')
  expect(wrapper!.get('.game-chat-list').text()).toContain('同一天的遗言'); expect(wrapper!.find('.is-streaming').exists()).toBe(false)
  store.view = { ...store.view!, messages: [...store.view!.messages, message('now', 3, 'DAY_DISCUSSION', '本轮发言')] }
  await flushPromises()
  expect(wrapper!.get('.game-chat-list').text()).toContain('本轮发言')
})
test('hunter reaction phase is titled for the hunter and waits for everyone else', async () => {
  const { store } = await render('hunter')
  expect(store.view?.phase).toBe('ROLE_REACTION')
  expect(wrapper!.get('#game-action-title').text()).toContain('猎人开枪')
  expect(wrapper!.get('#game-action-title').text()).not.toContain('本夜行动')
  store.view = { ...store.view!, me: { ...store.view!.me, role: 'WEREWOLF' }, permissions: [] }
  await flushPromises()
  expect(wrapper!.get('#game-action-title').text()).toContain('等待猎人行动')
  expect(wrapper!.get('.game-action-panel').text()).toContain('猎人正在决定是否开枪')
  expect(wrapper!.get('.game-action-panel').text()).not.toContain('今夜的目标')
  expect(wrapper!.text()).toContain('本阶段你没有可提交的行动')
})
test('a living hunter at night is not told he may shoot', async () => {
  const { store } = await render('hunter')
  expect(wrapper!.get('.game-panel-description').text()).toContain('你已获得本次开枪资格')
  store.view = { ...store.view!, phase: 'NIGHT_ACTION', permissions: [] }
  await flushPromises()
  expect(wrapper!.get('.game-panel-description').text()).not.toContain('已获得')
  expect(wrapper!.get('.game-panel-description').text()).toContain('现在无需行动')
})
test('changing route ignores a late snapshot from the previous game', async () => {
  const { store, router } = await render('voting')
  let finish: ((value: Awaited<ReturnType<GameAdapter['get']>>) => void) | undefined
  const previous = await adapter.get('demo')
  service.get.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  await router.push('/games/demo-old'); await flushPromises()
  await router.push('/games/demo-new'); await flushPromises()
  finish?.(previous); await flushPromises()
  expect(store.view?.gameId).toBe('demo-new')
})
test('a finished game links straight to its own replay and analysis', async () => {
  await render('game-over')
  const hrefs = wrapper!.findAll('.game-footer a').map(link => link.attributes('href'))
  expect(hrefs, '终局页必须能直接进入本局回放与分析').toContain('/games/demo/replay')
  expect(hrefs).toContain('/games/demo/analysis')
})

test('the rules dialog follows the current game policy', async () => {
  const { store } = await render('my-turn')
  store.view = { ...store.view!, rules: { winCondition: 'WOLVES_OUTNUMBER', witchSelfSave: false } }
  await button('查看规则')
  expect(wrapper!.get('dialog').text()).toContain('存活狼人数量达到或超过存活好人数量')
  expect(wrapper!.get('dialog').text()).toContain('女巫不能自救')
  expect(wrapper!.get('dialog').text()).not.toContain('屠边')
})
test('finished participant leaves on the server before navigating to the lobby', async () => {
  service.online = true; exits.request.mockResolvedValue({ status: 'CLOSED' })
  const { router } = await render('game-over')
  await button('退出房间并返回大厅')
  expect(exits.request).toHaveBeenCalledWith('/api/v1/games/demo/leave', expect.objectContaining({
    method: 'POST', idempotencyKey: expect.stringMatching(/^leave_/),
  }))
  expect(router.currentRoute.value.path).toBe('/lobby')
})
test('running participant is warned about takeover and can cancel without leaving', async () => {
  service.online = true
  const { router } = await render('my-turn')
  await button('退出房间并返回大厅')
  expect(wrapper!.text()).toContain('所有真人都退出后，本局会中止')
  expect(exits.request).not.toHaveBeenCalled()
  await button('继续游戏')
  expect(router.currentRoute.value.path).toBe('/games/demo')
})
test('exit failure keeps the game page and permits retry', async () => {
  service.online = true; exits.request.mockRejectedValueOnce(new Error('房间暂时繁忙'))
  const { router } = await render('my-turn')
  await button('退出房间并返回大厅'); await button('确认退出房间')
  expect(router.currentRoute.value.path).toBe('/games/demo')
  expect(wrapper!.text()).toContain('房间暂时繁忙')
  exits.request.mockResolvedValueOnce({ status: 'RUNNING' })
  await button('确认退出房间')
  expect(router.currentRoute.value.path).toBe('/lobby')
  expect(exits.request.mock.calls[0]![1].idempotencyKey).toBe(exits.request.mock.calls[1]![1].idempotencyKey)
})

test('a late exit response does not redirect a different game page', async () => {
  service.online = true
  let finish: ((value: unknown) => void) | undefined
  exits.request.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const { router } = await render('my-turn')
  await button('退出房间并返回大厅'); await button('确认退出房间')
  await router.push('/games/another-game'); await flushPromises()
  finish?.({ status: 'RUNNING' }); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/games/another-game')
})
test('a game that is still running does not offer replay or analysis', async () => {
  await render('discussion')
  const hrefs = wrapper!.findAll('.game-footer a').map(link => link.attributes('href'))
  expect(hrefs).not.toContain('/games/demo/replay')
  expect(hrefs).not.toContain('/games/demo/analysis')
})
test('compact tabs preserve the speech draft and move the authorized speech shortcut to the composer', async () => {
  vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
  await render('my-turn')
  expect(wrapper!.get('#game-chat-view').attributes('style')).toContain('display: none')
  await button('发言'); expect(wrapper!.get('#game-chat-tab').attributes('aria-selected')).toBe('true')
  expect(document.activeElement).toBe(wrapper!.get('#speech-content').element)
  await wrapper!.get('#speech-content').setValue('切换面板时保留判断。')
  await wrapper!.get('#game-table-tab').trigger('click'); await wrapper!.get('#game-table-tab').trigger('keydown', { key: 'ArrowRight' }); await flushPromises()
  expect(document.activeElement).toBe(wrapper!.get('#game-chat-tab').element)
  expect(wrapper!.get('#speech-content').element).toHaveProperty('value', '切换面板时保留判断。')
})
test('spectator state hides personal identity and all submission controls', async () => {
  const { store } = await render('voting')
  store.view = { ...store.view!, me: { ...store.view!.me, seatNo: 0 }, permissions: [] }
  await flushPromises()
  expect(wrapper!.get('.role-identity').text()).toContain('公开观战')
  expect(wrapper!.find('.game-confirm').exists()).toBe(false); expect(wrapper!.find('#speech-content').exists()).toBe(false)
  expect(wrapper!.get('.game-chat-composer').text()).toContain('只读')
})
