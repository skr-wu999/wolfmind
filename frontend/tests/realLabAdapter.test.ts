import { afterEach, describe, expect, test, vi } from 'vitest'
import { createRealLabAdapter } from '../src/features/lab/realLabAdapter'
import { defaultPrompt, validateDraft } from '../src/features/lab/types'
import type { AgentDraft } from '../src/features/lab/types'

/**
 * 真实 AI 实验室适配器的映射（`/lab/*`）。
 *
 * ## 它守什么
 *
 * 1. **只发送服务端真的会消费的字段**：工具开关与自定义提示词没有运行时消费点，
 *    发过去会让"我关了历史工具"变成一个看起来生效、实际无效的设置；
 * 2. **策略档位的双向映射只有一处**：中文预设是展示标签，服务端枚举才是规范取值；
 * 3. **没有口径的指标留空**：发言质量与稳定性服务端不计算，界面显示"暂无数据"；
 * 4. **批次由服务端推进**：`refresh` 是一次真实查询，不是本地步进；
 * 5. **真实模式下没有"清空本地记录"**：抛明确错误而不是静默 no-op。
 */

interface Call { url: string; method: string; body?: Record<string, unknown> }

function stubFetch(routes: Record<string, unknown>, calls: Call[] = []) {
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    calls.push({
      url: String(url),
      method: init?.method ?? 'GET',
      body: init?.body ? JSON.parse(String(init.body)) as Record<string, unknown> : undefined,
    })
    const key = Object.keys(routes).find(path => String(url).includes(path))
    if (!key) throw new Error(`没有为 ${url} 准备响应`)
    const body = routes[key]
    return {
      ok: true, status: 200,
      headers: new Headers({ 'content-type': 'application/json' }),
      text: async () => JSON.stringify(body), json: async () => body,
    } as unknown as Response
  }))
}

const catalog = {
  items: [{
    agentConfigId: 'ac_1', name: '狼人A', role: 'WEREWOLF', model: 'deepseek-flash',
    temperature: 0.7, strategyProfile: 'AGGRESSIVE', persona: '激进强势',
    memoryMode: 'long', promptTemplateId: 'default',
  }],
  total: 1,
  models: [{ id: 'deepseek-flash', name: 'deepseek-flash（服务端默认）', description: '由环境变量配置。' }],
  batches: [],
  latestBatchId: null,
}
const batchWire = {
  batchId: 'lb_1', name: '批次', status: 'RUNNING', completed: 1, total: 2, maxConcurrentGames: 2,
  agentConfigIds: ['ac_1'], error: null,
  statistics: { finished: 1, wolfWins: 1, villageWins: 0 },
  games: [
    {
      index: 0, gameId: 'g_lab_1', status: 'FINISHED', winner: 'WOLF', rounds: 3,
      roleAssignments: { WEREWOLF: { agentConfigId: 'ac_1', seatNo: 4, camp: 'WOLF' } },
    },
    { index: 1, gameId: 'g_lab_2', status: 'RUNNING', winner: null, rounds: 1, roleAssignments: {} },
  ],
}
const comparisons = {
  rows: [{
    agentConfigId: 'ac_1', name: '狼人A', model: 'deepseek-flash', role: 'WEREWOLF',
    games: 4, wins: 3, winRate: 0.75, averageRounds: 3.2, llmCalls: 20, tokens: 40000,
    averageLlmLatencyMs: 4200, fallbackTurns: 1,
  }],
}
/** 配置目录里的批次条目：`completed()` 是记录上的方法，不参与序列化，因此这里没有 completed。 */
const catalogBatchEntry = {
  batchId: 'lb_1', name: '批次', status: 'COMPLETED', total: 2, maxConcurrentGames: 2,
  agentConfigIds: ['ac_1'], games: batchWire.games, statistics: {}, error: null,
}
const catalogWithBatch = { ...catalog, batches: [catalogBatchEntry], latestBatchId: 'lb_1' }
const batchDetail = { ...batchWire, status: 'COMPLETED', completed: 2, total: 2 }
const draft: AgentDraft = {
  id: undefined, name: '新配置', role: 'SEER', personality: '理性分析', strategy: '信息核对',
  modelId: 'deepseek-flash', temperature: 0.4, promptMode: 'default', prompt: defaultPrompt,
  memory: 'short', tools: { analysis: true, history: true, summary: true },
}

