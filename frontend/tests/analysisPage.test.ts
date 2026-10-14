// @vitest-environment jsdom
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import AnalysisPage from '../src/pages/AnalysisPage.vue'
import { useAnalysisStore } from '../src/stores/analysis'
import { useObservationStore } from '../src/stores/observation'
import { analysisAdapter, createAgentAnalysis, createAnalysisSummary } from '../src/features/analysis/mockAnalysisAdapter'
import { observationAdapter, createObservationFixture } from '../src/features/observation/mockObservationAdapter'
import { AnalysisAccessError } from '../src/features/analysis/types'
vi.mock('../src/features/home/village', () => ({ createVillage: () => ({ drawGameTable: vi.fn() }) }))
let wrapper: VueWrapper | undefined
beforeEach(() => {
  vi.restoreAllMocks()
  HTMLDialogElement.prototype.showModal = function () { this.open = true }
  HTMLDialogElement.prototype.close = function () { if (this.open) { this.open = false; this.dispatchEvent(new Event('close')) } }
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; document.body.innerHTML = ''; vi.useRealTimers() })
async function render(id = 'demo') {
  const pinia = createPinia(), router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/games/:gameId/analysis', component: AnalysisPage }, { path: '/games/:gameId/replay', component: { template: '<p>回放</p>' } }, { path: '/lobby', component: { template: '<p>大厅</p>' } }] })
  await router.push(`/games/${id}/analysis`); await router.isReady()
  wrapper = mount(AnalysisPage, { attachTo: document.body, global: { plugins: [pinia, router], stubs: { FogVillageScene: true, VillageHeader: true, PixelAvatar: true } } })
  await flushPromises()
  return { page: wrapper, analysis: useAnalysisStore(pinia), playback: useObservationStore(pinia), router }
}
test('analysis route displays strategy, decision steps, charts and nine readonly seats', async () => {
  const { page, playback } = await render()
  expect(page.findAll('.game-seat')).toHaveLength(9); expect(playback.position).toBe(820)
  expect(page.find('.analysis-strategy').text()).toContain('4 号 · AI-灰狼')
  expect(page.findAll('.analysis-step')).toHaveLength(4)
  expect(page.find('.analysis-charts').text()).toContain('42%')
  expect(page.find('.analysis-footnote').text()).toContain('不代表真实胜率')
  expect(page.find('a[href="/games/demo/replay"]').exists()).toBe(true)
})
test('Agent selection pauses playback, updates strategy and moves focus to panel heading', async () => {
  const { page, analysis, playback } = await render()
  playback.toggle()
  await page.find('.analysis-phase select').setValue('1'); await flushPromises()
  expect(playback.playing).toBe(false); expect(analysis.selectedSeat).toBe(1)
  expect(page.find('.analysis-strategy').text()).toContain('预言家')
  expect(document.activeElement).toBe(page.find('.analysis-strategy h2').element)
  await page.findAll('.game-seat')[1]!.trigger('click'); await flushPromises()
  expect(analysis.selectedSeat).toBe(2)
})
test('key events seek backward and synchronize selected speech Agent and probabilities', async () => {
  const { page, playback, analysis } = await render()
  const event = page.findAll('.analysis-events li button').find(button => button.text().includes('1 号 AI-白狐 发言'))!
  await event.trigger('click'); await flushPromises()
  expect(playback.position).toBe(95); expect(analysis.selectedSeat).toBe(1); expect(analysis.trace?.at).toBe(95)
  expect(page.findAll('.seat-eliminated')).toHaveLength(0)
  expect(page.find('.analysis-charts').text()).toContain('50%')
})
test('timeline keyboard supports Home, End and focus on selected event', async () => {
  const { page, playback } = await render()
  await page.findAll('.analysis-events li button')[0]!.trigger('keydown', { key: 'End' }); await flushPromises()
  expect(playback.position).toBe(1694)
  expect(document.activeElement).toBe(page.findAll('.analysis-events li button').at(-1)!.element)
  await page.findAll('.analysis-events li button').at(-1)!.trigger('keydown', { key: 'Home' })
  expect(playback.position).toBe(0)
})
test('each decision step opens structured details and closing restores focus', async () => {
  const { page } = await render()
  const button = page.find('button[aria-label="展开候选行动"]'); (button.element as HTMLButtonElement).focus()
  await button.trigger('click'); await flushPromises()
  expect((page.find('dialog').element as HTMLDialogElement).open).toBe(true)
  expect(page.find('dialog').text()).toContain('追问对方的发言与投票依据')
  expect(page.find('dialog').text()).toContain('Token 用量：暂无数据')
  await page.find('button[aria-label="关闭窗口"]').trigger('click'); await flushPromises()
  expect(document.activeElement).toBe(button.element)
})
test('scope tabs change visible chart series and equivalent table values', async () => {
  const { page, analysis } = await render()
  await page.findAll('.analysis-scopes button')[1]!.trigger('click')
  expect(analysis.scope).toBe('camp')
  expect(page.find('.analysis-charts').text()).not.toContain('村民阵营')
  expect(page.find('.analysis-charts').text()).not.toContain('个人存活率')
  await page.find('.analysis-table-button').trigger('click'); await flushPromises()
  expect(page.find('table').text()).toContain('狼人阵营胜率42%')
  expect(page.find('table').text()).not.toContain('村民阵营胜率')
  await page.find('button[aria-label="关闭窗口"]').trigger('click')
  await page.findAll('.analysis-scopes button')[2]!.trigger('click')
  expect(page.find('.analysis-charts').text()).toContain('个人存活率')
  expect(page.find('.analysis-charts').text()).not.toContain('阵营胜率（当前）')
})
test('empty Agent traces show useful feedback and preserve strategy', async () => {
  const { page } = await render()
  await page.find('.analysis-phase select').setValue('6'); await flushPromises()
  expect(page.find('.analysis-empty').text()).toContain('暂无决策摘要')
  expect(page.find('.analysis-strategy').text()).toContain('村民')
  expect(page.find('.analysis-charts').text()).toContain('暂无数据')
  expect(page.find('.analysis-charts').text()).not.toContain('估计存活0%')
})
test('no future trace leaks into current decision; first decision button seeks explicitly', async () => {
  const { page, playback } = await render()
  playback.seek(0); await flushPromises()
  expect(page.find('.analysis-empty').text()).toContain('该时刻尚无')
  await page.find('.analysis-empty button').trigger('click')
  expect(playback.position).toBe(820)
})
test('Agent failure retains current record; retry fetches the failed selection', async () => {
  const { page, analysis } = await render()
  vi.spyOn(analysisAdapter, 'agent').mockRejectedValueOnce(new Error('示例读取失败'))
  await page.find('.analysis-phase select').setValue('1'); await flushPromises()
  expect(page.find('[role="alert"]').text()).toContain('仍显示 4 号')
  expect(page.find('.analysis-strategy').text()).toContain('AI-灰狼')
  expect((page.find('.analysis-phase select').element as HTMLSelectElement).value).toBe('4')
  await page.find('.analysis-agent-status button').trigger('click'); await flushPromises()
  expect(analysis.selectedSeat).toBe(1)
})
test('unknown and restricted matches show explicit errors without board or hidden roles', async () => {
  const { page } = await render('unknown')
  expect(page.text()).toContain('分析加载失败'); expect(page.find('.game-table').exists()).toBe(false)
  vi.spyOn(analysisAdapter, 'summary').mockRejectedValueOnce(new AnalysisAccessError('未获得授权'))
  await page.find('button.secondary-button').trigger('click'); await flushPromises()
  expect(page.text()).toContain('暂未开放分析'); expect(page.text()).toContain('未获得授权')
})
test('nullable metrics and empty event records are displayed without invented statistics', async () => {
  vi.spyOn(analysisAdapter, 'summary').mockResolvedValueOnce({ ...createAnalysisSummary(), estimates: [] })
  vi.spyOn(analysisAdapter, 'agent').mockResolvedValueOnce({ ...createAgentAnalysis('demo', 4), traces: [] })
  vi.spyOn(observationAdapter, 'get').mockResolvedValueOnce({ ...createObservationFixture(), events: [] })
  const { page } = await render()
  expect(page.text()).toContain('暂无关键事件'); expect(page.text()).toContain('暂无结果')
  expect(page.find('.analysis-charts').text()).toContain('暂无数据')
  expect(page.find('button[aria-label="开始播放"]').attributes('disabled')).toBeDefined()
})
test('回合筛选的选项来自真实对局轮次：四轮对局可以筛第 4 天', async () => {
  const fixture = createObservationFixture()
  // 第 4 轮的事件：写死 3 天的实现根本不会给出这个选项（QA P2）。
  const fourth = { ...fixture.events.at(-1)!, id: 'demo-event-fourth', version: 90, at: 1700,
    type: 'PHASE_CHANGED', title: '阶段推进', content: '第 4 天进入放逐投票。', change: { phase: '放逐投票' as const, round: 4 } }
  vi.spyOn(observationAdapter, 'get').mockResolvedValueOnce({ ...fixture, duration: 1700, events: [...fixture.events, fourth] })
  const { page } = await render()
  const select = page.find('.analysis-events select')

  expect(select.findAll('option').map(option => option.text())).toEqual(['全部回合', '第 1 天', '第 2 天', '第 3 天', '第 4 天'])

  await select.setValue('4')
  expect(page.findAll('.analysis-events li')).toHaveLength(1)
  expect(page.find('.analysis-events li').text()).toContain('阶段推进')
})
test('loading is visible and a stale route response cannot seek a new match', async () => {
  let finish!: (summary: ReturnType<typeof createAnalysisSummary>) => void
  vi.spyOn(analysisAdapter, 'summary').mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const { page, router, playback, analysis } = await render('demo-first')
  expect(page.text()).toContain('正在载入 AI 分析')
  await router.push('/games/demo-next/analysis'); await flushPromises()
  playback.seek(100)
  finish(createAnalysisSummary('demo-first')); await flushPromises()
  expect(analysis.summary?.gameId).toBe('demo-next'); expect(playback.position).toBe(100)
})
test('playback clock advances and unmount removes timer and readonly state', async () => {
  vi.useFakeTimers()
  const { page, playback, analysis } = await render()
  await page.find('button[aria-label="开始播放"]').trigger('click')
  vi.advanceTimersByTime(1000); expect(playback.position).toBe(821)
  page.unmount(); wrapper = undefined
  expect(vi.getTimerCount()).toBe(0); expect(analysis.summary).toBeNull(); expect(playback.match).toBeNull()
})
