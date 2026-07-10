<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import JSZip from 'jszip'
import { useApi } from '../../api/useApi'
import { useCellStore } from '../../stores/cell'
import type { Realm } from '../../api/types'
import { buildPlan, type ImportPlan } from '../../composables/obsidianImport'

const emit = defineEmits<{ (e: 'imported'): void; (e: 'close'): void }>()

const { t } = useI18n()
const api = useApi()
const cellStore = useCellStore()

const realms = ref<string[]>([])
const defaultRealm = ref('obsidian')
const plan = ref<ImportPlan | null>(null)
const empty = ref(false)
const importing = ref(false)
const progress = ref(0)
const total = ref(0)
const done = ref<{ cells: number; stubs: number; tunnels: number } | null>(null)
const error = ref(false)

onMounted(async () => {
  const list = await api.call<Realm[]>('list').catch(() => [] as Realm[])
  realms.value = list.map(r => r.name)
})

async function onFile(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file) return
  plan.value = null
  empty.value = false
  done.value = null
  error.value = false
  try {
    const zip = await JSZip.loadAsync(file)
    const files: { name: string; text: string }[] = []
    const entries = Object.values(zip.files).filter(f => !f.dir && /\.md$/i.test(f.name))
    for (const entry of entries) files.push({ name: entry.name, text: await entry.async('string') })
    if (!files.length) { empty.value = true; return }
    plan.value = buildPlan(files)
  } catch {
    // Corrupt / unreadable zip: surface the error instead of freezing the dialog.
    error.value = true
  }
}

async function runImport() {
  if (!plan.value) return
  importing.value = true
  error.value = false
  progress.value = 0
  total.value = plan.value.notes.length
  try {
    const res = await cellStore.importObsidian(plan.value.notes, {
      defaultRealm: defaultRealm.value || 'obsidian',
      onProgress: (d, tot) => { progress.value = d; total.value = tot },
    })
    done.value = { cells: res.cellsCreated, stubs: res.stubsCreated, tunnels: res.tunnelsCreated }
    emit('imported')
  } catch {
    error.value = true
  } finally {
    importing.value = false
  }
}
</script>

<template>
  <div class="oi-overlay" data-test="obsidian-dialog" @click.self="emit('close')">
    <div class="oi-modal" role="dialog" aria-modal="true">
      <div class="oi-head">
        <h2 class="oi-title">{{ t('editor.importTitle') }}</h2>
        <button class="oi-x" data-test="obsidian-close" :title="t('editor.cancel')" @click="emit('close')">✕</button>
      </div>

      <label class="oi-field">
        <span class="oi-label">{{ t('editor.importDefaultRealm') }}</span>
        <input v-model="defaultRealm" list="oi-realms" class="oi-input" data-test="obsidian-realm" />
        <datalist id="oi-realms"><option v-for="r in realms" :key="r" :value="r" /></datalist>
      </label>

      <label class="oi-drop">
        <input type="file" accept=".zip,application/zip" data-test="obsidian-file" @change="onFile" />
        <span>{{ t('editor.importPickZip') }}</span>
      </label>

      <p v-if="empty" class="oi-error" data-test="obsidian-empty">{{ t('editor.importEmpty') }}</p>
      <p v-if="error" class="oi-error" data-test="obsidian-error">{{ t('editor.importError') }}</p>

      <div v-if="plan && !done" class="oi-preview" data-test="obsidian-preview">
        <strong>{{ t('editor.importPreview') }}</strong>
        <span>{{ t('editor.importNotes', { n: plan.stats.noteCount }) }}</span>
        <span>{{ t('editor.importTags', { n: plan.stats.tagCount }) }}</span>
        <span>{{ t('editor.importLinks', { n: plan.stats.linkCount }) }}</span>
        <span>{{ t('editor.importStubs', { n: plan.missingTargets.length }) }}</span>
      </div>

      <div v-if="importing" class="oi-progress" data-test="obsidian-progress">
        <div class="oi-bar" :style="{ width: total ? (progress / total * 100) + '%' : '0%' }" />
        <span class="oi-pct">{{ progress }} / {{ total }}</span>
      </div>

      <p v-if="done" class="oi-done" data-test="obsidian-done">
        {{ t('editor.importDone', { cells: done.cells, stubs: done.stubs, tunnels: done.tunnels }) }}
      </p>

      <div class="oi-actions">
        <button class="oi-btn" data-test="obsidian-cancel" @click="emit('close')">{{ t('editor.cancel') }}</button>
        <button
          v-if="!done"
          class="oi-btn primary"
          data-test="obsidian-run"
          :disabled="!plan || importing"
          @click="runImport"
        >
          {{ importing ? t('editor.importing') : t('editor.importRun') }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.oi-overlay { position: fixed; inset: 0; z-index: 60; display: grid; place-items: center;
  background: rgba(0, 0, 0, .55); padding: 20px; }
.oi-modal { width: min(560px, 100%); max-height: 90vh; overflow: auto; background: var(--bg-1, #16161e);
  border: 1px solid var(--line); border-radius: 14px; padding: 20px; display: flex; flex-direction: column; gap: 14px; }
.oi-head { display: flex; align-items: center; justify-content: space-between; }
.oi-title { font-family: var(--font-display); font-size: 19px; margin: 0; color: var(--text-0); }
.oi-x { background: none; border: none; color: var(--text-2); font-size: 18px; cursor: pointer; min-width: 36px; min-height: 36px; border-radius: 8px; }
.oi-x:hover { background: var(--bg-3); color: var(--text-0); }
.oi-field { display: flex; flex-direction: column; gap: 4px; }
.oi-label { font-size: 11px; text-transform: uppercase; letter-spacing: .04em; color: var(--text-2); }
.oi-input { background: var(--bg-0); border: 1px solid var(--line); border-radius: 8px; color: var(--text-1); padding: 8px 10px; font-size: 13px; min-height: 38px; }
.oi-drop { border: 1px dashed var(--line); border-radius: 10px; padding: 16px; display: flex; flex-direction: column; gap: 8px;
  align-items: flex-start; color: var(--text-2); font-size: 13px; }
.oi-error { color: #ef9a9a; font-size: 13px; margin: 0; }
.oi-preview { display: flex; flex-wrap: wrap; gap: 12px; font-size: 13px; color: var(--text-1); background: var(--bg-0);
  border: 1px solid var(--line); border-radius: 10px; padding: 12px; }
.oi-progress { display: flex; align-items: center; gap: 10px; }
.oi-bar { height: 8px; background: var(--honey); border-radius: 4px; transition: width .15s ease; }
.oi-pct { font-size: 12px; color: var(--text-2); font-variant-numeric: tabular-nums; }
.oi-done { color: #8fe7ad; font-size: 13px; margin: 0; }
.oi-actions { display: flex; justify-content: flex-end; gap: 8px; }
.oi-btn { padding: 7px 14px; min-height: 38px; border-radius: 8px; border: 1px solid var(--line);
  background: var(--bg-4); color: var(--text-1); font-size: 13px; cursor: pointer; }
.oi-btn:hover:not(:disabled) { background: var(--bg-3); color: var(--text-0); }
.oi-btn:disabled { opacity: .5; cursor: default; }
.oi-btn.primary { background: var(--honey-dim); color: var(--honey); border-color: var(--line-honey); }
</style>
