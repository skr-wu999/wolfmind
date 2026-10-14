<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { newIdempotencyKey, request } from '@/api/apiClient'
import { storeToRefs } from 'pinia'
import FogVillageScene from '@/components/FogVillageScene.vue'
import PixelDialog from '@/components/PixelDialog.vue'
import GameRuleSummary from '@/components/GameRuleSummary.vue'
import PixelIcon from '@/components/PixelIcon.vue'
import PixelAvatar from '@/components/PixelAvatar.vue'
import GameTable from '@/components/GameTable.vue'
import GameChatPanel from '@/components/GameChatPanel.vue'
import GameActionDock from '@/components/GameActionDock.vue'
import { useGameStore } from '@/stores/game'
import { useHomeDemoStore } from '@/stores/homeDemo'
import { useVillageAudio } from '@/features/home/useVillageAudio'
import { gameScenarios, phaseNames } from '@/features/game/types'
import type { GameActionType, GameCommand } from '@/features/game/types'
import { usingRealBackend } from '@/api/adapters'

const route = useRoute(), router = useRouter(), game = useGameStore()
const leaving = ref(false)
let exitKey: string | undefined
let disposed = false
const online = usingRealBackend()
const { view, loading, busy, error, connection, remaining, selected, activeAction, permission, actionable } = storeToRefs(game)
/**
 * 终局标记。
 *
 * 终局页必须能直接进入本局回放与分析：入口只放在大厅会让用户在"刚打完这一局"
 * 的时刻拿着一个 gameId 自己找路，而这正是最想看回放的时刻。
 */
const finished = computed(() => view.value?.phase === 'GAME_OVER')
const dialog = ref<'confirm' | 'leave' | 'logs' | 'skills' | 'history' | 'player' | 'rules' | 'about' | 'result' | 'audio' | null>(null)
const speechDraft = ref(''), wolfDraft = ref(''), validation = ref(''), feedback = ref('')
const inspectedSeat = ref<number | null>(null)
const pending = ref<{ gameId: string; scenario: string; type: GameActionType; params: GameCommand['params']; summary: string } | null>(null)
const actionPanel = ref<InstanceType<typeof GameActionDock> | null>(null)
const { sound, toggleSound } = useVillageAudio(() => { dialog.value = 'audio' })
const isNight = computed(() => view.value?.atmosphere === 'night')
const discussing = computed(() => ['DAY_DISCUSSION', 'LAST_WORDS'].includes(view.value?.phase || ''))
const voting = computed(() => view.value?.phase === 'DAY_VOTING')
const acting = computed(() => !discussing.value && ['NIGHT_ACTION', 'ROLE_REACTION'].includes(view.value?.phase || ''))
const inspected = computed(() => view.value?.players.find(p => p.seatNo === inspectedSeat.value))
const selectionLabel = computed(() => selected.value === 'abstain' ? activeAction.value === 'HUNTER_SHOOT' ? '放弃开枪' : '弃票' : selected.value === null ? '未选择' : `${selected.value} 号 ${view.value?.players.find(p => p.seatNo === selected.value)?.name}`)
const skillIcon = computed(() => ({ WEREWOLF: 'wolf', SEER: 'eye', WITCH: 'bottle', GUARD: 'shield', HUNTER: 'gun', VILLAGER: 'leaf' }[view.value?.me.role || 'VILLAGER']))
const dialogTitle = computed(() => ({ confirm: '确认行动', leave: '退出房间', logs: '游戏日志', skills: '技能记录', history: '公开发言记录', player: '玩家公开信息', rules: '本局规则', about: '关于 WolfMind', result: view.value?.privateResult?.title || '行动结果', audio: '音效暂时不可用' }[dialog.value || 'about']))
const phaseSubtitle = computed(() => view.value?.phase === 'GAME_OVER' ? '对局已结束，可查看公开记录和结算。' : view.value?.phase === 'LAST_WORDS' ? '听取出局玩家的最后发言。' : discussing.value ? '围坐营火，听完每一位玩家的判断。' : voting.value ? '讨论已经结束，请选择一位最可疑的玩家。' : acting.value ? '技能行动仅在自己的视角中进行。' : '保持座位，等待公开阶段更新。')


const narrow = ref(false), mobilePane = ref<'table' | 'chat'>('table')
const chatPanel = ref<InstanceType<typeof GameChatPanel> | null>(null)
let compactQuery: MediaQueryList | undefined
function compact(event: Pick<MediaQueryListEvent, 'matches'>) { narrow.value = event.matches }
onMounted(() => {
  if (typeof matchMedia === 'undefined') return
  compactQuery = matchMedia('(max-width: 850px)'); compact(compactQuery)
  compactQuery.addEventListener('change', compact)
})
async function focusTab(pane: 'table' | 'chat') { await nextTick(); document.getElementById(`game-${pane}-tab`)?.focus() }

