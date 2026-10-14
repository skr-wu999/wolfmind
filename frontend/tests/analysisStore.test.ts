import { beforeEach, expect, test, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAnalysisStore } from '../src/stores/analysis'
import { useObservationStore } from '../src/stores/observation'
import { analysisAdapter, createAgentAnalysis, createAnalysisSummary } from '../src/features/analysis/mockAnalysisAdapter'
import { createObservationFixture } from '../src/features/observation/mockObservationAdapter'
import { AnalysisAccessError } from '../src/features/analysis/types'
import type { AgentAnalysis, AnalysisSummary } from '../src/features/analysis/types'

beforeEach(() => { setActivePinia(createPinia()); vi.restoreAllMocks() })
test('decision traces reference existing accepted speeches and fetch only the selected Agent', async () => {
  const match = createObservationFixture()
  for (const seat of [1, 2, 3, 4, 5, 7]) {
    const agent = await analysisAdapter.agent('demo', seat)
    expect(agent.seatNo).toBe(seat)
    for (const trace of agent.traces) {
      const speech = match.events.find(e => e.id === trace.eventId)!
      expect(speech.seatNo).toBe(seat); expect(trace.result).toBe(speech.content)
      expect(trace.at).toBe(speech.at); expect(trace.tokens).toBeNull(); expect(trace.latencyMs).toBeNull()
    }
  }
  expect(createAgentAnalysis('demo', 6).traces).toEqual([])
  expect(createAgentAnalysis('demo', 5).traces.flatMap(t => t.observations).join()).not.toContain('本人记录中有')
})
test('seeking chooses only past decisions and estimates; no lookahead on backward playback', async () => {
  const store = useAnalysisStore(), player = useObservationStore()
  await player.load('demo'); await store.load('demo'); player.seek(820)
  expect(store.trace?.at).toBe(820); expect(store.estimate?.wolf).toBe(42)
  player.seek(819); expect(store.trace).toBeNull(); expect(store.estimate?.wolf).toBe(38)
  await store.selectAgent(1); expect(store.trace?.at).toBe(735)
  player.seek(100); expect(store.trace?.at).toBe(95)
  player.seek(0); expect(store.trace).toBeNull()
})
test('failed Agent selection preserves the current record and retries the requested seat', async () => {
  const store = useAnalysisStore(); await store.load('demo')
  vi.spyOn(analysisAdapter, 'agent').mockRejectedValueOnce(new Error('读取失败'))
  expect(await store.selectAgent(1)).toBe(false)
  expect(store.selectedSeat).toBe(4); expect(store.agentError).toBe('读取失败')
  await store.retryAgent(); expect(store.selectedSeat).toBe(1); expect(store.agentError).toBe('')
})
test('late Agent response cannot replace a newer selection', async () => {
  const store = useAnalysisStore(); await store.load('demo')
  let finish!: (agent: AgentAnalysis) => void
  vi.spyOn(analysisAdapter, 'agent').mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const pending = store.selectAgent(1)
  await store.selectAgent(2); finish(createAgentAnalysis('demo', 1)); await pending
  expect(store.selectedSeat).toBe(2); expect(store.agentLoading).toBe(false)
})
test('late summary or disposed response cannot overwrite a different match', async () => {
  const store = useAnalysisStore(); let finish!: (summary: AnalysisSummary) => void
  vi.spyOn(analysisAdapter, 'summary').mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const first = store.load('demo-first'); await store.load('demo-next')
  finish(createAnalysisSummary('demo-first')); await first
  expect(store.summary?.gameId).toBe('demo-next'); expect(store.agent?.gameId).toBe('demo-next')
  vi.spyOn(analysisAdapter, 'summary').mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const pending = store.load('demo-pending'); store.dispose(); finish(createAnalysisSummary('demo-pending')); await pending
  expect(store.summary).toBeNull(); expect(store.agent).toBeNull(); expect(store.loading).toBe(false)
})
test('unsupported or denied matches show errors instead of a substituted fixture', async () => {
  const store = useAnalysisStore(); await store.load('real-match')
  expect(store.error).toContain('尚未接入'); expect(store.summary).toBeNull()
  vi.spyOn(analysisAdapter, 'summary').mockRejectedValueOnce(new AnalysisAccessError('无权限'))
  await store.load('demo'); expect(store.restricted).toBe(true); expect(store.agent).toBeNull()
})
test('unfinished matches and mismatched Agent records are rejected', async () => {
  const store = useAnalysisStore()
  vi.spyOn(analysisAdapter, 'summary').mockResolvedValueOnce({ ...createAnalysisSummary(), status: 'RUNNING' } as unknown as AnalysisSummary)
  await store.load('demo'); expect(store.restricted).toBe(true); expect(store.summary).toBeNull()
  await store.load('demo')
  vi.spyOn(analysisAdapter, 'agent').mockResolvedValueOnce(createAgentAnalysis('demo-other', 1))
  await store.selectAgent(1); expect(store.agentError).toContain('不匹配'); expect(store.selectedSeat).toBe(4)
})
test('missing estimates remain null and do not become zero probabilities', async () => {
  vi.spyOn(analysisAdapter, 'summary').mockResolvedValueOnce({ ...createAnalysisSummary(), estimates: [{ at: 0, wolf: null, village: null }] })
  const store = useAnalysisStore(); await store.load('demo')
  expect(store.estimate).toEqual({ at: 0, wolf: null, village: null })
})
