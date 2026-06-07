<script setup lang="ts">
import { defineAsyncComponent, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useCanvasStore } from '../stores/canvas'

const router = useRouter()
const canvas = useCanvasStore()

const TresCanvas = defineAsyncComponent(() =>
  import('@tresjs/core').then((m) => ({ default: m.TresCanvas })),
)
const HiveSphere = defineAsyncComponent(() => import('../components/hive/HiveSphere.vue'))
const CyberBees  = defineAsyncComponent(() => import('../components/hive/CyberBees.vue'))
const HiveFloor  = defineAsyncComponent(() => import('../components/hive/HiveFloor.vue'))

onMounted(() => { if (!canvas.loaded) canvas.loadTopLevel() })
</script>

<template>
  <div class="cinema">
    <v-btn class="back" icon="mdi-arrow-left" @click="router.push({ name: 'hive' })" />
    <Suspense>
      <TresCanvas clear-color="#000010" :dpr="1.5">
        <TresPerspectiveCamera :position="[0, 0, 14]" />
        <TresAmbientLight :intensity="0.3" />
        <HiveSphere v-if="canvas.loaded" :realms="canvas.realms" :cells="canvas.cells" />
        <CyberBees />
        <HiveFloor />
      </TresCanvas>
    </Suspense>
  </div>
</template>

<style scoped>
.cinema { position: fixed; inset: 0; background: #000010; }
.back { position: fixed; top: 16px; left: 16px; z-index: 2; }
</style>
