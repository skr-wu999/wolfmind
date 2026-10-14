// @vitest-environment jsdom
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import LabPage from '../src/pages/LabPage.vue'
import { useLabStore } from '../src/stores/lab'
import { useObservationStore } from '../src/stores/observation'
import { labAdapter, resetLabDemo } from '../src/features/lab/mockLabAdapter'
import { LabAccessError, ruleBaselineModel } from '../src/features/lab/types'
import type { AgentConfig, AgentDraft, BatchOptions, ComparisonRow, LabAdapter, LabBatch, LabCatalog } from '../src/features/lab/types'
vi.mock('../src/features/home/village', () => ({ createVillage: () => ({ drawGameTable: vi.fn() }) }))

/*
 * 真实模式的页面 branch 只有走注册表才可达，因此这里替换注册表：
 * `mode.real` 对应 `usingRealBackend()`，`mode.adapter` 对应真实适配器（默认仍是演示适配器）。
 */
const mode = vi.hoisted(() => ({ real: false, adapter: null as unknown as LabAdapter | null }))
vi.mock('../src/api/adapters', async importOriginal => {
  const actual = await importOriginal<typeof import('../src/api/adapters')>()
  return { ...actual, usingRealBackend: () => mode.real, labAdapter: () => mode.adapter ?? actual.labAdapter() }
})

