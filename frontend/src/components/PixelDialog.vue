<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, useId, watch } from 'vue'
import PixelIcon from './PixelIcon.vue'
const props = defineProps<{ open: boolean; title: string; eyebrow?: string }>()
const emit = defineEmits<{ 'update:open': [value: boolean]; 'focus-lost': [] }>()
const dialog = ref<HTMLDialogElement | null>(null)
const titleId = `dialog-${useId()}`
let returnFocus: HTMLElement | null = null
watch(() => props.open, async (open) => {
  if (open) {
    returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    await nextTick()
    if (!props.open) return
    dialog.value?.showModal()
    ;(dialog.value?.querySelector<HTMLElement>('input, select, textarea') || dialog.value?.querySelector<HTMLElement>('button'))?.focus()
  } else dialog.value?.close()
}, { immediate: true })
watch(() => props.title, async () => {
  if (!props.open) return
  await nextTick()
  if (dialog.value?.open && !dialog.value.contains(document.activeElement)) dialog.value.querySelector<HTMLElement>('input, select, textarea, button')?.focus()
})
function afterClose() {
  emit('update:open', false)
  returnFocus?.focus()
  // 打开对话框的按钮可能已随提交被移除或禁用，焦点会落回 body；交给页面放到合适的位置。
  if (!document.activeElement || document.activeElement === document.body) emit('focus-lost')
}
function clickBackdrop(event: MouseEvent) {
  if (event.target !== dialog.value || !dialog.value) return
  const rect = dialog.value.getBoundingClientRect()
  if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) emit('update:open', false)
}
onBeforeUnmount(() => dialog.value?.close())
</script>

<template>
  <dialog ref="dialog" class="pixel-dialog framed-dialog" :aria-labelledby="titleId" @close="afterClose" @click="clickBackdrop">
    <div class="dialog-header"><span class="dialog-eyebrow">{{ eyebrow || 'WOLFMIND / 雾隐村' }}</span><button class="close-button" aria-label="关闭窗口" @click="$emit('update:open', false)"><PixelIcon name="close" :size="18" /></button></div>
    <h2 :id="titleId">{{ title }}</h2>
    <slot />
  </dialog>
</template>
