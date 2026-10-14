import { defaultPrompt, validateBatch, validateDraft } from './types'
import type { AgentConfig, ComparisonRow, LabAdapter, LabBatch, LabModel } from './types'

export const labModels: LabModel[] = [
  { id: 'demo-fox', name: '白狐 · 推理型', avatar: 'fox', description: '手工样例，用于体验信息核对与清晰表达。' },
  { id: 'demo-raven', name: '夜鸦 · 均衡型', avatar: 'wolf', description: '手工样例，用于体验稳健的发言与选择。' },
  { id: 'demo-owl', name: '星尘 · 轻量型', avatar: 'owl', description: '手工样例，用于体验简洁的决策摘要。' },
  { id: 'demo-bot', name: '规则 Bot · 对照组', avatar: 'blue', description: '预置对照数据；本页不执行游戏规则。' },
]
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T
export function defaultConfigs(): AgentConfig[] {
  return labModels.map((model, index) => ({ id: `demo-config-${index + 1}`, name: model.name.split(' · ')[0]!, role: 'WEREWOLF', personality: '理性分析', strategy: '深度伪装', modelId: model.id, temperature: 0.7, promptMode: 'default', prompt: defaultPrompt, memory: 'short', tools: { analysis: true, history: true, summary: true } }))
}
let configs = defaultConfigs(), latest: LabBatch | null = null, serial = 0, showReference = true
const storageKey = 'wolfmind.lab.configs.v1'
function persist() { try { sessionStorage.setItem(storageKey, JSON.stringify(configs)) } catch { /* Session storage can be unavailable; retain this tab's in-memory draft. */ } }
function loadSaved() {
  try {
    const raw = sessionStorage.getItem(storageKey)
    if (!raw) return
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed) || parsed.length > 30) return
    const valid = parsed.every(item => item && typeof item === 'object' && typeof item.id === 'string' && typeof item.name === 'string' && typeof item.prompt === 'string' && !Object.keys(validateDraft(item, labModels.map(m => m.id))).length)
    if (valid && new Set(parsed.map(item => item.id)).size === parsed.length) configs = clone(parsed)
  } catch { /* Ignore corrupt local drafts rather than replacing the built-in catalog. */ }
}
export function sampleResults(total: number, seedStart: number, selected = defaultConfigs()) {
  return Array.from({ length: total }, (_, index) => ({ index: index + 1, seed: seedStart + index, outcomes: selected.map(config => {
    const model = labModels.findIndex(item => item.id === config.modelId)
    const won = (index + model * 2) % 20 < [14, 11, 10, 9][model]!
    return { configId: config.id, winner: ((config.role === 'WEREWOLF') === won ? '狼人阵营' : '村民阵营') as '狼人阵营' | '村民阵营' }
  }), gameId: null }))
}
function rows(batch: LabBatch): ComparisonRow[] {
  const quality = [8.7, 8.3, 7.9, null], latency = [2.1, 1.8, 1.3, 0.2], stability = [8.5, 8.1, 7.8, 9.0]
  return batch.configs.map(config => {
    const model = labModels.findIndex(m => m.id === config.modelId)
    const camp = config.role === 'WEREWOLF' ? '狼人阵营' : '村民阵营'
    const wins = batch.results.filter(result => result.outcomes.some(outcome => outcome.configId === config.id && outcome.winner === camp)).length
    return { configId: config.id, name: config.name, modelId: config.modelId, role: config.role, games: batch.completed, wins, winRate: batch.completed ? Math.round(wins / batch.completed * 1000) / 10 : null, speechQuality: batch.completed ? quality[model]! : null, latency: batch.completed ? latency[model]! : null, stability: batch.completed ? stability[model]! : null }
  })
}
export const labAdapter: LabAdapter = {
  async catalog() { loadSaved(); return clone({ models: labModels, configs, batch: latest }) },
  async save(draft) {
    const errors = validateDraft(draft, labModels.map(m => m.id))
    if (Object.keys(errors).length) throw new Error(Object.values(errors)[0])
    let newId = draft.id
    if (!newId) { do { newId = `demo-custom-${++serial}` } while (configs.some(item => item.id === newId)) }
    const config: AgentConfig = { ...clone(draft), name: draft.name.trim(), id: newId }
    const index = configs.findIndex(item => item.id === config.id)
    if (index >= 0) configs[index] = config
    else { if (configs.length >= 30) throw new Error('本地示例最多保存 30 份配置。'); configs.push(config) }
    persist(); return clone(config)
  },
  async start(configIds, options) {
    if (latest?.status === 'RUNNING') throw new Error('当前批次尚未完成，请先刷新进度。')
    const errors = validateBatch(options, configIds)
    if (Object.keys(errors).length) throw new Error(Object.values(errors)[0])
    const selected = [...new Set(configIds)].map(id => configs.find(item => item.id === id))
    if (selected.some(item => !item)) throw new Error('存在已失效的 Agent 配置，请重新加载。')
    latest = clone({ id: `demo-batch-${++serial}`, status: 'RUNNING', total: options.total, completed: 0, options, configs: selected as AgentConfig[], results: [], games: [] })
    showReference = false; return clone(latest)
  },
  async refresh(batchId) {
    if (!latest || latest.id !== batchId) throw new Error('本地批次不存在，请重新开始测试。')
    const step = Math.max(1, Math.round(4 * latest.options.speed))
    latest.completed = Math.min(latest.total, latest.completed + step)
    latest.results = sampleResults(latest.completed, latest.options.seedStart, latest.configs)
    if (latest.completed === latest.total) latest.status = 'COMPLETED'
    return clone(latest)
  },
  async comparisons(batchId) {
    if (batchId) {
      if (!latest || latest.id !== batchId) throw new Error('该批次比较记录不可用。')
      return rows(clone(latest))
    }
    if (!showReference) return []
    // 演示批次没有真实对局 id，逐局导航只属于真实模式，因此 `games` 为空。
    return rows({ id: 'demo-reference', status: 'COMPLETED', total: 20, completed: 20, configs: defaultConfigs(), options: { total: 20, seedStart: 1001, seedEnd: 1020, mode: 'standard', speed: 1, concurrency: 2 }, results: sampleResults(20, 1001), games: [] })
  },
  async clearLocalResults() { if (latest?.status === 'RUNNING') throw new Error('正在演示的批次不能清空。'); latest = null; showReference = false },
}
export function resetLabDemo() { configs = defaultConfigs(); latest = null; serial = 0; showReference = true; try { sessionStorage.removeItem(storageKey) } catch { /* Test and non-browser environments may not expose storage. */ } }
