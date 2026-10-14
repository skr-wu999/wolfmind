<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import PixelIcon from '@/components/PixelIcon.vue'
import PixelAvatar from '@/components/PixelAvatar.vue'
import PixelDialog from '@/components/PixelDialog.vue'
import GameRuleSummary from '@/components/GameRuleSummary.vue'
import PixelTitle from '@/components/PixelTitle.vue'
import FogVillageScene from '@/components/FogVillageScene.vue'
import RoomSeatBoard from '@/components/RoomSeatBoard.vue'
// 从注册表取适配器，**不直接 import mock**：原来那行直接引 `mockRoomAdapter`，
// 后果是 `VITE_USE_REAL_API=true` 时大厅走真实接口、而等待房仍显示 mock 房间——
// 用户从大厅点进一个真实房间号，看到的却是演示数据，而两边都"看起来正常"。
import { roomAdapter as selectedAdapter, usingRealBackend } from '@/api/adapters'
import { startGame } from '@/api/realRoomAdapter'
import { RoomSocket, type ConnectionState } from '@/api/gameSocket'
import { toDemoRoom } from '@/api/mapRoom'
import type { RoomView } from '@/api/restTypes'
import { useAuthStore } from '@/stores/auth'

/**
 * 当前房间适配器。
 *
 * 每次用时取一次，而不是模块加载时取一次：注册表在 `USE_REAL_API` 为真时才构造真实适配器，
 * 而模块求值发生在登录之前——取一次会让首个请求带着空身份发出。
 */
const roomAdapter = () => selectedAdapter()
import type { DemoRoom, DemoSeat } from '@/features/lobby/types'
import { useHomeDemoStore } from '@/stores/homeDemo'
import { useSeatStore } from '@/stores/session'
import { useVillageAudio } from '@/features/home/useVillageAudio'

const route = useRoute()
const router = useRouter()
const online = usingRealBackend()
const autoFillAi = ref(false)
const room = ref<DemoRoom | null>(null)
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const feedback = ref('')
const copied = ref('')
const seatOpen = ref(false)
const selectedSeatNo = ref(1)
const infoOpen = ref(false)
const infoKind = ref<'rules' | 'about' | 'audio-error' | 'settings'>('rules')
const { day } = storeToRefs(useHomeDemoStore())
let generation = 0
let active = true
const { sound, playChime, toggleSound } = useVillageAudio(() => openInfo('audio-error'))
const skeletonSeats: DemoSeat[] = Array.from({ length: 9 }, (_, i) => ({ seatNo: i + 1, name: null, isMe: false, controller: null, ready: false, avatar: 'green' }))
const seats = computed(() => room.value?.seats || skeletonSeats)
const selectedSeat = computed(() => room.value?.seats.find((seat) => seat.seatNo === selectedSeatNo.value))
const mySeat = computed(() => room.value?.seats.find((seat) => seat.isMe))
const isOwner = computed(() => room.value?.isOwner ?? (!!room.value?.mySeatNo && room.value.mySeatNo === room.value.ownerSeatNo))
const readyCount = computed(() => seats.value.filter((seat) => seat.name && seat.ready).length)
const aiCount = computed(() => seats.value.filter((seat) => seat.controller === 'AI').length)
const canStart = computed(() => online && isOwner.value && room.value?.status === 'WAITING' && !loading.value && !busy.value && readyCount.value === room.value.occupied && (room.value.occupied === 9 || autoFillAi.value))
const startHint = computed(() => room.value?.status === 'PLAYING' ? (online ? '正在进入已开始的对局。' : '本地演示不创建在线对局。') : !mySeat.value && !isOwner.value ? '请先选择空位，加入房间。' : !isOwner.value ? '等待玩家准备，由房主开始游戏。' : readyCount.value < room.value!.occupied ? '等待已入座的玩家准备。' : room.value!.occupied < 9 ? (online && autoFillAi.value ? `开局时将补齐 ${9 - room.value!.occupied} 个 AI 座位。` : `还差 ${9 - room.value!.occupied} 个座位，可由房主添加 AI。`) : online ? '全部就绪，可以开始游戏。' : '全部就绪。本地演示不创建在线对局。')
function openInfo(kind: typeof infoKind.value) { infoKind.value = kind; infoOpen.value = true }
function message(cause: unknown) { return cause instanceof Error ? cause.message : '操作暂时未能完成，请重试。' }

