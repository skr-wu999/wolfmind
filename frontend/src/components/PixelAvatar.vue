<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { createVillage } from '@/features/home/village'
import type { DemoSeat } from '@/features/lobby/types'
const props = defineProps<{ variant: DemoSeat['avatar']; size?: number }>()
const canvas = ref<HTMLCanvasElement | null>(null)
const colors = { green: '#588861', blue: '#478597', red: '#b96749' }
function draw() { if (canvas.value && props.variant in colors) createVillage(canvas.value).drawPortrait(colors[props.variant as keyof typeof colors]) }
onMounted(draw)
watch(() => props.variant, draw, { flush: 'post' })
</script>

<template>
  <canvas v-if="variant in colors" ref="canvas" class="pixel-avatar" :style="{ width: `${size || 34}px`, height: `${size || 34}px` }" aria-hidden="true"></canvas>
  <svg v-else class="pixel-avatar" :width="size || 34" :height="size || 34" viewBox="0 0 32 32" shape-rendering="crispEdges" aria-hidden="true">
    <path fill="#383c37" d="M3 1h6v5h14V1h6v8h2v21H1V9h2z" />
    <path :fill="variant === 'wolf' ? '#92998c' : variant === 'fox' ? '#ece8dd' : '#a98d69'" d="M5 4h3v6h16V4h3v8h2v16H3V12h2z" />
    <path :fill="variant === 'owl' ? '#eee0b7' : '#f7f0df'" d="M5 14h9v12H5zM18 14h9v12h-9zM12 23h9v5h-9z" />
    <path :fill="variant === 'fox' ? '#c85f35' : '#474b43'" d="M5 10h8v7H5zM19 10h8v7h-8z" />
    <path fill="#2c3934" d="M8 14h3v4H8zM21 14h3v4h-3zM13 22h7v3h-7z" />
    <path fill="#fff9e8" d="M9 14h2v2H9zM22 14h2v2h-2z" />
  </svg>
</template>
