// @vitest-environment jsdom
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ObservationPage from '../src/pages/ObservationPage.vue'
import { observationAdapter, createObservationFixture } from '../src/features/observation/mockObservationAdapter'
import { useObservationStore } from '../src/stores/observation'
vi.mock('../src/features/home/village', () => ({ createVillage: () => ({ drawGameTable: vi.fn() }) }))
let wrapper: VueWrapper | undefined
beforeEach(() => {
  vi.restoreAllMocks()
  HTMLDialogElement.prototype.showModal = function () { this.open = true }
  HTMLDialogElement.prototype.close = function () { if (this.open) { this.open = false; this.dispatchEvent(new Event('close')) } }
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; document.body.innerHTML = ''; vi.useRealTimers() })
async function render(mode: 'watch' | 'replay' = 'replay', id = 'demo') {
  const pinia = createPinia(), router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/watch/:gameId', component: ObservationPage }, { path: '/games/:gameId/replay', component: ObservationPage }, { path: '/lobby', component: { template: '<p>大厅</p>' } }] })
  await router.push(mode === 'replay' ? `/games/${id}/replay` : `/watch/${id}`)
  await router.isReady()
  wrapper = mount(ObservationPage, { props: { mode }, attachTo: document.body, global: { plugins: [pinia, router], stubs: { FogVillageScene: true, VillageHeader: true, PixelAvatar: true } } })
  await flushPromises()
  return { page: wrapper, store: useObservationStore(pinia), router }
}
test('watch displays nine AI seats, current speech and reusable readonly board', async () => {
  const { page } = await render('watch')
  expect(page.findAll('.game-seat')).toHaveLength(9)
  expect(page.findAll('.observation-roster li')).toHaveLength(9)
  expect(page.find('.observation-speech').text()).toContain('昨晚我查验了 4 号')
  expect(page.find('.observation-demo').text()).toContain('本地观战')
  expect(page.find('.game-targets').exists()).toBe(false)
  expect(page.find('.game-skill-bar').exists()).toBe(false)
})
test('timeline changes board, detail and playback position; stepping backward restores players', async () => {
  const { page, store } = await render()
  await page.findAll('.event-timeline-button')[9]!.trigger('click')
  expect(store.position).toBe(370)
  expect(page.find('.replay-event-detail').text()).toContain('7 号获得最多票')
  expect(page.findAll('.seat-eliminated')).toHaveLength(1)
  await page.find('button[aria-label="上一事件"]').trigger('click')
  expect(store.position).toBe(340)
  expect(page.findAll('.seat-eliminated')).toHaveLength(0)
})
test('timeline keyboard Home, End and arrows move cursor and focus', async () => {
  const { page, store } = await render()
  await page.findAll('.event-timeline-button')[0]!.trigger('keydown', { key: 'End' })
  expect(store.position).toBe(1694)
  expect(document.activeElement).toBe(page.findAll('.event-timeline-button').at(-1)!.element)
  await page.findAll('.event-timeline-button').at(-1)!.trigger('keydown', { key: 'Home' })
  expect(store.position).toBe(0)
  await page.findAll('.event-timeline-button')[0]!.trigger('keydown', { key: 'ArrowDown' })
  expect(store.position).toBe(22)
})
test('seeking pauses playback, changes speed and phase through native controls', async () => {
  const { page, store } = await render()
  await page.find('button[aria-label="开始播放"]').trigger('click')
  expect(store.playing).toBe(true)
  await page.find('input[type="range"]').setValue('405')
  expect(store.playing).toBe(false); expect(store.view?.atmosphere).toBe('night')
  const selects = page.findAll('.playback-buttons select')
  await selects[0]!.setValue('4'); expect(store.speed).toBe(4)
  await selects[1]!.setValue('1190'); expect(store.view?.round).toBe(3)
})
test('跳转阶段下拉框每一项都有可读文案，不出现 undefined', async () => {
  const { page } = await render()
  const options = page.find('.playback-jump select').findAll('option').map(option => option.text())

  expect(options.length).toBeGreaterThan(0)
  expect(options.every(text => text.trim().length > 0 && !text.includes('undefined'))).toBe(true)
  // 夜间子阶段有各自的文案，不再折叠成同一个"黑夜行动"。
  expect(options.some(text => text.includes('狼人行动'))).toBe(true)
})
test('章节缺少阶段时显示未知占位，而不是 undefined', async () => {
  const fixture = createObservationFixture()
  vi.spyOn(observationAdapter, 'get').mockResolvedValueOnce({ ...fixture,
    events: fixture.events.map(event => event.type === 'PHASE_CHANGED' ? { ...event, change: { ...event.change, phase: undefined } } : event) })
  const { page } = await render()
  const options = page.find('.playback-jump select').findAll('option').map(option => option.text())

  expect(options.some(text => text.includes('未知阶段'))).toBe(true)
  expect(options.every(text => !text.includes('undefined'))).toBe(true)
})
test('private perspective filters timeline and other role tags without changing game state', async () => {
  const { page, store } = await render()
  await page.find('.replay-perspective select').setValue('1')
  expect(page.findAll('.event-timeline-button').some(button => button.text().includes('狼人选择目标'))).toBe(false)
  expect(store.visibleEvents.filter(e => e.scope === 'PRIVATE').every(e => e.recipient === 1)).toBe(true)
  await page.findAll('.game-seat')[1]!.trigger('click'); await flushPromises()
  expect(page.find('dialog').text()).toContain('当前视角不展示')
  expect(page.find('dialog').text()).not.toContain('角色：女巫')
})
test('empty visibility filter keeps game board and provides readable empty feedback', async () => {
  const { page } = await render()
  await page.find('.replay-timeline select').setValue('JUDGE')
  expect(page.findAll('.game-seat')).toHaveLength(9)
  expect(page.text()).toContain('该视角与筛选条件下没有事件')
  expect(page.find('button[aria-label="下一事件"]').attributes('disabled')).toBeDefined()
})
test('event modal copies ID with clipboard failure feedback and seek action', async () => {
  const { page } = await render()
  await page.find('button[aria-label="查看当前事件详情"]').trigger('click'); await flushPromises()
  const copy = page.findAll('dialog button').find(button => button.text() === '复制事件 ID')!
  await copy.trigger('click'); await flushPromises()
  expect(page.find('dialog').text()).toContain('手动复制')
  expect(page.find('.event-id').text()).toBe('demo-event-17')
})
test('unknown route gets retry and demo link, never a substituted game', async () => {
  const { page } = await render('replay', 'missing')
  expect(page.text()).toContain('暂时无法查看对局')
  expect(page.find('.game-table').exists()).toBe(false)
  expect(page.find('a[href="/games/demo/replay"]').exists()).toBe(true)
})
test('empty event record has a useful empty state and disabled playback', async () => {
  vi.spyOn(observationAdapter, 'get').mockResolvedValue({ ...createObservationFixture(), events: [] })
  const { page } = await render()
  expect(page.text()).toContain('该区间没有事件')
  expect(page.find('button[aria-label="开始播放"]').attributes('disabled')).toBeDefined()
  expect(page.find('.match-information').text()).toContain('暂无结果')
})
test('playback advances on the clock and cleanup stops the timer', async () => {
  vi.useFakeTimers()
  const { page, store } = await render('watch')
  await page.find('button[aria-label="开始播放"]').trigger('click')
  vi.advanceTimersByTime(1000)
  expect(store.position).toBe(757)
  page.unmount(); wrapper = undefined
  expect(store.match).toBeNull(); expect(store.playing).toBe(false)
  expect(vi.getTimerCount()).toBe(0)
})
test('automatic timeline following can be paused independently from playback', async () => {
  const { page, store } = await render()
  expect(page.find('.timeline-follow').attributes('aria-pressed')).toBe('true')
  await page.find('.timeline-follow').trigger('click')
  expect(page.find('.timeline-follow').attributes('aria-pressed')).toBe('false')
  expect(store.playing).toBe(false)
})
test('loading skeleton transitions to data and stale request is discarded on navigation', async () => {
  let complete: (match: ReturnType<typeof createObservationFixture>) => void = () => {}
  vi.spyOn(observationAdapter, 'get').mockImplementationOnce(() => new Promise(resolve => { complete = resolve }))
  const { page, router } = await render()
  expect(page.text()).toContain('正在载入事件记录')
  await router.push('/games/demo-next/replay'); await flushPromises()
  complete(createObservationFixture('demo')); await flushPromises()
  expect(page.find('.game-table').exists()).toBe(true)
  expect(useObservationStore().match?.gameId).toBe('demo-next')
})
