<script setup lang="ts">
withDefaults(defineProps<{ name?: string; size?: number }>(), { name: 'wolf', size: 24 })
const paths: Record<string, string> = {
  microphone: 'M9 1h6v13H9zM5 9h2v7h10V9h2v9h-6v3h4v2H7v-2h4v-3H5z',
  speech: 'M2 2h20v15h-9l-6 6v-6H2zM6 7v3h3V7zM11 7v3h3V7zM16 7v3h3V7z',
  skull: 'M6 1h12v3h4v12h-4v7H6v-7H2V4h4zM6 7v5h4V7zM14 7v5h4V7zM10 14v3h4v-3zM9 19v4h2v-4zM13 19v4h2v-4z',
  clock: 'M6 1h12v3h4v4h2v8h-2v4h-4v3H6v-3H2v-4H0V8h2V4h4zM6 4v3H3v10h3v3h12v-3h3V7h-3V4zM10 6h3v7h5v3h-8z',
  bottle: 'M8 1h8v4h-2v4h5v3h3v11H2V12h3V9h5V5H8zM6 14v6h12v-6z',
  shield: 'M2 2h20v12h-3v4h-4v4H9v-4H5v-4H2zM6 6v7h3v4h6v-4h3V6z',
  gun: 'M0 7h24v5H12v4H8v-4H4v11H0zM14 12h3v7h-7v-3h4z',
  skip: 'M3 1h18v3h3v17h-3v3H3v-3H0V4h3zM4 4v14h3v3h13v-3h1V7h-3V4zM5 5h4v4h4v4h4v4h4v4h-4v-4h-4v-4H9V9H5z',
  check: 'M1 12h4v4h3v3h3v-4h3v-4h3V7h3V3h4v6h-3v4h-3v4h-3v4h-4v3H7v-3H4v-3H1z',
  document: 'M4 1h12v3h4v19H4zM7 6v2h7V6zM7 11v2h10v-2zM7 16v2h10v-2z',
  copy: 'M7 1h14v18H7zM9 3v14h10V3zM3 5h2v16h12v2H3z',
  tag: 'M12 1h11v11L12 23H7L1 17v-5zM16 4v4h4V4z',
  gamepad: 'M4 4h16v3h2v14h-4v-4H6v4H2V7h2zM6 8v3H3v3h3v3h3v-3h3v-3H9V8zM16 8v3h3V8zM19 12v3h3v-3z',
  settings: 'M9 0h6v4h4V2h3v6h-4v3h6v4h-6v3h4v6h-3v-3h-4v3H9v-3H5v3H2v-6h4v-3H0v-4h6V8H2V2h3v2h4zM9 8v8h6V8z',
  plus: 'M10 2h4v8h8v4h-8v8h-4v-8H2v-4h8z',
  door: 'M4 1h15v2h3v20H2v-3h2z M7 4v16h9V4z M11 11h3v3h-3z',
  key: 'M3 2h9v3h3v9h-3v3H7v3H4v3H0v-7h3v-3H0V5h3z M5 5v6h6V5z',
  refresh: 'M5 2h12v3h3v3h4v7H13V8h4V5H7v3H4v9h3v3h10v-3h3v6H5v-3H2V5h3z',
  tree: 'M10 0h4v4h3v5h3v5h3v5h-9v5h-4v-5H1v-5h3V9h3V4h3z',
  wolf: 'M2 1h5v4h10V1h5v15h-3v5H5v-5H2z M5 8v4h4V8z M15 8v4h4V8z M10 15v4h4v-4z',
  sun: 'M10 0h4v4h-4z M10 20h4v4h-4z M0 10h4v4H0z M20 10h4v4h-4z M3 3h3v3H3z M18 3h3v3h-3z M3 18h3v3H3z M18 18h3v3h-3z M8 6h8v2h2v8h-2v2H8v-2H6V8h2z',
  moon: 'M10 1h6v3h-5v4H8v8h4v3h8v-4h3v6h-4v3H8v-3H4v-4H1V8h3V4h6z',
  arrow: 'M2 10h15V6h-4V3h4v3h4v4h3v4h-3v4h-4v3h-4v-3h4v-4H2z',
  play: 'M5 2h4v3h4v3h4v3h4v3h-4v3h-4v3H9v3H5z',
  pause: 'M5 3h5v18H5z M14 3h5v18h-5z',
  sound: 'M2 9h4V6h4V3h4v18h-4v-3H6v-3H2z M17 7h3v3h2v4h-2v3h-3v-3h2v-4h-2z',
  muted: 'M2 9h4V6h4V3h4v18h-4v-3H6v-3H2z M17 8h2v2h2V8h2v2h-2v4h2v2h-2v-2h-2v2h-2v-2h2v-4h-2z',
  ballot: 'M6 1h12v10H6z M9 4v4h6V4z M3 12h18v3h2v8H1v-8h2z M7 14v3h10v-3z',
  close: 'M4 2h3v3h3v3h4V5h3V2h3v3h-3v3h-3v6h3v3h3v3h-3v-3h-3v-3h-4v3H7v3H4v-3h3v-3h3V8H7V5H4z',
  people: 'M3 3h6v6H3z M15 3h6v6h-6z M1 11h10v10H1z M13 11h10v10H13z',
  eye: 'M6 5h12v3h3v3h3v3h-3v3h-3v3H6v-3H3v-3H0v-3h3V8h3z M6 8v9h12V8z M10 10h4v5h-4z',
  leaf: 'M12 1h10v10h-3v4h-4v4h-4v4H7v-4H3v-8h4V7h5z M9 12v7h3v-7h7V9h-7v3z',
}
</script>

<template>
  <svg :width="size" :height="size" viewBox="0 0 24 24" fill="currentColor" fill-rule="evenodd" shape-rendering="crispEdges" aria-hidden="true"><path :d="paths[name] || paths.wolf" /></svg>
</template>
