<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import PixelAvatar from './PixelAvatar.vue'
import PixelIcon from './PixelIcon.vue'
import { chatChannels, chatItems } from '@/features/game/chat'
import type { ChatChannel } from '@/features/game/chat'
import type { GamePresentation } from '@/features/game/types'

const props = defineProps<{ view: GamePresentation; actionable: boolean; busy: boolean; validation: string; error: string; active: boolean }>()
const emit = defineEmits<{ speech: []; wolf: []; history: [] }>()
const speechDraft = defineModel<string>('speechDraft', { required: true })
const wolfDraft = defineModel<string>('wolfDraft', { required: true })
const channel = ref<ChatChannel>('PUBLIC'), day = ref('all'), following = ref(true), unread = ref<string[]>([])
const list = ref<HTMLElement | null>(null), composer = ref<HTMLTextAreaElement | null>(null)
const channels = computed(() => chatChannels(props.view))
const items = computed(() => chatItems(props.view, channel.value))
const visibleItems = computed(() => items.value.filter(item => day.value === 'all' || item.round === Number(day.value)))
const rounds = computed(() => [...new Set(items.value.flatMap(item => item.round === undefined ? [] : [item.round]))])
const names: Record<ChatChannel, string> = { PUBLIC: '公共讨论', PRIVATE: '个人信息', WOLF: '狼人密谈' }
const speaker = computed(() => props.view.players.find(player => player.seatNo === props.view.speakerSeatNo))
const discussing = computed(() => ['DAY_DISCUSSION', 'LAST_WORDS'].includes(props.view.phase))
const canSpeak = computed(() => props.view.me.seatNo > 0 && props.view.phase !== 'GAME_OVER' && props.view.permissions.some(grant => grant.type === 'SPEAK'))
const canWolf = computed(() => props.view.me.seatNo > 0 && props.view.phase !== 'GAME_OVER' && props.view.permissions.some(grant => grant.type === 'WOLF_CHAT'))
const waiting = computed(() => props.view.phase === 'GAME_OVER' ? '对局已结束，可以回看讨论。' : props.view.submitted || (canSpeak.value ? '轮到你发言。' : !props.view.me.seatNo ? '公开观战 · 只读' : !props.view.me.alive ? '你已淘汰，等待授权的遗言或对局结束。' : speaker.value && ['DAY_DISCUSSION', 'LAST_WORDS'].includes(props.view.phase) ? `当前轮到 ${speaker.value.seatNo} 号 ${speaker.value.name}，等待你的轮次。` : '公共讨论只读，等待下一次发言授权。'))
const turns = computed(() => props.view.turnOrder.map(seatNo => ({ player: props.view.players.find(player => player.seatNo === seatNo), state: props.view.completedSpeakers.includes(seatNo) ? '已发言' : seatNo === props.view.speakerSeatNo ? props.view.preview ? '正在发言' : '当前回合' : '尚未发言' })))
let previous = new Map<string, string>(), revision = 0, disposed = false
function atEnd() {
  if (!list.value || !list.value.clientHeight) return
  following.value = list.value.scrollHeight - list.value.scrollTop - list.value.clientHeight < 40 && day.value === 'all'
  if (following.value) unread.value = []
}
function scrollLatest() { if (list.value) list.value.scrollTop = list.value.scrollHeight; unread.value = [] }
async function latest() { day.value = 'all'; following.value = true; await nextTick(); scrollLatest() }
async function focusComposer() { channel.value = 'PUBLIC'; await nextTick(); composer.value?.focus() }
async function focusAccepted() { await latest(); list.value?.focus({ preventScroll: true }) }
function submitSpeech() { if (canSpeak.value && props.actionable) emit('speech') }
function submitWolf() { if (canWolf.value && props.actionable) emit('wolf') }
defineExpose({ focusComposer, focusAccepted })
watch(() => `${props.view.gameId}|${props.view.scenario}|${channel.value}`, () => {
  ++revision; day.value = 'all'; following.value = true; unread.value = []; previous = new Map(items.value.map(item => [item.id, item.content]))
  void nextTick().then(() => { if (!disposed && props.active) scrollLatest() })
}, { immediate: true })
watch(channels, available => { if (!available.includes(channel.value)) channel.value = 'PUBLIC' })
watch(() => items.value.map(item => `${item.id}:${item.streaming ? 'preview' : 'committed'}:${item.content}`).join('|'), async () => {
  const current = revision, ids = new Set(items.value.map(item => item.id))
  const added = items.value.filter(item => !previous.has(item.id) || item.streaming && !!item.content && previous.get(item.id) !== item.content && (!following.value || !props.active)).map(item => item.id)
  previous = new Map(items.value.map(item => [item.id, item.content]))
  unread.value = [...new Set([...unread.value.filter(id => ids.has(id)), ...added])]
  await nextTick()
  if (!disposed && current === revision && props.active && following.value && day.value === 'all') scrollLatest()
})
watch(() => props.active, async active => { if (active && following.value) { await nextTick(); scrollLatest() } })
watch(day, value => { if (value !== 'all') following.value = false })
onBeforeUnmount(() => { disposed = true; ++revision })
</script>

