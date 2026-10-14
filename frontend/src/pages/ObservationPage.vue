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
import { useObservationStore } from '@/stores/observation'
import { useHomeDemoStore } from '@/stores/homeDemo'
import { useVillageAudio } from '@/features/home/useVillageAudio'
import { roleNames } from '@/features/game/types'
import { formatTime, isNightPhase, scopeNames } from '@/features/observation/types'
import type { ObservationEvent } from '@/features/observation/types'
import { projectObservation } from '@/features/observation/projection'
import { USE_REAL_API } from '@/api/config'
import { useAuthStore } from '@/stores/auth'

const props = defineProps<{ mode: 'watch' | 'replay' }>()
const route = useRoute(), player = useObservationStore()
const replay = computed(() => props.mode === 'replay')
const online = USE_REAL_API, live = computed(() => online && !replay.value)
const fullRecords = computed(() => !online || player.match?.snapshot?.finished)
const availableScopes = computed(() => fullRecords.value ? scopeNames : { PUBLIC: scopeNames.PUBLIC })
const connectionLabel = computed(() => ({ connecting: '正在连接', syncing: '正在同步', live: '实时同步', reconnecting: '正在重连', closed: '连接已断开', 'login-required': '登录后实时同步', finished: '对局已结束' }[player.connection]))
const auth = useAuthStore()
function reload() { return player.load(String(route.params.gameId), live.value) }
const replayResult = computed(() => player.match ? projectObservation(player.match, player.match.duration).winner || '暂无结果' : '暂无结果')
const dialog = ref<'rules' | 'about' | 'event' | 'seat' | 'summary' | 'audio' | null>(null)
const detail = ref<ObservationEvent | null>(null), selectedSeat = ref<number | null>(null), speechDay = ref('all')
const feedback = ref(''), stage = ref<HTMLElement | null>(null)
const timeline = ref<HTMLElement | null>(null), following = ref(true)
const { sound, toggleSound } = useVillageAudio(() => { dialog.value = 'audio' })
const night = computed(() => player.view?.atmosphere === 'night')
const speaker = computed(() => player.view?.players.find(p => p.seatNo === player.view?.speaker))
const inspected = computed(() => player.view?.players.find(p => p.seatNo === selectedSeat.value))
const currentSpeech = computed(() => player.speeches.find(event => event.seatNo === player.view?.speaker && eventRound(event) === player.view?.round) || null)
// 下一位还没开口时继续显示本轮最近一条发言，与对局页一致。
const shownSpeech = computed(() => currentSpeech.value || (night.value ? null : player.speeches.find(event => eventRound(event) === player.view?.round) || null))
const shownSpeaker = computed(() => player.view?.players.find(p => p.seatNo === shownSpeech.value?.seatNo))
const history = computed(() => player.speeches.filter(event => speechDay.value === 'all' || eventRound(event) === Number(speechDay.value)).slice(0, 4))
const pastEvents = computed(() => player.visibleEvents.filter(event => event.at <= player.position))
const eventCursor = computed(() => pastEvents.value.at(-1)?.id)
const perspectivePlayers = computed(() => player.view?.players.map(p => ({ ...p, disclosedRole: player.perspective === 'public' || player.perspective !== 'all' && Number(player.perspective) !== p.seatNo ? undefined : p.disclosedRole })) || [])
/**
 * 角色标签。
 *
 * `disclosedRole` 现在是**可选**的：对局未结束时后端不下发角色
 * （观战页是公开路由，披露未结束对局的身份会泄漏隐藏信息）。
 * 因此这里必须处理"没有角色"这一情况，而不是直接拿 `undefined` 去索引名字表。
 *
 * 未披露时显示"未披露"——**不显示一个猜测的角色名**。
 */
function roleLabelOf(role: string | undefined): string {
  if (!role) return '未披露'
  return roleNames[role as keyof typeof roleNames] ?? role
}
/** 当前视角对应的玩家；视角是 `all`/`public` 时没有对应玩家。 */
const perspectivePlayer = computed(() =>
  player.perspective === 'all' || player.perspective === 'public'
    ? null
    : player.match?.players.find(p => p.seatNo === Number(player.perspective)) ?? null)