afterEach(() => vi.unstubAllGlobals())

describe('真实 AI 实验室适配器', () => {
  test('目录：配置与模型来自服务端，策略档位双向映射回中文预设', async () => {
    stubFetch({ '/lab/agent-configs': catalog })
    const adapter = createRealLabAdapter()
    const result = await adapter.catalog()

    expect(result.models.map(model => model.id)).toEqual(['deepseek-flash'])
    expect(result.models[0]!.avatar).toBeTruthy()
    const config = result.configs[0]!
    expect(config.id).toBe('ac_1')
    expect(config.strategy).toBe('深度伪装')
    expect(config.memory).toBe('long')
    expect(config.modelId).toBe('deepseek-flash')
  })

  test('保存：只发送服务端消费的字段，工具开关与自定义提示词不发', async () => {
    const calls: Call[] = []
    stubFetch({ '/lab/agent-configs': catalog }, calls)
    const adapter = createRealLabAdapter()
    const saved = await adapter.save({
      id: undefined, name: '  新配置  ', role: 'SEER', personality: '理性分析', strategy: '信息核对',
      modelId: 'deepseek-flash', temperature: 0.4, promptMode: 'custom', prompt: '自定义指令',
      memory: 'short', tools: { analysis: false, history: false, summary: false },
    })

    const post = calls.find(call => call.method === 'POST')!
    expect(post.body).toEqual({
      agentConfigId: undefined,
      name: '新配置',
      role: 'SEER',
      model: 'deepseek-flash',
      temperature: 0.4,
      strategyProfile: 'CONSERVATIVE',
      persona: '理性分析',
      memoryMode: 'short',
      promptTemplateId: 'custom',
    })
    expect(post.body).not.toHaveProperty('tools')
    expect(post.body).not.toHaveProperty('prompt')
    // 正文没有被发送，就不能作为"已保存"的结果回到界面；模板标识仍如实保留。
    expect(saved.prompt).toBe(defaultPrompt)
    expect(saved.promptMode).toBe('custom')
  })

  test('目录：批次条目缺 completed 时按 latestBatchId 取详情，进度与逐局 id 都来自服务端', async () => {
    const calls: Call[] = []
    stubFetch({ '/lab/agent-configs': catalogWithBatch, '/lab/batches': batchDetail }, calls)
    const adapter = createRealLabAdapter()
    const result = await adapter.catalog()

    expect(calls).toHaveLength(2)
    expect(calls[0]!.method).toBe('GET')
    expect(calls[0]!.url).toContain('/api/v1/lab/agent-configs?page=1&pageSize=100')
    expect(calls[1]!.url).toContain('/api/v1/lab/batches/lb_1')
    expect(result.batch!.id).toBe('lb_1')
    expect(result.batch!.completed).toBe(2)
    expect(result.batch!.total).toBe(2)
    // 逐局列表带真实 gameId 与状态：实验室的观战/回放/分析都靠它，而不是写死的 demo。
    expect(result.batch!.games.map(game => game.gameId)).toEqual(['g_lab_1', 'g_lab_2'])
    expect(result.batch!.games.map(game => game.status)).toEqual(['FINISHED', 'RUNNING'])
    expect(result.batch!.games[0]!.winner).toBe('狼人阵营')
    expect(result.batch!.games[0]!.rounds).toBe(3)
  })

  test('目录：没有批次时返回 null，且不去请求批次详情', async () => {
    const calls: Call[] = []
    stubFetch({ '/lab/agent-configs': catalog }, calls)
    const adapter = createRealLabAdapter()
    expect((await adapter.catalog()).batch).toBeNull()
    expect(calls).toHaveLength(1)
  })

  test('目录：批次详情里 completed 缺失、非整数或超出 total 时忽略该批次，而不是让 NaN 进入界面', async () => {
    // 目录条目本身就是"没有 completed"的形状；详情接口如果也是这个形状，界面只能当作暂无批次。
    const detailWithoutCompleted = {
      batchId: 'lb_1', name: '批次', status: 'COMPLETED', total: 2, maxConcurrentGames: 2,
      agentConfigIds: ['ac_1'], games: batchWire.games, statistics: {}, error: null,
    }
    for (const detail of [
      detailWithoutCompleted,
      { ...batchDetail, completed: '1' },
      { ...batchDetail, completed: Number.NaN },
      { ...batchDetail, completed: 3 },
      { ...batchDetail, completed: -1 },
    ]) {
      stubFetch({ '/lab/agent-configs': catalogWithBatch, '/lab/batches': detail })
      const adapter = createRealLabAdapter()
      const result = await adapter.catalog()
      expect(result.batch).toBeNull()
    }
  })

  test('目录：服务端没有模型时给出可保存的规则机器人选项', async () => {
    stubFetch({ '/lab/agent-configs': { ...catalog, models: [] } })
    const adapter = createRealLabAdapter()
    const result = await adapter.catalog()

    expect(result.models.map(model => model.id)).toEqual(['rule-baseline'])
    expect(result.models[0]!.name).toContain('规则机器人')
    // 只有一个选项还不够：它必须能通过草稿校验，否则"无模型也能起步"只是文案。
    expect(validateDraft({ ...draft, modelId: result.models[0]!.id }, result.models.map(model => model.id))).toEqual({})
  })

  test('批次：进度来自服务端；结果按角色记录阵营，未完成的对局不产生结果行', async () => {
    const calls: Call[] = []
    stubFetch({ '/lab/batches': batchWire, '/lab/agent-configs': catalog }, calls)
    const adapter = createRealLabAdapter()
    await adapter.catalog()
    const started = await adapter.start(['ac_1'], { total: 2, seedStart: 1, seedEnd: 2, mode: 'standard', speed: 1, concurrency: 2 })
    expect(started.status).toBe('RUNNING')
    expect(started.completed).toBe(0)
    expect(started.total).toBe(2)

    const batch = await adapter.refresh(started.id)
    expect(batch.completed).toBe(1)
    expect(batch.results).toHaveLength(1)
    expect(batch.results[0]!.outcomes[0]!.winner).toBe('狼人阵营')
    expect(batch.results[0]!.gameId).toBe('g_lab_1')

    const start = calls.find(call => call.method === 'POST' && call.url.includes('/lab/batches'))!
    expect(start.body!.gameCount).toBe(2)
    expect(start.body!.agentConfigIds).toEqual(['ac_1'])
    // 服务端并发上限是配置项，不是"演示速度"：请求体里发的是并发而不是速度
    expect(start.body!.maxConcurrentGames).toBe(2)
    expect(start.body).not.toHaveProperty('speed')
    expect(start.body).not.toHaveProperty('seedStart')
  })

  test('比对：胜率换算成百分比；没有服务端口径的指标留 null', async () => {
    stubFetch({ '/lab/comparisons': comparisons })
    const adapter = createRealLabAdapter()
    const rows = await adapter.comparisons(null)
    expect(rows[0]!.winRate).toBe(75)
    expect(rows[0]!.latency).toBe(4.2)
    expect(rows[0]!.games).toBe(4)
    expect(rows[0]!.speechQuality).toBeNull()
    expect(rows[0]!.stability).toBeNull()
  })

  test('刷新未知批次被拒绝，而不是返回上一个批次的数据', async () => {
    stubFetch({ '/lab/batches': batchWire, '/lab/agent-configs': catalog })
    const adapter = createRealLabAdapter()
    await expect(adapter.refresh('lb_unknown')).rejects.toThrow('批次不属于当前会话')
  })

  test('清空本地记录在真实模式下抛错，而不是静默成功', async () => {
    stubFetch({ '/lab/agent-configs': catalog })
    const adapter = createRealLabAdapter()
    await expect(adapter.clearLocalResults()).rejects.toThrow('服务端')
  })
})
