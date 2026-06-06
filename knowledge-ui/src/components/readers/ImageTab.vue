<script setup lang="ts">
defineProps<{ url: string }>()
</script>

<template>
  <div class="image-tab">
    <img :src="url" alt="Original document" />
  </div>
</template>

<style scoped>
.image-tab { position: absolute; inset: 0; overflow: auto; display: flex; justify-content: center; align-items: flex-start; padding: 20px; }
.image-tab img { max-width: 100%; height: auto; }
</style>