const dialogTitle = computed(() => ({ rules: '经典九人局', about: online ? '关于在线观战' : '关于这场演练', event: detail.value?.title || '事件详情', seat: inspected.value?.name || '玩家信息', summary: '当前决策摘要', audio: '音效暂时不可用' }[dialog.value || 'about']))
let timer: ReturnType<typeof setInterval> | undefined, previousTime = 0
function eventRound(event: ObservationEvent) { return player.match?.events.filter(e => e.version <= event.version && e.change?.round).at(-1)?.change?.round || 1 }
function openEvent(event: ObservationEvent) { detail.value = event; dialog.value = 'event' }
function inspectSeat(seatNo: number) { selectedSeat.value = seatNo; dialog.value = 'seat' }
function seekEvent(event: ObservationEvent) { player.seek(event.at) }
async function moveTimeline(event: KeyboardEvent, index: number) {
  const keys = ['ArrowUp', 'ArrowDown', 'Home', 'End']
  if (!keys.includes(event.key)) return
  event.preventDefault()
  const events = player.visibleEvents
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? events.length - 1 : Math.max(0, Math.min(events.length - 1, index + (event.key === 'ArrowDown' ? 1 : -1)))
  if (!events[next]) return
  const list = (event.currentTarget as HTMLElement).parentElement?.parentElement
  player.seek(events[next]!.at)
  await nextTick()
  const buttons = list?.querySelectorAll<HTMLButtonElement>('.event-timeline-button')
  buttons?.[next]?.focus()
}
async function fullscreen() {
  try {
    if (document.fullscreenElement) await document.exitFullscreen()
    else if (stage.value?.requestFullscreen) await stage.value.requestFullscreen()
    else feedback.value = '当前浏览器不支持全屏，可使用浏览器的全屏模式。'
  } catch { feedback.value = '全屏暂时不可用，请使用浏览器的全屏模式。' }
}
async function copyId() {
  if (!detail.value) return
  try { await navigator.clipboard.writeText(detail.value.id); feedback.value = '事件 ID 已复制。' }
  catch { feedback.value = '复制不可用，可选中下方事件 ID 手动复制。' }
}
watch(() => [route.params.gameId, props.mode], () => {
  dialog.value = null; selectedSeat.value = null; detail.value = null; speechDay.value = 'all'; feedback.value = ''
  void reload()
}, { immediate: true })
watch(() => auth.userId, () => { if (online) { dialog.value = null; detail.value = null; void reload() } })
watch(() => player.view?.atmosphere, phase => { if (phase) useHomeDemoStore().phase = phase })
watch(() => player.perspective, () => { dialog.value = null; detail.value = null; selectedSeat.value = null; player.scope = 'all' })
watch(() => player.playing, () => { previousTime = performance.now() })
watch(eventCursor, async () => {
  if (!following.value || !(replay.value || live.value)) return
  await nextTick()
  const list = timeline.value, active = list?.querySelector<HTMLElement>('[aria-current="step"]')
  if (list && active) {
    const item = active.getBoundingClientRect(), bounds = list.getBoundingClientRect()
    if (item.top < bounds.top || item.bottom > bounds.bottom) list.scrollTop += item.top - bounds.top - list.clientHeight / 2 + item.height / 2
  }
})
onMounted(() => {
  previousTime = performance.now()
  timer = setInterval(() => {
    const now = performance.now()
    player.tick((now - previousTime) / 1000)
    previousTime = now
  }, 250)
})
onBeforeUnmount(() => { clearInterval(timer); player.dispose() })
</script>

