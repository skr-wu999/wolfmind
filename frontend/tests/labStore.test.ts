// @vitest-environment jsdom
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useLabStore } from '../src/stores/lab'
import { defaultConfigs, labAdapter, labModels, resetLabDemo } from '../src/features/lab/mockLabAdapter'
import { defaultPrompt, LabAccessError, ruleBaselineModel, validateBatch, validateDraft } from '../src/features/lab/types'
import type { AgentConfig, AgentDraft, BatchOptions, ComparisonRow, LabAdapter, LabBatch, LabCatalog } from '../src/features/lab/types'
const options = { total: 20, seedStart: 1001, seedEnd: 1020, mode: 'standard' as const, speed: 1, concurrency: 2 }
const deferred = <T>() => { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done }); return { promise, resolve } }

/*
 * 真实模式的 branch 只有走注册表才可达，因此这里替换注册表而不是复制一份 store 逻辑：
 * `mode.real` 对应 `usingRealBackend()`，`mode.adapter` 对应适配器本身（默认仍是演示适配器）。
 */
const mode = vi.hoisted(() => ({ real: false, adapter: null as unknown as LabAdapter | null }))
vi.mock('../src/api/adapters', async importOriginal => {
  const actual = await importOriginal<typeof import('../src/api/adapters')>()
  return { ...actual, usingRealBackend: () => mode.real, labAdapter: () => mode.adapter ?? actual.labAdapter() }
})

/** 真实适配器的替身：只关心 store 拿到响应之后的行为。 */
function realAdapter(overrides: Partial<LabAdapter> = {}): LabAdapter {
  return {
    catalog: vi.fn(async (): Promise<LabCatalog> => ({ models: [ruleBaselineModel], configs: [], batch: null })),
    save: vi.fn(async (draft: AgentDraft): Promise<AgentConfig> => ({ ...draft, id: 'ac_1', name: draft.name.trim(), prompt: defaultPrompt })),
    start: vi.fn(async (_configIds: string[], batchOptions: BatchOptions): Promise<LabBatch> => ({ id: 'lb_1', status: 'RUNNING', total: batchOptions.total, completed: 0, options: batchOptions, configs: [], results: [], games: [] })),
    refresh: vi.fn(async (id: string): Promise<LabBatch> => ({ id, status: 'COMPLETED', total: 1, completed: 1, options, configs: [], results: [], games: [] })),
    comparisons: vi.fn(async (): Promise<ComparisonRow[]> => []),
    clearLocalResults: vi.fn(async () => {}),
    ...overrides,
  }
}
beforeEach(() => { vi.restoreAllMocks(); resetLabDemo(); setActivePinia(createPinia()); mode.real = false; mode.adapter = null })
afterEach(() => vi.useRealTimers())

