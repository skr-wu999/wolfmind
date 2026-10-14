import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { analysisAdapter as selectedAnalysisAdapter } from '@/api/adapters'
import { AnalysisAccessError } from '@/features/analysis/types'
import type { AgentAnalysis, AnalysisScope, AnalysisSummary } from '@/features/analysis/types'
import { useObservationStore } from './observation'

export const useAnalysisStore = defineStore('analysis', () => {
  const summary = ref<AnalysisSummary | null>(null), agent = ref<AgentAnalysis | null>(null)
  const loading = ref(false), agentLoading = ref(false), error = ref(''), agentError = ref(''), restricted = ref(false)
  const scope = ref<AnalysisScope>('all')
  const playback = useObservationStore()
  let summaryRequest = 0, agentRequest = 0, requestedSeat: number | null = null
  const trace = computed(() => [...(agent.value?.traces || [])].reverse().find(item => item.at <= playback.position) || null)
  const estimate = computed(() => [...(summary.value?.estimates || [])].reverse().find(item => item.at <= playback.position) || null)
  const camp = computed(() => !agent.value ? null : agent.value.role === 'WEREWOLF' ? '狼人阵营' : '村民阵营')
  const selectedSeat = computed(() => agent.value?.seatNo ?? null)
  async function selectAgent(seatNo: number) {
    if (!summary.value) return false
    const adapter = selectedAnalysisAdapter()
    const request = ++agentRequest, gameId = summary.value.gameId
    requestedSeat = seatNo; agentLoading.value = true; agentError.value = ''
    try {
      const result = await adapter.agent(gameId, seatNo)
      if (request !== agentRequest || summary.value?.gameId !== gameId) return false
      if (result.gameId !== gameId || result.seatNo !== seatNo) throw new Error('Agent 记录与当前对局或座位不匹配。')
      agent.value = { ...result, traces: [...result.traces].sort((a, b) => a.at - b.at) }
      return true
    } catch (cause) {
      if (request === agentRequest) agentError.value = cause instanceof Error ? cause.message : 'Agent 记录加载失败。'
      return false
    } finally { if (request === agentRequest) agentLoading.value = false }
  }
  async function load(gameId: string) {
    const adapter = selectedAnalysisAdapter()
    const request = ++summaryRequest
    ++agentRequest; requestedSeat = null
    summary.value = null; agent.value = null; loading.value = true; agentLoading.value = false
    error.value = ''; agentError.value = ''; restricted.value = false; scope.value = 'all'
    try {
      const result = await adapter.summary(gameId)
      if (request !== summaryRequest) return
      if (result.gameId !== gameId) throw new Error('分析记录与当前对局不匹配。')
      if (result.status !== 'COMPLETED') throw new AnalysisAccessError('对局结束并获得授权后才能查看分析。')
      summary.value = { ...result, estimates: [...result.estimates].sort((a, b) => a.at - b.at) }
      await selectAgent(result.defaultSeat)
    } catch (cause) {
      if (request === summaryRequest) { error.value = cause instanceof Error ? cause.message : '分析加载失败。'; restricted.value = cause instanceof AnalysisAccessError }
    } finally { if (request === summaryRequest) loading.value = false }
  }
  function retryAgent() { return requestedSeat !== null ? selectAgent(requestedSeat) : Promise.resolve(false) }
  function dispose() { summaryRequest++; agentRequest++; summary.value = null; agent.value = null; loading.value = false; agentLoading.value = false }
  return { summary, agent, loading, agentLoading, error, agentError, restricted, scope, trace, estimate, camp, selectedSeat, selectAgent, load, retryAgent, dispose }
})