const batchOptions: BatchOptions = { total: 2, seedStart: 1, seedEnd: 2, mode: 'standard', speed: 1, concurrency: 2 }
const realConfig: AgentConfig = { id: 'ac_1', name: '规则基线对照', role: 'WEREWOLF', personality: '理性分析', strategy: '深度伪装', modelId: ruleBaselineModel.id, temperature: 0.7, promptMode: 'default', prompt: '默认提示词', memory: 'short', tools: { analysis: true, history: true, summary: true } }
const realBatch: LabBatch = {
  id: 'lb_1', status: 'COMPLETED', total: 2, completed: 2, options: batchOptions, configs: [realConfig],
  results: [{ index: 1, seed: 1, outcomes: [{ configId: 'ac_1', winner: '狼人阵营' }], gameId: 'g_lab_1' }],
  games: [
    { index: 1, gameId: 'g_lab_1', status: 'FINISHED', winner: '狼人阵营', rounds: 3 },
    { index: 2, gameId: 'g_lab_2', status: 'RUNNING', winner: null, rounds: 1 },
  ],
}
function realAdapter(overrides: Partial<LabAdapter> = {}): LabAdapter {
  return {
    catalog: vi.fn(async (): Promise<LabCatalog> => ({ models: [ruleBaselineModel], configs: [realConfig], batch: null })),
    save: vi.fn(async (draft: AgentDraft): Promise<AgentConfig> => ({ ...draft, id: draft.id ?? 'ac_1', name: draft.name.trim() })),
    start: vi.fn(async (): Promise<LabBatch> => realBatch),
    refresh: vi.fn(async (): Promise<LabBatch> => realBatch),
    comparisons: vi.fn(async (): Promise<ComparisonRow[]> => []),
    clearLocalResults: vi.fn(async () => {}),
    ...overrides,
  }
}
let wrapper: VueWrapper | undefined
beforeEach(() => {
  vi.restoreAllMocks(); resetLabDemo(); mode.real = false; mode.adapter = null
  HTMLDialogElement.prototype.showModal = function () { this.open = true }
  HTMLDialogElement.prototype.close = function () { if (this.open) { this.open = false; this.dispatchEvent(new Event('close')) } }
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; document.body.innerHTML = ''; vi.useRealTimers() })
async function render() {
  const pinia = createPinia(), router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/lab', component: LabPage }, { path: '/lobby', component: { template: '<p>大厅</p>' } }, { path: '/watch/:gameId', component: { template: '<p>观战</p>' } }, { path: '/games/:gameId/:view', component: { template: '<p>回看</p>' } }] })
  await router.push('/lab'); await router.isReady()
  wrapper = mount(LabPage, { attachTo: document.body, global: { plugins: [pinia, router], stubs: { FogVillageScene: true, VillageHeader: true, PixelAvatar: true } } })
  await flushPromises()
  return { page: wrapper, store: useLabStore(pinia), preview: useObservationStore(pinia), router }
}
test('lab displays seven config groups, nine seats, batch controls and equivalent comparison table', async () => {
  const { page } = await render()
  expect(page.findAll('.lab-config-section')).toHaveLength(7); expect(page.findAll('.game-seat')).toHaveLength(9)
  expect(page.findAll('.lab-model-table tbody tr')).toHaveLength(4); expect(page.find('.lab-heading').text()).toContain('手工编写')
  expect(page.find('.lab-progress').text()).toContain('0%'); expect(page.findAll('.lab-summary-grid > div')).toHaveLength(4)
})
test('custom prompt, role and memory edits save, persist and focus the results heading', async () => {
  const { page, store } = await render()
  await page.find('.lab-role-buttons button:nth-child(2)').trigger('click')
  await page.findAll('.lab-prompt-tabs')[0]!.findAll('button')[1]!.trigger('click')
  await page.find('#lab-prompt').setValue('核对公开发言，提出合法行动建议。')
  await page.findAll('.lab-prompt-tabs')[1]!.findAll('button')[1]!.trigger('click')
  await page.find('.lab-config').trigger('submit'); await flushPromises()
  expect(store.configs[0]?.role).toBe('SEER'); expect(store.configs[0]?.memory).toBe('long')
  expect(store.configs[0]?.prompt).toContain('核对公开发言'); expect(document.activeElement).toBe(page.find('.lab-results h2').element)
  expect(page.find('[role=status]').text()).toContain('已保存')
})
test('validation focuses the first bad field and advanced temperature errors reopen their dialog', async () => {
  const { page, store } = await render()
  await page.find('[data-lab-field=name]').setValue(' '); await page.find('.lab-config').trigger('submit'); await flushPromises()
  expect(document.activeElement).toBe(page.find('[data-lab-field=name]').element)
  expect(page.find('#lab-name-error').text()).toContain('1–24')
  await page.find('[data-lab-field=name]').setValue('测试'); store.draft!.temperature = 2
  await page.find('.lab-config').trigger('submit'); await flushPromises()
  expect((page.find('dialog').element as HTMLDialogElement).open).toBe(true)
  expect(document.activeElement).toBe(page.find('#lab-temperature').element)
})
test('batch count updates seed range and explicit refresh advances progress', async () => {
  const { page, store } = await render()
  await page.find('[data-lab-field=total]').setValue('4'); expect(store.options.seedEnd).toBe(1004)
  await page.find('[data-lab-field=configIds]').trigger('click'); await flushPromises()
  expect(store.batch?.completed).toBe(0); expect(page.find('[data-lab-field=configIds]').attributes('disabled')).toBeDefined()
  expect(page.find('.lab-model-table').text()).toContain('暂无数据')
  await page.find('.lab-refresh').trigger('click'); await flushPromises()
  expect(store.progress).toBe(100); expect(page.find('.lab-progress').text()).toContain('4 / 4')
  expect(page.find('[data-lab-field=configIds]').attributes('disabled')).toBeUndefined()
})
test('saved config selection requires at least one configuration and preserves count inputs on failure', async () => {
  const { page, store } = await render()
  await page.find('.lab-config-picker .lab-link:last-child').trigger('click'); await flushPromises()
  for (const input of page.findAll('.lab-saved-configs input')) await input.setValue(false)
  await page.find('button[aria-label="关闭窗口"]').trigger('click'); await flushPromises()
  await page.find('[data-lab-field=configIds]').trigger('click'); await flushPromises()
  expect(store.batch).toBeNull(); expect(page.find('.lab-batch').text()).toContain('至少选择一份')
  expect(store.options.total).toBe(20); expect(document.activeElement).toBe(page.find('[data-lab-field=configIds]').element)
})
test('comparison sorting and role filtering show correct best value and empty feedback', async () => {
  const { page } = await render()
  await page.find('.lab-comparison .lab-panel-title select').setValue('latency')
  expect(page.find('.lab-model-table tbody tr').text()).toContain('规则 Bot')
  expect(page.find('.lab-summary-grid').text()).toContain('0.2s')
  await page.find('.lab-comparison-filters select').setValue('SEER')
  expect(page.find('.lab-model-table').exists()).toBe(false); expect(page.find('.lab-comparison').text()).toContain('没有比较数据')
})
test('stage changes filter past speech and logs support Agent summaries, system events and seat filtering', async () => {
  const { page, preview } = await render()
  expect(page.find('.lab-log-list').text()).toContain('昨晚我查验')
  await page.find('.lab-footer select').setValue('0'); expect(preview.position).toBe(0)
  expect(page.find('.lab-workbench').text()).toContain('没有已播放记录')
  await page.find('.lab-log-toolbar button:last-child').trigger('click')
  expect(page.find('.lab-log-list').text()).toContain('游戏开始')
  await page.find('.lab-workbench-tabs button:last-child').trigger('click')
  expect(page.find('.lab-full-log').text()).toContain('游戏开始'); expect(page.find('.game-table').exists()).toBe(false)
})
test('hiding roles removes table labels and seat dialog remains read-only with focus restoration', async () => {
  const { page } = await render()
  await page.find('.lab-workbench-tabs button').trigger('click')
  expect(page.findAll('.seat-role')).toHaveLength(0)
  const seat = page.find('.game-seat'); (seat.element as HTMLButtonElement).focus()
  await seat.trigger('click'); await flushPromises()
  expect(page.find('dialog').text()).toContain('隐藏身份标签'); expect(page.find('dialog').text()).toContain('不是当前批次')
  await page.find('button[aria-label="关闭窗口"]').trigger('click'); await flushPromises()
  expect(document.activeElement).toBe(seat.element)
})
test('reports label all metrics as examples and expose replay only for the independent sample', async () => {
  const { page } = await render()
  await page.find('.lab-results .lab-link').trigger('click'); await flushPromises()
  expect(page.find('dialog').text()).toContain('不作为模型性能或胜率的评测结论')
  expect(page.find('dialog a[href="/games/demo/replay"]').exists()).toBe(true)
  expect(page.find('dialog').text()).toContain('暂无当前批次结果')
})
test('export creates a local JSON download with provenance and releases its URL', async () => {
  const { page } = await render(), create = vi.fn((_blob: Blob) => 'blob:lab-export'), revoke = vi.fn()
  Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: create })
  Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: revoke })
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) { expect(this.download).toBe('wolfmind-lab-reference.json') })
  await page.find('.lab-footer button:not(.lab-clear)').trigger('click'); await flushPromises()
  expect(create).toHaveBeenCalledOnce(); expect(create.mock.calls[0]?.[0]).toBeInstanceOf(Blob)
  expect(click).toHaveBeenCalledOnce(); expect(revoke).toHaveBeenCalledWith('blob:lab-export')
  expect(page.find('[role=status]').text()).toContain('已导出 JSON')
})
test('clear confirmation removes only local result records and keeps configurations', async () => {
  const { page, store } = await render()
  await page.find('.lab-clear').trigger('click'); await flushPromises()
  expect(store.comparisons).toHaveLength(4)
  await page.find('dialog .primary-button').trigger('click'); await flushPromises()
  expect(store.comparisons).toHaveLength(0); expect(store.configs).toHaveLength(4)
  expect((page.find('dialog').element as HTMLDialogElement).open).toBe(false)
})
test('empty catalog, load retry and permission denial have explicit page states', async () => {
  vi.spyOn(labAdapter, 'catalog').mockRejectedValueOnce(new Error('载入失败'))
  const { page } = await render(); expect(page.find('[role=alert]').text()).toContain('载入失败')
  await page.find('main .secondary-button').trigger('click'); await flushPromises(); expect(page.find('.lab-config').exists()).toBe(true)
  page.unmount(); wrapper = undefined
  sessionStorage.setItem('wolfmind.lab.configs.v1', '[]')
  const empty = await render(); expect(empty.page.find('.lab-config').text()).toContain('还没有 Agent 配置')
  await empty.page.find('.lab-config-picker .lab-link').trigger('click'); expect(empty.page.find('.lab-name').exists()).toBe(true)
  empty.page.unmount(); wrapper = undefined
  vi.spyOn(labAdapter, 'catalog').mockRejectedValueOnce(new LabAccessError('实验权限不足'))
  const denied = await render(); expect(denied.page.find('[role=alert]').text()).toContain('没有实验室权限')
  expect(denied.page.find('.lab-batch').exists()).toBe(false)
})
test('pending catalog shows loading feedback and navigation reaches the lobby', async () => {
  const catalog = await labAdapter.catalog()
  let finish!: (value: typeof catalog) => void
  vi.spyOn(labAdapter, 'catalog').mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const { page, router } = await render()
  expect(page.find('[role=status]').text()).toContain('正在载入实验室'); expect(page.find('.lab-config').exists()).toBe(false)
  finish(catalog); await flushPromises(); expect(page.find('.lab-config').exists()).toBe(true)
  await page.find('nav a[href="/lobby"]').trigger('click'); await flushPromises(); expect(router.currentRoute.value.path).toBe('/lobby')
})

