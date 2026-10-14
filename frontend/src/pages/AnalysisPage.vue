<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import FogVillageScene from '@/components/FogVillageScene.vue'
import PixelTitle from '@/components/PixelTitle.vue'
import VillageHeader from '@/components/VillageHeader.vue'
import GameTable from '@/components/GameTable.vue'
import PixelAvatar from '@/components/PixelAvatar.vue'
import PixelIcon from '@/components/PixelIcon.vue'
import PixelDialog from '@/components/PixelDialog.vue'
import ObservationControls from '@/components/ObservationControls.vue'
import AnalysisMetric from '@/components/AnalysisMetric.vue'
import { useAnalysisStore } from '@/stores/analysis'
import { useObservationStore } from '@/stores/observation'
import { usingRealBackend } from '@/api/adapters'
import { useHomeDemoStore } from '@/stores/homeDemo'
import { useVillageAudio } from '@/features/home/useVillageAudio'
import { roleNames } from '@/features/game/types'
import { formatTime, isNightPhase } from '@/features/observation/types'
import { projectObservation } from '@/features/observation/projection'
import type { ObservationEvent } from '@/features/observation/types'
import type { AnalysisScope } from '@/features/analysis/types'

const route = useRoute(), analysis = useAnalysisStore(), playback = useObservationStore()
/**
 * 真实模式：概览与决策轨迹来自服务端 `GET /games/{id}/analysis/*`。
 *
 * 服务端只给运行时可观察的事实，因此真实模式下**没有**胜率估计、信念变化与存活率——
 * 那几项在页面上显示"暂无数据"，而不是由前端补一组看起来像结论的数字。
 * 文案必须跟着变：写着"手工编写的估计"会让人以为数字是编的，而它其实是算出来的。
 */
const real = usingRealBackend()
const dialog = ref<'rules' | 'about' | 'audio' | 'step' | 'table' | null>(null)
const step = ref(0), day = ref('all'), feedback = ref('')
const strategyHeading = ref<HTMLElement | null>(null), stage = ref<HTMLElement | null>(null)
const timeline = ref<HTMLElement | null>(null)
const { sound, toggleSound } = useVillageAudio(() => { dialog.value = 'audio' })
const loading = computed(() => analysis.loading || playback.loading)
const selectedPlayer = computed(() => playback.match?.players.find(p => p.seatNo === analysis.selectedSeat))
const matchResult = computed(() => playback.match ? projectObservation(playback.match, playback.match.duration).winner || '暂无结果' : '暂无结果')
const events = computed(() => playback.match?.events.filter(event => (event.type === 'PLAYER_SPOKE' || event.change?.phase || event.change?.eliminated) && (day.value === 'all' || eventRound(event) === Number(day.value))) || [])
/**
 * 可选的回合来自**真实事件**里的轮次，而不是写死的 3 天。
 *
 * 写死会让第 4 轮的记录无法被筛出来：选项里根本没有第 4 天（QA P2），
 * 而"这局有几轮"只有服务端的事件知道。
 */
