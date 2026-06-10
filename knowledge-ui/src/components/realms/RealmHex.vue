<script setup lang="ts">
withDefaults(defineProps<{ color: string; label: string; size?: number }>(), { size: 30 })
</script>

<template>
  <div class="hex-av" :style="{ '--s': size + 'px', '--c': color }">
    <span>{{ label }}</span>
  </div>
</template>

<style scoped>
.hex-av { --s:30px; width:var(--s); height:calc(var(--s) * 1.1); flex:none;
  display:grid; place-items:center; position:relative; color:#fff; font-weight:600;
  font-family:var(--font-display); font-size:calc(var(--s) * 0.4); }
.hex-av::before { content:''; position:absolute; inset:0; background:var(--c, var(--honey));
  clip-path:polygon(50% 0,100% 25%,100% 75%,50% 100%,0 75%,0 25%); }
.hex-av span { position:relative; z-index:1; }
</style>