test('validates names, prompt boundaries and temperature before saving', async () => {
  const store = useLabStore(); await store.load()
  const save = vi.spyOn(labAdapter, 'save')
  Object.assign(store.draft!, { name: ' ', temperature: 1.1, prompt: 'x'.repeat(801) })
  expect(await store.save()).toBe(false); expect(Object.keys(store.fields)).toEqual(['name', 'temperature', 'prompt']); expect(save).not.toHaveBeenCalled()
  expect(validateDraft({ ...defaultConfigs()[0]!, role: 'GUARD' as never }, labModels.map(m => m.id)).role).toBeTruthy()
})
test('rejects invalid counts, incomplete seed ranges, concurrency and empty selection', async () => {
  const store = useLabStore(); await store.load()
  const start = vi.spyOn(labAdapter, 'start')
  store.options.total = 51; store.options.seedEnd = 1005; store.options.concurrency = 9; store.selectedIds = []
  expect(await store.start()).toBe(false); expect(Object.keys(store.fields)).toEqual(['total', 'seedEnd', 'concurrency', 'configIds']); expect(start).not.toHaveBeenCalled()
  expect(validateBatch({ ...options, seedStart: -1 }, ['a']).seedStart).toBeTruthy()
  expect(validateBatch({ ...options, seedStart: 2147483647, seedEnd: 2147483666 }, ['a']).seedEnd).toBeTruthy()
})
test('saves a configuration and restores it from local session storage without sharing drafts', async () => {
  const store = useLabStore(); await store.load(); store.newConfig(); store.draft!.name = '证据核对'
  store.draft!.memory = 'long'; store.draft!.tools.history = false
  expect(await store.save()).toBe(true)
  const id = store.draft!.id!, saved = store.configs.find(c => c.id === id)!
  store.draft!.name = '尚未保存'; expect(saved.name).toBe('证据核对')
  const catalog = await labAdapter.catalog(); expect(catalog.configs.find(c => c.id === id)?.tools.history).toBe(false)
  catalog.configs[0]!.name = '外部修改'; expect((await labAdapter.catalog()).configs[0]!.name).toBe('白狐')
})
test('restored custom IDs do not cause a new configuration to overwrite a saved one', async () => {
  const restored = { ...defaultConfigs()[0]!, id: 'demo-custom-1', name: '已恢复配置' }
  sessionStorage.setItem('wolfmind.lab.configs.v1', JSON.stringify([restored])); await labAdapter.catalog()
  const saved = await labAdapter.save({ ...restored, id: undefined, name: '新配置' })
  expect(saved.id).not.toBe(restored.id); expect((await labAdapter.catalog()).configs.map(c => c.name)).toEqual(['已恢复配置', '新配置'])
})
test('corrupt session data does not erase the built-in catalog', async () => {
  sessionStorage.setItem('wolfmind.lab.configs.v1', '{bad')
  expect((await labAdapter.catalog()).configs).toHaveLength(4)
  sessionStorage.setItem('wolfmind.lab.configs.v1', JSON.stringify([{ id: 'broken', name: 'x', prompt: 'x' }]))
  expect((await labAdapter.catalog()).configs).toHaveLength(4)
})
test('an empty configuration catalog can create and save its first Agent', async () => {
  sessionStorage.setItem('wolfmind.lab.configs.v1', '[]')
  const store = useLabStore(); await store.load(); expect(store.draft).toBeNull()
  store.newConfig(); expect(await store.save()).toBe(true); expect(store.selectedIds).toHaveLength(1)
})
test('batch captures saved configuration and options; unsaved edits cannot change it', async () => {
  const store = useLabStore(); await store.load(); store.draft!.name = '未保存'
  await store.start(); expect(store.batch?.configs[0]?.name).toBe('白狐')
  store.options.speed = 5; store.options.total = 1; store.configs[0]!.name = '批次外改动'
  expect(store.batch?.total).toBe(20); expect(store.batch?.options.speed).toBe(1)
  await store.refresh(); expect(store.batch?.completed).toBe(4); expect(store.batch?.configs[0]?.name).toBe('白狐')
})
test('only explicit refresh advances a batch, with no polling or fabricated completed metrics', async () => {
  const store = useLabStore(); await store.load(); vi.useFakeTimers()
  const refresh = vi.spyOn(labAdapter, 'refresh'); await store.start()
  expect(store.batch?.completed).toBe(0); expect(store.comparisons.every(row => row.winRate === null)).toBe(true)
  await vi.advanceTimersByTimeAsync(600000); expect(refresh).not.toHaveBeenCalled(); expect(store.progress).toBe(0)
  for (let index = 0; index < 5; index++) await store.refresh()
  expect(store.progress).toBe(100); expect(store.batch?.status).toBe('COMPLETED'); expect(store.batch?.results).toHaveLength(20)
  expect(store.batch?.results.every(result => result.gameId === null)).toBe(true)
  expect(await store.refresh()).toBe(false); expect(refresh).toHaveBeenCalledTimes(5)
})
test('prevents duplicate submissions and refuses unknown saved configurations', async () => {
  const store = useLabStore(); await store.load(); const wait = deferred<LabBatch>()
  const start = vi.spyOn(labAdapter, 'start').mockReturnValueOnce(wait.promise)
  const pending = store.start(); expect(await store.start()).toBe(false); expect(start).toHaveBeenCalledTimes(1)
  wait.resolve({ id: 'queued', status: 'RUNNING', total: 20, completed: 0, options, configs: defaultConfigs(), results: [], games: [] }); await pending
  expect(await store.start()).toBe(false)
  await expect(labAdapter.start(['missing'], options)).rejects.toThrow('失效')
})
test('sorts latency ascending, other metrics descending and places missing values last', async () => {
  const store = useLabStore(); await store.load()
  expect(store.rows[0]?.winRate).toBe(70)
  store.metric = 'latency'; expect(store.rows[0]?.name).toBe('规则 Bot'); expect(store.best.latency?.latency).toBe(.2)
  store.metric = 'speechQuality'; expect(store.rows.at(-1)?.speechQuality).toBeNull()
  store.roleFilter = 'SEER'; expect(store.rows).toEqual([]); expect(store.best.winRate).toBeNull()
})
test('win rates reconcile with the separate per-config sample outcomes, including good roles', async () => {
  const store = useLabStore(); await store.load(); store.draft!.role = 'SEER'; await store.save()
  await store.start(); for (let i = 0; i < 5; i++) await store.refresh()
  for (const row of store.comparisons) {
    const camp = row.role === 'WEREWOLF' ? '狼人阵营' : '村民阵营'
    const wins = store.batch!.results.filter(result => result.outcomes.some(outcome => outcome.configId === row.configId && outcome.winner === camp)).length
    expect(row.wins).toBe(wins); expect(row.winRate).toBeCloseTo(wins / 20 * 100)
  }
  expect(store.comparisons[0]?.winRate).toBe(70)
})
test('failed save, batch start and refresh preserve the editable draft and prior progress', async () => {
  const store = useLabStore(); await store.load(); store.draft!.name = '保留编辑'
  vi.spyOn(labAdapter, 'save').mockRejectedValueOnce(new Error('保存失败'))
  expect(await store.save()).toBe(false); expect(store.draft?.name).toBe('保留编辑')
  vi.spyOn(labAdapter, 'start').mockRejectedValueOnce(new Error('队列失败'))
  expect(await store.start()).toBe(false); expect(store.options.total).toBe(20)
  await store.start(); await store.refresh(); const before = JSON.stringify(store.batch)
  vi.spyOn(labAdapter, 'refresh').mockRejectedValueOnce(new Error('查询失败'))
  expect(await store.refresh()).toBe(false); expect(JSON.stringify(store.batch)).toBe(before)
  await store.refresh(); expect(store.batch?.completed).toBe(8); expect(store.error).toBe('')
})
test('comparison failure keeps previous data and supports retry', async () => {
  const store = useLabStore(); await store.load(); const before = JSON.stringify(store.comparisons)
  vi.spyOn(labAdapter, 'comparisons').mockRejectedValueOnce(new Error('比较暂不可用'))
  await store.compare(); expect(store.comparisonError).toBe('比较暂不可用'); expect(JSON.stringify(store.comparisons)).toBe(before)
  await store.compare(); expect(store.comparisonError).toBe('')
})
test('invalid refresh totals, backward progress and inconsistent terminal status are rejected', async () => {
  const store = useLabStore(); await store.load(); await store.start(); await store.refresh()
  const before = JSON.parse(JSON.stringify(store.batch)) as LabBatch
  const refresh = vi.spyOn(labAdapter, 'refresh')
  for (const change of [{ total: 30 }, { completed: 3 }, { status: 'COMPLETED' as const }]) {
    refresh.mockResolvedValueOnce({ ...before, ...change }); expect(await store.refresh()).toBe(false); expect(store.batch).toEqual(before)
  }
})
test('access denial prevents save, submission, refresh, comparison and clearing', async () => {
  const store = useLabStore(); vi.spyOn(labAdapter, 'catalog').mockRejectedValueOnce(new LabAccessError('无权限'))
  await store.load(); expect(store.restricted).toBe(true)
  const compare = vi.spyOn(labAdapter, 'comparisons'), clear = vi.spyOn(labAdapter, 'clearLocalResults')
  expect(await store.save()).toBe(false); expect(await store.start()).toBe(false); expect(await store.refresh()).toBe(false)
  await store.compare(); expect(await store.clear()).toBe(false); expect(compare).not.toHaveBeenCalled(); expect(clear).not.toHaveBeenCalled()
})
test('late load and comparison responses cannot replace newer data', async () => {
  const store = useLabStore(), loading = deferred<LabCatalog>()
  vi.spyOn(labAdapter, 'catalog').mockReturnValueOnce(loading.promise)
  const first = store.load(); await store.load(); loading.resolve({ models: [], configs: [], batch: null }); await first
  expect(store.models).toHaveLength(4)
  const comparing = deferred<ComparisonRow[]>()
  vi.spyOn(labAdapter, 'comparisons').mockReturnValueOnce(comparing.promise)
  const pending = store.compare(); await store.compare(); comparing.resolve([]); await pending
  expect(store.rows).toHaveLength(4)
})
test('disposed save and refresh cannot repopulate page state', async () => {
  const store = useLabStore(); await store.load(); const saving = deferred<AgentConfig>()
  vi.spyOn(labAdapter, 'save').mockReturnValueOnce(saving.promise)
  const save = store.save(); store.dispose(); saving.resolve({ ...defaultConfigs()[0]!, name: '迟到保存' }); expect(await save).toBe(false)
  expect(store.configs[0]?.name).toBe('白狐')
  await store.load(); await store.start(); const refreshing = deferred<LabBatch>(), prior = JSON.parse(JSON.stringify(store.batch)) as LabBatch
  vi.spyOn(labAdapter, 'refresh').mockReturnValueOnce(refreshing.promise)
  const refresh = store.refresh(); store.dispose(); refreshing.resolve({ ...prior, completed: 4 }); expect(await refresh).toBe(false)
  expect(store.batch).toBeNull(); expect(store.refreshing).toBe(false)
})
test('clearing is blocked during a run and preserves saved configurations after completion', async () => {
  const store = useLabStore(); await store.load(); await store.start(); expect(await store.clear()).toBe(false)
  for (let i = 0; i < 5; i++) await store.refresh()
  const data = store.exportData(); expect(data.source).toContain('手工'); expect(data.reference).toBe(false)
  data.comparisons[0]!.name = '导出副本'; expect(store.comparisons[0]?.name).toBe('白狐')
  expect(await store.clear()).toBe(true); expect(store.batch).toBeNull(); expect(store.comparisons).toEqual([]); expect(store.configs).toHaveLength(4)
  await store.compare(); expect(store.comparisons).toEqual([])
})

