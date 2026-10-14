import { request, newIdempotencyKey } from '@/api/apiClient'
import { defaultPrompt, ruleBaselineModel } from './types'
import type {
  AgentConfig,
  AgentDraft,
  BatchOptions,
  ComparisonRow,
  LabAdapter,
  LabBatch,
  LabBatchGame,
  LabCatalog,
  LabGameResult,
  LabGameStatus,
  LabModel,
  LabRole,
} from './types'

/**
 * 真实 AI 实验室适配器（`/lab/*`，api-spec.md §8）。
 *
 * **服务端契约只包含运行时真的会消费的字段**，因此这里要处理一个"演示项多于真实项"的落差：
 * - 只发送 `role / model / temperature / strategyProfile / persona / memoryMode / promptTemplateId`；
 *   演示表单里的 `tools` 开关与自定义 `prompt` 文本没有服务端消费点，**不发**——
 *   发一个后端不读的字段，会让"我关了历史工具"变成一个看起来生效、实际无效的设置；
 * - `strategyProfile` 是服务端的规范取值（AGGRESSIVE / BALANCED / CONSERVATIVE），
 *   页面上的四个中文预设是**展示标签**，映射写在这里并且是双向的，避免两处定义漂移。
 *
 * 对局由服务端自行推进（与真人对局同一条驱动），因此 `refresh` 就是一次真实的进度查询，
 * 而不是本地"演示步进"。
 */
interface WireConfig {
  agentConfigId: string
  name: string
  role: string
  model: string
  temperature: number
  strategyProfile: string
  persona: string
  memoryMode: string
  promptTemplateId: string
}
interface WireModel {
  id: string
  name: string
  description: string
}
interface WireCatalog {
  items: WireConfig[]
  total: number
  batches: WireBatch[]
  latestBatchId: string | null
  models: WireModel[]
}
interface WireAssigned {
  agentConfigId: string
  seatNo: number
  camp: string
}
interface WireGame {
  index: number
  gameId: string | null
  status: string
  winner: string | null
  rounds: number | null
  roleAssignments: Record<string, WireAssigned>
}
interface WireBatch {
  batchId: string
  name: string
  status: string
  /** 可选：配置目录里的 `batches[]` 是 `LabBatch` 记录，`completed()` 是方法、不参与序列化。 */
  completed?: number
  total: number
  maxConcurrentGames: number
  agentConfigIds: string[]
  games: WireGame[]
  statistics: Record<string, unknown>
  error: string | null
}
interface WireComparisonRow {
  agentConfigId: string
  name: string
  model: string | null
  role: string
  games: number
  wins: number
  winRate: number | null
  averageRounds: number | null
  llmCalls: number
  tokens: number
  averageLlmLatencyMs: number | null
  fallbackTurns: number
}

/** 中文预设 → 服务端档位。服务端枚举是规范取值，中文只是展示标签。 */
const profileByStrategy: Record<string, string> = {
  深度伪装: 'AGGRESSIVE',
  证据优先: 'BALANCED',
  信息核对: 'CONSERVATIVE',
  协同投票: 'BALANCED',
}
const strategyByProfile: Record<string, string> = {
  AGGRESSIVE: '深度伪装',
  BALANCED: '证据优先',
  CONSERVATIVE: '信息核对',
}
const avatars = ['fox', 'wolf', 'owl', 'blue'] as const

