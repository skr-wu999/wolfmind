<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { createFogVillage, fitViewport } from '@/features/home/fogVillage'
import type { FogVillageRenderer } from '@/features/home/fogVillage'
import { useHomeDemoStore } from '@/stores/homeDemo'

const emit = defineEmits<{ phaseChanged: [] }>()
const demo = useHomeDemoStore()
const { phase, elapsed, paused } = storeToRefs(demo)
const scene = ref<HTMLDivElement | null>(null)
const canvas = ref<HTMLCanvasElement | null>(null)
const canvasStyle = ref<Record<string, string>>({})
let renderer: FogVillageRenderer | null = null
let observer: ResizeObserver | undefined
let motionQuery: MediaQueryList | undefined
let frame = 0
let previous: number | undefined
let lastPaint = 0
let sceneTime = 0

function fit() {
  if (!scene.value || !renderer) return
  const view = fitViewport(scene.value.clientWidth, scene.value.clientHeight, window.devicePixelRatio || 1)
  renderer.resize(view.W, view.H)
  canvasStyle.value = {
    width: `${view.cssWidth}px`,
    height: `${view.cssHeight}px`,
    left: `${(scene.value.clientWidth - view.cssWidth) / 2}px`,
  }
  lastPaint = 0
}

function tick(now: number) {
  const dt = previous === undefined ? 0 : Math.min(0.25, (now - previous) / 1000)
  previous = now
  if (!paused.value) {
    sceneTime += dt
    if (demo.advance(dt)) emit('phaseChanged')
  }
  if (renderer && now - lastPaint >= 1000 / 30) {
    renderer.render({ time: sceneTime, phase: phase.value, phaseTime: elapsed.value, now })
    lastPaint = now
  }
  frame = requestAnimationFrame(tick)
}

function changeMotion(event: Pick<MediaQueryListEvent, 'matches'>) {
  if (event.matches) paused.value = true
}

onMounted(() => {
  if (!scene.value || !canvas.value) return
  renderer = createFogVillage(canvas.value)
  if (!renderer) return
  fit()
  observer = new ResizeObserver(fit)
  observer.observe(scene.value)
  motionQuery = matchMedia('(prefers-reduced-motion: reduce)')
  changeMotion(motionQuery)
  motionQuery.addEventListener('change', changeMotion)
  frame = requestAnimationFrame(tick)
})

onBeforeUnmount(() => {
  cancelAnimationFrame(frame)
  observer?.disconnect()
  motionQuery?.removeEventListener('change', changeMotion)
  demo.reset()
})
</script>

<template>
  <div ref="scene" class="fog-scene" aria-hidden="true">
    <canvas ref="canvas" :style="canvasStyle"></canvas>
  </div>
</template>
