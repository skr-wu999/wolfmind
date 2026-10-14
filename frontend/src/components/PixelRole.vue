<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'

type PixelRoleName = 'werewolf' | 'villager' | 'seer' | 'witch' | 'hunter'
const props = withDefaults(defineProps<{ role: PixelRoleName; scale?: number }>(), { scale: 3 })
const canvas = ref<HTMLCanvasElement | null>(null)
const style = ref<Record<string, string>>({})

const SPRITES: Record<PixelRoleName, string[]> = {
  villager: ['..HHH..', '.HHHHH.', '.HSSSH.', '.SESES.', '..SSS..', '.CCCCC.', 'CCCCCCC', 'S.CCC.S', '..DDD..', '..P.P..', '..P.P..', '..B.B..'],
  werewolf: ['.G...G.', '.GG.GG.', '.GGGGG.', 'GGYGYGG', '.GGWGG.', '..gEg..', '.DDDDD.', 'DDDDDDD', 'G.DDD.G', '..DDD..', '..g.g..', '..g.g..'],
  seer: ['...M...', '..MMM..', '.MMMMM.', 'MMSSSMM', 'MSESESM', 'MMSSSMM', '.MMMMM.', 'MMMMMMM', 'MSQWQSM', '.MQQQM.', '.MMMMM.', '..B.B..'],
  witch: ['....KK.', '...KK..', '..KKK..', '.KKKKK.', 'KKKKKKK', '.HSSSH.', '.SESES.', '..SSS..', '.VVVVV.', 'SVVVVVS', '.VVVVV.', '..B.B..'],
  hunter: ['....F..', '..TTTF.', '.TTTTT.', 'TTTTTTT', '.HSSSH.', '.SESES.', '..SSS..', '.CCCC.L', 'SCCCCSL', '.CCCC.L', '..P.P..', '..B.B..'],
}
const COMMON: Record<string, string> = { S: '#f0c69a', E: '#2a2230', P: '#4a4658', B: '#3a2a22' }
const COLORS: Record<PixelRoleName, Record<string, string>> = {
  villager: { H: '#5a3a26', C: '#b8573a', D: '#6e4a33' },
  werewolf: { G: '#8b8a98', g: '#5f5e6c', Y: '#ffd23a', W: '#cfcfd8', D: '#3b3548' },
  seer: { M: '#7a5aa0', Q: '#8fd6e0', W: '#ffffff' },
  witch: { K: '#2f2a3a', V: '#4f8a5a', H: '#3a2a2a' },
  hunter: { T: '#6e4a33', F: '#d8643a', C: '#5f7a45', H: '#4a3020', L: '#b07a48' },
}
const OUTLINE = '#2a2230'

function draw() {
  const el = canvas.value
  const ctx = el?.getContext('2d')
  if (!el || !ctx) return
  const rows = SPRITES[props.role], colors = { ...COMMON, ...COLORS[props.role] }
  const w = rows[0].length, h = rows.length
  el.width = w + 2
  el.height = h + 2
  const at = (x: number, y: number) => (x < 0 || y < 0 || x >= w || y >= h ? '.' : rows[y][x])
  for (let y = -1; y <= h; y++) for (let x = -1; x <= w; x++) {
    const ch = at(x, y)
    let fill: string | null = null
    if (ch !== '.') fill = colors[ch] ?? OUTLINE
    else if (at(x - 1, y) !== '.' || at(x + 1, y) !== '.' || at(x, y - 1) !== '.' || at(x, y + 1) !== '.') fill = OUTLINE
    if (!fill) continue
    ctx.fillStyle = fill
    ctx.fillRect(x + 1, y + 1, 1, 1)
  }
  const dpr = window.devicePixelRatio || 1
  const unit = Math.max(1, Math.round(props.scale * dpr)) / dpr
  style.value = { width: `${(w + 2) * unit}px`, height: `${(h + 2) * unit}px` }
}

onMounted(draw)
watch(() => [props.role, props.scale], draw)
</script>

<template>
  <canvas ref="canvas" class="pixel-role" :style="style" aria-hidden="true"></canvas>
</template>
