<script setup lang="ts">
import { onMounted } from 'vue'
import ScanPanel from '../components/ScanPanel.vue'
import ForceGraphBridge from '../components/graph/ForceGraphBridge.vue'
import { useCanvasStore } from '../stores/canvas'
import { useKeybindings } from '../composables/keybindings'
import { useI18n } from 'vue-i18n'

// The rail, side panels and the global Reader are owned by AppShell (SP-A); this
// route is just the force-graph stage plus the node-detail ScanPanel. In-graph
// search/realm panels are reintroduced in the graph restyle (SP-F).
const canvas = useCanvasStore()
const { t } = useI18n()

onMounted(() => {
  if (!canvas.loaded) canvas.loadTopLevel()
})

useKeybindings()
</script>

<template>
  <div class="graph-stage">
    <ForceGraphBridge v-if="canvas.loaded" />
    <div v-else class="splash">{{ t('common.loading') }}</div>
    <ScanPanel />
  </div>
</template>

<style scoped>
.graph-stage { position:absolute; inset:0; }
.splash { display:grid; place-items:center; width:100%; height:100%; color:var(--honey); }
</style>
