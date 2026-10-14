<script setup lang="ts">
import { RouterLink } from 'vue-router'
import PixelIcon from './PixelIcon.vue'
defineProps<{ sound: boolean }>()
defineEmits<{ info: [kind: 'rules' | 'about']; sound: [] }>()
</script>

<template>
  <header class="landing-header">
    <RouterLink to="/" class="landing-brand" aria-label="WolfMind 首页">
      <span class="brand-badge"><PixelIcon name="wolf" :size="20" /></span>
      <span>WolfMind</span>
    </RouterLink>
    <nav aria-label="主导航" class="landing-nav">
      <button class="px-link" @click="$emit('info', 'rules')">玩法介绍</button>
      <button class="px-link" @click="$emit('info', 'about')">关于项目</button>
      <button class="px-icon-button" :aria-label="sound ? '关闭音效' : '开启音效'" :aria-pressed="sound" @click="$emit('sound')">
        <PixelIcon :name="sound ? 'sound' : 'muted'" :size="18" />
      </button>
    </nav>
  </header>
</template>