function choosePlayer(seatNo: number) {
  if ((voting.value || acting.value) && permission.value) {
    if (!permission.value.targets.includes(seatNo)) { feedback.value = '该玩家不在本次行动的可选目标中。'; return }
    game.select(seatNo); feedback.value = ''; return
  }
  inspectedSeat.value = seatNo; dialog.value = 'player'
}
async function openSpeech() { validation.value = ''; if (view.value?.permissions.some(p => p.type === 'SPEAK') && actionable.value) { mobilePane.value = 'chat'; await nextTick(); await chatPanel.value?.focusComposer() } }
async function sendSpeech() {
  const content = speechDraft.value.trim()
  if (!content || content.length > 500) { validation.value = '请输入 1 至 500 个字符的发言。'; return }
  if (await game.submit('SPEAK', { content })) { speechDraft.value = ''; feedback.value = '发言已提交。'; await nextTick(); await chatPanel.value?.focusAccepted() }
}
async function sendWolf() {
  const content = wolfDraft.value.trim()
  if (!content || content.length > 500) { validation.value = '密谈内容需为 1 至 500 个字符。'; return }
  if (await game.submit('WOLF_CHAT', { content })) { wolfDraft.value = ''; validation.value = ''; feedback.value = '密谈已发送，仅狼人阵营可见。' }
}
function confirm(type = activeAction.value, params?: GameCommand['params']) {
  if (!view.value || !type || !actionable.value) return
  const grant = view.value.permissions.find(p => p.type === type)
  if (!grant) return
  if (!params && selected.value === null) return
  const payload = params || (selected.value === 'abstain' ? type === 'HUNTER_SHOOT' ? { giveUp: true } : { abstain: true } : { targetSeatNo: selected.value as number })
  pending.value = { gameId: view.value.gameId, scenario: view.value.scenario, type, params: payload, summary: `${grant.label}${payload.targetSeatNo ? `：${payload.targetSeatNo} 号 ${view.value.players.find(p => p.seatNo === payload.targetSeatNo)?.name}` : payload.abstain ? '：弃票' : payload.giveUp ? '：放弃开枪' : ''}` }
  dialog.value = 'confirm'
}

async function sendConfirmation() {
  const command = pending.value
  if (!command || !view.value || view.value.gameId !== command.gameId || view.value.scenario !== command.scenario) { dialog.value = null; return }
  const ok = await game.submit(command.type, command.params)
  if (ok) { pending.value = null; dialog.value = view.value?.privateResult ? 'result' : null; feedback.value = view.value?.submitted || '操作已提交。' }
}
async function focusAction() { mobilePane.value = 'table'; await nextTick(); actionPanel.value?.focus() }
async function leaveGame() {
  if (leaving.value || busy.value) return
  const gameId = String(route.params.gameId)
  leaving.value = true; error.value = ''
  try {
    if (online && view.value && view.value.me.seatNo > 0) {
      await request(`/api/v1/games/${encodeURIComponent(view.value.gameId)}/leave`, {
        method: 'POST', body: {}, idempotencyKey: exitKey ??= newIdempotencyKey('leave'),
      })
    }
    if (disposed || String(route.params.gameId) !== gameId) return
    game.disconnect(); dialog.value = null
    await router.push('/lobby')
  } catch (cause) {
    if (!disposed && String(route.params.gameId) === gameId)
      error.value = cause instanceof Error ? cause.message : '退出失败，请重试。'
  } finally { leaving.value = false }
}
function requestLeave() {
  if (view.value && view.value.me.seatNo > 0 && !finished.value) dialog.value = 'leave'
  else void leaveGame()
}
async function scenario(event: Event) { dialog.value = null; pending.value = null; feedback.value = ''; validation.value = ''; speechDraft.value = ''; wolfDraft.value = ''; await game.changeScenario((event.target as HTMLSelectElement).value) }
function tick() { game.clock = Date.now() }
const timer = setInterval(tick, 500)
function offline() { game.disconnect() }
window.addEventListener('offline', offline)
watch(() => route.params.gameId, (id) => { exitKey = undefined; dialog.value = null; speechDraft.value = ''; wolfDraft.value = ''; void game.load(String(id)) }, { immediate: true })
watch(() => view.value?.atmosphere, (phase) => { if (phase) useHomeDemoStore().phase = phase })
watch(() => `${view.value?.scenario}|${view.value?.phase}|${view.value?.round}`, () => { if (dialog.value === 'confirm') dialog.value = null })
onBeforeUnmount(() => { disposed = true; compactQuery?.removeEventListener('change', compact); clearInterval(timer); window.removeEventListener('offline', offline); game.dispose() })
</script>

