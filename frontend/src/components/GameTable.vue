<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { createVillage } from '@/features/home/village'
import type { GamePlayer } from '@/features/game/types'
import { roleNames } from '@/features/game/types'
import PixelAvatar from './PixelAvatar.vue'
import PixelIcon from './PixelIcon.vue'
const props = defineProps<{ players: GamePlayer[]; phase: 'day' | 'night'; me: number; speaker: number | null; selected: number | 'abstain' | null; targets: number[]; selecting: boolean; selectionLabel?: string; speakerLabel?: string }>()
const emit = defineEmits<{ choose: [seatNo: number] }>()
const canvas = ref<HTMLCanvasElement | null>(null)
const positions = [[50, 9], [73, 19], [85, 39], [80, 62], [63, 81], [37, 81], [20, 62], [15, 39], [27, 19]]
function draw() { if (canvas.value) createVillage(canvas.value).drawGameTable(props.phase) }
onMounted(draw)
watch(() => props.phase, draw)
function position(index: number) { const p = positions[index]!; return { left: `${p[0]}%`, top: `${p[1]}%` } }
function move(event: KeyboardEvent, index: number) {
  if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) return
  event.preventDefault()
  const forward = event.key === 'ArrowRight' || event.key === 'ArrowDown'
  const next = (index + (forward ? 1 : -1) + props.players.length) % props.players.length
  const cards = (event.currentTarget as HTMLElement).parentElement?.querySelectorAll<HTMLButtonElement>('.game-seat')
  cards?.[next]?.focus()
}
</script>
<template>
  <div class="game-table" :class="{ 'table-night': phase === 'night' }" aria-label="营火游戏桌">
    <canvas ref="canvas" class="game-table-art" aria-hidden="true"></canvas>
    <button v-for="(player, index) in players" :key="player.seatNo" class="game-seat" :style="position(index)" :class="{ 'seat-lower': [4, 5, 6, 7].includes(player.seatNo), 'seat-me': player.seatNo === me, 'seat-speaking': player.seatNo === speaker, 'seat-eliminated': !player.alive, 'seat-selected': selected === player.seatNo, 'seat-eligible': selecting && targets.includes(player.seatNo) }" :aria-label="`${player.seatNo} 号 ${player.name}，${player.controller === 'AI' ? 'AI，' : ''}${player.alive ? '存活' : '已淘汰'}${player.seatNo === me ? '，你' : ''}${player.seatNo === speaker ? '，' + (speakerLabel || '正在发言') : ''}${selectionLabel && selected === player.seatNo ? '，' + selectionLabel : ''}${selecting && !targets.includes(player.seatNo) ? '，不可选为目标' : ''}`" :aria-pressed="selecting || selectionLabel ? selected === player.seatNo : undefined" @keydown="move($event, index)" @click="emit('choose', player.seatNo)">
      <span class="game-seat-title"><b>{{ player.seatNo }}</b><strong>{{ player.name }}</strong></span>
      <span class="game-seat-content"><PixelAvatar :variant="player.avatar" :size="30" /><span><small v-if="player.controller === 'AI'" class="seat-type">AI</small><small v-if="player.seatNo === me" class="seat-type">你</small><small class="seat-life"><PixelIcon :name="!player.alive ? 'skull' : player.seatNo === speaker ? 'microphone' : 'leaf'" :size="12" />{{ !player.alive ? '已淘汰' : player.seatNo === speaker ? speakerLabel || '发言中' : '存活' }}</small></span></span>
      <small v-if="player.disclosedRole" class="seat-role">{{ roleNames[player.disclosedRole] }}</small>
      <span v-if="selected === player.seatNo" class="seat-selection" aria-hidden="true"><PixelIcon name="check" :size="13" /></span>
      <span class="table-portrait" aria-hidden="true"><PixelAvatar :variant="player.avatar" :size="43" /></span>
    </button>
  </div>
</template>
