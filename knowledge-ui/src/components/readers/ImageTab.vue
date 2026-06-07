<script setup lang="ts">
import { useI18n } from 'vue-i18n'
defineProps<{ url: string }>()
const { t } = useI18n()
</script>

<template>
  <div class="image-tab">
    <img :src="url" :alt="t('reader.imageAlt')" />
  </div>
</template>

<style scoped>
.image-tab { position: absolute; inset: 0; overflow: auto; display: flex; justify-content: center; align-items: flex-start; padding: 20px; }
.image-tab img { max-width: 100%; height: auto; }
</style>
