<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import type { GamePresentation } from '@/features/game/types'
import { roleNames, roleDescriptions } from '@/features/game/types'
import PixelIcon from './PixelIcon.vue'
defineProps<{ view: GamePresentation; actionable: boolean; voting: boolean; discussing: boolean; acting: boolean; hasPermission: boolean; icon: string }>()
defineEmits<{ speech: []; vote: []; skill: []; logs: [] }>()
const bar = ref<HTMLElement | null>(null)
// 窄屏、矮屏（含 200% 缩放）下操作栏固定在底部，Tab 滚动到的焦点会落在它下面；
// 把它的实际高度交给根元素的 scroll-padding-bottom，滚动时为它留出位置。
function reserve() {
  const el = bar.value
  const height = el && getComputedStyle(el).position === 'fixed' ? Math.ceil(el.getBoundingClientRect().height) + 12 : 0
  document.documentElement.style.setProperty('--skill-bar-offset', `${height}px`)
}
let observer: ResizeObserver | undefined
onMounted(() => {
  reserve()
  observer = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(reserve)
  if (bar.value) observer?.observe(bar.value)
  window.addEventListener('resize', reserve)
})
onBeforeUnmount(() => {
  observer?.disconnect()
  window.removeEventListener('resize', reserve)
  document.documentElement.style.removeProperty('--skill-bar-offset')
})
</script>
<template>
  <section ref="bar" class="px-panel game-skill-bar" aria-label="你的身份与操作">
    <div class="role-identity"><span class="role-emblem"><PixelIcon :name="view.me.seatNo ? icon : 'eye'" :size="34" /></span><div><strong>{{ view.me.seatNo ? `你的身份：${roleNames[view.me.role]}` : '公开观战' }}</strong><small>{{ view.me.seatNo ? roleDescriptions[view.me.role] : '只读查看公开局势与发言。' }}</small></div></div>
    <slot name="actions"><div class="role-actions">
      <div><button id="game-speak" class="lobby-button px-button px-button-primary" :disabled="!view.permissions.some(p => p.type === 'SPEAK') || !actionable" @click="$emit('speech')"><PixelIcon name="speech" :size="21" />{{ view.permissions.some(p => p.type === 'SPEAK') ? '发言' : '等待发言' }}</button><small>{{ discussing ? '轮到你时可提交' : '讨论阶段可用' }}</small></div>
      <div><button class="lobby-button px-button px-button-primary" :disabled="!voting || !hasPermission || !actionable" @click="$emit('vote')"><PixelIcon name="ballot" :size="21" />投票</button><small>{{ voting ? '选择玩家或弃票' : '投票阶段可用' }}</small></div>
      <div><button :class="acting && hasPermission ? 'lobby-button' : 'secondary-button'" @click="$emit('skill')"><PixelIcon :name="icon" :size="21" />{{ acting && hasPermission ? '技能行动' : '技能记录' }}</button><small>{{ acting && hasPermission ? '本次行动仅你可见' : '查看自己的记录' }}</small></div>
      <div><button class="secondary-button px-button" @click="$emit('logs')"><PixelIcon name="document" :size="21" />查看日志</button><small>本局公开记录</small></div>
    </div></slot>
  </section>
</template>
