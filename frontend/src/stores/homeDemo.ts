import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { VillagePhase } from '@/features/home/village'

export const useHomeDemoStore = defineStore('homeDemo', () => {
  const phase = ref<VillagePhase>('day')
  const elapsed = ref(0)
  const paused = ref(false)
  const vote = ref<number | null>(null)
  const day = computed(() => phase.value === 'day')
  const seconds = computed(() => Math.max(1, Math.ceil(15 - elapsed.value)))
  const remaining = computed(() => Math.max(0, 15 - elapsed.value))

  function setPhase(value: VillagePhase) {
    phase.value = value
    elapsed.value = 0
    vote.value = null
  }

  function advance(dt: number): boolean {
    if (paused.value) return false
    const total = elapsed.value + dt
    const switches = Math.floor(total / 15)
    if (switches > 0) {
      if (switches % 2 === 1) phase.value = day.value ? 'night' : 'day'
      vote.value = null
    }
    elapsed.value = total % 15
    return switches > 0
  }

  function reset() {
    setPhase('day')
    paused.value = false
  }

  return { phase, elapsed, paused, vote, day, seconds, remaining, setPhase, advance, reset }
})
