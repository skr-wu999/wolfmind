<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { RouterLink } from 'vue-router'
import PixelIcon from '@/components/PixelIcon.vue'
import PixelRole from '@/components/PixelRole.vue'
import PixelTitle from '@/components/PixelTitle.vue'
import FogVillageScene from '@/components/FogVillageScene.vue'
import type { VillagePhase } from '@/features/home/village'
import { useHomeDemoStore } from '@/stores/homeDemo'
import { useVillageAudio } from '@/features/home/useVillageAudio'

type ModalKind = 'start' | 'rules' | 'about' | 'audio-error'
const demo = useHomeDemoStore()
const { elapsed, vote, day, seconds, paused, remaining } = storeToRefs(demo)
const lineup = [
  { role: 'werewolf', name: '狼人', count: 3 },
  { role: 'villager', name: '村民', count: 3 },
  { role: 'seer', name: '预言家', count: 1 },
  { role: 'witch', name: '女巫', count: 1 },
  { role: 'hunter', name: '猎人', count: 1 },
] as const
const timerBlocks = computed(() => Math.ceil(remaining.value))
const { sound, playChime, toggleSound } = useVillageAudio(() => { void openModal('audio-error') })
const modal = ref<ModalKind | null>(null)
const dialog = ref<HTMLDialogElement | null>(null)
const mode = ref<'human' | 'watch'>('human')
const playerName = ref('旅行者')
const entered = ref(false)
const formError = ref('')
const players = ['你', '松果', '南瓜', '阿栗', '小麦', '青苔']
const logText = computed(() => {
  if (!day.value) {
    if (elapsed.value < 2.9) return '夜幕降临，村民回到屋内。'
    if (elapsed.value < 6.8) return '狼人正在靠近村民的屋子，悄悄推开门。'
    if (elapsed.value < 10.7) return '狼人进入屋内，窗灯渐渐熄灭。'
    return '狼人离开屋子，等待天亮公布消息。'
  }
  if (vote.value) return `你已投给 ${vote.value} 号村民。演示将在夜晚继续。`
  return elapsed.value < 4 ? '村民正在前往广场，准备今天的讨论。' : '3 号村民：我昨晚一直在家，谁可以证明？'
})
let returnFocus: HTMLElement | null = null

function setPhase(value: VillagePhase) {
  demo.setPhase(value)
  if (sound.value) playChime()
}


async function openModal(kind: ModalKind) {
  if (!dialog.value?.open) returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  modal.value = kind
  entered.value = false
  formError.value = ''
  await nextTick()
  dialog.value?.showModal()
  ;(dialog.value?.querySelector('input') || dialog.value?.querySelector('button'))?.focus()
}

function onDialogClick(event: MouseEvent) {
  if (!dialog.value || event.target !== dialog.value) return
  const bounds = dialog.value.getBoundingClientRect()
  if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) closeModal()
}

function closeModal() {
  dialog.value?.close()
}

function afterClose() {
  modal.value = null
  returnFocus?.focus()
}

async function enterDemo() {
  if (!playerName.value.trim()) {
    formError.value = '先给自己起个名字吧。'
    dialog.value?.querySelector('input')?.focus()
    return
  }
  formError.value = ''
  entered.value = true
  vote.value = null
  setPhase('day')
  await nextTick()
  dialog.value?.querySelector<HTMLButtonElement>('.close-button')?.focus()
}

</script>