/* ---------- 真实模式：服务端响应不完整时界面必须仍然可读 ---------- */

test('真实模式：计数不完整的批次被丢弃，不会渲染 NaN% 或 undefined 局', async () => {
  mode.real = true; mode.adapter = realAdapter({ catalog: vi.fn(async (): Promise<LabCatalog> => ({ models: [ruleBaselineModel], configs: [], batch: { id: 'lb_1', status: 'COMPLETED', total: 1, completed: undefined as unknown as number, options, configs: [], results: [], games: [] } })) })
  const store = useLabStore(); await store.load()
  expect(store.batch).toBeNull(); expect(store.progress).toBe(0); expect(store.completedGames).toBe(0); expect(store.totalGames).toBe(0)
  expect(store.error).toContain('批次进度数据无效')
})

test('真实模式：完整批次显示真实进度、局数与逐局 gameId', async () => {
  mode.real = true
  mode.adapter = realAdapter({ catalog: vi.fn(async (): Promise<LabCatalog> => ({
    models: [ruleBaselineModel], configs: [],
    batch: {
      id: 'lb_1', status: 'COMPLETED', total: 1, completed: 1, options, configs: [], results: [],
      games: [{ index: 1, gameId: 'g_lab_1', status: 'FINISHED', winner: '狼人阵营', rounds: 3 }],
    },
  })) })
  const store = useLabStore(); await store.load()
  expect(store.progress).toBe(100); expect(store.completedGames).toBe(1); expect(store.totalGames).toBe(1)
  expect(store.latestGame?.gameId).toBe('g_lab_1')
})

test('真实模式：保存不会把未发送的提示词正文报告成已保存', async () => {
  const adapter = realAdapter()
  mode.real = true; mode.adapter = adapter
  const store = useLabStore(); await store.load(); store.newConfig()
  store.draft!.prompt = 'QA验证：发言前核对当前轮次。'
  expect(await store.save()).toBe(true)
  expect(adapter.save).toHaveBeenCalledWith(expect.objectContaining({ prompt: defaultPrompt }))
  expect(store.draft!.prompt).toBe(defaultPrompt)
  expect(store.feedback).toContain('未发送')
})

test('真实模式：服务端没有模型时也能新建、保存并创建批次', async () => {
  mode.real = true; mode.adapter = realAdapter()
  const store = useLabStore(); await store.load()
  expect(store.models.map(model => model.id)).toEqual(['rule-baseline'])
  expect(store.draft).toBeNull(); expect(store.canCreate).toBe(true)
  store.newConfig()
  expect(store.draft?.modelId).toBe('rule-baseline')
  expect(await store.save()).toBe(true); expect(store.selectedIds).toHaveLength(1)
  expect(await store.start()).toBe(true); expect(store.batch?.id).toBe('lb_1')
})
