<script setup lang="ts">
import { onMounted } from 'vue'
import IconRail from '../components/shell/IconRail.vue'
import SlidePanel from '../components/shell/SlidePanel.vue'
import SearchPanel from '../components/shell/SearchPanel.vue'
import RealmsPanel from '../components/shell/RealmsPanel.vue'
import SettingsPanel from '../components/shell/SettingsPanel.vue'
import SphereCanvas from '../components/canvas/SphereCanvas.vue'
import ScanPanel from '../components/ScanPanel.vue'
import Reader from '../components/Reader.vue'
import { useCanvasStore } from '../stores/canvas'
import { useKeybindings } from '../composables/keybindings'
import { useI18n } from 'vue-i18n'

const canvas = useCanvasStore()
const { t } = useI18n()

onMounted(() => {
  if (!canvas.loaded) canvas.loadTopLevel()
})

useKeybindings()
</script>
<template>
  <div class="home-root">
    <IconRail />
    <SlidePanel id="search" :title="t('nav.search')"><SearchPanel /></SlidePanel>
    <SlidePanel id="realms" :title="t('nav.realms')"><RealmsPanel /></SlidePanel>
    <SlidePanel id="settings" :title="t('nav.settings')"><SettingsPanel /></SlidePanel>
    <main class="canvas-slot">
      <SphereCanvas v-if="canvas.loaded" />
      <div v-else class="splash">{{ t('common.loading') }}</div>
    </main>
    <ScanPanel />
    <Reader />
  </div>
</template>

<style scoped>
.home-root { position:fixed; inset:0; background:#050510; color:#eee; }
.canvas-slot { position:absolute; inset:0 0 0 56px; }
.splash { display:grid; place-items:center; height:100%; color:#4dc4ff; }
</style>
