<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/useApi'
import type { Cell } from '../../api/types'
import { useCellStore } from '../../stores/cell'
import { cellLabel } from '../../api/cellLabel'

const q = ref('')
const results = ref<Cell[]>([])
const loading = ref(false)
const cellStore = useCellStore()
const { t } = useI18n()

let timer: number | null = null
watch(q, v => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(async () => {
    if (!v) { results.value = []; return }
    loading.value = true
    try {
      // Request content + summary so every result has a real label (cells have no title)
      // and the detail panel can show the OCR/parsed text.
      results.value = await useApi().call<Cell[]>('search', {
        query: v,
        limit: 50,
        include: ['content', 'summary', 'created_at']
      })
    } finally { loading.value = false }
  }, 180) as unknown as number
})

function subtitleFor(c: Cell): string {
  const bits: string[] = [c.realm]
  if (c.signal) bits.push(c.signal)
  return bits.join(' · ')
}
</script>

<template>
  <v-text-field v-model="q" density="compact" variant="solo-filled" :placeholder="t('search.placeholder')" autofocus />
  <v-list density="compact">
    <v-list-item
      v-for="c in results"
      :key="c.id"
      :title="cellLabel(c)"
      :subtitle="subtitleFor(c)"
      @click="cellStore.open(c)"
    />
  </v-list>
  <div v-if="loading" style="color:#666;padding:8px">{{ t('search.searching') }}</div>
  <div v-else-if="!results.length && q" style="color:#666;padding:8px">{{ t('common.noResults') }}</div>
</template>
