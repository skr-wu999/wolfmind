import { request } from '@/api/apiClient'
import { roleNames, type GameRole } from '@/features/game/types'
import { AnalysisAccessError } from './types'
import type {
  AgentAnalysis,
  AnalysisAdapter,
  AnalysisCandidate,
  AnalysisSummary,
  DecisionTrace,
} from './types'

/**
 * 真实 AI 分析适配器（`GET /games/{id}/analysis/summary` 与 `.../agents/{playerId}`）。
 *
 * **这里不编数据。** 服务端能给出的都是运行时可观察的事实：发言与投票统计、关键事件、
 * 策略层算出的候选集与打分、工具调用、token 与耗时、是否兜底。
 * 前端**没有**的东西一律留空，界面显示"暂无数据"——
 * 因为分析页上任何一个看起来像结论的数字都会被当成事实引用，而编造出来的"胜率曲线"
 * 或"信念变化"恰恰是最像结论的那一类。
 *
 * 因此与演示适配器的差别不只是数据来源：
 * - `estimates`（胜率曲线）为空：服务端没有信念/胜率模型，不做推测；
 * - `belief` 只标注"服务端未提供信念估计"，而不是给出一组虚构的 before/after；
 * - `identity` 为空：置信度分布属于模型内部状态，不是可观察事实；
 * - `style` / `tactics` 改成**实测**内容（参与次数、实际用过的动作类型），
 *   而不是"谨慎伪装 / 转移焦点"这类按角色套用的形容词。
 */
interface WireStat {
  playerId: string
  seatNo: number
  role: string | null
  camp: string | null
  controllerType: string | null
  alive: boolean
  speechCount: number
  voteCount: number
  abstainCount: number
  voteHitCount: number | null
  survivedRounds: number
  deathRound: number | null
  decisionTraces: number
  llmCalls: number
  promptTokens: number
  completionTokens: number
  toolCalls: number
  fallbackTurns: number
  totalLlmLatencyMs: number
  maxLlmLatencyMs: number
}
interface WireSummary {
  gameId: string
  status: string
  disclosure: 'FULL' | 'PUBLIC'
  winner: string | null
  finishReason: string | null
  rounds: number
  version: number
  startedAt: number
  finishedAt: number | null
  players: WireStat[]
  keyEvents: { version: number; type: string; round: number; occurredAt: number; summary: string }[]
  truncated: boolean
  provenance: string
}
interface WireTrace {
  id: string
  kind: string
  version: number
  phase: string
  round: number
  occurredAt: number
  candidates: { actionType: string; targetSeatNo: number | null; score: number }[]
  chosenAction: string | null
  chosenTargetSeatNo: number | null
  submitted: boolean | null
  fallbackUsed: boolean | null
  llmCalls: number | null
  repairRounds: number | null
  model: string | null
  provider: string | null
  promptTokens: number | null
  completionTokens: number | null
  llmLatencyMs: number | null
  tool: string | null
  toolArgsDigest: string | null
  toolDecision: string | null
  denialCode: string | null
  stage: string | null
  reason: string | null
  /** 仅复读拦截：被拦下的发言稿。旧服务端不返回该字段。 */
  draft?: string | null
}
interface WireAgentAnalysis {
  gameId: string
  playerId: string
  seatNo: number
  role: string | null
  camp: string | null
  controllerType: string | null
  disclosure: 'FULL' | 'PUBLIC'
  traces: WireTrace[]
  note: string
}

const candidateIds = 'ABCDEFGH'

/** 座位号 → 领域玩家标识。服务端按 `p_{seat}` 派生，前端不猜别的形式。 */
function playerIdOf(seatNo: number): string {
  return `p_${seatNo}`
}
function isRole(value: string | null): value is GameRole {
  return value !== null && value in roleNames
}
function label(candidate: { actionType: string; targetSeatNo: number | null }): string {
  return candidate.targetSeatNo === null
    ? candidate.actionType
    : `${candidate.actionType} → ${candidate.targetSeatNo} 号`
}
/** 决策轨迹的秒偏移：与回放时间轴同源（`GAME_STARTED.occurredAt` 为原点）。 */
function offsetSeconds(occurredAt: number, origin: number): number {
  return Math.max(0, Math.round((occurredAt - origin) / 100) / 10)
}

