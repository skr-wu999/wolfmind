<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ label: string; value: number | null; tone?: 'wolf' | 'village' | 'neutral'; unit?: '%' | '分'; signed?: boolean }>()
const valid = computed(() => props.value !== null && Number.isFinite(props.value))
const display = computed(() => valid.value ? `${props.signed && props.value! > 0 ? '+' : ''}${props.value}${props.unit || '%'}` : '暂无数据')
const width = computed(() => valid.value ? Math.max(0, Math.min(100, props.signed ? Math.abs(props.value!) : props.value!)) : 0)
</script>
<template><div class="analysis-metric" :class="tone || 'neutral'"><span>{{ label }}</span><div v-if="valid" class="analysis-metric-track" aria-hidden="true"><span :style="{ width: `${width}%` }"></span></div><strong>{{ display }}</strong></div></template>