<template>
  <div class="landing game-page game-conversation-page" :class="[{ 'is-night': isNight, 'is-skill-phase': acting }, view ? `role-${view.me.role.toLowerCase()}` : '']">
    <FogVillageScene :controlled-phase="view?.atmosphere || 'day'" hide-actors />
    <header class="landing-header">
      <RouterLink to="/" class="landing-brand" aria-label="WolfMind 首页">
        <span class="brand-badge"><PixelIcon name="wolf" :size="20" /></span>
        <span>WolfMind</span>
      </RouterLink>
      <nav class="landing-nav" aria-label="主导航">
        <button class="px-link" @click="dialog = 'rules'">玩法介绍</button>
        <button class="px-link" @click="dialog = 'about'">关于项目</button>
        <button class="px-icon-button" :aria-label="sound ? '关闭音效' : '开启音效'" :aria-pressed="sound" @click="toggleSound"><PixelIcon :name="sound ? 'sound' : 'muted'" :size="18" /></button>
      </nav>
    </header>
    <main class="game-main">

      <div class="game-topline px-panel">
        <div class="game-room-sign"><h1>{{ view?.roomName || '雾隐村 · 狼人游戏' }}</h1><p>{{ online ? `对局：${route.params.gameId}` : '房间号：WOLF-1024' }} <span>经典九人 · 标准局</span></p></div>
        <header v-if="view" class="game-phase-header"><div><h2 id="phase-title"><PixelIcon :name="isNight ? 'moon' : 'sun'" :size="23" />第 {{ view.round }} {{ isNight ? '夜' : '天' }} · {{ phaseNames[view.phase] }}</h2><p>{{ phaseSubtitle }}</p></div><div class="game-timer"><span><PixelIcon name="clock" :size="17" />{{ discussing ? '发言阶段' : voting ? '投票阶段' : '当前阶段' }}</span><strong>{{ view.phase === 'GAME_OVER' ? '已结束' : remaining === null ? '等待更新' : remaining === 0 ? '等待阶段更新' : `${remaining}s` }}</strong><progress v-if="view.phase !== 'GAME_OVER' && remaining !== null && view.windowMs > 0" :max="view.windowMs / 1000" :value="remaining" aria-label="本阶段剩余时间" /></div></header>
      </div>
      <details v-if="!online" class="game-demo-tools"><summary>本地演示 · 切换场景</summary><div><label for="game-scenario">场景</label><select id="game-scenario" :value="view?.scenario || 'discussion'" :disabled="loading || busy || connection !== 'local'" @change="scenario"><option v-for="[id, label] in gameScenarios" :key="id" :value="id">{{ label }}</option></select><button v-if="connection === 'local'" class="demo-network" @click="game.disconnect()">模拟断线</button></div></details>
      <div v-if="connection !== 'local' && view" class="game-connection" role="status"><PixelIcon name="refresh" :size="20" /><span>{{ connection === 'syncing' ? '正在同步对局，行动暂时禁用。' : '连接已中断，已保留当前画面与输入。' }}</span><button class="secondary-button px-button" :disabled="connection === 'syncing'" @click="game.reconnect()">重新同步</button></div>
      <div v-if="error" class="game-error" role="alert">{{ error }}<button v-if="!view" class="secondary-button px-button" @click="game.load(String(route.params.gameId))">重试</button></div>
      <section v-if="loading && !view" class="px-panel game-loading" aria-busy="true"><h2>正在加载对局与行动权限…</h2><div class="game-skeleton-seats"><span v-for="n in 9" :key="n">{{ n }} 号座位</span></div></section>
      <section v-else-if="!view" class="px-panel game-unavailable"><h2>暂时无法进入对局</h2><p>{{ online ? '请检查对局地址和网络连接，再重试。' : '可以先体验示例对局中的游戏交互。' }}</p><RouterLink v-if="!online" class="secondary-button px-button" to="/games/demo">打开示例对局</RouterLink><RouterLink class="secondary-button px-button" to="/lobby">返回游戏大厅</RouterLink></section>

      <nav v-if="view" class="game-mobile-tabs" role="tablist" aria-label="对局面板"><button id="game-table-tab" role="tab" :aria-selected="mobilePane === 'table'" :tabindex="mobilePane === 'table' ? 0 : -1" aria-controls="game-table-view" @click="mobilePane = 'table'" @keydown.right.prevent="mobilePane = 'chat'; focusTab('chat')">游戏桌</button><button id="game-chat-tab" role="tab" :aria-selected="mobilePane === 'chat'" :tabindex="mobilePane === 'chat' ? 0 : -1" aria-controls="game-chat-view" @click="mobilePane = 'chat'" @keydown.left.prevent="mobilePane = 'table'; focusTab('table')">群聊</button></nav>
      <div v-if="view" class="game-layout">
        <div class="game-left">
          <section v-show="!narrow || mobilePane === 'table'" id="game-table-view" class="px-panel game-table-panel" aria-label="游戏桌">
            <div class="game-table-heading"><h2>篝火会议</h2><span>存活 {{ view.aliveCount }} / {{ view.players.length }} · 已淘汰 {{ view.eliminatedCount }}</span></div>
            <GameTable :players="view.players" :phase="view.atmosphere" :me="view.me.seatNo" :speaker="discussing ? view.speakerSeatNo : null" :speaker-label="view.preview ? '发言中' : '当前回合'" :selected="selected" :targets="permission?.targets || []" :selecting="(voting || acting) && actionable && !!permission" @choose="choosePlayer" />
            <p class="table-footnote">{{ voting || acting ? `当前选择：${selectionLabel}` : '点击玩家查看公开信息 · 投票与技能阶段可选择目标' }}</p>
            <div v-if="view.result || ['NIGHT_TRANSITION', 'NIGHT_RESOLVING'].includes(view.phase)" class="game-stage-result" role="status"><PixelIcon :name="isNight ? 'moon' : 'sun'" :size="32" /><h3>{{ view.result?.title || (view.phase === 'NIGHT_TRANSITION' ? '天黑了，请闭眼' : '等待天亮') }}</h3><p>{{ view.result?.content || '雾隐村陷入沉睡。等待公开阶段更新。' }}</p><button v-if="!online && view.phase === 'DAYBREAK'" class="lobby-button px-button px-button-primary" @click="game.changeScenario('discussion')">继续演示讨论</button></div>
          </section>
          <GameActionDock ref="actionPanel" :view="view" :actionable="actionable" :busy="busy" :remaining="remaining" :permission="permission" :active-action="activeAction" :selected="selected" :voting="voting" :discussing="discussing" :acting="acting" :icon="skillIcon" :online="online" @select="game.select" @action="game.chooseAction" @confirm="confirm" @speech="openSpeech" @logs="dialog = 'logs'" @skills="dialog = 'skills'" @result="dialog = 'result'" @restart="game.changeScenario('discussion')" />
        </div>
        <GameChatPanel v-show="!narrow || mobilePane === 'chat'" id="game-chat-view" ref="chatPanel" v-model:speech-draft="speechDraft" v-model:wolf-draft="wolfDraft" :view="view" :actionable="actionable" :busy="busy" :error="error" :validation="validation" :active="!narrow || mobilePane === 'chat'" @speech="sendSpeech" @wolf="sendWolf" @history="dialog = 'history'" />
      </div>
      <div v-if="feedback" class="game-feedback" role="status">{{ feedback }}</div>
      <footer class="game-footer px-panel"><button class="px-button game-leave" :disabled="busy || leaving" @click="requestLeave"><PixelIcon name="door" :size="16" />{{ leaving ? '正在退出…' : view && view.me.seatNo > 0 ? '退出房间并返回大厅' : '返回游戏大厅' }}</button><span>{{ online ? '在线对局 · 阶段、行动权限和结果由服务端同步。' : '本地 UI 演示 · 发言、投票、技能及结果均为示例。' }}</span><RouterLink v-if="finished" class="px-link" :to="`/games/${route.params.gameId}/replay`">本局回放</RouterLink><RouterLink v-if="finished" class="px-link" :to="`/games/${route.params.gameId}/analysis`">AI 分析</RouterLink><button class="px-link" @click="dialog = 'rules'">查看规则</button></footer>
    </main>

    <PixelDialog :class="{ 'game-log-drawer': dialog === 'logs' }" :open="dialog !== null" :title="dialogTitle" :eyebrow="dialog === 'skills' || dialog === 'result' ? '仅自己可见' : dialog === 'logs' || dialog === 'history' ? '公开信息' : undefined" @update:open="value => { if (!value) dialog = null }" @focus-lost="actionPanel?.focus()">
      <template v-if="dialog === 'leave'">
        <p>退出后，你的角色将由 AI 接管。所有真人都退出后，本局会中止，不计胜负。</p>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <div class="game-dialog-actions"><button class="secondary-button px-button" :disabled="leaving" @click="dialog = null">继续游戏</button><button class="lobby-button px-button px-button-primary" :disabled="leaving || busy" @click="leaveGame">{{ leaving ? '正在退出…' : '确认退出房间' }}</button></div>
      </template>
      <template v-else-if="dialog === 'confirm'">
        <p class="game-panel-description">{{ pending?.summary }}</p><p class="game-muted">提交后不能修改本次选择，请确认目标。</p>
        <p v-if="remaining === 0" role="status" class="form-error">行动时间已到，等待阶段更新。</p><p v-else-if="connection !== 'local'" role="status" class="form-error">连接已中断。关闭窗口后重新同步，再确认行动。</p>
        <p v-if="error" role="alert" class="form-error">{{ error }}</p>
        <div class="game-dialog-actions"><button class="secondary-button px-button" :disabled="busy" @click="dialog = null">取消</button><button class="lobby-button px-button px-button-primary" :disabled="!actionable" @click="sendConfirmation">{{ busy ? '提交中…' : '确认提交' }}</button></div>
      </template>
      <template v-else-if="dialog === 'history' || dialog === 'player'"><div v-if="dialog === 'player' && inspected" class="player-public-detail"><PixelAvatar :variant="inspected.avatar" :size="48" /><strong>{{ inspected.seatNo }} 号 {{ inspected.name }}</strong><span>{{ inspected.controller === 'AI' ? 'AI 玩家' : '真人玩家' }} · {{ inspected.alive ? '存活' : '已淘汰' }}</span></div><div class="game-history"><p v-for="message in view?.messages.filter(m => dialog !== 'player' || m.seatNo === inspectedSeat)" :key="message.id"><strong>第 {{ message.round }} 天 · {{ message.seatNo }} 号 {{ view?.players.find(p => p.seatNo === message.seatNo)?.name }}</strong><span>{{ message.content }}</span></p><p v-if="!view?.messages.some(m => dialog !== 'player' || m.seatNo === inspectedSeat)">还没有已提交的公开发言。</p></div><p v-if="dialog === 'player'" class="game-muted">职业与私有行动不会在公开信息中展示。</p></template>
      <template v-else-if="dialog === 'logs' || dialog === 'skills'"><div class="game-history"><p v-for="record in (dialog === 'logs' ? view?.records : view?.privateRecords)" :key="record.id"><small>{{ record.scope === 'PUBLIC' ? '公开' : record.scope === 'WOLF' ? '狼人阵营可见' : '仅自己可见' }}</small><span>{{ record.text }}</span></p><p v-if="!(dialog === 'logs' ? view?.records.length : view?.privateRecords.length)">{{ dialog === 'logs' ? '本局尚无公开记录。' : '你还没有可查看的技能记录。' }}</p></div></template>
      <template v-else-if="dialog === 'result'"><div class="game-private-result"><PixelIcon name="eye" :size="40" /><p>{{ view?.privateResult?.content }}</p></div><button class="secondary-button px-button full-width" @click="dialog = null">我已知晓</button></template>
      <template v-else-if="dialog === 'rules'"><div class="about-content"><p>经典九人：3 狼人、3 村民、1 预言家、1 女巫、1 猎人。</p><GameRuleSummary :rules="view?.rules" /><p>白天按发言顺序讨论，再进行放逐投票；夜晚仅显示本人有权限的行动。AI 头像与动物形象不表示职业。</p><p>{{ online ? '阶段与计时以服务端为准。倒计时结束后请等待同步更新。' : '本地演示不自动推进阶段。可使用「场景」选择器体验各状态。' }}</p><p v-if="!online">守卫仅为扩展职业 UI 预览，不属于当前九人规则。</p><p>技能结果、胜负和出局事实以当前对局视图为准。</p></div></template>
      <template v-else-if="dialog === 'about'"><div class="about-content"><p>WolfMind 是真人与 AI 一同参与的狼人杀平台。</p><p>{{ online ? '当前页面通过在线服务获取授权的对局视图和实时更新。' : '当前页面用于展示核心游戏交互，使用本地示例数据。' }}</p></div></template>
      <template v-else><p class="dialog-description">浏览器暂时无法播放音效，仍可正常体验游戏页面。</p></template>
    </PixelDialog>
  </div>
</template>