function toTrace(raw: WireTrace, origin: number): DecisionTrace {
  const candidates: AnalysisCandidate[] = raw.candidates.map((candidate, index) => ({
    id: candidateIds[index % candidateIds.length]!,
    label: label(candidate),
    detail: `策略层打分 ${candidate.score.toFixed(2)}`,
    impact: Math.round(candidate.score * 100),
  }))
  const chosenIndex = raw.candidates.findIndex(
    candidate =>
      candidate.actionType === raw.chosenAction &&
      (candidate.targetSeatNo ?? null) === (raw.chosenTargetSeatNo ?? null),
  )
  const observations = observationsOf(raw)
  return {
    id: raw.id,
    eventId: raw.id,
    at: offsetSeconds(raw.occurredAt, origin),
    round: raw.round,
    phase: raw.phase,
    observations,
    // 服务端不提供信念估计：给出结构但把数值留空，界面显示"暂无数据"。
    belief: {
      seatNo: raw.chosenTargetSeatNo ?? 0,
      label: '服务端未提供信念估计',
      before: null,
      after: null,
      summary: '决策轨迹只记录可观察事实，不推断模型内部判断。',
    },
    candidates,
    chosenId: chosenIndex >= 0 ? candidateIds[chosenIndex % candidateIds.length]! : '',
    result: resultOf(raw),
    survival: null,
    identity: [],
    latencyMs: raw.llmLatencyMs,
    tokens:
      raw.promptTokens === null && raw.completionTokens === null
        ? null
        : (raw.promptTokens ?? 0) + (raw.completionTokens ?? 0),
  }
}

function observationsOf(raw: WireTrace): string[] {
  switch (raw.kind) {
    case 'TURN_STARTED':
      return [`第 ${raw.round} 轮 ${raw.phase} 的回合开始`]
    case 'CONTEXT':
      return [`上下文装配完成（策略层候选 ${raw.candidates.length} 个）`]
    case 'STRATEGY':
      return [
        `策略层给出 ${raw.candidates.length} 个候选`,
        ...raw.candidates.slice(0, 3).map(candidate => `${label(candidate)}（${candidate.score.toFixed(2)}）`),
      ]
    case 'LLM':
      return [
        `${raw.model ?? '模型'} 返回 ${(raw.promptTokens ?? 0) + (raw.completionTokens ?? 0)} token`,
        `耗时 ${raw.llmLatencyMs ?? 0} ms`,
      ]
    case 'TOOL':
      return [`工具 ${raw.tool ?? '-'}：${raw.toolDecision === 'DENIED' ? '被拒绝' : '允许'}`]
    case 'REJECTED':
      return [
        `${raw.stage ?? '校验'} 阶段拒绝：${raw.denialCode ?? raw.reason ?? '未说明'}`,
        ...(raw.draft ? [`被拦下的稿子：${raw.draft}`] : []),
      ]
    case 'TIMEOUT':
      return [`在 ${raw.stage ?? '未知阶段'} 转入规则兜底`]
    case 'FAILED':
      return [`回合失败：${raw.denialCode ?? raw.reason ?? '未说明'}`]
    case 'FINISHED':
      return [`回合结论：选择 ${raw.chosenAction ?? '-'}`]
    default:
      return []
  }
}