<template>
  <section class="px-panel game-chat-panel" aria-label="村庄群聊">
    <header class="game-chat-header"><div><h2><PixelIcon name="speech" :size="22" />村庄公聊</h2><p>{{ view.players.length }} 位玩家 · {{ discussing ? `本轮已发言 ${view.completedSpeakers.length} / ${view.turnOrder.length}` : '公开记录' }}</p></div><button class="game-text-button" @click="emit('history')">发言记录</button></header>
    <div class="game-chat-toolbar"><div v-if="channels.length > 1" class="game-chat-channels" role="group" aria-label="消息频道"><button v-for="value in channels" :key="value" :aria-pressed="channel === value" @click="channel = value">{{ names[value] }}</button></div><span v-else>雾隐村 · {{ names[channel] }}</span><label><span class="sr-only">讨论回合筛选</span><select v-model="day"><option value="all">全部记录</option><option v-for="round in rounds" :key="round" :value="String(round)">第 {{ round }} 天</option></select></label></div>
    <p v-if="channel !== 'PUBLIC'" class="game-channel-note">{{ channel === 'PRIVATE' ? '仅自己可见 · 不进入公共讨论' : '狼人阵营可见 · 不进入公共讨论' }}</p>
    <details v-if="channel === 'PUBLIC' && discussing && turns.length" class="game-turn-order"><summary>发言顺序 <span>{{ speaker ? `当前 ${speaker.seatNo} 号 ${speaker.name}` : '等待回合更新' }}</span></summary><ol class="discussion-order"><li v-for="entry in turns" :key="entry.player?.seatNo" :class="{ current: entry.player?.seatNo === view.speakerSeatNo }"><template v-if="entry.player"><b>{{ entry.player.seatNo }}</b><PixelAvatar :variant="entry.player.avatar" :size="22" /><span>{{ entry.player.name }}</span><small>{{ entry.state }}</small></template></li></ol></details>
    <div ref="list" class="game-chat-list" role="log" :aria-label="`${names[channel]}消息`" aria-live="off" tabindex="0" @scroll="atEnd">
      <p v-if="!visibleItems.length" class="game-chat-empty">{{ day === 'all' ? '还没有消息，等待新的记录。' : '该回合没有消息。' }}</p>
      <template v-for="(item, index) in visibleItems" :key="item.id">
        <p v-if="item.round !== undefined && item.round !== visibleItems[index - 1]?.round" class="game-chat-day">第 {{ item.round }} {{ channel === 'PUBLIC' ? '天' : '轮' }}</p>
        <div v-if="item.kind === 'system'" class="game-chat-system"><PixelIcon :name="channel === 'PUBLIC' ? 'document' : 'key'" :size="13" /><span>{{ item.content }}</span></div>
        <article v-else class="game-chat-message" :class="{ 'is-mine': item.seatNo === view.me.seatNo, 'is-streaming': item.streaming }" :data-message-id="item.id">
          <PixelAvatar :variant="view.players.find(player => player.seatNo === item.seatNo)?.avatar || 'green'" :size="32" />
          <div><div class="game-message-author"><strong>{{ item.seatNo }} 号 · {{ view.players.find(player => player.seatNo === item.seatNo)?.name || '玩家' }}</strong><small>{{ item.seatNo === view.me.seatNo ? '你' : view.players.find(player => player.seatNo === item.seatNo)?.controller === 'AI' ? 'AI' : '真人' }}</small></div><p class="game-message-bubble">{{ item.content || '正在组织公开发言…' }}<span v-if="item.streaming" class="game-stream-label">正在发言 · 预览</span></p></div>
        </article>
      </template>
    </div>
    <div class="game-chat-notifications" aria-live="polite"><button v-if="unread.length && (!following || !active || day !== 'all')" class="game-new-messages" @click="latest">{{ unread.length }} 条新消息 · 返回最新 ↓</button></div>
    <form v-if="channel === 'PUBLIC'" class="game-chat-composer game-speech-form" @submit.prevent="submitSpeech"><p class="game-composer-state" role="status">{{ waiting }}{{ canSpeak && !actionable ? ' 等待连接或阶段更新。' : '' }}</p><template v-if="view.phase !== 'GAME_OVER' && view.me.seatNo > 0"><label class="sr-only" for="speech-content">输入本轮公开发言</label><textarea id="speech-content" ref="composer" v-model="speechDraft" rows="2" maxlength="500" :disabled="!canSpeak || !actionable" :placeholder="canSpeak ? '写下你的判断…' : '等待你的发言轮次'" :aria-invalid="!!validation" aria-describedby="game-compose-error" @keydown.ctrl.enter.prevent="canSpeak && actionable && emit('speech')" /><div class="game-compose-actions"><small>{{ speechDraft.length }} / 500 · Ctrl + Enter</small><button class="lobby-button px-button px-button-primary" :disabled="!canSpeak || !actionable || !speechDraft.trim()" type="submit">{{ busy ? '提交中…' : '提交公开发言' }}<PixelIcon name="arrow" :size="16" /></button></div></template><p v-if="validation || error" id="game-compose-error" class="form-error" role="alert">{{ validation || error }}</p></form>
    <form v-else-if="channel === 'WOLF' && canWolf" class="game-chat-composer wolf-chat-form" @submit.prevent="submitWolf"><label class="sr-only" for="wolf-message">狼人密谈内容</label><input id="wolf-message" v-model="wolfDraft" maxlength="500" :disabled="!actionable" placeholder="与同伴商议…" /><button class="secondary-button px-button" :disabled="!actionable || !wolfDraft.trim()">发送</button><p v-if="validation || error" class="form-error" role="alert">{{ validation || error }}</p></form>
    <p v-else class="game-chat-readonly">{{ channel === 'PRIVATE' ? '个人技能信息只读，行动请使用游戏桌下方的操作栏。' : '当前频道只读，没有发送权限。' }}</p>
  </section>
</template>
