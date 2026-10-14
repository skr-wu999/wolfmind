import type { GameRole } from '@/features/game/types'

export type AnalysisScope = 'all' | 'camp' | 'agent'
export interface AnalysisCandidate { id: string; label: string; detail: string; impact: number | null }
export interface DecisionTrace {
  id: string
  eventId: string
  at: number
  round: number
  phase: string
  observations: string[]
  belief: { seatNo: number; label: string; before: number | null; after: number | null; summary: string }
  candidates: AnalysisCandidate[]
  chosenId: string
  result: string
  survival: number | null
  identity: { label: string; value: number | null }[]
  latencyMs: number | null
  tokens: number | null
}
export interface AgentAnalysis {
  gameId: string
  seatNo: number
  role: GameRole
  style: string[]
  goal: string
  tactics: string[]
  constraints: string[]
  traces: DecisionTrace[]
}
export interface AnalysisSummary {
  gameId: string
  status: 'COMPLETED'
  defaultSeat: number
  initialPosition: number
  estimates: { at: number; wolf: number | null; village: number | null }[]
  provenance: string
}
// Frontend demo projections; production responses require a separate authorized mapping.
export interface AnalysisAdapter {
  summary(gameId: string): Promise<AnalysisSummary>
  agent(gameId: string, seatNo: number): Promise<AgentAnalysis>
}
export class AnalysisAccessError extends Error {}
