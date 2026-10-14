<script setup lang="ts">
import { storeToRefs } from 'pinia'
import PixelIcon from './PixelIcon.vue'
import { useHomeDemoStore } from '@/stores/homeDemo'
import type { VillagePhase } from '@/features/home/village'
const emit = defineEmits<{ phaseChanged: [] }>()
const demo = useHomeDemoStore()
const { day, paused, remaining, seconds } = storeToRefs(demo)
function setPhase(value: VillagePhase) {
  demo.setPhase(value)
  emit('phaseChanged')
}
</script>

<template>
  <aside class="village-hud" :class="{ 'hud-paused': paused }" aria-label="村庄昼夜控制">
    <div class="hud-status">
      <PixelIcon :name="day ? 'sun' : 'moon'" :size="22" />
      <div class="hud-info">
        <div class="hud-status-line" aria-live="polite"><span class="phase-copy">{{ day ? '白天' : '黑夜' }}<span class="phase-detail">{{ day ? ' · 村民正在交谈' : ' · 狼人悄悄行动' }}</span><span class="phase-short">{{ day ? ' · 交谈中' : ' · 行动中' }}</span></span><span v-if="paused" class="pause-state">已暂停</span></div>
        <progress class="time-progress" max="15" :value="remaining" :aria-label="day ? '距离入夜的剩余时间' : '距离天亮的剩余时间'" :aria-valuetext="`${seconds} 秒${paused ? '，已暂停' : ''}`" :title="`${seconds} 秒后${day ? '入夜' : '天亮'}${paused ? '（已暂停）' : ''}`"></progress>
      </div>
    </div>
    <div class="scene-controls">
      <div class="phase-switch" aria-label="村庄时段">
        <button :class="{ active: day }" :aria-pressed="day" aria-label="切换到白天" @click="setPhase('day')"><PixelIcon name="sun" :size="16" /><span class="control-label">昼</span></button>
        <button :class="{ active: !day }" :aria-pressed="!day" aria-label="切换到黑夜" @click="setPhase('night')"><PixelIcon name="moon" :size="16" /><span class="control-label">夜</span></button>
      </div>
      <button class="pause-button" :aria-label="paused ? '继续村庄动画' : '暂停村庄动画'" :aria-pressed="paused" @click="paused = !paused"><PixelIcon :name="paused ? 'play' : 'pause'" :size="16" /></button>
    </div>
  </aside>
</template>