function avatarFor(id: string) {
  let hash = 0
  for (const char of id) hash = (hash * 31 + char.charCodeAt(0)) % 997
  return avatars[hash % avatars.length]!
}
function toModel(raw: WireModel): LabModel {
  return { id: raw.id, name: raw.name, avatar: avatarFor(raw.id), description: raw.description }
}
/** 目录里的模型清单：服务端一个模型都没配时给出显式的规则机器人选项。 */
function modelsOf(raw: WireModel[]): LabModel[] {
  const models = raw.filter(model => typeof model?.id === 'string' && model.id.trim().length > 0).map(toModel)
  return models.length ? models : [ruleBaselineModel]
}
function toConfig(raw: WireConfig): AgentConfig {
  return {
    id: raw.agentConfigId,
    name: raw.name,
    role: raw.role as LabRole,
    personality: raw.persona || '理性分析',
    strategy: strategyByProfile[raw.strategyProfile] ?? '证据优先',
    modelId: raw.model,
    temperature: raw.temperature,
    promptMode: raw.promptTemplateId === 'default' ? 'default' : 'custom',
    prompt: defaultPrompt,
    memory: (raw.memoryMode === 'off' || raw.memoryMode === 'long' ? raw.memoryMode : 'short'),
    tools: { analysis: true, history: true, summary: true },
  }
}
function toBody(draft: AgentDraft) {
  return {
    agentConfigId: draft.id,
    name: draft.name.trim(),
    role: draft.role,
    model: draft.modelId,
    temperature: draft.temperature,
    strategyProfile: profileByStrategy[draft.strategy] ?? 'BALANCED',
    persona: draft.personality,
    memoryMode: draft.memory,
    promptTemplateId: draft.promptMode === 'default' ? 'default' : 'custom',
  }
}
function toGame(raw: WireGame): LabGameResult {
  const outcomes = Object.values(raw.roleAssignments).map(assigned => {
    // `outcomes[].winner` 表示"这份配置这一局站在哪个阵营"，与演示适配器的口径一致：
    // 它回答"我扮演的是谁"，而不是"我赢没赢"——胜负由服务端给出，客户端不重算。
    const camp = assigned.camp === 'WOLF' ? '狼人阵营' : '村民阵营'
    return { configId: assigned.agentConfigId, winner: camp as '狼人阵营' | '村民阵营' }
  })
  return { index: raw.index + 1, seed: raw.index + 1, outcomes, gameId: raw.gameId }
}
const gameStatuses: LabGameStatus[] = ['PENDING', 'RUNNING', 'FINISHED', 'ABORTED', 'FAILED']
function toGameRef(raw: WireGame): LabBatchGame {
  const winner = raw.winner === 'WOLF' ? '狼人阵营' : raw.winner === 'VILLAGE' ? '村民阵营' : null
  return {
    index: raw.index + 1,
    gameId: raw.gameId,
    // 未知状态（服务端加了新取值）按"待开局"显示，而不是把原始枚举漏到界面上
    status: gameStatuses.find(status => status === raw.status) ?? 'PENDING',
    winner,
    rounds: typeof raw.rounds === 'number' ? raw.rounds : null,
  }
}
/**
 * 进度字段必须可信：`completed` / `total` 是整数且 `0 <= completed <= total`。
 *
 * <p>配置目录里的批次条目没有 `completed`（它是记录上的方法），把它当完整响应用会算出
 * `NaN`；缺字段变成用户可见的百分比或 `undefined 局`，用户无法判断批次到底跑没跑。
 */
function progressOf(raw: WireBatch): { completed: number; total: number } | null {
  const { completed, total } = raw
  if (typeof completed !== 'number' || typeof total !== 'number') return null
  if (!Number.isInteger(completed) || !Number.isInteger(total) || completed < 0 || total < 0 || completed > total) return null
  return { completed, total }
}
function toBatch(raw: WireBatch, options: BatchOptions, configs: AgentConfig[]): LabBatch {
  const progress = progressOf(raw)
  if (!progress) {
    throw new Error('批次进度响应缺少完整的 completed/total，已拒绝这次结果。')
  }
  return {
    id: raw.batchId,
    status: raw.status === 'COMPLETED' ? 'COMPLETED' : 'RUNNING',
    total: progress.total,
    completed: progress.completed,
    options: { ...options, total: progress.total, concurrency: raw.maxConcurrentGames },
    configs,
    results: raw.games.filter(game => game.status === 'FINISHED').map(toGame),
    games: raw.games.map(toGameRef),
  }
}
function toRow(raw: WireComparisonRow): ComparisonRow {
  return {
    configId: raw.agentConfigId,
    name: raw.name,
    modelId: raw.model ?? '',
    role: raw.role as LabRole,
    games: raw.games,
    wins: raw.wins,
    winRate: raw.winRate === null ? null : Math.round(raw.winRate * 1000) / 10,
    // 发言质量与稳定性没有服务端口径：留空显示"暂无数据"，而不是给一个编出来的分数。
    speechQuality: null,
    latency: raw.averageLlmLatencyMs === null ? null : Math.round(raw.averageLlmLatencyMs / 100) / 10,
    stability: null,
  }
}

