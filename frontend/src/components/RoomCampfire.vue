<script setup lang="ts">
const stumps = [[190, 80], [266, 103], [292, 178], [256, 249], [174, 266], [111, 215], [101, 130], [132, 85]]
const grass = [[80, 166], [140, 139], [251, 152], [284, 223], [171, 224], [192, 119], [206, 277], [308, 152], [87, 223], [295, 84]]
</script>
<template>
  <svg class="room-campfire" viewBox="0 0 400 360" shape-rendering="crispEdges" aria-hidden="true">
    <path fill="#e9dfa8" d="M125 31h135v16h48v28h31v31h20v113h-18v41h-37v29h-39v20H137v-18H98v-29H64v-42H47V118h19V82h28V53h31z" />
    <path fill="#ebdca0" d="M141 64h99v12h47v24h28v114h-22v36h-51v23H135v-27H97V105h44z" />
    <g v-for="(point, i) in grass" :key="i" :transform="`translate(${point[0]} ${point[1]})`"><path fill="#829953" d="M0 0h3v7h3V3h3v7H0z" /><path v-if="i % 3 === 0" fill="#fbf3cc" d="M14 0h4v-4h4v4h4v4h-4v4h-4V4h-4z" /><rect v-if="i % 3 === 0" x="18" width="4" height="4" fill="#d19c41" /></g>
    <g v-for="(point, i) in stumps" :key="i" :transform="`translate(${point[0]} ${point[1]})`"><path fill="#80643e" d="M0 2h19v22H0zM-3 20h5v9h-5zM17 20h5v9h-5z" /><path fill="#ab8751" d="M0-1h19v8H0z" /><path fill="#937243" d="M3 1h12v4H3zM4 10h3v15H4z" /></g>
    <path fill="#827f65" d="M178 162h42v8h18v13h7v18h-9v14h-20v7h-45v-8h-16v-20h-5v-15h11v-9h17z" />
    <path fill="#aaa48a" d="M177 162h17v12h-17zM206 164h18v13h-18zM224 181h16v13h-16zM216 204h19v10h-19zM164 199h17v14h-17zM154 180h15v14h-15z" />
    <path fill="#564a34" d="M170 184h46v9h-46zM181 177h9v34h-9zM202 176h9v31h-9z" />
    <path fill="#d87237" d="M190 137h10v11h9v12h7v26h-9v9h-27v-10h-7v-19h9v-17h8z" />
    <path fill="#eea442" d="M190 152h9v13h8v18h-9v6h-13v-9h-6v-13h11z" />
    <path fill="#f7d563" d="M191 165h8v14h-4v6h-8v-13h4z" />
  </svg>
</template>
