<script setup lang="ts">
import { ref } from 'vue'
import PixelIcon from './PixelIcon.vue'
import PixelAvatar from './PixelAvatar.vue'
import RoomCampfire from './RoomCampfire.vue'
import type { DemoSeat } from '@/features/lobby/types'
defineProps<{ seats: DemoSeat[]; ownerSeatNo: number | null; loading?: boolean }>()
defineEmits<{ select: [seatNo: number] }>()
const board = ref<HTMLElement | null>(null)
const positions = [[50, 15], [26, 26], [74, 26], [84, 49], [16, 49], [23, 70], [50, 85], [77, 72], [16, 90]]
function moveFocus(event: KeyboardEvent, seatNo: number) {
  const step = ['ArrowRight', 'ArrowDown'].includes(event.key) ? 1 : ['ArrowLeft', 'ArrowUp'].includes(event.key) ? -1 : 0
  if (!step) return
  event.preventDefault()
  board.value?.querySelector<HTMLButtonElement>(`[data-seat="${(seatNo - 1 + step + 9) % 9 + 1}"]`)?.focus()
}
</script>
<template>
  <div ref="board" class="seat-board" role="group" aria-label="九人座位布局">
    <RoomCampfire />
    <button v-for="seat in seats" :key="seat.seatNo" :data-seat="seat.seatNo" class="waiting-seat" :class="{ 'empty-waiting-seat': !seat.name, 'my-waiting-seat': seat.isMe, 'seat-loading': loading }" :style="{ left: `${positions[seat.seatNo - 1]![0]}%`, top: `${positions[seat.seatNo - 1]![1]}%` }" :disabled="loading" :aria-label="`${seat.seatNo}号座位，${seat.name ? `${seat.name}，${seat.controller === 'AI' ? 'AI' : '玩家'}，${seat.ready ? '已准备' : '未准备'}${seat.isMe ? '，你的座位' : ''}` : '等待加入'}，查看详情`" @click="$emit('select', seat.seatNo)" @keydown="moveFocus($event, seat.seatNo)">
      <span class="seat-number">{{ seat.seatNo }}号</span>
      <template v-if="seat.name"><span class="seat-kind" :class="{ 'seat-owner': seat.seatNo === ownerSeatNo }">{{ seat.seatNo === ownerSeatNo ? '房主' : seat.controller === 'AI' ? 'AI' : seat.isMe ? '你' : '玩家' }}</span><span class="seat-person"><PixelAvatar :variant="seat.avatar" :size="34" /><strong>{{ seat.name }}</strong></span><span class="ready-badge" :class="{ 'is-ready': seat.ready }"><i aria-hidden="true"></i>{{ seat.ready ? '已准备' : '未准备' }}</span></template>
      <template v-else><PixelIcon name="plus" :size="20" /><span>{{ loading ? '加载中' : '等待加入' }}</span></template>
    </button>
  </div>
</template>
