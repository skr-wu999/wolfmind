<script setup lang="ts">
import type { GamePlayer } from '@/features/game/types'
import PixelAvatar from './PixelAvatar.vue'
import PixelIcon from './PixelIcon.vue'
defineProps<{ players: GamePlayer[]; targets: number[]; selected: number | 'abstain' | null; allowEmpty?: boolean; emptyLabel?: string; disabled?: boolean }>()
defineEmits<{ select: [value: number | 'abstain'] }>()
</script>
<template>
  <div class="game-targets" role="group" aria-label="选择行动目标">
    <button v-for="player in players.filter(p => targets.includes(p.seatNo))" :key="player.seatNo" :disabled="disabled" :aria-pressed="selected === player.seatNo" :aria-label="`选择 ${player.seatNo} 号 ${player.name}`" :class="{ selected: selected === player.seatNo }" @click="$emit('select', player.seatNo)"><b>{{ player.seatNo }}</b><PixelAvatar :variant="player.avatar" :size="27" /><strong>{{ player.name }}</strong><span class="target-radio" aria-hidden="true">{{ selected === player.seatNo ? '●' : '○' }}</span></button>
    <button v-if="allowEmpty" :disabled="disabled" :aria-pressed="selected === 'abstain'" :class="{ selected: selected === 'abstain' }" @click="$emit('select', 'abstain')"><PixelIcon name="skip" :size="20" /><strong>{{ emptyLabel || '弃票' }}</strong><span class="target-radio" aria-hidden="true">{{ selected === 'abstain' ? '●' : '○' }}</span></button>
    <p v-if="!targets.length && !allowEmpty" class="game-muted">当前没有可选目标。</p>
  </div>
</template>
