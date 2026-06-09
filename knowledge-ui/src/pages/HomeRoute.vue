<script setup lang="ts">
import { onMounted } from 'vue'
import SphereCanvas from '../components/canvas/SphereCanvas.vue'
import { useCanvasStore } from '../stores/canvas'
import { useKeybindings } from '../composables/keybindings'
import { useI18n } from 'vue-i18n'

const canvas = useCanvasStore()
const { t } = useI18n()
onMounted(() => { if (!canvas.loaded) canvas.loadTopLevel() })
useKeybindings()
</script>
<template>
  <div class="hive-stage">
    <SphereCanvas v-if="canvas.loaded" />
    <div v-else class="splash">{{ t('common.loading') }}</div>
  </div>
</template>
<style scoped>
.hive-stage { position:absolute; inset:0; }
.splash { display:grid; place-items:center; height:100%; color:var(--honey); }
</style>
