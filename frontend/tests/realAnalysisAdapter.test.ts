import { afterEach, describe, expect, test, vi } from 'vitest'
import { createRealAnalysisAdapter } from '../src/features/analysis/realAnalysisAdapter'
import { AnalysisAccessError } from '../src/features/analysis/types'

/**
 * 真实 AI 分析适配器的映射（`GET /games/{id}/analysis/*`）。
 *
 * ## 它守什么
 *
 * 这个适配器最容易错的不是解析，而是**补上服务端没有的字段**。服务端刻意不提供
 * 胜率估计、信念变化、存活率与置信度分布——那些属于模型内部状态而不是可观察事实。
 * 一旦前端补上，分析页会显示看起来可信、实际是编造的内容，而分析页上的数字会被当成事实引用。
 * 因此下面有一半断言是"**某个字段没有被填**"。
 *
 * 另一半是**披露分层**：进行中的对局不得给分析，必须抛 `AnalysisAccessError`，
 * 界面据此显示"暂未开放分析"而不是半份数据。
 */

function stubFetch(routes: Record<string, unknown>) {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    const key = Object.keys(routes).find(path => String(url).includes(path))
    if (!key) throw new Error(`没有为 ${url} 准备响应`)
    return {
      ok: true,
      status: 200,
      headers: new Headers({ 'content-type': 'application/json' }),
      text: async () => JSON.stringify(routes[key]),
      json: async () => routes[key],
    } as unknown as Response
  }))
}

const fullSummary = {
  gameId: 'g_1',
  status: 'FINISHED',
  disclosure: 'FULL',
  winner: 'WOLF',
  finishReason: 'WOLVES_OUTNUMBER_VILLAGE',
  rounds: 3,
  version: 150,
  startedAt: 1_730_000_000_000,
  finishedAt: 1_730_000_600_000,
  players: [
    {
      playerId: 'p_1', seatNo: 1, role: 'SEER', camp: 'VILLAGE', controllerType: 'AI', alive: false,
      speechCount: 4, voteCount: 3, abstainCount: 0, voteHitCount: 2, survivedRounds: 1,
      deathRound: 2, decisionTraces: 24, llmCalls: 5, promptTokens: 900, completionTokens: 300,
      toolCalls: 12, fallbackTurns: 1, totalLlmLatencyMs: 8000, maxLlmLatencyMs: 3000,
    },
  ],
  keyEvents: [
    { version: 12, type: 'PLAYER_VOTED', round: 1, occurredAt: 1_730_000_010_000, summary: '1 号投票给 2 号' },
  ],
  truncated: false,
  provenance: '统计来自已归档的对局事件与 AGENT_ 决策轨迹。',
}

const fullAgent = {
  gameId: 'g_1',
  playerId: 'p_1',
  seatNo: 1,
  role: 'SEER',
  camp: 'VILLAGE',
  controllerType: 'AI',
  disclosure: 'FULL',
  note: '轨迹只包含运行时可观察的事实，不包含模型私有推理。',
  traces: [
    {
      id: 'p_1:12:STRATEGY:1000', kind: 'STRATEGY', version: 12, phase: 'DAY_DISCUSS', round: 1,
      occurredAt: 1_730_000_010_000,
      candidates: [
        { actionType: 'SPEAK', targetSeatNo: null, score: 0.7 },
        { actionType: 'VOTE', targetSeatNo: 2, score: 0.55 },
      ],
      chosenAction: null, chosenTargetSeatNo: null, submitted: null, fallbackUsed: null,
      llmCalls: null, repairRounds: null, model: null, provider: null, promptTokens: null,
      completionTokens: null, llmLatencyMs: null, tool: null, toolArgsDigest: null,
      toolDecision: null, denialCode: null, stage: null, reason: null,
    },
    {
      id: 'p_1:13:LLM:2000', kind: 'LLM', version: 13, phase: 'DAY_DISCUSS', round: 1,
      occurredAt: 1_730_000_020_000, candidates: [],
      chosenAction: null, chosenTargetSeatNo: null, submitted: null, fallbackUsed: null,
      llmCalls: null, repairRounds: null, model: 'deepseek-flash', provider: 'deepseek',
      promptTokens: 700, completionTokens: 200, llmLatencyMs: 4200, tool: null, toolArgsDigest: null,
      toolDecision: null, denialCode: null, stage: null, reason: null,
    },
    {
      id: 'p_1:14:FINISHED:3000', kind: 'FINISHED', version: 14, phase: 'DAY_DISCUSS', round: 1,
      occurredAt: 1_730_000_030_000, candidates: [],
      chosenAction: 'VOTE', chosenTargetSeatNo: 2, submitted: true, fallbackUsed: false,
      llmCalls: 1, repairRounds: 0, model: null, provider: null, promptTokens: null,
      completionTokens: null, llmLatencyMs: null, tool: null, toolArgsDigest: null,
      toolDecision: null, denialCode: null, stage: null, reason: null,
    },
  ],
}

const publicSummary = { ...fullSummary, disclosure: 'PUBLIC', players: [{ ...fullSummary.players[0]!, role: null, camp: null, voteHitCount: null }] }

