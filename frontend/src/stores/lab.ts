import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { labAdapter as selectedLabAdapter, usingRealBackend } from '@/api/adapters'
import { defaultPrompt, LabAccessError, validateBatch, validateDraft } from '@/features/lab/types'
import type { AgentConfig, AgentDraft, BatchOptions, ComparisonMetric, ComparisonRow, LabBatch, LabBatchGame, LabModel, LabRole } from '@/features/lab/types'

/**
 * 真实模式：批次与配置都在服务端，由服务端自己推进对局。
 *
 * <p>文案必须跟着变——真实模式下写"演示步进"会让用户以为数字是本地算出来的，
 * 而那些数字来自真实跑完的对局。
 *
 * <p>每次调用都问一次注册表，而不是在模块加载时定死：适配器的选择点是注册表，
 * 固定成模块常量会让"当前是哪种模式"与注册表的实际返回值可能不一致。
 */
const liveLab = () => usingRealBackend()

const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T
/** `completed` / `total` 可信才允许进入界面：非整数会渲染成 NaN% 与「undefined 局」。 */
function countsValid(batch: LabBatch): boolean {
  return Number.isInteger(batch.completed) && Number.isInteger(batch.total) && batch.completed >= 0 && batch.completed <= batch.total
}
export const useLabStore = defineStore('lab', () => {
  const models = ref<LabModel[]>([]), configs = ref<AgentConfig[]>([]), draft = ref<AgentDraft | null>(null), selectedIds = ref<string[]>([])
  const batch = ref<LabBatch | null>(null), comparisons = ref<ComparisonRow[]>([])
  const options = ref<BatchOptions>({ total: 20, seedStart: 1001, seedEnd: 1020, mode: 'standard', speed: 1, concurrency: 2 })
  const loading = ref(false), saving = ref(false), submitting = ref(false), refreshing = ref(false), comparing = ref(false), clearing = ref(false)
  const error = ref(''), comparisonError = ref(''), restricted = ref(false), feedback = ref(''), fields = ref<Record<string, string>>({})
  const metric = ref<ComparisonMetric>('winRate'), roleFilter = ref<LabRole | 'all'>('all')
  let generation = 0, comparisonRequest = 0
  const running = computed(() => batch.value?.status === 'RUNNING')
  /** 进度只在计数可信时才有值；`null` 表示"暂无进度"，界面据此显示占位而不是 NaN。 */
  const counts = computed(() => batch.value && countsValid(batch.value) ? { completed: batch.value.completed, total: batch.value.total } : null)
  const completedGames = computed(() => counts.value?.completed ?? 0)
  const totalGames = computed(() => counts.value?.total ?? 0)
  const progress = computed(() => counts.value && counts.value.total > 0 ? Math.min(100, Math.round(counts.value.completed / counts.value.total * 100)) : 0)
  /** 最近一局有 gameId 的对局：实验室的导航只指向真实对局，不指向演示样例。 */
  const latestGame = computed<LabBatchGame | null>(() => [...(batch.value?.games ?? [])].reverse().find(game => !!game.gameId) ?? null)
  /** 有模型（含无模型时适配器给的规则机器人）或已有配置时才能新建草稿。 */
  const canCreate = computed(() => models.value.length > 0 || configs.value.length > 0)
  const rows = computed(() => comparisons.value.filter(row => roleFilter.value === 'all' || row.role === roleFilter.value).sort((a, b) => {
    const x = a[metric.value], y = b[metric.value]
    return x == null ? y == null ? 0 : 1 : y == null ? -1 : metric.value === 'latency' ? x - y : y - x
  }))
  const best = computed(() => Object.fromEntries((['winRate', 'speechQuality', 'latency', 'stability'] as ComparisonMetric[]).map(key => [key, [...rows.value].filter(row => row[key] != null).sort((a,b) => key === 'latency' ? a[key]! - b[key]! : b[key]! - a[key]!)[0] || null])) as Record<ComparisonMetric, ComparisonRow | null>)
  function failure(cause: unknown) { if (cause instanceof LabAccessError) restricted.value = true; return cause instanceof Error ? cause.message : '实验室暂时不可用，请重试。' }
  async function compare() {
    if (restricted.value || clearing.value) return
    const request = ++comparisonRequest, scope = generation, id = batch.value?.id || null
    comparing.value = true; comparisonError.value = ''
    try {
      const result = await selectedLabAdapter().comparisons(id)
      if (scope === generation && request === comparisonRequest && id === (batch.value?.id || null)) comparisons.value = result
    } catch (cause) { if (scope === generation && request === comparisonRequest) comparisonError.value = failure(cause) }
    finally { if (scope === generation && request === comparisonRequest) comparing.value = false }
  }
  async function load() {
    const request = ++generation; ++comparisonRequest
    loading.value = true; error.value = ''; comparisonError.value = ''; restricted.value = false; fields.value = {}; feedback.value = ''
    models.value = []; configs.value = []; draft.value = null; batch.value = null; comparisons.value = []; selectedIds.value = []
    saving.value = false; submitting.value = false; refreshing.value = false; comparing.value = false; clearing.value = false
    try {
      const catalog = await selectedLabAdapter().catalog()
      if (request !== generation) return
      models.value = catalog.models; configs.value = catalog.configs
      // 计数无效的批次记录直接丢弃：留着它会让进度、局数都变成 NaN 或 undefined。
      const usable = catalog.batch && countsValid(catalog.batch) ? catalog.batch : null
      if (catalog.batch && !usable) error.value = '批次进度数据无效，已忽略这条记录。'
      batch.value = usable
      selectedIds.value = catalog.configs.map(config => config.id)
      draft.value = catalog.configs[0] ? clone(catalog.configs[0]) : null
      await compare()
    } catch (cause) { if (request === generation) error.value = failure(cause) }
    finally { if (request === generation) loading.value = false }
  }
  function selectConfig(id: string) { const config = configs.value.find(item => item.id === id); if (config) { draft.value = clone(config); fields.value = {}; feedback.value = '' } }
  function newConfig() {
    const first = configs.value[0]
    if (first) draft.value = { ...clone(first), id: undefined, name: '新 Agent' }
    else draft.value = { name: '新 Agent', role: 'WEREWOLF', personality: '理性分析', strategy: '深度伪装', modelId: models.value[0]?.id ?? '', temperature: 0.7, promptMode: 'default', prompt: defaultPrompt, memory: 'short', tools: { analysis: true, history: true, summary: true } }
    fields.value = {}
  }
  async function save() {
    if (!draft.value || saving.value || restricted.value) return false
    /*
     * 真实模式：提示词正文不在服务端契约里（api-spec.md §8.1），被保存的比对维度是 `promptTemplateId`。
     * 因此提交前把正文归位到默认模板——留着它会让"已保存"覆盖一段服务端从未收到的文字。
     */
    const unsentPrompt = liveLab() && draft.value.prompt !== defaultPrompt
    if (unsentPrompt) draft.value.prompt = defaultPrompt
    fields.value = validateDraft(draft.value, models.value.map(model => model.id))
    if (Object.keys(fields.value).length) return false
    const request = generation, submitted = clone(draft.value)
    saving.value = true; error.value = ''; feedback.value = ''
    try {
      const config = await selectedLabAdapter().save(submitted)
      if (request !== generation) return false
      const index = configs.value.findIndex(item => item.id === config.id)
      if (index < 0) { configs.value.push(config); selectedIds.value.push(config.id) } else configs.value[index] = config
      if (JSON.stringify(draft.value) === JSON.stringify(submitted)) draft.value = clone(config)
      feedback.value = liveLab()
        ? `已保存“${config.name}”；提示词模板标识仅作为比对维度${unsentPrompt ? '，自定义正文未发送' : ''}。`
        : `已保存“${config.name}”到本地会话。`; return true
    } catch (cause) { if (request === generation) error.value = failure(cause); return false }
    finally { if (request === generation) saving.value = false }
  }
  async function start() {
    if (running.value || submitting.value || restricted.value || clearing.value) return false
    fields.value = validateBatch(options.value, selectedIds.value)
    if (Object.keys(fields.value).length) return false
    const request = generation; submitting.value = true; error.value = ''; feedback.value = ''
    try {
      const result = await selectedLabAdapter().start([...selectedIds.value], clone(options.value))
      if (request !== generation) return false
      if (!countsValid(result)) { error.value = '批次进度数据无效，已忽略这次结果。'; return false }
      batch.value = result; comparisons.value = []
      feedback.value = liveLab()
        ? '批次已创建，服务端正在用与真人对局相同的规则推进；点击“刷新进度”查看结果。'
        : '本地批次已创建，点击“刷新进度”推进预置样例。'
      await compare(); return true
    } catch (cause) { if (request === generation) error.value = failure(cause); return false }
    finally { if (request === generation) submitting.value = false }
  }
  async function refresh() {
    if (!running.value || !batch.value || refreshing.value || restricted.value || clearing.value) return false
    const request = generation, id = batch.value.id; refreshing.value = true; error.value = ''
    try {
      const result = await selectedLabAdapter().refresh(id)
      if (request !== generation || batch.value?.id !== id) return false
      if (result.id !== id || result.total !== batch.value.total || !Number.isInteger(result.completed) || result.completed < batch.value.completed || result.completed > result.total || (result.status === 'COMPLETED') !== (result.completed === result.total)) throw new Error('批次进度无效，请重新查询。')
      batch.value = result
      feedback.value = result.status === 'COMPLETED'
        ? (liveLab() ? '批次已完成，可查看比对结果。' : '本地样例已完成，可查看报告和导出结果。')
        : (liveLab() ? `已完成 ${result.completed} / ${result.total} 局。` : `已展示 ${result.completed} / ${result.total} 局样例。`)
      await compare(); return true
    } catch (cause) { if (request === generation) error.value = failure(cause); return false }
    finally { if (request === generation) refreshing.value = false }
  }
  async function clear() {
    if (running.value || clearing.value || submitting.value || refreshing.value || restricted.value) return false
    const request = generation; clearing.value = true
    try { await selectedLabAdapter().clearLocalResults(); if (request !== generation) return false; ++comparisonRequest; comparing.value = false; batch.value = null; comparisons.value = []; feedback.value = '已清空本地实验记录，Agent 配置仍保留。'; return true }
    catch (cause) { if (request === generation) error.value = failure(cause); return false }
    finally { if (request === generation) clearing.value = false }
  }
  function exportData() {
    return {
      source: liveLab()
        ? 'WolfMind 服务端实验室：批次对局由与真人对局相同的规则与驱动完成，胜率来自已结束的对局。'
        : 'WolfMind 手工编写的本地实验样例；不代表真实模型性能。',
      batch: clone(batch.value),
      comparisons: clone(comparisons.value),
      reference: !batch.value,
    }
  }
  function dispose() { ++generation; ++comparisonRequest; loading.value = false; saving.value = false; submitting.value = false; refreshing.value = false; comparing.value = false; clearing.value = false; batch.value = null; comparisons.value = [] }
  return { models, configs, draft, selectedIds, batch, comparisons, options, loading, saving, submitting, refreshing, comparing, clearing, error, comparisonError, restricted, feedback, fields, metric, roleFilter, running, progress, counts, completedGames, totalGames, latestGame, canCreate, rows, best, load, selectConfig, newConfig, save, start, refresh, compare, clear, exportData, dispose }
})