<template>
  <div class="home landing" :class="{ 'is-night': !day }">
    <FogVillageScene @phase-changed="playChime" />

    <header class="landing-header">
      <RouterLink to="/" class="landing-brand" aria-label="WolfMind 首页">
        <span class="brand-badge"><PixelIcon name="wolf" :size="20" /></span>
        <span>WolfMind</span>
      </RouterLink>
      <nav class="landing-nav" aria-label="主导航">
        <button class="px-link" @click="openModal('rules')">玩法介绍</button>
        <button class="px-link" @click="openModal('about')">关于项目</button>
        <button class="px-icon-button" :aria-label="sound ? '关闭音效' : '开启音效'" :aria-pressed="sound" @click="toggleSound"><PixelIcon :name="sound ? 'sound' : 'muted'" :size="18" /></button>
      </nav>
    </header>

    <main class="landing-main">
      <section class="landing-hero" aria-labelledby="hero-title">
        <p class="hero-kicker">多智能体狼人杀 · 经典九人局</p>
        <h1 id="hero-title" class="hero-title"><span class="hero-welcome">欢迎来到</span><PixelTitle text="雾隐村" /></h1>
        <p class="hero-lead">今晚，谁是狼人？</p>
        <p class="hero-copy">和拥有独立思考能力的 AI 一起推理、怀疑与博弈。你可以亲自入座，也可以旁观 AI 之间的试探。</p>
        <div class="hero-actions">
          <RouterLink to="/lobby" class="px-button px-button-primary hero-start"><PixelIcon name="play" :size="16" />开始游戏</RouterLink>
          <button class="px-button explore-button" @click="openModal('rules')">探索玩法</button>
        </div>
      </section>
    </main>

    <div class="landing-dock">
      <section class="lineup px-panel" aria-labelledby="lineup-title">
        <h2 id="lineup-title">本局阵容<span>9 人</span></h2>
        <ul>
          <li v-for="item in lineup" :key="item.role"><PixelRole :role="item.role" /><span>{{ item.name }}</span><small>×{{ item.count }}</small></li>
        </ul>
      </section>

      <aside class="landing-hud px-panel" :class="{ 'hud-paused': paused }" aria-label="村庄昼夜控制">
        <div class="hud-clock"><PixelIcon :name="day ? 'sun' : 'moon'" :size="20" /><strong>{{ day ? '白天' : '黑夜' }}</strong></div>
        <p class="hud-log" aria-live="polite">{{ logText }}</p>
        <div class="hud-time">
          <div class="hud-timer" role="progressbar" aria-valuemin="0" aria-valuemax="15" :aria-valuenow="Math.round(remaining)" :aria-label="day ? '距离入夜的剩余时间' : '距离天亮的剩余时间'" :aria-valuetext="`${seconds} 秒${paused ? '，已暂停' : ''}`">
            <i v-for="n in 15" :key="n" :class="{ on: n <= timerBlocks }"></i>
          </div>
          <span v-if="paused" class="pause-state">已暂停</span>
          <span v-else class="hud-seconds">{{ seconds }}s</span>
        </div>
        <div class="hud-controls">
          <button :class="{ active: day }" :aria-pressed="day" aria-label="切换到白天" @click="setPhase('day')"><PixelIcon name="sun" :size="16" /></button>
          <button :class="{ active: !day }" :aria-pressed="!day" aria-label="切换到黑夜" @click="setPhase('night')"><PixelIcon name="moon" :size="16" /></button>
          <button class="pause-button" :aria-label="paused ? '继续村庄动画' : '暂停村庄动画'" :aria-pressed="paused" @click="paused = !paused"><PixelIcon :name="paused ? 'play' : 'pause'" :size="16" /></button>
        </div>
      </aside>
    </div>

    <dialog ref="dialog" class="pixel-dialog" aria-labelledby="dialog-title" @close="afterClose" @click="onDialogClick">
      <template v-if="modal">
        <div class="dialog-header"><span class="dialog-eyebrow">WOLFMIND / {{ modal === 'start' ? '村庄登记处' : modal === 'rules' ? '村庄手册' : '项目档案' }}</span><button class="close-button" aria-label="关闭窗口" @click="closeModal"><PixelIcon name="close" :size="18" /></button></div>
        <template v-if="modal === 'start' && !entered">
          <h2 id="dialog-title">今晚，你来入局。</h2>
          <p class="dialog-description">选择一种方式，走进雾隐村。</p>
          <form @submit.prevent="enterDemo">
            <label class="field-label" for="player-name">村庄里的名字</label>
            <input id="player-name" v-model="playerName" maxlength="12" autocomplete="nickname" :aria-invalid="!!formError" :aria-describedby="formError ? 'form-error' : undefined" />
            <p v-if="formError" id="form-error" class="form-error" role="alert">{{ formError }}</p>
            <div class="mode-options" role="group" aria-label="演示模式">
              <button type="button" :class="{ selected: mode === 'human' }" :aria-pressed="mode === 'human'" @click="mode = 'human'"><PixelIcon name="people" :size="24" /><strong>加入村庄</strong><span>与 AI 同桌，参与投票</span><span class="selection-mark">{{ mode === 'human' ? '✓' : '+' }}</span></button>
              <button type="button" :class="{ selected: mode === 'watch' }" :aria-pressed="mode === 'watch'" @click="mode = 'watch'"><PixelIcon name="eye" :size="24" /><strong>看看对局</strong><span>旁观 AI 们的推理现场</span><span class="selection-mark">{{ mode === 'watch' ? '✓' : '+' }}</span></button>
            </div>
            <p class="demo-note">首页交互演示：使用本地脚本角色，尚未连接 AI 或在线房间。</p>
            <button class="start-button" type="submit"><span>进入演示房间</span><PixelIcon name="arrow" :size="22" /></button>
          </form>
        </template>
        <template v-else-if="modal === 'start' && entered">
          <div class="room-title"><h2 id="dialog-title">雾隐村 · 演示房间</h2><span class="room-phase">{{ day ? '白天' : '黑夜' }} {{ seconds }}s</span></div>
          <p class="dialog-description">{{ mode === 'human' ? `${playerName.trim()}，你是一名村民。白天可以选择投票对象。` : `${playerName.trim()}，你正在旁观脚本角色的昼夜演示。` }}</p>
          <div class="player-grid">
            <button v-for="(name, i) in players" :key="name" :class="['player-seat', `seat-${i}`, { voted: vote === i + 1 }]" :disabled="mode === 'watch' || !day || i === 0 || !!vote" @click="vote = i + 1"><span class="mini-villager"><i></i></span><strong>{{ i + 1 }} 号 · {{ i === 0 ? (mode === 'human' ? playerName.trim() : '橡果') : name }}</strong><span>{{ vote === i + 1 ? '你投出了这一票' : i === 0 && mode === 'human' ? '你的座位' : '脚本角色' }}</span></button>
          </div>
          <div class="room-log" role="status"><PixelIcon :name="day ? 'ballot' : 'moon'" :size="22" /><p>{{ logText }}</p></div>
          <p class="demo-note">这是首页演示，不进行真实 AI 推理或完整胜负结算。</p>
          <button class="secondary-button full-width" @click="closeModal">返回村庄</button>
        </template>
        <template v-else-if="modal === 'rules'">
          <h2 id="dialog-title">相信谁，由你决定。</h2>
          <p class="dialog-description">狼人隐藏在村民之间。靠发言、观察和投票，找出他们。</p>
          <div class="rule-row"><span class="rule-icon"><PixelIcon name="sun" :size="26" /></span><div><h3>天亮了，聊聊昨晚。</h3><p>村民轮流发言，讨论可疑线索，再投票选出最值得怀疑的人。</p></div></div>
          <div class="rule-row"><span class="rule-icon"><PixelIcon name="moon" :size="26" /></span><div><h3>天黑了，各自行动。</h3><p>狼人秘密选择击杀目标；其他身份按规则使用自己的技能。</p></div></div>
          <div class="rule-row"><span class="rule-icon"><PixelIcon name="wolf" :size="26" /></span><div><h3>身份不同，目标不同。</h3><p>好人要找出所有狼人，狼人要隐藏身份并淘汰好人。</p></div></div>
          <p class="demo-note">首页动画每 15 秒切换昼夜，仅演示投票与狼人行动的氛围。</p>
          <button class="start-button" @click="openModal('start')"><span>去村庄看看</span><PixelIcon name="arrow" :size="22" /></button>
        </template>
        <template v-else-if="modal === 'about'">
          <h2 id="dialog-title">AI 也有自己的小算盘。</h2>
          <p class="dialog-description">WolfMind 是一个基于 Multi-Agent AI 技术的在线狼人杀游戏平台项目。</p>
          <div class="about-content"><p>每个 AI Agent 都有独立的记忆、角色和策略。它们会分析发言、形成判断，也会为了自己的阵营试着说服你。</p><p>项目计划支持 AI 自主对局、真人与 AI 混合对战、在线多人游戏，以及决策分析和游戏回放。</p><p>游戏规则由后端确定性控制，AI Agent 负责推理与策略决策。当前页面是独立的首页视觉与交互 demo。</p></div>
          <button class="secondary-button full-width" @click="closeModal">继续逛逛村庄</button>
        </template>
        <template v-else>
          <h2 id="dialog-title">音效暂时无法开启</h2><p class="dialog-description">当前浏览器未能启动音频。你可以继续观看动画，或稍后再次开启音效。</p><button class="secondary-button full-width" @click="closeModal">返回村庄</button>
        </template>
      </template>
    </dialog>
  </div>
</template>