afterEach(() => vi.unstubAllGlobals())

describe('真实 AI 分析适配器', () => {
  test('概览：进行中的对局被拒绝，而不是给出半份数据', async () => {
    stubFetch({ '/analysis/agents/': fullAgent, '/analysis/summary': publicSummary })
    const adapter = createRealAnalysisAdapter()
    await expect(adapter.summary('g_1')).rejects.toBeInstanceOf(AnalysisAccessError)
  })

  test('概览：终局后给出座位、轮数与来源，且不从空数组编出估计曲线', async () => {
    stubFetch({ '/analysis/agents/': fullAgent, '/analysis/summary': fullSummary })
    const adapter = createRealAnalysisAdapter()
    const summary = await adapter.summary('g_1')
    expect(summary.status).toBe('COMPLETED')
    expect(summary.defaultSeat).toBe(1)
    expect(summary.estimates).toEqual([])
    expect(summary.provenance).toContain('版本 150')
    expect(summary.provenance).toContain('已归档')
    expect(summary.initialPosition).toBeGreaterThan(0)
  })

  test('轨迹：候选集、耗时与 token 来自服务端，信念与置信度一律留空', async () => {
    stubFetch({ '/analysis/agents/': fullAgent, '/analysis/summary': fullSummary })
    const adapter = createRealAnalysisAdapter()
    await adapter.summary('g_1')
    const agent = await adapter.agent('g_1', 1)

    expect(agent.traces).toHaveLength(3)
    const strategy = agent.traces[0]!
    expect(strategy.candidates.map(candidate => candidate.label)).toEqual(['SPEAK', 'VOTE → 2 号'])
    expect(strategy.candidates[0]!.impact).toBe(70)
    expect(strategy.result).toBe('无动作 · 未记录')

    const llm = agent.traces[1]!
    expect(llm.latencyMs).toBe(4200)
    expect(llm.tokens).toBe(900)

    const finished = agent.traces[2]!
    expect(finished.result).toContain('VOTE')
    expect(finished.result).toContain('已提交')

    // 这三项服务端没有口径：留空即"暂无数据"，不能补一个看起来合理的数字
    for (const trace of agent.traces) {
      expect(trace.survival).toBeNull()
      expect(trace.identity).toEqual([])
      expect(trace.belief.before).toBeNull()
      expect(trace.belief.after).toBeNull()
      expect(trace.belief.label).toContain('未提供')
    }
  })

  test('策略档案是实测内容（参与次数与实际用过的动作），不是按角色套用的形容词', async () => {
    stubFetch({ '/analysis/agents/': fullAgent, '/analysis/summary': fullSummary })
    const adapter = createRealAnalysisAdapter()
    await adapter.summary('g_1')
    const agent = await adapter.agent('g_1', 1)

    expect(agent.role).toBe('SEER')
    expect(agent.style).toContain('发言 4 次')
    expect(agent.tactics).toEqual(['VOTE'])
    expect(agent.constraints.join(' ')).toContain('不包含模型私有推理')
    expect(agent.style.join(' ')).not.toContain('谨慎伪装')
  })

  test('复读拦截显示被比对的发言与被拦下的稿子；没有稿子时不补一行', async () => {
    const rejected = (stage: string, reason: string, draft?: string | null) => ({
      ...fullAgent.traces[2]!, id: `p_1:15:REJECTED:${stage}`, kind: 'REJECTED', version: 15,
      chosenAction: null, chosenTargetSeatNo: null, submitted: null, fallbackUsed: null,
      llmCalls: null, repairRounds: null, stage, reason, draft,
    })
    stubFetch({
      '/analysis/agents/': {
        ...fullAgent,
        traces: [
          rejected('ECHO_CHECK', '与 3 号第 1 轮发言相似度 0.81', '我先听听后面怎么说'),
          rejected('LEAK_CHECK', 'PRIVATE_FACT'),
        ],
      },
      '/analysis/summary': fullSummary,
    })
    const adapter = createRealAnalysisAdapter()
    await adapter.summary('g_1')
    const agent = await adapter.agent('g_1', 1)

    expect(agent.traces[0]!.observations).toEqual([
      'ECHO_CHECK 阶段拒绝：与 3 号第 1 轮发言相似度 0.81',
      '被拦下的稿子：我先听听后面怎么说',
    ])
    expect(agent.traces[1]!.observations).toEqual(['LEAK_CHECK 阶段拒绝：PRIVATE_FACT'])
  })

  test('座位号被翻译成服务端的玩家标识，适配器不猜别的形式', async () => {
    const calls: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      calls.push(String(url))
      const body = String(url).includes('/summary') ? fullSummary : fullAgent
      return {
        ok: true, status: 200,
        headers: new Headers({ 'content-type': 'application/json' }),
        text: async () => JSON.stringify(body), json: async () => body,
      } as unknown as Response
    }))
    const adapter = createRealAnalysisAdapter()
    await adapter.summary('g_1')
    await adapter.agent('g_1', 7)
    expect(calls.some(url => url.includes('/analysis/agents/p_7'))).toBe(true)
  })
})
