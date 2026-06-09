<script setup lang="ts">
import { computed } from 'vue'
const props = withDefaults(defineProps<{ value: number; size?: number }>(), { size: 26 })
const r = 9
const c = 2 * Math.PI * r
const off = computed(() => c * (1 - Math.max(0, Math.min(1, props.value))))
</script>
<template>
  <svg :width="size" :height="size" viewBox="0 0 26 26" style="flex:none">
    <circle cx="13" cy="13" :r="r" fill="none" stroke="var(--bg-4)" stroke-width="3" />
    <circle cx="13" cy="13" :r="r" fill="none" stroke="var(--honey)" stroke-width="3"
      :stroke-dasharray="c" :stroke-dashoffset="off" stroke-linecap="round" transform="rotate(-90 13 13)" />
  </svg>
</template>
