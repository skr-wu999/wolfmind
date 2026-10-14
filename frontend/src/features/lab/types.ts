import type { GamePlayer, GameRole } from '@/features/game/types'

export type LabRole = Exclude<GameRole, 'GUARD'>
export type ComparisonMetric = 'winRate' | 'speechQuality' | 'latency' | 'stability'
export interface LabModel { id: string; name: string; avatar: GamePlayer['avatar']; description: string }
export interface AgentDraft {
  id?: string
  name: string
  role: LabRole
  personality: string
  strategy: string
  modelId: string
  temperature: number
  promptMode: 'default' | 'custom'
  prompt: string
  memory: 'short' | 'long' | 'off'
  tools: { analysis: boolean; history: boolean; summary: boolean }
}
export interface AgentConfig extends AgentDraft { id: string }
export interface BatchOptions { total: number; seedStart: number; seedEnd: number; mode: 'standard' | 'quick'; speed: number; concurrency: number }
export interface LabGameResult { index: number; seed: number; outcomes: { configId: string; winner: '狼人阵营' | '村民阵营' }[]; gameId: string | null }
export type LabGameStatus = 'PENDING' | 'RUNNING' | 'FINISHED' | 'ABORTED' | 'FAILED'
/**
 * 批次里的单局（`GET /lab/batches/{id}` 的 `games[]`）。
 *
 * <p>它是"这一批到底跑了哪些对局"的唯一来源：`gameId` 指的是一局**普通对局**，
 * 因此观战/回放/分析都直接用服务端的真实 id，不需要实验室自己的跳转格式。
 */
export interface LabBatchGame { index: number; gameId: string | null; status: LabGameStatus; winner: '狼人阵营' | '村民阵营' | null; rounds: number | null }
export interface LabBatch { id: string; status: 'RUNNING' | 'COMPLETED'; total: number; completed: number; options: BatchOptions; configs: AgentConfig[]; results: LabGameResult[]; games: LabBatchGame[] }
export interface ComparisonRow { configId: string; name: string; modelId: string; role: LabRole; games: number; wins: number; winRate: number | null; speechQuality: number | null; latency: number | null; stability: number | null }
export interface LabCatalog { models: LabModel[]; configs: AgentConfig[]; batch: LabBatch | null }
// Local presentation adapters; production DTOs remain defined by the shared API contract.
export interface LabAdapter {
  catalog(): Promise<LabCatalog>
  save(draft: AgentDraft): Promise<AgentConfig>
  start(configIds: string[], options: BatchOptions): Promise<LabBatch>
  refresh(batchId: string): Promise<LabBatch>
  comparisons(batchId: string | null): Promise<ComparisonRow[]>
  clearLocalResults(): Promise<void>
}
export class LabAccessError extends Error {}
export const personalities = ['理性分析', '情感表达', '激进强势', '稳健保守', '幽默风趣', '自定义']
export const strategies = ['深度伪装', '证据优先', '信息核对', '协同投票']
export const metricNames: Record<ComparisonMetric, string> = { winRate: '胜率', speechQuality: '发言质量', latency: '决策耗时', stability: '稳定性' }
export const defaultPrompt = '遵守角色的信息边界，仅使用已授权的观察与公开记录。给出可核对的发言和结构化行动建议，所有行动由游戏规则校验。'
/**
 * 服务端一个模型都没有时的显式选项。
 *
 * <p>`model` 是必填字段（1–120 字符，api-spec.md §8.1），空模型会被 400 拒绝；
 * 而 `LlmSettings` 未配置时运行时的 `decide()` 本来就直接走确定性规则基线、不调模型。
 * 因此给一个非空标识，让"这台服务器没配模型"也能从页面起步，而不是把入口禁用掉。
 */
export const ruleBaselineModel: LabModel = {
  id: 'rule-baseline',
  name: '规则机器人（不使用模型）',
  avatar: 'blue',
  description: '服务端未配置模型：该配置不调用模型，由确定性规则基线出招，token 与模型耗时恒为 0。',
}
export function validateDraft(draft: AgentDraft, modelIds: string[]): Record<string, string> {
  const errors: Record<string, string> = {}
  if (!draft.name.trim() || draft.name.length > 24) errors.name = '配置名称需为 1–24 个字符。'
  if (!['WEREWOLF', 'SEER', 'WITCH', 'HUNTER', 'VILLAGER'].includes(draft.role)) errors.role = '请选择经典九人局中的角色。'
  if (!modelIds.includes(draft.modelId)) errors.modelId = '请选择可用的示例模型。'
  if (!personalities.includes(draft.personality)) errors.personality = '请选择行为风格。'
  if (!strategies.includes(draft.strategy)) errors.strategy = '请选择策略预设。'
  if (!Number.isFinite(draft.temperature) || draft.temperature < 0 || draft.temperature > 1) errors.temperature = '温度需在 0–1 之间。'
  if (!draft.prompt.trim() || draft.prompt.length > 800) errors.prompt = '提示词需为 1–800 个字符。'
  if (!['default', 'custom'].includes(draft.promptMode)) errors.promptMode = '提示词模式无效。'
  if (!['short', 'long', 'off'].includes(draft.memory)) errors.memory = '记忆模式无效。'
  if (!draft.tools || ['analysis', 'history', 'summary'].some(key => typeof draft.tools[key as keyof AgentDraft['tools']] !== 'boolean')) errors.tools = '工具配置无效。'
  return errors
}
export function validateBatch(options: BatchOptions, configIds: string[]): Record<string, string> {
  const errors: Record<string, string> = {}
  if (!Number.isInteger(options.total) || options.total < 1 || options.total > 50) errors.total = '本地示例支持 1–50 局，建议 10–50 局。'
  if (!Number.isInteger(options.seedStart) || options.seedStart < 0 || options.seedStart > 2147483647) errors.seedStart = '起始种子需为 0–2147483647 的整数。'
  if (!Number.isInteger(options.seedEnd) || options.seedEnd > 2147483647 || options.seedEnd < options.seedStart + options.total - 1) errors.seedEnd = '种子范围需包含足够的局数，且不超过 2147483647。'
  if (!Number.isInteger(options.concurrency) || options.concurrency < 1 || options.concurrency > 8) errors.concurrency = '并发上限需在 1–8 之间。'
  if (![0.5, 1, 2, 5].includes(options.speed)) errors.speed = '请选择可用的演示速度。'
  if (!['standard', 'quick'].includes(options.mode)) errors.mode = '请选择演示模式。'
  if (!configIds.length) errors.configIds = '至少选择一份已保存的 Agent 配置。'
  return errors
}
