<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { createVillage, projectVillageActors } from '@/features/home/village'
import type { ProjectedVillageActor, SceneBounds, VillageRenderer } from '@/features/home/village'
import { useHomeDemoStore } from '@/stores/homeDemo'

const emit = defineEmits<{ phaseChanged: [] }>()
const props = defineProps<{ controlledPhase?: 'day' | 'night'; hideActors?: boolean; dusk?: boolean }>()
const demo = useHomeDemoStore()
const { phase, elapsed, paused } = storeToRefs(demo)
const canvas = ref<HTMLCanvasElement | null>(null)
const scene = ref<HTMLDivElement | null>(null)
const actors = ref<ProjectedVillageActor[]>([])
const hoveredActor = ref<number | null>(null)
const talkingActor = ref<number | null>(null)
const activeActor = computed(() => talkingActor.value ?? hoveredActor.value)
const reducedMotion = ref(false)
const actorLines = {
  day: ['先听完大家的发言。', '昨晚？我一直在家。', '别急，再想一想。', '你也觉得他可疑吗？', '今天的票，可要想清楚。', '村里的事，我都听着呢。'],
  night: ['小声点，从这扇门进去。', '该回屋了，关好门。', '', '跟紧我，别惊动村民。', '', ''],
}
let renderer: VillageRenderer
let frame = 0
let previousTime: number | undefined
let sceneTime = 0
let lastActorUpdate = 0
let motionQuery: MediaQueryList | undefined
let observer: ResizeObserver | undefined
let actorTimer: ReturnType<typeof setTimeout> | undefined
let bounds: SceneBounds = { width: 0, height: 0, crop: .5 }

function clearActor() {
  clearTimeout(actorTimer)
  talkingActor.value = null
  hoveredActor.value = null
}
function talkToActor(id: number) {
  clearTimeout(actorTimer)
  talkingActor.value = id
  actorTimer = setTimeout(() => { talkingActor.value = null }, 4000)
}
function highlightActor(id: number) {
  if (talkingActor.value !== id) clearActor()
  hoveredActor.value = id
}
function updateBounds() {
  if (!scene.value) return
  const style = getComputedStyle(scene.value)
  bounds = {
    width: scene.value.clientWidth,
    height: scene.value.clientHeight,
    crop: parseFloat(style.getPropertyValue('--scene-position')) / 100,
    anchorY: parseFloat(style.getPropertyValue('--scene-vertical-position')) / 100,
  }
  lastActorUpdate = 0
}
function animate(timestamp: number) {
  const dt = previousTime === undefined ? 0 : (timestamp - previousTime) / 1000
  previousTime = timestamp
  if (!paused.value && !props.controlledPhase) {
    sceneTime += dt
    if (demo.advance(dt)) emit('phaseChanged')
  }
  const interaction = activeActor.value === null ? null : { id: activeActor.value, text: actorLines[phase.value][activeActor.value]! }
  const visibleActors = renderer.draw(sceneTime, props.controlledPhase || phase.value, props.controlledPhase ? 0 : elapsed.value, reducedMotion.value, interaction, { ...bounds, hideActors: props.hideActors, dusk: props.dusk })
  if (timestamp - lastActorUpdate > 50) {
    actors.value = projectVillageActors(visibleActors, bounds)
    if (activeActor.value !== null && !actors.value.some((actor) => actor.id === activeActor.value)) clearActor()
    lastActorUpdate = timestamp
  }
  frame = requestAnimationFrame(animate)
}
function changeMotion(event: Pick<MediaQueryListEvent, 'matches'>) {
  reducedMotion.value = event.matches
  if (event.matches) paused.value = true
}
watch(phase, clearActor)
watch(elapsed, (value) => { if (value === 0) clearActor() })
onMounted(() => {
  if (!canvas.value || !scene.value) return
  renderer = createVillage(canvas.value)
  updateBounds()
  observer = new ResizeObserver(updateBounds)
  observer.observe(scene.value)
  motionQuery = matchMedia('(prefers-reduced-motion: reduce)')
  changeMotion(motionQuery)
  motionQuery.addEventListener('change', changeMotion)
  frame = requestAnimationFrame(animate)
})
onBeforeUnmount(() => {
  cancelAnimationFrame(frame)
  observer?.disconnect()
  motionQuery?.removeEventListener('change', changeMotion)
  clearTimeout(actorTimer)
  demo.reset()
})
</script>

<template>
  <div ref="scene" class="scene" :aria-label="hideActors ? '雾隐村游戏场景' : '雾隐村，点击村民可以交谈'" @keydown.esc="clearActor">
    <canvas ref="canvas" aria-hidden="true" @click="clearActor"></canvas>
    <button v-for="actor in actors" :key="actor.id" class="npc-target" :class="{ 'npc-active': activeActor === actor.id }" :style="{ left: `${actor.screenX}px`, top: `${actor.screenY}px` }" :aria-label="`与${actor.name}交谈`" :aria-describedby="activeActor === actor.id ? `npc-line-${actor.id}` : undefined" @pointerenter="highlightActor(actor.id)" @pointerleave="hoveredActor = null" @focus="highlightActor(actor.id)" @blur="hoveredActor = null" @click="talkToActor(actor.id)">
      <span class="npc-focus-corners" aria-hidden="true"></span>
      <span v-if="activeActor === actor.id" :id="`npc-line-${actor.id}`" role="tooltip" class="sr-only">{{ actor.name }}：{{ actorLines[phase][actor.id] }}</span>
    </button>
  </div>
</template>
