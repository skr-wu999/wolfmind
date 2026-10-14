import { onBeforeUnmount, ref } from 'vue'
import { useHomeDemoStore } from '@/stores/homeDemo'

export function useVillageAudio(onError: () => void) {
  const demo = useHomeDemoStore()
  const sound = ref(false)
  let context: AudioContext | undefined
  let timer: ReturnType<typeof setInterval> | undefined
  function tone(frequency: number, duration: number, volume: number) {
    if (!context || context.state !== 'running') return
    const oscillator = context.createOscillator()
    const gain = context.createGain()
    oscillator.type = 'triangle'
    oscillator.frequency.value = frequency
    gain.gain.setValueAtTime(0, context.currentTime)
    gain.gain.linearRampToValueAtTime(volume, context.currentTime + .04)
    gain.gain.exponentialRampToValueAtTime(.001, context.currentTime + duration)
    oscillator.connect(gain)
    gain.connect(context.destination)
    oscillator.start()
    oscillator.stop(context.currentTime + duration)
  }
  function playChime() { if (sound.value) tone(demo.day ? 660 : 330, .5, .045) }
  async function toggleSound() {
    if (sound.value) {
      sound.value = false
      clearInterval(timer)
      await context?.suspend()
      return
    }
    try {
      const Audio = window.AudioContext || (window as Window & { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
      if (!Audio) throw new Error('AudioContext unavailable')
      context ||= new Audio()
      await context.resume()
      sound.value = true
      playChime()
      timer = setInterval(() => {
        if (demo.paused || document.hidden) return
        tone(demo.day ? 880 + Math.random() * 300 : 220 + Math.random() * 80, .2, .013)
      }, 1800)
    } catch { sound.value = false; onError() }
  }
  onBeforeUnmount(() => { clearInterval(timer); void context?.close() })
  return { sound, playChime, toggleSound }
}
