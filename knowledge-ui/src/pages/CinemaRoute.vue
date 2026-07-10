<script setup lang="ts">
import { defineAsyncComponent, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useCanvasStore } from '../stores/canvas'

const router = useRouter()
const canvas = useCanvasStore()
const { t } = useI18n()
const loadError = ref(false)

const TresCanvas = defineAsyncComponent(() =>
  import('@tresjs/core').then((m) => ({ default: m.TresCanvas })),
)
const HiveSphere = defineAsyncComponent(() => import('../components/hive/HiveSphere.vue'))
const CyberBees  = defineAsyncComponent(() => import('../components/hive/CyberBees.vue'))
const HiveFloor  = defineAsyncComponent(() => import('../components/hive/HiveFloor.vue'))

async function load() {
  loadError.value = false
  try { await canvas.loadTopLevel() } catch { loadError.value = true }
}
onMounted(() => { if (!canvas.loaded) void load() })
</script>

<template>
  <div class="cinema">
    <v-btn class="back" icon="mdi-arrow-left" @click="router.push({ name: 'hive' })" />
    <div v-if="loadError && !canvas.loaded" class="load-err">
      <span>{{ t('common.loadFailed') }}</span>
      <button class="retry" @click="load">{{ t('common.retry') }}</button>
    </div>
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
.load-err { position: fixed; inset: 0; z-index: 1; display: grid; align-content: center; justify-items: center;
  gap: 12px; color: var(--text-1, #eee); }
.retry { padding: 7px 16px; border-radius: 8px; border: 1px solid var(--line-honey, rgba(240,180,40,.3));
  background: var(--honey-dim, rgba(240,180,40,.08)); color: var(--honey, #e5a000); font-size: 13px; cursor: pointer; }
.retry:hover { background: var(--honey-dim, rgba(240,180,40,.16)); }
</style>
