<script setup lang="ts">
import { onMounted, ref } from 'vue'
import SphereCanvas from '../components/canvas/SphereCanvas.vue'
import { useCanvasStore } from '../stores/canvas'
import { useKeybindings } from '../composables/keybindings'
import { useI18n } from 'vue-i18n'

const canvas = useCanvasStore()
const { t } = useI18n()
const loadError = ref(false)

async function load() {
  loadError.value = false
  try { await canvas.loadTopLevel() } catch { loadError.value = true }
}
onMounted(() => { if (!canvas.loaded) void load() })
useKeybindings()
</script>
<template>
  <div class="hive-stage">
    <SphereCanvas v-if="canvas.loaded" />
    <div v-else-if="loadError" class="splash err">
      <span>{{ t('common.loadFailed') }}</span>
      <button class="retry" @click="load">{{ t('common.retry') }}</button>
    </div>
    <div v-else class="splash">{{ t('common.loading') }}</div>
  </div>
</template>
<style scoped>
.hive-stage { position:absolute; inset:0; }
.splash { display:grid; place-items:center; height:100%; color:var(--honey); }
.splash.err { align-content:center; justify-items:center; gap:12px; color:var(--text-1); }
.retry { padding:7px 16px; border-radius:8px; border:1px solid var(--line-honey, rgba(240,180,40,.3));
  background:var(--honey-dim, rgba(240,180,40,.08)); color:var(--honey); font-size:13px; cursor:pointer; }
.retry:hover { background:var(--honey-dim, rgba(240,180,40,.16)); }
</style>
