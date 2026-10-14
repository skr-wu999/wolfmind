<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { RouterLink, useRouter } from 'vue-router'
import PixelIcon from '@/components/PixelIcon.vue'
import PixelDialog from '@/components/PixelDialog.vue'
import PixelRole from '@/components/PixelRole.vue'
import PixelTitle from '@/components/PixelTitle.vue'
import FogVillageScene from '@/components/FogVillageScene.vue'
import { useHomeDemoStore } from '@/stores/homeDemo'
import { useLobbyStore } from '@/stores/lobby'
import { useAuthStore } from '@/stores/auth'
import { useVillageAudio } from '@/features/home/useVillageAudio'
import type { DemoRoomVisibility, LobbyFilter } from '@/features/lobby/types'
import { usingRealBackend } from '@/api/adapters'

const router = useRouter()
const online = usingRealBackend()
const lobby = useLobbyStore()
const { visibleRooms, page, pageCount, loading, error, actionError, pendingRoomId, creating, filter } = storeToRefs(lobby)
const { day } = storeToRefs(useHomeDemoStore())
const auth = useAuthStore()
const { activeGameId } = storeToRefs(auth)
const createOpen = ref(false)
const infoOpen = ref(false)
const infoKind = ref<'rules' | 'about' | 'audio-error'>('rules')
const roomName = ref('')
const visibility = ref<DemoRoomVisibility>('PUBLIC')
const createError = ref('')
const roomCode = ref('')
const codeError = ref('')
const roomNameInput = ref<HTMLInputElement | null>(null)
const roomCodeInput = ref<HTMLInputElement | null>(null)
let active = true
const lineup = ['werewolf', 'villager', 'seer', 'witch', 'hunter'] as const
const filters: { value: LobbyFilter; label: string }[] = [
  { value: 'ALL', label: '全部房间' }, { value: 'PUBLIC', label: '公开房' },
  { value: 'PLAYING', label: '进行中' }, { value: 'WAITING', label: '等待中' },
]
const infoTitle = computed(() => infoKind.value === 'rules' ? '今晚，靠推理找到狼人。' : infoKind.value === 'about' ? 'AI 也有自己的小算盘。' : '音效暂时无法开启')
const { sound, playChime, toggleSound } = useVillageAudio(() => openInfo('audio-error'))
function openInfo(kind: typeof infoKind.value) { infoKind.value = kind; infoOpen.value = true }
function openCreate() { roomName.value = ''; createError.value = ''; lobby.actionError = ''; createOpen.value = true }
async function createRoom() {
  if (!roomName.value.trim()) {
    createError.value = '给房间起个名字吧。'
    roomNameInput.value?.focus()
    return
  }
  createError.value = ''
  const room = await lobby.create({ name: roomName.value, visibility: visibility.value })
  if (room && active && createOpen.value) { createOpen.value = false; await router.push({ name: 'room-waiting', params: { roomId: room.roomId } }) }
}
async function joinRoom(roomId: string) {
  const room = await lobby.join(roomId)
  if (room && active) await router.push({ name: 'room-waiting', params: { roomId: room.roomId } })
}
async function joinByCode() {
  codeError.value = ''
  const value = roomCode.value.trim()
  if (!value) {
    codeError.value = '请输入房间号。'
    roomCodeInput.value?.focus()
    return
  }
  await joinRoom(value)
  if (lobby.actionError) { codeError.value = lobby.actionError; lobby.actionError = ''; await nextTick(); roomCodeInput.value?.focus() }
}
function refreshActiveGame() { if (online && auth.authenticated) void auth.me() }
function onVisible() { if (!document.hidden && !createOpen.value && pendingRoomId.value === null) { void lobby.refresh(); refreshActiveGame() } }
let refreshTimer: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  void lobby.refresh(); refreshActiveGame(); document.addEventListener('visibilitychange', onVisible)
  if (online) refreshTimer = setInterval(() => { if (!loading.value && !creating.value) onVisible() }, 15000)
})
onBeforeUnmount(() => { active = false; clearInterval(refreshTimer); document.removeEventListener('visibilitychange', onVisible) })
</script>

