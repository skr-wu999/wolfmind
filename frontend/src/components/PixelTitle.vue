<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = withDefaults(defineProps<{ text: string; size?: number }>(), { size: 22 })
const canvas = ref<HTMLCanvasElement | null>(null)
const style = ref<Record<string, string>>({})
const FONT = '"Microsoft YaHei", "PingFang SC", "Noto Sans SC", "Source Han Sans SC", sans-serif'
const COLORS = { shadow: [26, 21, 36], outline: [42, 34, 48], top: [255, 246, 223], bottom: [238, 205, 148] }

function draw() {
  const out = canvas.value
  const ctx = out?.getContext('2d')
  if (!out || !ctx || typeof ctx.getImageData !== 'function') return
  const fs = props.size, gap = 2
  const scratch = document.createElement('canvas')
  const sctx = scratch.getContext('2d')
  if (!sctx) return
  sctx.font = `900 ${fs}px ${FONT}`
  const chars = [...props.text]
  const widths = chars.map((ch) => Math.ceil(sctx.measureText(ch).width))
  const w = widths.reduce((a, b) => a + b, 0) + gap * (chars.length - 1) + 2, h = Math.ceil(fs * 1.3)
  scratch.width = w
  scratch.height = h
  sctx.font = `900 ${fs}px ${FONT}`
  sctx.textBaseline = 'middle'
  sctx.fillStyle = '#000'
  let cursor = 1
  chars.forEach((ch, i) => { sctx.fillText(ch, cursor, h / 2); cursor += widths[i] + gap })
  const src = sctx.getImageData(0, 0, w, h).data
  const pad = 3, W = w + pad * 2, H = h + pad * 2
  const mask = new Uint8Array(W * H)
  let minY = H, maxY = 0
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    if (src[(y * w + x) * 4 + 3] < 120) continue
    mask[(y + pad) * W + x + pad] = 1
    minY = Math.min(minY, y + pad); maxY = Math.max(maxY, y + pad)
  }
  const on = (x: number, y: number) => x >= 0 && y >= 0 && x < W && y < H && mask[y * W + x] === 1
  const ring = (x: number, y: number) => on(x - 1, y) || on(x + 1, y) || on(x, y - 1) || on(x, y + 1) || on(x - 1, y - 1) || on(x + 1, y + 1) || on(x - 1, y + 1) || on(x + 1, y - 1)
  out.width = W
  out.height = H
  const image = ctx.createImageData(W, H)
  const split = minY + Math.round((maxY - minY) * 0.52)
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    let c: number[] | null = null
    if (on(x, y)) c = y === minY || !on(x, y - 1) ? COLORS.top : y > split ? COLORS.bottom : COLORS.top
    else if (ring(x, y)) c = COLORS.outline
    else if (ring(x - 2, y - 2) || on(x - 2, y - 2)) c = COLORS.shadow
    if (!c) continue
    const i = (y * W + x) * 4
    image.data[i] = c[0]; image.data[i + 1] = c[1]; image.data[i + 2] = c[2]; image.data[i + 3] = 255
  }
  ctx.putImageData(image, 0, 0)
  const cssScale = window.innerWidth < 640 ? 3 : 4
  const dpr = window.devicePixelRatio || 1
  const unit = Math.max(1, Math.round(cssScale * dpr)) / dpr
  style.value = { width: `${W * unit}px`, height: `${H * unit}px` }
}

onMounted(() => {
  draw()
  window.addEventListener('resize', draw)
})
onBeforeUnmount(() => window.removeEventListener('resize', draw))
watch(() => [props.text, props.size], draw)
</script>

<template>
  <span class="pixel-title"><canvas ref="canvas" :style="style" aria-hidden="true"></canvas><span class="sr-only">{{ text }}</span></span>
</template>