export function createRealLabAdapter(): LabAdapter {
  /** 最近一次批次的选项与所选配置：进度响应里没有它们，而界面渲染需要。 */
  let current: { id: string; options: BatchOptions; configs: AgentConfig[] } | null = null

  function assertBatch(id: string): void {
    if (!current || current.id !== id) throw new Error('批次不属于当前会话，请重新加载实验室。')
  }

  async function catalog(): Promise<LabCatalog> {
    const raw = await request<WireCatalog>('/api/v1/lab/agent-configs?page=1&pageSize=100')
    const configs = raw.items.map(toConfig)
    const models = modelsOf(raw.models)
    current = null
    const latestId = typeof raw.latestBatchId === 'string' && raw.latestBatchId.trim() ? raw.latestBatchId : null
    if (!latestId) return { models, configs, batch: null }
    // 目录里的批次条目是 `LabBatch` 记录，缺 `completed`——直接拿它当进度响应会算出 NaN%。
    // 因此用 `latestBatchId` 再取一次批次详情，和"刷新进度"走同一个接口。
    const detail = await request<WireBatch>(`/api/v1/lab/batches/${encodeURIComponent(latestId)}`)
    if (!progressOf(detail)) {
      // 详情也不完整时就当"暂无批次"：宁可没有进度，也不能把 NaN 或 undefined 画到界面上。
      return { models, configs, batch: null }
    }
    const selected = configs.filter(config => detail.agentConfigIds.includes(config.id))
    current = {
      id: detail.batchId,
      options: { total: detail.total, seedStart: 1, seedEnd: detail.total, mode: 'standard', speed: 1, concurrency: detail.maxConcurrentGames },
      configs: selected,
    }
    return { models, configs, batch: toBatch(detail, current.options, selected) }
  }

  async function save(draft: AgentDraft): Promise<AgentConfig> {
    const saved = await request<{ agentConfigId: string }>('/api/v1/lab/agent-configs', {
      method: 'POST',
      body: toBody(draft),
      idempotencyKey: newIdempotencyKey('lab-config'),
    })
    // 正文（`prompt`）不在契约里，服务端不会存它：返回默认模板，
    // 界面才不会把一段服务端从未收到的文字显示成"已保存"。
    return { ...draft, id: saved.agentConfigId, name: draft.name.trim(), prompt: defaultPrompt }
  }

  async function refresh(batchId: string): Promise<LabBatch> {
    assertBatch(batchId)
    const raw = await request<WireBatch>(`/api/v1/lab/batches/${encodeURIComponent(batchId)}`)
    return toBatch(raw, current!.options, current!.configs)
  }

  return {
    async catalog() {
      return catalog()
    },
    save,
    async start(configIds, options) {
      const selected = [...new Set(configIds)]
      const raw = await request<{ batchId: string; total: number; maxConcurrentGames: number }>(
        '/api/v1/lab/batches',
        {
          method: 'POST',
          body: {
            name: `批次 ${new Date().toLocaleString('zh-CN')}`,
            gameCount: options.total,
            agentConfigIds: selected,
            maxConcurrentGames: options.concurrency,
          },
          idempotencyKey: newIdempotencyKey('lab-batch'),
        },
      )
      const catalogNow = await catalog()
      current = {
        id: raw.batchId,
        options: { ...options, total: raw.total, concurrency: raw.maxConcurrentGames },
        configs: catalogNow.configs.filter(config => selected.includes(config.id)),
      }
      return toBatch(
        {
          batchId: raw.batchId, name: '', status: 'RUNNING', completed: 0,
          total: raw.total, maxConcurrentGames: raw.maxConcurrentGames,
          agentConfigIds: selected, games: [], statistics: {}, error: null,
        },
        current.options,
        current.configs,
      )
    },
    refresh,
    async comparisons(batchId) {
      const raw = await request<{ rows: WireComparisonRow[] }>('/api/v1/lab/comparisons')
      const rows = raw.rows.map(toRow)
      // 批次页只看本批选中的配置；没有批次时给全部——与演示适配器的接口形状一致。
      if (!batchId) return rows
      assertBatch(batchId)
      const selected = new Set(current!.configs.map(config => config.id))
      return rows.filter(row => selected.has(row.configId))
    },
    /**
     * 真实模式下没有"清空本地结果"这件事：批次与配置都在服务端。
     *
     * <p>把它做成静默 no-op 是不诚实的——用户会以为删掉了。因此它抛出一个明确的错误，
     * 由界面把这个入口隐藏掉（`LabPage` 在真实模式下不显示该按钮）。
     */
    async clearLocalResults() {
      throw new Error('真实模式下的批次记录保存在服务端，无法在本地清空。')
    },
  }
}

export const realLabAdapter = createRealLabAdapter()
