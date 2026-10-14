<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { createVillage } from '@/features/home/village'
const props = withDefaults(defineProps<{ variant?: 'red' | 'grey' | 'trees'; large?: boolean }>(), { variant: 'red', large: false })
const canvas = ref<HTMLCanvasElement | null>(null)
onMounted(() => {
  if (!canvas.value) return
  const source = document.createElement('canvas')
  createVillage(source).drawIllustration(props.variant)
  const ctx = canvas.value.getContext('2d')
  if (!ctx) return
  ctx.imageSmoothingEnabled = false
  const crop = props.large ? [73, 274, 160, 136] : [82, 284, 142, 123]
  ctx.drawImage(source, crop[0]!, crop[1]!, crop[2]!, crop[3]!, 0, 0, canvas.value.width, canvas.value.height)
})
</script>

<template>
  <canvas ref="canvas" :width="large ? 228 : 64" :height="large ? 175 : 56" :class="['village-illustration', { 'illustration-large': large }]" aria-hidden="true"></canvas>
</template>