<template>
  <div class="landing lobby-page" :class="{ 'is-night': !day }">
    <FogVillageScene @phase-changed="playChime" />

    <header class="landing-header">
      <RouterLink to="/" class="landing-brand" aria-label="WolfMind 首页">
        <span class="brand-badge"><PixelIcon name="wolf" :size="20" /></span>
        <span>WolfMind</span>
      </RouterLink>
      <nav class="landing-nav" aria-label="主导航">
        <RouterLink to="/" class="px-link lobby-back"><PixelIcon name="door" :size="16" />返回村庄</RouterLink>
        <button class="px-link" @click="openInfo('rules')">玩法介绍</button>
        <button class="px-link" @click="openInfo('about')">关于项目</button>
        <button class="px-icon-button" :aria-label="sound ? '关闭音效' : '开启音效'" :aria-pressed="sound" @click="toggleSound"><PixelIcon :name="sound ? 'sound' : 'muted'" :size="18" /></button>
      </nav>
    </header>

    <main class="lobby-shell">
      <div class="lobby-intro">
        <div>
          <p class="hero-kicker">多智能体狼人杀 · 村中央广场</p>
          <h1 class="lobby-title"><PixelTitle text="游戏大厅" :size="18" /></h1>
          <p class="lobby-lead">选择一个房间，加入今晚的推理。</p>
        </div>
        <p v-if="online && activeGameId" class="lobby-resume px-panel" role="status"><span>你有一局正在进行。</span><RouterLink class="px-button px-button-primary" :to="`/games/${activeGameId}`">继续对局 <PixelIcon name="arrow" :size="18" /></RouterLink></p>
      </div>

      <div class="lobby-board">
        <section class="px-panel room-list-panel" aria-labelledby="room-list-title" :aria-busy="loading">
          <div class="list-heading">
            <div class="action-card-heading"><span class="action-icon" aria-hidden="true"><PixelIcon name="people" :size="20" /></span><div><h2 id="room-list-title">房间列表</h2><p>与真实玩家或 AI 一起，体验推理与表达的乐趣。</p></div></div>
            <div class="room-filters" role="group" aria-label="筛选房间"><button v-for="item in filters" :key="item.value" :aria-pressed="filter === item.value" :class="{ selected: filter === item.value }" @click="filter = item.value">{{ item.label }}</button></div>
          </div>

          <p v-if="error" class="lobby-error" role="alert">{{ error }} <button @click="lobby.refresh">重试</button></p>
          <p v-if="actionError && !createOpen" class="lobby-error" role="alert">{{ actionError }}</p>
          <div class="room-table" role="table" aria-label="大厅房间列表">
            <div class="room-table-head" role="row"><span role="columnheader">房间名称</span><span role="columnheader">模式</span><span role="columnheader">人数</span><span role="columnheader">状态</span><span role="columnheader">操作</span></div>
            <div v-if="loading && !lobby.loaded" class="list-loading" role="status"><PixelIcon name="refresh" :size="24" /><span>正在寻找村里的房间…</span></div>
            <template v-else>
              <div v-for="room in visibleRooms" :key="room.roomId" class="room-row" role="row">
                <div class="room-identity" role="cell"><span class="room-tile" :class="room.status === 'WAITING' ? 'tile-waiting' : 'tile-playing'" aria-hidden="true"><PixelIcon :name="room.status === 'WAITING' ? 'door' : 'moon'" :size="20" /></span><div><h3>{{ room.name }}</h3><p>{{ room.description }}</p><span class="room-number">房间号 {{ room.roomId }}<span v-if="room.visibility === 'INVITE'"> · 凭号加入</span></span></div></div>
                <div class="room-mode" role="cell"><span>{{ room.modeLabel }}</span></div>
                <div class="room-occupancy" role="cell"><PixelIcon name="people" :size="15" /><span><span class="sr-only">占用座位 </span>{{ room.occupied }} / {{ room.capacity }}<small v-if="room.humanCount !== undefined && room.aiCount !== undefined">真人 {{ room.humanCount }} · AI {{ room.aiCount }}</small></span></div>
                <div class="room-status" :class="room.status === 'WAITING' ? 'status-waiting' : 'status-playing'" role="cell"><i aria-hidden="true"></i>{{ room.status === 'WAITING' ? '等待中' : '进行中' }}</div>
                <div class="room-action" role="cell"><RouterLink v-if="online && room.status === 'PLAYING' && room.gameId" class="px-button room-join" :to="`/watch/${room.gameId}`" :aria-label="`观战${room.name}`">观战 <PixelIcon name="eye" :size="18" /></RouterLink><button v-else class="px-button room-join" :class="{ 'px-button-primary': room.canJoin }" :disabled="!room.canJoin || pendingRoomId !== null" :aria-label="room.canJoin ? `加入${room.name}` : `${room.name}${room.status === 'WAITING' ? '已满' : '已开始'}`" @click="joinRoom(room.roomId)"><span>{{ pendingRoomId === room.roomId ? '加入中…' : room.canJoin ? '加入游戏' : room.status === 'WAITING' ? '房间已满' : room.modeLabel === '观战示例' ? '观战中' : '已开始' }}</span><PixelIcon v-if="room.canJoin" name="arrow" :size="18" /><PixelIcon v-else name="eye" :size="18" /></button></div>
              </div>
              <div v-if="!visibleRooms.length && !error" class="empty-rooms" role="status"><PixelIcon name="tree" :size="34" /><h3>{{ filter === 'ALL' ? '当前没有房间' : '这里暂时没有房间' }}</h3><p>换个筛选看看，或为今晚的推理开一间新房。</p><button class="px-button" @click="openCreate">创建房间</button></div>
            </template>
          </div>
          <div class="list-footer"><span>{{ online ? '在线房间 · 每15秒更新，支持手动刷新' : '本地演示 · 房间与人数为示例' }}</span><nav v-if="pageCount > 1" class="room-pagination" aria-label="房间列表分页"><button :disabled="page === 1" aria-label="上一页房间" @click="page--">←</button><span>{{ page }} / {{ pageCount }}</span><button :disabled="page === pageCount" aria-label="下一页房间" @click="page++">→</button></nav><button :disabled="loading" @click="lobby.refresh"><PixelIcon name="refresh" :size="14" />{{ loading ? '刷新中…' : '刷新列表' }}</button></div>
        </section>

        <aside class="lobby-actions" aria-label="房间操作">
          <section class="px-panel create-room-panel" aria-labelledby="create-room-title">
            <div class="action-card-heading"><span class="action-icon" aria-hidden="true"><PixelIcon name="plus" :size="20" /></span><div><h2 id="create-room-title">创建房间</h2><p>开一间新房，邀请好友一起游戏。</p></div></div>
            <div class="create-lineup">
              <ul aria-hidden="true"><li v-for="role in lineup" :key="role"><PixelRole :role="role" :scale="4" /></li></ul>
              <p><strong>经典九人局</strong><span>3 狼人 · 3 村民 · 预言家 · 女巫 · 猎人</span></p>
            </div>
            <button class="px-button px-button-primary create-room-button" @click="openCreate"><PixelIcon name="plus" :size="18" />创建房间</button>
          </section>

          <section class="px-panel join-room-panel" aria-labelledby="join-room-title">
            <div class="action-card-heading"><span class="action-icon" aria-hidden="true"><PixelIcon name="door" :size="20" /></span><div><h2 id="join-room-title">加入游戏</h2><p>输入房间号，快速加入游戏。</p></div></div>
            <form @submit.prevent="joinByCode">
              <label class="room-code-field"><PixelIcon name="key" :size="18" /><span class="sr-only">房间号</span><input ref="roomCodeInput" v-model="roomCode" placeholder="请输入房间号" maxlength="64" autocomplete="off" :aria-invalid="!!codeError" :aria-describedby="codeError ? 'room-code-error' : undefined" @input="codeError = ''" /></label>
              <p v-if="codeError" id="room-code-error" class="form-error" role="alert">{{ codeError }}</p>
              <button class="px-button" type="submit" :disabled="pendingRoomId !== null"><span>{{ pendingRoomId ? '加入中…' : '加入游戏' }}</span><PixelIcon name="arrow" :size="18" /></button>
            </form>
          </section>
        </aside>
      </div>
      <nav class="lobby-links px-panel" aria-label="更多入口"><span class="lobby-links-label" aria-hidden="true">更多入口</span><template v-if="!online"><RouterLink to="/games/demo" class="px-link">核心游戏页 · 本地演示</RouterLink><RouterLink to="/watch/demo" class="px-link">AI 对局观战</RouterLink><RouterLink to="/games/demo/replay" class="px-link">游戏回放</RouterLink><RouterLink to="/games/demo/analysis" class="px-link">AI 分析</RouterLink></template><RouterLink to="/lab" class="px-link">AI 实验室</RouterLink></nav>
    </main>

    <PixelDialog v-model:open="createOpen" title="为今晚的推理开一间房。" eyebrow="WOLFMIND / 创建房间">
      <p class="dialog-description">经典九人局，等你的朋友一起入座。</p>
      <form @submit.prevent="createRoom">
        <label class="field-label" for="room-name">房间名称</label><input id="room-name" ref="roomNameInput" v-model="roomName" placeholder="例如：今晚来雾隐村" maxlength="24" :aria-invalid="!!createError" :aria-describedby="createError ? 'room-name-error' : undefined" @input="createError = ''" />
        <p v-if="createError" id="room-name-error" class="form-error" role="alert">{{ createError }}</p>
        <fieldset class="visibility-options"><legend>加入方式</legend><label><input v-model="visibility" type="radio" value="PUBLIC" />公开房间<span>展示在公开房列表</span></label><label><input v-model="visibility" type="radio" value="INVITE" />凭号加入<span>把房间号分享给朋友</span></label></fieldset>
        <div class="rules-summary"><PixelIcon name="people" :size="20" /><div><strong>经典九人局</strong><p>3 狼人 · 3 村民 · 预言家 · 女巫 · 猎人</p></div></div>
        <p v-if="actionError" class="form-error" role="alert">{{ actionError }}</p>
        <p class="demo-note">{{ online ? '房间创建后可分享房间号，邀请好友入座。' : '本地演示：创建的房间仅保存在当前浏览器会话。' }}</p>
        <button class="px-button px-button-primary dialog-submit" type="submit" :disabled="creating"><span>{{ creating ? '创建中…' : '创建并进入房间' }}</span><PixelIcon name="arrow" :size="18" /></button>
      </form>
    </PixelDialog>
    <PixelDialog v-model:open="infoOpen" :title="infoTitle">
      <template v-if="infoKind === 'rules'"><div class="rule-row"><span class="rule-icon"><PixelIcon name="sun" :size="25" /></span><div><h3>天亮了，聊聊昨晚。</h3><p>轮流发言、观察线索，再投出你的一票。</p></div></div><div class="rule-row"><span class="rule-icon"><PixelIcon name="moon" :size="25" /></span><div><h3>天黑了，各自行动。</h3><p>不同身份有各自的能力；正式对局由服务端控制规则和阶段。</p></div></div><p class="demo-note">{{ online ? '房主开局后，真人与 AI 按同一套规则参与对局。' : '当前大厅为本地交互演示。' }}</p></template>
      <template v-else-if="infoKind === 'about'"><p class="dialog-description">WolfMind 是真人与 AI 一同参与的在线狼人杀平台。</p><p class="demo-note">{{ online ? '游戏规则由后端确定性控制，AI 负责提出决策。房间列表来自在线服务。' : '当前房间列表使用本地示例数据。' }}</p></template>
      <p v-else class="dialog-description">当前浏览器未能启动音频，可以继续观看村庄或稍后重试。</p>
      <button class="secondary-button full-width" @click="infoOpen = false">返回大厅</button>
    </PixelDialog>
  </div>
</template>