function resultOf(raw: WireTrace): string {
  if (raw.kind === 'TOOL') {
    return `${raw.tool ?? '工具'}：${raw.toolArgsDigest ?? '无参数摘要'}`
  }
  const target = raw.chosenTargetSeatNo === null ? '' : ` → ${raw.chosenTargetSeatNo} 号`
  const submitted = raw.submitted === true ? '已提交' : raw.submitted === false ? '未提交' : '未记录'
  const fallback = raw.fallbackUsed === true ? '（规则兜底）' : ''
  return `${raw.chosenAction ?? '无动作'}${target} · ${submitted}${fallback}`
}

export function createRealAnalysisAdapter(): AnalysisAdapter {
  /** 概览缓存：`agent()` 需要 `startedAt` 把轨迹时间戳换算成回放时间轴位置。 */
  let lastSummary: WireSummary | null = null

  async function summary(gameId: string): Promise<AnalysisSummary> {
    const raw = await request<WireSummary>(
      `/api/v1/games/${encodeURIComponent(gameId)}/analysis/summary`,
    )
    lastSummary = raw
    if (raw.disclosure !== 'FULL') {
      // 进行中的对局只给公开事实。分析页是"赛后复盘"，此时应当明确拒绝而不是显示半份数据。
      throw new AnalysisAccessError('对局结束后才开放 AI 分析：进行中只保留公开记录。')
    }
    const defaultSeat = raw.players[0]?.seatNo ?? 1
    const origin = raw.startedAt
    let initialPosition = 0
    try {
      const first = await request<WireAgentAnalysis>(
        `/api/v1/games/${encodeURIComponent(gameId)}/analysis/agents/${encodeURIComponent(playerIdOf(defaultSeat))}`,
      )
      const at = first.traces[0]?.occurredAt
      if (at !== undefined) initialPosition = offsetSeconds(at, origin)
    } catch {
      // 首次决策位置只是"打开页面时停在哪"，取不到就从 0 开始，不影响分析内容。
      initialPosition = 0
    }
    return {
      gameId: raw.gameId,
      status: 'COMPLETED',
      defaultSeat,
      initialPosition,
      estimates: [],
      provenance:
        `${raw.provenance} 共 ${raw.players.length} 个座位、${raw.rounds} 轮、版本 ${raw.version}` +
        `${raw.truncated ? '（可见事件被截断）' : ''}。`,
    }
  }

  async function agent(gameId: string, seatNo: number): Promise<AgentAnalysis> {
    const raw = await request<WireAgentAnalysis>(
      `/api/v1/games/${encodeURIComponent(gameId)}/analysis/agents/${encodeURIComponent(playerIdOf(seatNo))}`,
    )
    const origin = lastSummary?.startedAt ?? raw.traces[0]?.occurredAt ?? 0
    const traces = raw.traces.map(trace => toTrace(trace, origin))
    const role: GameRole = isRole(raw.role) ? raw.role : 'VILLAGER'
    const stat = lastSummary?.players.find(player => player.seatNo === seatNo)

    return {
      gameId: raw.gameId,
      seatNo: raw.seatNo,
      role,
      // 以下四项都是**实测**内容，不是按角色套用的形容词。
      style: stat
        ? [
            `发言 ${stat.speechCount} 次`,
            `投票 ${stat.voteCount} 次`,
            stat.abstainCount > 0 ? `弃票 ${stat.abstainCount} 次` : '未弃票',
            `决策轨迹 ${stat.decisionTraces} 条`,
          ]
        : [],
      goal: `该座位以${roleNames[role]}身份行动；以下全部为服务端记录的可观察事实。`,
      tactics: [...new Set(raw.traces.map(trace => trace.chosenAction).filter((action): action is string => !!action))],
      constraints: [
        '决策轨迹只包含候选集与打分、工具调用、token 与耗时、是否兜底；不包含模型私有推理。',
        '角色与阶段可见性由服务端视图决定，前端不做裁剪。',
        raw.note,
      ].filter(Boolean),
      traces,
    }
  }

  return { summary, agent }
}

export const realAnalysisAdapter = createRealAnalysisAdapter()