/**
 * 把房间里的座位号同步到会话 store。
 *
 * **这是"对局页知道我是谁"的来源**：服务端视图按可见性脱敏，但没有单独的"我"
 * （那是 `ViewerContext` 的职责），因此界面必须自己记住座位号。
 * 每次房间快照到达时同步一次，而不是只在入座时写一次——用户可能换座，
 * 也可能在另一个标签页里被换座，以快照为准才不会留下过期的座位号。
 */
const seat = useSeatStore()
function syncSeat(next: DemoRoom | null): void { seat.set(next ? next.mySeatNo : null) }

/**
 * 房间推送（websocket-protocol.md §5 ROOM_SUBSCRIBE / ROOM_SNAPSHOT）。
 * 其他成员的入座、准备、AI 补位与开局由此实时到达；开局快照带 gameId，所有在页成员据此进入对局。
 */
let roomSocket: RoomSocket | null = null
const live = ref<ConnectionState>('idle')
function watchRoom(roomId: string) {
  roomSocket?.close()
  roomSocket = null
  live.value = 'idle'
  if (!online) return
  roomSocket = new RoomSocket({
    roomId,
    onState: (state) => { live.value = state },
    onSnapshot: (view) => applySnapshot(roomId, view as unknown as RoomView),
  })
  roomSocket.connect()
}
function applySnapshot(roomId: string, view: RoomView) {
  if (!active || String(route.params.roomId) !== roomId) return
  const next = toDemoRoom(view, useAuthStore().userId)
  room.value = next; syncSeat(next)
  if (next.gameId) void router.replace(`/games/${next.gameId}`)
}

async function load(clearError = true) {
  const current = ++generation
  const roomId = String(route.params.roomId)
  loading.value = true
  if (clearError) error.value = ''
  try {
    const result = await roomAdapter().get(roomId)
    if (active && current === generation) {
      room.value = result; syncSeat(result)
      if (online && result.gameId) await router.replace(`/games/${result.gameId}`)
    }
  } catch (cause) { if (active && current === generation) error.value = message(cause) }
  finally { if (active && current === generation) loading.value = false }
}
async function mutate(action: (roomId: string) => Promise<DemoRoom>, success: string) {
  if (!room.value || busy.value || loading.value) return
  const roomId = room.value.roomId
  const current = ++generation
  busy.value = true
  error.value = ''
  feedback.value = ''
  try {
    await action(roomId)
    const snapshot = await roomAdapter().get(roomId)
    if (active && current === generation) { room.value = snapshot; syncSeat(snapshot); feedback.value = success; seatOpen.value = false }
  } catch (cause) {
    if (active && current === generation) { error.value = message(cause); await load(false) }
  } finally { if (active) busy.value = false }
}
function selectSeat(seatNo: number) { selectedSeatNo.value = seatNo; seatOpen.value = true; error.value = '' }
function takeSeat() { void mutate((id) => roomAdapter().takeSeat(id, selectedSeatNo.value), `你已加入房间，坐在 ${selectedSeatNo.value} 号座位。`) }
function setAi(enabled: boolean) { void mutate((id) => roomAdapter().setAi(id, selectedSeatNo.value, enabled), enabled ? 'AI 已加入座位，并自动准备。' : 'AI 已移出，座位可重新加入。') }
function ready() { void mutate((id) => roomAdapter().ready(id), '你已准备，等待大家入座。') }
async function start() {
  if (!canStart.value || !room.value) return
  const current = ++generation
  busy.value = true; error.value = ''
  try {
    const started = await startGame(room.value.roomId, autoFillAi.value)
    if (active && current === generation) await router.push(`/games/${started.gameId}`)
  } catch (cause) {
    if (active && current === generation) { error.value = message(cause); await load(false) }
  } finally { if (active) busy.value = false }
}
async function leave() {
  if (busy.value) return
  const current = ++generation
  busy.value = true
  try {
    if (room.value?.mySeatNo) await roomAdapter().leave(room.value.roomId)
    // 离开时必须清座位号：留着它会让下一次进入**另一个**对局时，
    // 界面以旧座位渲染操作区——用户会看到自己的按钮指向别人的座位。
    seat.clear()
    if (active && current === generation) await router.push('/lobby')
  }
  catch (cause) { if (active && current === generation) { error.value = message(cause); await load(false) } }
  finally { if (active) busy.value = false }
}
async function copyCode() {
  const roomId = room.value?.roomId
  if (!roomId) return
  try { await navigator.clipboard.writeText(roomId); if (active && room.value?.roomId === roomId) copied.value = '房间号已复制' }
  catch { if (active && room.value?.roomId === roomId) copied.value = `请手动复制房间号：${roomId}` }
}
function onFocus() { if (!busy.value && !seatOpen.value && !infoOpen.value) void load() }
watch(() => route.params.roomId, () => { room.value = null; seatOpen.value = false; feedback.value = ''; copied.value = ''; watchRoom(String(route.params.roomId)); void load() }, { immediate: true })
watch(() => useAuthStore().userId, () => { if (online && active && !busy.value) void load() })
onMounted(() => window.addEventListener('focus', onFocus))
onBeforeUnmount(() => { active = false; generation++; window.removeEventListener('focus', onFocus); roomSocket?.close(); roomSocket = null })
</script>

