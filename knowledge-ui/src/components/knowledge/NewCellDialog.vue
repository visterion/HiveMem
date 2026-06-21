<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/useApi'
import { useCellStore } from '../../stores/cell'
import type { Realm } from '../../api/types'
import CellEditor from './CellEditor.vue'

const emit = defineEmits<{
  (e: 'created', id: string): void
  (e: 'close'): void
}>()

const { t } = useI18n()
const api = useApi()
const cellStore = useCellStore()

// Signal is a fixed enum on the backend; realms are free-form and come from `list`.
const SIGNALS = ['facts', 'events', 'discoveries', 'preferences', 'advice']
const realms = ref<string[]>([])
const realm = ref('')
const signal = ref('')
const topic = ref('')
const importance = ref(3)
const saving = ref(false)
const error = ref(false)

onMounted(async () => {
  const list = await api.call<Realm[]>('list').catch(() => [] as Realm[])
  realms.value = list.map(r => r.name)
  realm.value = realms.value[0] ?? 'personal'
})

async function onCreate(content: string) {
  saving.value = true
  error.value = false
  try {
    const res = await cellStore.addCell({
      content,
      realm: realm.value || 'personal',
      signal: signal.value || undefined,
      topic: topic.value || undefined,
      importance: importance.value,
    })
    emit('created', res.id)
  } catch {
    error.value = true
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="nc-overlay" data-test="new-cell-dialog" @click.self="emit('close')">
    <div class="nc-modal" role="dialog" aria-modal="true">
      <div class="nc-head">
        <h2 class="nc-title">{{ t('editor.newCellTitle') }}</h2>
        <button class="nc-x" data-test="new-cell-close" :title="t('editor.cancel')" @click="emit('close')">✕</button>
      </div>

      <p v-if="error" class="nc-error" data-test="new-cell-error">{{ t('editor.createError') }}</p>

      <div class="nc-fields">
        <label class="nc-field">
          <span class="nc-label">{{ t('editor.realm') }}</span>
          <input v-model="realm" list="nc-realms" class="nc-input" data-test="new-cell-realm" />
          <datalist id="nc-realms">
            <option v-for="r in realms" :key="r" :value="r" />
          </datalist>
        </label>
        <label class="nc-field">
          <span class="nc-label">{{ t('editor.signal') }}</span>
          <select v-model="signal" class="nc-input" data-test="new-cell-signal">
            <option value="">{{ t('editor.signalNone') }}</option>
            <option v-for="s in SIGNALS" :key="s" :value="s">{{ s }}</option>
          </select>
        </label>
        <label class="nc-field">
          <span class="nc-label">{{ t('editor.topic') }}</span>
          <input v-model="topic" class="nc-input" data-test="new-cell-topic" :placeholder="t('editor.topicPlaceholder')" />
        </label>
        <label class="nc-field nc-imp">
          <span class="nc-label">{{ t('editor.importance') }}</span>
          <select v-model.number="importance" class="nc-input" data-test="new-cell-importance">
            <option v-for="n in [1, 2, 3, 4, 5]" :key="n" :value="n">{{ '★'.repeat(n) }} {{ n }}</option>
          </select>
        </label>
      </div>

      <CellEditor :content="''" :saving="saving" @save="onCreate" @cancel="emit('close')" />
    </div>
  </div>
</template>

<style scoped>
.nc-overlay { position: fixed; inset: 0; z-index: 60; display: grid; place-items: center;
  background: rgba(0, 0, 0, .55); padding: 20px; }
.nc-modal { width: min(720px, 100%); max-height: 90vh; overflow: auto; background: var(--bg-1, #16161e);
  border: 1px solid var(--line); border-radius: 14px; padding: 20px; display: flex; flex-direction: column; gap: 14px; }
.nc-head { display: flex; align-items: center; justify-content: space-between; }
.nc-title { font-family: var(--font-display); font-size: 19px; margin: 0; color: var(--text-0); }
.nc-x { background: none; border: none; color: var(--text-2); font-size: 18px; cursor: pointer;
  min-width: 36px; min-height: 36px; border-radius: 8px; }
.nc-x:hover { background: var(--bg-3); color: var(--text-0); }
.nc-error { color: #ef9a9a; font-size: 13px; margin: 0; }
.nc-fields { display: flex; gap: 12px; flex-wrap: wrap; }
.nc-field { display: flex; flex-direction: column; gap: 4px; flex: 1 1 180px; }
.nc-imp { flex: 0 0 130px; }
.nc-label { font-size: 11px; text-transform: uppercase; letter-spacing: .04em; color: var(--text-2); }
.nc-input { background: var(--bg-0); border: 1px solid var(--line); border-radius: 8px; color: var(--text-1);
  padding: 8px 10px; font-size: 13px; min-height: 38px; }
.nc-input:focus { outline: none; border-color: var(--accent, #8ab4f8); }
</style>
