<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useCanvasStore } from '../../stores/canvas'
import { useUiStore, type SizeMetric } from '../../stores/ui'

const canvas = useCanvasStore()
const ui = useUiStore()
const { t } = useI18n()
const metrics = computed<{ v: SizeMetric; label: string }[]>(() => [
  { v: 'cell_count', label: t('realms.cellCount') },
  { v: 'content_volume', label: t('realms.contentVolume') },
  { v: 'importance', label: t('realms.importance') },
  { v: 'popularity', label: t('realms.popularity') }
])

onMounted(() => { if (!canvas.loaded) canvas.loadTopLevel() })

function colorFor(realm: string): string {
  let h = 0; for (let i = 0; i < realm.length; i++) h = (h * 31 + realm.charCodeAt(i)) % 360
  return `hsl(${h}, 70%, 55%)`
}
</script>

<template>
  <div>
    <v-list density="compact">
      <v-list-item v-for="r in canvas.realms" :key="r.name" :title="r.name" :subtitle="t('realms.cells', { n: r.cell_count })">
        <template #prepend>
          <span class="dot" :style="{ background: colorFor(r.name) }" />
        </template>
      </v-list-item>
    </v-list>
    <v-divider class="my-3" />
    <strong style="font-size:12px;letter-spacing:0.1em">{{ t('realms.sizeMetric') }}</strong>
    <v-radio-group v-model="ui.sizeMetric" density="compact">
      <v-radio v-for="m in metrics" :key="m.v" :label="m.label" :value="m.v" />
    </v-radio-group>
  </div>
</template>

<style scoped>
.dot { display:inline-block; width:10px; height:10px; border-radius:50%; margin-right:8px; }
</style>