const rounds = computed(() => [...new Set((playback.match?.events || []).map(event => event.change?.round).filter((round): round is number => round !== undefined))].sort((a, b) => a - b))
const eventCursor = computed(() => [...events.value].reverse().find(event => event.at <= playback.position)?.id)
const scopeLabels: Record<AnalysisScope, string> = { all: '全局分析', camp: '阵营视角', agent: '单 Agent' }
const steps = ['观察到的信号', '判断更新', '候选行动', '最终选择']
const identity = computed(() => analysis.trace?.identity || [])
const chosen = computed(() => analysis.trace?.candidates.find(item => item.id === analysis.trace?.chosenId))
const title = computed(() => dialog.value === 'step' ? steps[step.value]! : dialog.value === 'table' ? (real ? '分析数据表' : '分析数据表 · 示例估计') : dialog.value === 'rules' ? '经典九人局' : dialog.value === 'audio' ? '音效暂时不可用' : '关于 AI 分析')
let pageRequest = 0, timer: ReturnType<typeof setInterval> | undefined, previousTime = 0
function eventRound(event: ObservationEvent) { return playback.match?.events.filter(e => e.version <= event.version && e.change?.round).at(-1)?.change?.round || 1 }
async function load() {
  const request = ++pageRequest, id = String(route.params.gameId)
  dialog.value = null; day.value = 'all'; feedback.value = ''
  await Promise.all([playback.load(id), analysis.load(id)])
  if (request === pageRequest && analysis.summary && playback.match) playback.seek(analysis.summary.initialPosition)
}
async function selectAgent(seat: number) {
  playback.playing = false; dialog.value = null
  if (await analysis.selectAgent(seat)) { await nextTick(); strategyHeading.value?.focus() }
}
async function seekEvent(event: ObservationEvent) {
  playback.seek(event.at)
  if (event.seatNo && event.type === 'PLAYER_SPOKE') await selectAgent(event.seatNo)
}
async function moveEvent(event: KeyboardEvent, index: number) {
  if (!['ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? events.value.length - 1 : Math.max(0, Math.min(events.value.length - 1, index + (event.key === 'ArrowDown' ? 1 : -1)))
  const target = events.value[next], list = (event.currentTarget as HTMLElement).closest('ol')
  if (target) { await seekEvent(target); await nextTick(); list?.querySelectorAll<HTMLButtonElement>('button')[next]?.focus() }
}
function openStep(index: number) { step.value = index; playback.playing = false; dialog.value = 'step' }
async function fullscreen() {
  try {
    if (document.fullscreenElement) await document.exitFullscreen()
    else if (stage.value?.requestFullscreen) await stage.value.requestFullscreen()
    else feedback.value = '当前浏览器不支持全屏，可使用浏览器的全屏模式。'
  } catch { feedback.value = '全屏暂时不可用，请使用浏览器的全屏模式。' }
}
function valueText(value: number | null | undefined, unit = '%') { return value == null || !Number.isFinite(value) ? '暂无数据' : `${value}${unit}` }
watch(() => route.params.gameId, () => { void load() }, { immediate: true })
watch(() => playback.playing, () => { previousTime = performance.now() })
watch(eventCursor, async () => {
  await nextTick()
  const list = timeline.value, active = list?.querySelector<HTMLElement>('[aria-current="step"]')
  if (list && active) {
    const bounds = list.getBoundingClientRect(), item = active.getBoundingClientRect()
    if (item.top < bounds.top || item.bottom > bounds.bottom) list.scrollTop += item.top - bounds.top - list.clientHeight / 2 + item.height / 2
  }
})
onMounted(() => {
  useHomeDemoStore().phase = 'night'; previousTime = performance.now()
  timer = setInterval(() => { const now = performance.now(); playback.tick((now - previousTime) / 1000); previousTime = now }, 250)
})
onBeforeUnmount(() => { ++pageRequest; clearInterval(timer); analysis.dispose(); playback.dispose() })
</script>

<template>
  <div class="landing inner-page analysis-page is-night">
    <FogVillageScene controlled-phase="night" hide-actors />
    <VillageHeader :sound="sound" @sound="toggleSound" @info="dialog = $event" />
    <main class="analysis-main">
      <nav class="analysis-nav" aria-label="对局导航"><RouterLink :to="`/watch/${route.params.gameId}`">AI 对局</RouterLink><RouterLink :to="`/games/${route.params.gameId}/replay`">游戏回放</RouterLink><RouterLink to="/lobby">游戏大厅</RouterLink><span aria-current="page">AI 分析</span></nav>
      <div class="analysis-heading"><strong>AGENT ANALYSIS</strong><h1><PixelTitle text="AI 分析" :size="16" /></h1><p>从 AI 的视角，回看每次发言与选择，理解营火旁的博弈。</p><span>{{ real ? '赛后分析 · 服务端统计与决策轨迹 · 不含模型私有推理' : '赛后分析 · 本地九人局示例 · 手工编写的估计与决策摘要' }}</span></div>
      <section v-if="loading" class="px-panel observation-state" role="status"><h2>正在载入 AI 分析…</h2><p>读取对局记录与 Agent 摘要。</p></section>
      <section v-else-if="analysis.error || playback.error" class="px-panel observation-state" role="alert"><h2>{{ analysis.restricted ? '暂未开放分析' : '分析加载失败' }}</h2><p>{{ analysis.error || playback.error }}</p><button class="secondary-button px-button" @click="load">重试</button><RouterLink to="/games/demo/analysis" class="secondary-button px-button">打开本地示例</RouterLink><RouterLink to="/lobby" class="secondary-button px-button">返回大厅</RouterLink></section>
      <template v-else-if="analysis.summary && playback.match && playback.view">
        <div class="analysis-layout">
          <aside class="px-panel analysis-left">
            <section class="analysis-panel analysis-information"><h2>对局信息</h2><dl><div><dt>房间编号</dt><dd>{{ playback.match.gameId }}</dd></div><div><dt>对局模式</dt><dd>AI 对局（9 人）</dd></div><div><dt>规则配置</dt><dd>经典局 · 九人</dd></div><div><dt>开始时间</dt><dd>{{ playback.match.startedAt }}</dd></div><div><dt>当前回合</dt><dd>第 {{ playback.view.round }} 天 · {{ playback.view.phase }}</dd></div><div><dt>游戏时长</dt><dd>{{ formatTime(playback.position) }} / {{ formatTime(playback.match.duration) }}</dd></div><div><dt>对局结果</dt><dd><span class="analysis-result">{{ matchResult }}</span></dd></div></dl><RouterLink :to="`/games/${route.params.gameId}/replay`" class="analysis-replay-link">查看完整回放 <PixelIcon name="arrow" :size="14" /></RouterLink></section>
            <section class="analysis-panel analysis-events"><div class="analysis-panel-title"><h2>关键事件</h2><label><span class="sr-only">回合筛选</span><select v-model="day"><option value="all">全部回合</option><option v-for="round in rounds" :key="round" :value="String(round)">第 {{ round }} 天</option></select></label></div><ol v-if="events.length" ref="timeline"><li v-for="(event, index) in events" :key="event.id"><button :aria-current="event.id === eventCursor ? 'step' : undefined" :class="{ current: event.id === eventCursor, future: event.at > playback.position }" @click="seekEvent(event)" @keydown="moveEvent($event, index)"><span class="analysis-event-node"><PixelIcon :name="event.change?.eliminated ? 'skull' : event.type === 'PLAYER_SPOKE' ? 'speech' : isNightPhase(event.change?.phase) ? 'moon' : 'sun'" :size="12" /></span><time>{{ formatTime(event.at) }}</time><span><strong>{{ event.title }}</strong><small>{{ event.content }}</small></span></button></li></ol><p v-else class="game-muted">该回合暂无关键事件。</p></section>
          </aside>
          <section ref="stage" class="px-panel analysis-board" aria-label="分析游戏桌">
            <div class="analysis-phase"><PixelIcon :name="playback.view.atmosphere === 'night' ? 'moon' : 'sun'" :size="32" /><div><h2>第 {{ playback.view.round }} 天 · {{ playback.view.phase }}</h2><p>点击玩家卡牌，切换到对应的 Agent 分析。</p></div><label><span class="sr-only">切换 Agent</span><select :value="analysis.selectedSeat || ''" @change="selectAgent(Number(($event.target as HTMLSelectElement).value))"><option v-if="!analysis.agent" value="" disabled>选择 Agent</option><option v-for="person in playback.match.players" :key="person.seatNo" :value="person.seatNo">{{ person.seatNo }} 号 · {{ person.name }}</option></select></label></div>
            <GameTable :players="playback.view.players" :phase="playback.view.atmosphere" :me="0" :speaker="playback.view.speaker" :selected="analysis.selectedSeat" selection-label="当前分析" :targets="[]" :selecting="false" @choose="selectAgent" />
            <p class="analysis-board-caption">{{ real ? '角色标签来自服务端赛后披露；分析记录按所选 Agent 单独加载，轨迹不含模型私有推理。' : '全知角色标签仅用于这场已结束的本地示例；分析记录按所选 Agent 单独加载。' }}</p>
          </section>
          <aside class="px-panel analysis-right" :aria-busy="analysis.agentLoading">
            <p v-if="analysis.agentLoading" class="analysis-agent-status" role="status">正在读取 Agent 摘要…</p>
            <p v-if="analysis.agentError" class="analysis-agent-status" role="alert">{{ analysis.agentError }}{{ analysis.agent ? ` 仍显示 ${analysis.selectedSeat} 号。` : '' }} <button @click="analysis.retryAgent">重试 Agent</button></p>
            <section class="analysis-panel analysis-strategy"><h2 ref="strategyHeading" tabindex="-1"><PixelIcon name="settings" :size="21" /> Agent 策略</h2><template v-if="analysis.agent && selectedPlayer"><div class="analysis-agent"><PixelAvatar :variant="selectedPlayer.avatar" :size="52" /><div><strong>{{ analysis.selectedSeat }} 号 · {{ selectedPlayer.name }}</strong><span class="analysis-role" :class="{ wolf: analysis.agent.role === 'WEREWOLF' }">{{ roleNames[analysis.agent.role] }} · {{ analysis.camp }}</span><small>{{ analysis.agent.goal }}</small></div></div><dl class="analysis-strategy-rows"><div><dt>行为风格</dt><dd><span v-for="style in analysis.agent.style" :key="style" class="analysis-tag">{{ style }}</span></dd></div><div><dt>威胁等级</dt><dd><PixelIcon name="skull" :size="16" /><span>{{ analysis.trace ? analysis.agent.role === 'WEREWOLF' ? '受到公开质疑 · 示例判断' : '依据待核对 · 示例判断' : '暂无决策记录' }}</span></dd></div><div><dt>关键策略</dt><dd><span v-for="tactic in analysis.agent.tactics" :key="tactic" class="analysis-tag">{{ tactic }}</span></dd></div></dl></template><p v-else class="game-muted">选择一名 Agent 查看策略。</p></section>
            <section class="analysis-panel analysis-decisions"><div class="analysis-panel-title"><h2><PixelIcon name="key" :size="19" /> 决策过程</h2><small>{{ analysis.trace ? `第 ${analysis.trace.round} 天 · ${formatTime(analysis.trace.at)}` : '暂无摘要' }}</small></div><p class="analysis-summary-note">基于角色可见信息的结构化摘要</p><ol v-if="analysis.trace" class="analysis-steps"><li v-for="(label, index) in steps" :key="label"><button class="analysis-step" :class="{ final: index === 3 }" :aria-label="`展开${label}`" @click="openStep(index)"><span class="analysis-step-label"><b>{{ index === 3 ? '✓' : index + 1 }}</b><strong>{{ label }}</strong><small>查看详情 →</small></span><span class="analysis-step-body"><template v-if="index === 0"><span v-for="signal in analysis.trace.observations" :key="signal">· {{ signal }}</span></template><template v-else-if="index === 1"><span>{{ analysis.trace.belief.seatNo }} 号 · {{ analysis.trace.belief.label }}</span><strong>{{ valueText(analysis.trace.belief.before) }} → {{ valueText(analysis.trace.belief.after) }}</strong><small>{{ analysis.trace.belief.summary }}</small></template><template v-else-if="index === 2"><span v-for="candidate in analysis.trace.candidates" :key="candidate.id">{{ candidate.id }}. {{ candidate.label }}</span></template><template v-else><strong>选择 {{ chosen?.id }}：{{ chosen?.label }}</strong><small>{{ analysis.trace.result }}</small></template></span></button></li></ol><div v-else class="analysis-empty"><PixelIcon name="document" :size="30" /><p>{{ analysis.agent?.traces.length ? '该时刻尚无此 Agent 的决策摘要。' : '此 Agent 暂无决策摘要。' }}</p><button v-if="analysis.agent?.traces.length" class="secondary-button px-button" @click="playback.seek(analysis.agent.traces[0]!.at)">跳到首次决策</button><p class="game-muted">策略资料仍可查看，换一名 Agent 或选择其他事件。</p></div></section>
          </aside>
          <section class="px-panel analysis-panel analysis-charts"><div class="analysis-chart-heading"><h2>胜率分析</h2><small>{{ real ? `${scopeLabels[analysis.scope]} · 服务端未提供胜率口径` : `${scopeLabels[analysis.scope]} · 示例估计` }}</small><button class="analysis-table-button" @click="playback.playing = false; dialog = 'table'">数据表 <PixelIcon name="document" :size="14" /></button></div><div class="analysis-chart-grid" :class="`scope-${analysis.scope}`"><section v-if="analysis.scope !== 'agent'"><h3>阵营胜率（当前）</h3><AnalysisMetric v-if="analysis.scope === 'all' || analysis.camp === '狼人阵营'" label="狼人阵营" :value="analysis.estimate?.wolf ?? null" tone="wolf" /><AnalysisMetric v-if="analysis.scope === 'all' || analysis.camp === '村民阵营'" label="村民阵营" :value="analysis.estimate?.village ?? null" tone="village" /></section><section v-if="analysis.scope !== 'camp'"><h3>个人存活率 · 决策时</h3><p>{{ analysis.selectedSeat ? `${analysis.selectedSeat} 号 · ${selectedPlayer?.name}` : '尚未选择 Agent' }}</p><AnalysisMetric label="估计存活" :value="analysis.trace?.survival ?? null" :tone="analysis.agent?.role === 'WEREWOLF' ? 'wolf' : 'village'" /></section><section v-if="analysis.scope !== 'camp'"><h3>目标判断置信度</h3><p v-if="analysis.trace">{{ analysis.trace.belief.seatNo }} 号 · {{ formatTime(analysis.trace.at) }}</p><template v-if="identity.length"><AnalysisMetric v-for="item in identity" :key="item.label" :label="item.label" :value="item.value" tone="neutral" /></template><p v-else class="game-muted">暂无数据</p></section><section><h3>候选行动评分</h3><template v-if="analysis.trace"><AnalysisMetric v-for="candidate in analysis.trace.candidates" :key="candidate.id" :label="`${candidate.id} · ${candidate.label}`" :value="candidate.impact" unit="分" signed :tone="candidate.id === chosen?.id ? 'wolf' : 'neutral'" /></template><p v-else class="game-muted">暂无数据</p></section></div></section>
        </div>
        <footer class="px-panel analysis-footer"><div class="analysis-scopes" aria-label="分析视角"><button v-for="(label, key) in scopeLabels" :key="key" :aria-pressed="analysis.scope === key" :class="{ active: analysis.scope === key }" @click="analysis.scope = key"><PixelIcon :name="key === 'all' ? 'people' : key === 'camp' ? 'shield' : 'eye'" :size="17" />{{ label }}</button></div><ObservationControls fullscreen @fullscreen="fullscreen" /></footer>
        <p class="analysis-footnote">{{ analysis.summary.provenance }} 决策摘要不包含模型私有思维过程。决策耗时：{{ valueText(analysis.trace?.latencyMs, ' ms') }} · Token 用量：{{ valueText(analysis.trace?.tokens, '') }}。</p>
      </template>
      <p v-if="feedback" class="observation-feedback" role="status">{{ feedback }}</p>
    </main>
    <PixelDialog :open="!!dialog" :title="title" @update:open="value => { if (!value) dialog = null }">
      <template v-if="dialog === 'step' && analysis.trace"><p class="game-muted">{{ analysis.selectedSeat }} 号 · 第 {{ analysis.trace.round }} 天 · {{ formatTime(analysis.trace.at) }} · 示例摘要</p><ul v-if="step === 0"><li v-for="signal in analysis.trace.observations" :key="signal">{{ signal }}</li></ul><template v-else-if="step === 1"><p>{{ analysis.trace.belief.seatNo }} 号 · {{ analysis.trace.belief.label }}：{{ valueText(analysis.trace.belief.before) }} → {{ valueText(analysis.trace.belief.after) }}</p><p>{{ analysis.trace.belief.summary }}</p></template><ul v-else-if="step === 2"><li v-for="candidate in analysis.trace.candidates" :key="candidate.id"><strong>{{ candidate.id }}. {{ candidate.label }}</strong><p>{{ candidate.detail }}</p></li></ul><template v-else><p>选择 {{ chosen?.id }}：{{ chosen?.label }}</p><blockquote>{{ analysis.trace.result }}</blockquote><RouterLink :to="`/games/${route.params.gameId}/replay`" class="secondary-button px-button">查看对局回放</RouterLink></template><p class="game-muted">角色信息边界</p><ul><li v-for="constraint in analysis.agent?.constraints" :key="constraint">{{ constraint }}</li></ul><p class="game-muted">决策耗时：{{ valueText(analysis.trace.latencyMs, ' ms') }} · Token 用量：{{ valueText(analysis.trace.tokens, '') }}</p></template>
      <template v-else-if="dialog === 'table'"><p>{{ analysis.summary?.provenance }}</p><div class="analysis-data-table"><table><caption>{{ scopeLabels[analysis.scope] }} · {{ formatTime(playback.position) }}</caption><thead><tr><th scope="col">指标</th><th scope="col">示例值</th></tr></thead><tbody><template v-if="analysis.scope !== 'agent'"><tr v-if="analysis.scope === 'all' || analysis.camp === '狼人阵营'"><th scope="row">狼人阵营胜率</th><td>{{ valueText(analysis.estimate?.wolf) }}</td></tr><tr v-if="analysis.scope === 'all' || analysis.camp === '村民阵营'"><th scope="row">村民阵营胜率</th><td>{{ valueText(analysis.estimate?.village) }}</td></tr></template><template v-if="analysis.scope !== 'camp'"><tr><th scope="row">{{ analysis.selectedSeat }} 号存活率（决策时）</th><td>{{ valueText(analysis.trace?.survival) }}</td></tr><tr v-for="item in identity" :key="item.label"><th scope="row">{{ analysis.trace?.belief.seatNo }} 号{{ item.label }}置信度</th><td>{{ valueText(item.value) }}</td></tr></template><tr v-for="candidate in analysis.trace?.candidates || []" :key="candidate.id"><th scope="row">{{ candidate.label }}评分</th><td>{{ valueText(candidate.impact, ' 分') }}</td></tr></tbody></table></div></template>
      <template v-else-if="dialog === 'rules'"><p>经典九人局：3 狼人、3 村民、1 预言家、1 女巫、1 猎人。页面只读展示记录，规则与胜负由服务端决定。</p></template>
      <template v-else-if="dialog === 'audio'"><p>浏览器暂时无法播放音效，可以稍后再次开启。</p></template>
      <template v-else><p>{{ real ? 'WolfMind · 雾隐村。本页读取服务端记录的决策轨迹与对局统计：候选集与打分、工具调用、token 与耗时、是否兜底，以及发言与投票的统计。' : 'WolfMind · 雾隐村。分析页复用本地回放事件，展示手工编写的策略、结构化决策摘要和示例估计。' }}</p><p>{{ real ? '轨迹不包含模型私有推理；胜率估计、信念变化与存活率没有服务端口径，因此显示"暂无数据"而不是推测。正式数据仅在服务端授权后展示，未结束的对局不开放全知分析。' : '真实分析接口尚未接入。正式数据仅在服务端授权后展示，未结束的对局不开放全知分析。' }}</p></template>
    </PixelDialog>
  </div>
</template>
