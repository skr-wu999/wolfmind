<script setup lang="ts">
import { useObservationStore } from '@/stores/observation'
import { formatTime, unknownPhase } from '@/features/observation/types'
import type { ObservationEvent } from '@/features/observation/types'
import PixelIcon from './PixelIcon.vue'
defineProps<{ fullscreen?: boolean }>()
const emit = defineEmits<{ fullscreen: [] }>()
const player = useObservationStore()
/**
 * 章节下拉的文案。
 *
 * 每一项都必须有名字：`undefined` 会渲染成"第 3 天 · undefined"（QA P2）。
 * 阶段缺失时给明确的未知占位；天数缺失时退到该章节之前最近的有效轮次，再退到第 1 天——
 * 都不猜一个具体阶段名。
 */
function chapterLabel(chapter: ObservationEvent) {
  const phase = chapter.change?.phase ?? unknownPhase
  if (phase === '游戏结束') return '游戏结束'
  const round = chapter.change?.round
    ?? player.match?.events.filter(event => event.version <= chapter.version && event.change?.round).at(-1)?.change?.round
    ?? 1
  return `第 ${round} 天 · ${phase}`
}
</script>
<template>
  <div v-if="player.match" class="observation-controls" aria-label="播放控制">
    <div class="playback-chapters"><button v-for="chapter in player.chapters.filter(e => e.change?.phase === '白天讨论')" :key="chapter.id" @click="player.seek(chapter.at)">第 {{ chapter.change?.round }} 天 <PixelIcon name="sun" :size="14" /></button></div>
    <input class="playback-range" type="range" aria-label="播放进度" :aria-valuetext="formatTime(player.position)" :value="player.position" min="0" :max="player.match.duration" step="1" @input="player.seek(Number(($event.target as HTMLInputElement).value))" />
    <div class="playback-buttons">
      <label><span class="sr-only">播放速度</span><select v-model.number="player.speed"><option :value="0.5">0.5x</option><option :value="1">1.0x</option><option :value="2">2.0x</option><option :value="4">4.0x</option></select></label>
      <button aria-label="上一事件" :disabled="!player.visibleEvents.some(e => e.at < player.position)" @click="player.step(-1)"><PixelIcon name="skip" :size="18" class="previous-icon" /></button>
      <button :aria-label="player.playing ? '暂停播放' : '开始播放'" :aria-pressed="player.playing" :disabled="!player.match.events.length" @click="player.toggle()"><PixelIcon :name="player.playing ? 'pause' : 'play'" :size="18" /></button>
      <button aria-label="下一事件" :disabled="!player.visibleEvents.some(e => e.at > player.position)" @click="player.step(1)"><PixelIcon name="skip" :size="18" /></button>
      <span class="playback-time">{{ formatTime(player.position) }} / {{ formatTime(player.match.duration) }}</span>
      <label class="playback-jump"><span class="sr-only">跳转阶段</span><select :value="[...player.chapters].reverse().find(e => e.at <= player.position)?.at" @change="player.seek(Number(($event.target as HTMLSelectElement).value))"><option v-for="chapter in player.chapters" :key="chapter.id" :value="chapter.at">{{ chapterLabel(chapter) }}</option></select></label>
      <button v-if="fullscreen" aria-label="切换全屏" @click="emit('fullscreen')"><span aria-hidden="true">⛶</span></button>
    </div>
  </div>
</template>