<template>
  <div class="landing waiting-page" :class="{ 'is-night': !day }">
    <FogVillageScene @phase-changed="playChime" />
    <header class="landing-header">
      <RouterLink to="/" class="landing-brand" aria-label="WolfMind 首页">
        <span class="brand-badge"><PixelIcon name="wolf" :size="20" /></span>
        <span>WolfMind</span>
      </RouterLink>
      <nav class="landing-nav" aria-label="主导航">
        <button class="px-link" @click="openInfo('rules')">玩法介绍</button>
        <button class="px-link" @click="openInfo('about')">关于项目</button>
        <button class="px-icon-button" :aria-label="sound ? '关闭音效' : '开启音效'" :aria-pressed="sound" @click="toggleSound"><PixelIcon :name="sound ? 'sound' : 'muted'" :size="18" /></button>
      </nav>
    </header>
    <main class="lobby-shell waiting-shell">
      <div class="lobby-intro">
        <div>
          <p class="hero-kicker">多智能体狼人杀 · 篝火旁集结</p>
          <h1 class="lobby-title"><PixelTitle text="等待开局" :size="18" /></h1>
          <p class="lobby-lead">与玩家和 AI 集结完毕，准备进入今晚的推理。</p>
        </div>
        <button class="px-button waiting-leave" :disabled="busy" @click="leave"><PixelIcon name="door" :size="18" />{{ busy ? '正在返回…' : mySeat ? '离开房间，返回大厅' : '返回游戏大厅' }}</button>
      </div>
      <div class="lobby-board waiting-board">
        <section class="px-panel waiting-panel" aria-label="房间座位与玩家" :aria-busy="loading">
          <p v-if="error && !seatOpen" class="lobby-error" role="alert">{{ error }} <button :disabled="loading || busy" @click="load()">重试</button></p>
          <p v-if="loading" class="waiting-load" role="status">正在读取房间…</p>
          <template v-if="room || loading">
            <div class="waiting-content">
              <div class="room-seating">
                <div class="action-card-heading waiting-room-heading"><span class="action-icon" aria-hidden="true"><PixelIcon name="tree" :size="20" /></span><div><div class="waiting-name-line"><h2>{{ room?.name || '村庄房间' }}</h2><span class="room-waiting-label"><i aria-hidden="true"></i>{{ room?.status === 'PLAYING' ? '已经开始' : '等待开始' }}</span></div><p>等待玩家准备，一起进入今晚的推理。</p></div></div>
                <section class="seat-layout-panel" aria-labelledby="seat-layout-title"><div class="seat-layout-heading"><h3 id="seat-layout-title"><PixelIcon name="people" :size="20" />座位布局</h3><span>{{ room?.occupied || 0 }} / 9</span><small>点击座位可查看详情</small></div><RoomSeatBoard :seats="seats" :owner-seat-no="room?.ownerSeatNo ?? null" :loading="loading && !room" @select="selectSeat" /></section>
              </div>
              <section class="waiting-player-panel" aria-labelledby="waiting-players-title"><div class="waiting-player-heading"><PixelIcon name="people" :size="20" /><h3 id="waiting-players-title">玩家列表</h3><span>{{ room?.occupied || 0 }} / 9</span></div><div class="waiting-player-table" role="table" aria-label="玩家与准备状态"><div class="waiting-player-columns" role="row"><span role="columnheader">玩家</span><span role="columnheader">身份</span><span role="columnheader">状态</span></div><div v-for="seat in seats" :key="seat.seatNo" class="waiting-player-row" role="row"><div role="cell"><button :aria-label="`查看${seat.seatNo}号座位详情`" :disabled="loading && !room" @click="selectSeat(seat.seatNo)"><PixelAvatar v-if="seat.name" :variant="seat.avatar" :size="23" /><span v-else class="empty-avatar" aria-hidden="true">+</span><span>{{ seat.name || '等待加入' }}</span><b v-if="seat.seatNo === room?.ownerSeatNo" class="player-owner">房主</b><small v-else-if="seat.isMe">你</small></button></div><span role="cell">{{ seat.name ? seat.controller === 'AI' ? 'AI' : '玩家' : '空缺' }}</span><span role="cell" class="player-ready-state" :class="{ 'is-ready': seat.ready }"><i v-if="seat.name" aria-hidden="true"></i>{{ seat.name ? seat.ready ? '已准备' : '未准备' : '空位' }}</span></div></div><div class="waiting-player-summary"><span>玩家 {{ (room?.occupied || 0) - aiCount }} · AI {{ aiCount }}</span><span>已准备 {{ readyCount }} / 9</span></div></section>
            </div>
            <div class="waiting-panel-footer"><span v-if="feedback" class="waiting-feedback" role="status">{{ feedback }}</span><span v-else-if="mySeat" class="seat-feedback" role="status">你已加入房间，坐在 {{ mySeat.seatNo }} 号座位。</span><span v-else>{{ !online ? '本地房间 · 点击空位加入' : live === 'ready' ? '在线房间 · 座位实时同步' : '在线房间 · 实时连接中，可手动刷新' }}</span><button :disabled="loading || busy" @click="load()"><PixelIcon name="refresh" :size="14" />刷新房间</button></div>
          </template>
          <div v-else class="empty-rooms"><PixelIcon name="door" :size="36" /><h3>暂时无法打开房间</h3><p>检查房间号，或返回大厅选择其他房间。</p><button class="px-button" @click="leave">返回大厅</button></div>
        </section>
        <aside v-if="room" class="waiting-actions" aria-label="房间信息与设置">
          <section class="px-panel waiting-info-panel"><div class="action-card-heading"><span class="action-icon" aria-hidden="true"><PixelIcon name="document" :size="20" /></span><div><h2>房间信息</h2></div></div><dl class="waiting-info"><div><dt><PixelIcon name="people" :size="17" />房间名称</dt><dd>{{ room.name }}</dd></div><div><dt><PixelIcon name="gamepad" :size="17" />游戏模式</dt><dd>经典九人局</dd></div><div><dt><PixelIcon name="people" :size="17" />当前人数</dt><dd>{{ room.occupied }} / 9</dd></div><div><dt><PixelIcon name="tag" :size="17" />房间号</dt><dd>{{ room.roomId }}<PixelIcon name="copy" :size="15" /></dd></div></dl><button class="px-button waiting-copy" @click="copyCode"><PixelIcon name="key" :size="17" />复制房间号</button><p v-if="copied" class="copy-feedback" role="status">{{ copied }}</p></section>
          <section class="px-panel waiting-settings-panel">
            <div class="action-card-heading"><span class="action-icon" aria-hidden="true"><PixelIcon name="settings" :size="20" /></span><div><h2>游戏设置</h2></div></div>
            <dl class="waiting-settings"><div><dt><PixelIcon name="moon" :size="17" />昼夜时长</dt><dd>服务端配置</dd></div><div><dt><PixelIcon name="people" :size="17" />角色配置</dt><dd>经典九人配置</dd></div></dl>
            <button class="settings-details" @click="openInfo('settings')"><span><PixelIcon name="document" :size="17" />进阶选项</span><span>未启用 <b aria-hidden="true">›</b></span></button>
            <button class="px-button px-button-primary ready-button" :class="{ 'is-prepared': mySeat?.ready }" :disabled="busy || loading || !mySeat || mySeat.ready || room.status !== 'WAITING'" @click="ready"><span><PixelIcon name="check" :size="21" />{{ busy ? '处理中…' : mySeat?.ready ? '已准备' : '准备' }}</span><PixelIcon name="arrow" :size="20" /></button>
            <label v-if="online && isOwner && room.status === 'WAITING'" class="room-ai-fill"><input v-model="autoFillAi" type="checkbox" :disabled="busy || loading" />用 AI 补齐空位</label>
            <button v-if="isOwner" class="px-button px-button-primary start-game-button" :disabled="!canStart" aria-describedby="start-hint" @click="start"><span><PixelIcon name="play" :size="17" />{{ busy ? '处理中…' : '开始游戏' }}</span><PixelIcon name="arrow" :size="20" /></button>
            <p id="start-hint" class="start-hint">{{ startHint }}</p><p v-if="!online" class="waiting-demo-note">本地演示 · AI 为示例座位</p>
          </section>
        </aside>
      </div>
    </main>
    <PixelDialog v-model:open="seatOpen" :title="`${selectedSeatNo} 号座位 · ${selectedSeat?.name || '等待加入'}`" eyebrow="WOLFMIND / 座位详情"><template v-if="selectedSeat"><div class="seat-detail-person"><PixelAvatar v-if="selectedSeat.name" :variant="selectedSeat.avatar" :size="54" /><PixelIcon v-else name="plus" :size="38" /><div><strong>{{ selectedSeat.name || '空位' }}</strong><p>{{ selectedSeat.name ? `${selectedSeat.controller === 'AI' ? 'AI 座位' : '玩家'} · ${selectedSeat.ready ? '已准备' : '未准备'}` : '等待玩家或 AI 加入' }}<span v-if="selectedSeat.isMe"> · 你的座位</span><span v-if="selectedSeatNo === room?.ownerSeatNo"> · 房主</span></p></div></div><p v-if="selectedSeat.controller === 'AI'" class="demo-note">{{ online ? 'AI 座位自动准备，开局后由服务端请求 AI 行动。' : 'AI 座位自动准备。当前为本地展示，不调用模型。' }}</p><p v-if="error" class="form-error" role="alert">{{ error }}</p><div class="seat-detail-actions"><button v-if="!selectedSeat.name && !mySeat && room?.status === 'WAITING'" class="lobby-button px-button px-button-primary" :disabled="busy" @click="takeSeat">{{ busy ? '正在加入…' : '加入这个座位' }}</button><button v-if="isOwner && room?.status === 'WAITING' && (!selectedSeat.name || selectedSeat.controller === 'AI')" class="secondary-button" :disabled="busy" @click="setAi(selectedSeat.controller !== 'AI')">{{ selectedSeat.controller === 'AI' ? '移出 AI' : '添加 AI 到此座位' }}</button><button v-if="selectedSeat.isMe && !selectedSeat.ready && room?.status === 'WAITING'" class="lobby-button px-button px-button-primary" :disabled="busy" @click="ready">我准备好了</button><button class="secondary-button" @click="seatOpen = false">返回房间</button></div></template></PixelDialog>
    <PixelDialog v-model:open="infoOpen" :title="infoKind === 'audio-error' ? '音效暂时无法开启' : infoKind === 'rules' || infoKind === 'settings' ? '经典九人局' : '关于 WolfMind'"><p class="dialog-description">{{ infoKind === 'audio-error' ? '当前浏览器未能启动音频，请稍后重试。' : infoKind === 'rules' || infoKind === 'settings' ? '3 狼人、3 村民、1 预言家、1 女巫、1 猎人。等待页只展示玩家和 AI 控制方式，不展示秘密身份。' : 'WolfMind 是真人与 AI 一同参与的在线狼人杀平台。' }}</p><GameRuleSummary v-if="infoKind === 'rules' || infoKind === 'settings'" :rules="room?.rules" /><p class="demo-note">{{ online ? '规则配置由服务端提供。准备完毕后，房主可开始对局。' : '当前页面为本地演示，玩家、准备和 AI 座位仅在当前浏览器会话中保存。' }}</p><button class="secondary-button" @click="infoOpen = false">返回房间</button></PixelDialog>
  </div>
</template>