/* ---------- 真实模式：真实对局 id、只读正文、无模型也能起步 ---------- */

test('真实模式：逐局用真实 gameId 提供观战/回放/分析，页面里没有任何 demo 链接', async () => {
  mode.real = true
  mode.adapter = realAdapter({ catalog: vi.fn(async () => ({ models: [ruleBaselineModel], configs: [realConfig], batch: realBatch })) })
  const { page, router } = await render()

  expect(page.findAll('a').map(link => link.attributes('href') || '').some(href => href.includes('demo'))).toBe(false)
  // 重新载入后（catalog 走 latestBatchId → 批次详情）进度、当前批次行与 x / y 局都是真实数字
  expect(page.find('.lab-progress').text()).toContain('100%')
  expect(page.find('.lab-progress').text()).toContain('2 / 2 局')
  expect(page.find('.lab-comparison').text()).toContain('当前批次 · 已结束 2 局')
  expect(page.find('.lab-footer-state').text()).toContain('批次已完成')
  expect(page.find('.lab-games').text()).toContain('g_lab_1')
  expect(page.find('.lab-games').text()).toContain('已结束')
  expect(page.find('.lab-games a[href="/watch/g_lab_1"]').exists()).toBe(true)
  expect(page.find('.lab-games a[href="/games/g_lab_1/replay"]').exists()).toBe(true)
  expect(page.find('.lab-games a[href="/games/g_lab_1/analysis"]').exists()).toBe(true)
  // 导航指向最近一局真实对局，而不是演示样例
  expect(page.find('nav a[href="/watch/g_lab_2"]').exists()).toBe(true)
  await page.find('.lab-games a[href="/games/g_lab_1/analysis"]').trigger('click'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/games/g_lab_1/analysis')
})

test('真实模式：没有批次时给出对局空状态，导航不回退到 demo', async () => {
  mode.real = true; mode.adapter = realAdapter()
  const { page } = await render()

  expect(page.find('.lab-games').text()).toContain('还没有批次')
  expect(page.findAll('a').map(link => link.attributes('href') || '').some(href => href.includes('demo'))).toBe(false)
  expect(page.find('nav a[href="/watch/demo"]').exists()).toBe(false)
})

test('真实模式：提示词正文只读，并说明模板标识只作为比对维度', async () => {
  mode.real = true
  mode.adapter = realAdapter({ catalog: vi.fn(async () => ({ models: [ruleBaselineModel], configs: [realConfig], batch: null })) })
  const { page } = await render()

  expect(page.find('#lab-prompt').attributes('readonly')).toBeDefined()
  expect(page.find('#lab-prompt-note').text()).toContain('只作为比对维度')
  expect(page.find('.lab-textarea-footer').text()).toContain('不会被发送')
  await page.findAll('.lab-prompt-tabs')[0]!.findAll('button')[1]!.trigger('click')
  expect(page.find('#lab-prompt').attributes('readonly')).toBeDefined()
})

test('真实模式：服务端没有模型时，空状态的主按钮能建出可保存的规则机器人配置', async () => {
  const adapter = realAdapter({ catalog: vi.fn(async () => ({ models: [ruleBaselineModel], configs: [], batch: null })) })
  mode.real = true; mode.adapter = adapter
  const { page, store } = await render()

  expect(page.find('.lab-empty-config').text()).toContain('规则机器人')
  expect(page.find('.lab-config-picker .lab-link').attributes('disabled')).toBeUndefined()
  await page.find('.lab-empty-config .primary-button').trigger('click'); await flushPromises()
  expect(store.draft?.modelId).toBe('rule-baseline')
  await page.find('.lab-config').trigger('submit'); await flushPromises()
  expect(adapter.save).toHaveBeenCalledOnce()
  expect(store.selectedIds).toEqual(['ac_1'])
  expect(page.find('[role=status]').text()).toContain('已保存')
})