<template>
  <div class="landing inner-page observation-page" :class="{ 'observation-replay': replay, 'observation-night': night, 'is-night': night }">
    <FogVillageScene :controlled-phase="night ? 'night' : 'day'" hide-actors />
    <VillageHeader :sound="sound" @sound="toggleSound" @info="dialog = $event" />
    <main class="observation-main">
      <nav class="observation-nav" aria-label="对局导航"><RouterLink :to="`/watch/${route.params.gameId}`" :aria-current="!replay ? 'page' : undefined">{{ online ? '对局观战' : 'AI 对局' }}</RouterLink><RouterLink :to="`/games/${route.params.gameId}/replay`" :aria-current="replay ? 'page' : undefined">游戏回放</RouterLink><RouterLink v-if="!online" :to="`/games/${route.params.gameId}/analysis`">AI 分析</RouterLink><RouterLink to="/lobby">游戏大厅</RouterLink></nav>
      <div class="observation-heading"><span class="location-label">{{ replay ? 'GAME REPLAY' : 'WATCH GAME' }}</span><h1><PixelTitle :text="replay ? '游戏回放' : online ? '对局观战' : 'AI 对局观战'" :size="14" /></h1><p>{{ online ? '进行中查看公开记录，对局结束后查看完整角色与事件。' : replay ? '回顾营火旁的每次选择，沿事件时间线重访这场对局。' : '观看 AI 在雾隐村里的博弈，听见不同的判断与策略。' }}</p><span class="observation-demo">{{ online ? fullRecords ? '赛后完整记录 · 只读观看' : '公开观战 · 只读观看' : `本地${replay ? '回放' : '观战'}示例 · 九人 AI 对局` }}</span></div>
      <section v-if="player.loading" class="px-panel observation-state" role="status"><h2>正在载入{{ replay ? '事件记录' : 'AI 对局' }}…</h2><p>等待游戏桌与玩家信息。</p></section>
      <section v-else-if="player.error" class="px-panel observation-state" role="alert"><h2>暂时无法查看对局</h2><p>{{ player.error }}</p><button class="secondary-button px-button" @click="reload">重试</button><RouterLink v-if="!online" :to="replay ? '/games/demo/replay' : '/watch/demo'" class="secondary-button px-button">打开本地示例</RouterLink><RouterLink v-if="online" :to="`/games/${route.params.gameId}`" class="secondary-button px-button">返回对局</RouterLink><RouterLink to="/lobby" class="secondary-button px-button">返回大厅</RouterLink></section>
      <div v-else-if="player.match && player.view" class="observation-layout">
        <aside class="px-panel observation-left">
          <section class="observation-panel match-information">
            <h2>{{ replay ? '回放信息' : '对局信息' }}</h2>
            <dl>
              <div><dt>{{ online ? '对局 ID' : '房间名称' }}</dt><dd>{{ online ? player.match.gameId : player.match.roomName }}</dd></div>
              <div><dt>游戏模式</dt><dd>经典 9 人局</dd></div>
              <div v-if="!replay"><dt>当前阶段</dt><dd>第 {{ player.view.round }} 天 · {{ player.view.phase }}</dd></div>
              <div v-if="replay"><dt>开始时间</dt><dd>{{ player.match.startedAt }}</dd></div>
              <div><dt>记录时长</dt><dd>{{ formatTime(replay ? player.match.duration : player.position) }}</dd></div>
              <div><dt>{{ replay ? '对局结果' : 'AI 玩家' }}</dt><dd>{{ replay ? replayResult : `${player.match.players.filter(p => p.controller === 'AI').length} / ${player.match.players.length}` }}</dd></div>
            </dl>
            <div v-if="!replay && !online" class="match-progress"><label for="watch-progress">对局进度</label><progress id="watch-progress" :value="player.position" :max="player.match.duration" /><small>第 {{ player.view.round }} 天（共 3 天）</small></div>
            <button v-if="replay" class="observation-text-button" @click="dialog = 'rules'">查看对局配置 <PixelIcon name="arrow" :size="16" /></button>
          </section>
          <section v-if="!replay" class="observation-panel observation-roster"><h2>玩家列表 <small>{{ player.view.players.filter(p => p.alive).length }} 人存活</small></h2><ul><li v-for="person in perspectivePlayers" :key="person.seatNo"><button @click="inspectSeat(person.seatNo)"><PixelAvatar :variant="person.avatar" :size="25" /><span class="roster-seat">{{ String(person.seatNo).padStart(2, '0') }}</span><strong>{{ person.name }}</strong><span v-if="person.disclosedRole" class="observation-role" :class="person.disclosedRole.toLowerCase()">{{ roleNames[person.disclosedRole] }}</span><span class="roster-life" :class="{ eliminated: !person.alive }" :aria-label="person.alive ? '存活' : '已出局'">{{ person.alive ? '●' : '×' }}</span></button></li></ul><p class="roster-legend">● 存活 <span>× 已出局</span></p></section>
          <section v-else class="observation-panel replay-timeline"><div class="observation-panel-title"><h2>事件时间线</h2><label><span class="sr-only">事件类型</span><select v-model="player.scope"><option value="all">全部事件</option><option v-for="(label, value) in scopeNames" :key="value" :value="value">{{ label }}事件</option></select></label></div><ol v-if="player.visibleEvents.length" ref="timeline" class="event-timeline"><li v-for="(event, index) in player.visibleEvents" :key="event.id"><button class="event-timeline-button" :class="{ current: event.id === eventCursor, future: event.at > player.position }" :aria-current="event.id === eventCursor ? 'step' : undefined" @click="seekEvent(event)" @keydown="moveTimeline($event, index)"><span class="event-node"><PixelIcon :name="event.type === 'PLAYER_SPOKE' ? 'speech' : event.change?.eliminated ? 'skull' : isNightPhase(event.change?.phase) ? 'moon' : event.scope === 'PRIVATE' ? 'key' : 'sun'" :size="13" /></span><time>{{ formatTime(event.at) }}</time><span><strong>{{ event.title }}</strong><small>v{{ event.version }} · {{ scopeNames[event.scope] }}</small></span></button></li></ol><p v-else class="game-muted">该视角与筛选条件下没有事件。</p><button class="timeline-follow observation-text-button" :aria-pressed="following" @click="following = !following">{{ following ? '暂停自动跟随' : '开启自动跟随' }}</button><div class="timeline-endpoints"><button class="observation-text-button" @click="player.seek(0)">回到开局</button><button class="observation-text-button" @click="player.seek(player.match.duration)">跳到结局</button></div></section>
        </aside>
        <section ref="stage" class="px-panel observation-stage" aria-label="游戏过程">
          <div class="observation-phase"><PixelIcon :name="night ? 'moon' : player.view.winner ? 'check' : 'sun'" :size="32" /><div><h2>{{ player.view.winner || `第 ${player.view.round} 天 · ${player.view.phase}` }}</h2><p>{{ night ? '村庄入夜，等待公开结果。' : player.view.winner ? '对局已结束，可以查看完整记录。' : '围坐营火，听见每一位玩家的判断。' }}</p></div></div>
          <GameTable :players="perspectivePlayers" :phase="player.view.atmosphere" :me="0" :speaker="player.view.speaker" :selected="null" :targets="[]" :selecting="false" @choose="inspectSeat" />
          <ObservationControls v-if="replay" fullscreen @fullscreen="fullscreen" />
        </section>
        <aside v-if="!replay" class="px-panel observation-right">
          <section class="observation-panel observation-speech"><h2>当前发言 <small>{{ live ? connectionLabel : player.playing ? '播放中' : '已暂停' }}</small></h2><template v-if="shownSpeaker && shownSpeech"><div class="observation-speaker"><PixelAvatar :variant="shownSpeaker.avatar" :size="47" /><div><strong>{{ String(shownSpeaker.seatNo).padStart(2, '0') }} {{ shownSpeaker.name }}</strong><span>{{ shownSpeech === currentSpeech ? '' : '上一位 · ' }}{{ shownSpeaker.controller === 'HUMAN' ? '真人' : 'AI' }} · 第 {{ player.view.round }} 天</span></div><PixelIcon name="microphone" :size="19" /></div><p>{{ shownSpeech.content }}</p><div class="speech-tags"><span>{{ scopeNames[shownSpeech.scope] }}发言</span></div></template><p v-else class="game-muted">{{ night ? '夜间没有公开发言，等待天亮公布结果。' : '当前阶段暂无已提交发言。' }}</p></section>
          <section class="observation-panel observation-history"><div class="observation-panel-title"><h2>发言记录</h2><label><span class="sr-only">发言日期</span><select v-model="speechDay"><option value="all">全部天数</option><option v-for="day in player.view.round" :key="day" :value="String(day)">第 {{ day }} 天</option></select></label></div><ul><li v-for="speech in history" :key="speech.id"><button @click="openEvent(speech)"><PixelAvatar :variant="player.match.players.find(p => p.seatNo === speech.seatNo)!.avatar" :size="28" /><span><strong>{{ speech.seatNo }} 号 {{ player.match.players.find(p => p.seatNo === speech.seatNo)?.name }}</strong><small>{{ speech.content }}</small></span><time>{{ formatTime(speech.at) }}</time></button></li></ul><p v-if="!history.length" class="game-muted">当前没有已播放的公开发言。</p></section>
          <section v-if="live" class="observation-panel replay-timeline">
            <div class="observation-panel-title"><h2>事件记录</h2><label><span class="sr-only">事件类型</span><select v-model="player.scope"><option value="all">全部可见事件</option><option v-for="(label, value) in availableScopes" :key="value" :value="value">{{ label }}事件</option></select></label></div>
            <ol v-if="player.visibleEvents.length" ref="timeline" class="event-timeline"><li v-for="(event, index) in player.visibleEvents" :key="event.id"><button class="event-timeline-button" :aria-current="event.id === eventCursor ? 'step' : undefined" @click="openEvent(event)" @keydown="moveTimeline($event, index)"><span class="event-node"><PixelIcon name="document" :size="13" /></span><time>{{ formatTime(event.at) }}</time><span><strong>{{ event.title }}</strong><small>v{{ event.version }} · {{ scopeNames[event.scope] }}</small></span></button></li></ol><p v-else class="game-muted">暂无可见事件。</p>
            <button class="timeline-follow observation-text-button" :aria-pressed="following" @click="following = !following">{{ following ? '暂停自动跟随' : '开启自动跟随' }}</button>
          </section>
          <section v-else-if="!online" class="observation-panel observation-analysis"><h2><PixelIcon name="settings" :size="18" /> AI 决策摘要</h2><p>{{ currentSpeech?.rationale || '当前阶段暂无决策摘要，可回到发言事件查看示例说明。' }}</p><button class="observation-text-button" :disabled="!currentSpeech?.rationale" @click="dialog = 'summary'">查看详情 <PixelIcon name="arrow" :size="16" /></button></section>
        </aside>
        <div v-if="replay" class="px-panel replay-detail-row">
          <section class="observation-panel replay-event-detail"><h2>关键事件回放 <small>{{ player.currentEvent ? formatTime(player.currentEvent.at) : '暂无事件' }}</small></h2><template v-if="player.currentEvent"><div class="replay-current-event"><PixelIcon name="document" :size="36" /><div><strong>{{ player.currentEvent.title }}</strong><span>{{ scopeNames[player.currentEvent.scope] }} · v{{ player.currentEvent.version }}</span><p>{{ player.currentEvent.content }}</p></div><button aria-label="查看当前事件详情" @click="openEvent(player.currentEvent)"><PixelIcon name="arrow" :size="23" /></button></div></template><p v-else class="game-muted">该区间没有事件。调整进度或事件筛选。</p></section>
          <section class="observation-panel replay-perspective"><h2>玩家视角</h2>
            <label><span class="sr-only">玩家视角切换</span><select v-model="player.perspective">
              <option value="all">{{ online ? fullRecords ? '完整记录 · 赛后' : '公开记录 · 进行中' : '上帝视角 · 赛后' }}</option>
              <option v-if="fullRecords" value="public">公开记录</option>
              <template v-if="fullRecords"><option v-for="person in player.match.players" :key="person.seatNo" :value="String(person.seatNo)">{{ person.seatNo }} 号 {{ person.name }}</option></template>
            </select></label>
            <div v-if="perspectivePlayer" class="perspective-identity"><PixelAvatar :variant="perspectivePlayer.avatar" :size="34" /><span>{{ roleLabelOf(perspectivePlayer.disclosedRole) }}<small>仅展示公开、本人及所属阵营记录</small></span></div>
            <p v-else class="game-muted">{{ online ? fullRecords && player.perspective === 'all' ? '对局已结束，完整角色与事件由服务端提供。' : '仅展示公开记录。' : player.perspective === 'all' ? '赛后示例包含全部角色与事件。' : '仅展示公开事件，隐藏角色标签。' }}</p>
          </section>
        </div>
      </div>
      <div v-if="!replay && player.match && !player.loading" class="px-panel watch-bottom"><ObservationControls v-if="!live" /><div v-else role="status"><strong>{{ connectionLabel }}</strong><span> · v{{ player.match.snapshot?.version }}</span><p v-if="player.syncError">{{ player.syncError }}</p><RouterLink v-if="player.connection === 'login-required'" class="secondary-button px-button" :to="{ path: '/login', query: { redirect: route.fullPath } }">登录后实时同步</RouterLink><button class="secondary-button px-button" @click="reload">刷新记录</button></div><label><span>视角</span><select v-model="player.perspective" aria-label="观战视角"><option value="all">{{ online ? fullRecords ? '完整记录 · 赛后' : '公开记录 · 进行中' : '上帝视角 · 示例' }}</option><option v-if="fullRecords" value="public">公开记录</option></select></label></div>
      <p v-if="feedback" class="observation-feedback" role="status">{{ feedback }}</p>
      <p v-if="player.match && !player.loading" class="observation-footnote">{{ online ? fullRecords ? '对局已结束，完整角色与事件由服务端授权提供。' : '进行中只展示公开记录，角色与夜间选择在结束后披露。' : '脚本与事件均为本地演示；正式观战、回放数据尚未接入。' }}</p>
    </main>
    <PixelDialog :open="!!dialog" :title="dialogTitle" @update:open="value => { if (!value) dialog = null }">
      <template v-if="dialog === 'event' && detail"><p class="game-muted">{{ formatTime(detail.at) }} · v{{ detail.version }} · {{ scopeNames[detail.scope] }} · {{ detail.type }}</p><p class="game-panel-description">{{ detail.content }}</p><p v-if="detail.rationale" class="game-panel-description">{{ detail.rationale }}</p><p class="event-id">{{ detail.id }}</p><button class="secondary-button px-button" @click="copyId">复制事件 ID</button><button v-if="!live" class="secondary-button px-button" @click="player.seek(detail.at); dialog = null">跳到此事件</button><p v-if="feedback" role="status">{{ feedback }}</p></template>
      <template v-else-if="dialog === 'seat' && inspected"><div class="player-public-detail"><PixelAvatar :variant="inspected.avatar" :size="50" /><strong>{{ inspected.seatNo }} 号 {{ inspected.name }}</strong><span>{{ inspected.controller === 'HUMAN' ? '真人' : 'AI' }} · {{ inspected.alive ? '存活' : '已出局' }}</span></div><p v-if="perspectivePlayers.find(p => p.seatNo === inspected?.seatNo)?.disclosedRole">角色：{{ roleNames[inspected.disclosedRole!] }}</p><p v-else class="game-muted">当前视角不展示该玩家的角色。</p><p class="game-panel-description">只读观看玩家信息，可在事件时间线中查看相关发言。</p></template>
      <template v-else-if="dialog === 'summary'"><p class="game-panel-description">{{ currentSpeech?.rationale }}</p><p class="game-muted">这是本地编写的决策示例，不是模型的私有思维过程。真实对局将展示已授权的结构化决策摘要。</p></template>
      <template v-else-if="dialog === 'rules'"><p class="game-panel-description">经典九人局：3 名狼人、3 名村民、1 名预言家、1 名女巫、1 名猎人。</p><p class="game-panel-description">{{ online ? '技能、投票和胜负均以服务端记录为准。进行中仅公开观战，结束后披露完整记录。' : '本页只展示已有示例事件，不计算技能、投票或胜负；正式过程以服务端记录为准。' }}</p></template>
      <template v-else-if="dialog === 'audio'"><p class="game-panel-description">浏览器暂时无法播放音效，可以稍后再次开启。</p></template>
      <template v-else><p class="game-panel-description">{{ online ? 'WolfMind · 雾隐村。在线观战展示服务端授权的角色、状态与已提交事件。' : 'WolfMind · 雾隐村。本地只读示例用于体验 AI 对局、发言记录和事件回放。真实模型与服务器数据尚未接入。' }}</p></template>
    </PixelDialog>
  </div>
</template>
