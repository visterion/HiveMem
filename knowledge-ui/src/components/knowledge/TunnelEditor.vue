<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/useApi'
import { useCellStore } from '../../stores/cell'
import { cellLabel } from '../../api/cellLabel'
import type { Cell, Relation } from '../../api/types'

const cellStore = useCellStore()
const api = useApi()
const { t } = useI18n()

const RELATIONS: Relation[] = ['related_to', 'builds_on', 'contradicts', 'refines']
const fromId = computed(() => cellStore.currentId)
const tunnels = computed(() => cellStore.current?.tunnels ?? [])

const query = ref('')
const results = ref<Cell[]>([])
const target = ref<{ id: string; label: string } | null>(null)
const relation = ref<Relation>('related_to')
const note = ref('')
const adding = ref(false)
let timer: number | null = null

function onSearch() {
  target.value = null
  if (timer) clearTimeout(timer)
  timer = setTimeout(async () => {
    const q = query.value.trim()
    if (!q) { results.value = []; return }
    const rows = await api.call<Cell[]>('search', { query: q, limit: 8 }).catch(() => [] as Cell[])
    results.value = rows.filter(c => c.id !== fromId.value)
  }, 180) as unknown as number
}

function pick(c: Cell) {
  target.value = { id: c.id, label: cellLabel(c) }
  query.value = cellLabel(c)
  results.value = []
}

async function add() {
  if (!target.value || !fromId.value) return
  adding.value = true
  try {
    await cellStore.addTunnel(fromId.value, target.value.id, relation.value, note.value.trim() || undefined)
    target.value = null
    query.value = ''
    note.value = ''
    relation.value = 'related_to'
  } finally {
    adding.value = false
  }
}
</script>

<template>
  <section class="tn" data-test="tunnel-editor">
    <h3 class="tn-h">{{ t('reader.tunnels', { n: tunnels.length }) }}</h3>

    <div v-if="tunnels.length" class="tn-list" data-test="tunnel-existing">
      <div v-for="tu in tunnels" :key="tu.id" class="tn-row" data-test="tunnel-row">
        <span class="tn-rel">{{ tu.relation }}</span>
        <span class="tn-to">{{ tu.to_cell === fromId ? tu.from_cell : tu.to_cell }}</span>
        <span v-if="tu.note" class="tn-note">{{ tu.note }}</span>
      </div>
    </div>

    <div class="tn-add">
      <div class="tn-pick">
        <input
          v-model="query"
          class="tn-input"
          data-test="tunnel-search"
          :placeholder="t('editor.tunnelSearch')"
          @input="onSearch"
        />
        <ul v-if="results.length" class="tn-results" data-test="tunnel-results">
          <li v-for="c in results" :key="c.id" class="tn-result" data-test="tunnel-result" @click="pick(c)">
            {{ cellLabel(c) }}
          </li>
        </ul>
      </div>
      <select v-model="relation" class="tn-input tn-rel-sel" data-test="tunnel-relation">
        <option v-for="r in RELATIONS" :key="r" :value="r">{{ r }}</option>
      </select>
      <input v-model="note" class="tn-input tn-note-in" data-test="tunnel-note" :placeholder="t('editor.note')" />
      <button class="tn-btn" data-test="tunnel-add" :disabled="!target || adding" @click="add">
        {{ t('editor.addTunnel') }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.tn { margin-top: 26px; border-top: 1px solid var(--line); padding-top: 16px; }
.tn-h { font-size: 12px; letter-spacing: .06em; color: var(--text-2); margin: 0 0 10px; font-weight: 600; }
.tn-list { display: flex; flex-direction: column; gap: 6px; margin-bottom: 12px; }
.tn-row { display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--text-1); }
.tn-rel { background: var(--honey-dim); color: var(--honey); border: 1px solid var(--line-honey);
  border-radius: 6px; padding: 1px 7px; font-size: 11px; }
.tn-to { font-family: var(--font-mono); font-size: 11px; color: var(--text-2); }
.tn-note { color: var(--text-2); font-style: italic; }
.tn-add { display: flex; gap: 8px; flex-wrap: wrap; align-items: flex-start; }
.tn-pick { position: relative; flex: 1 1 220px; }
.tn-input { background: var(--bg-0); border: 1px solid var(--line); border-radius: 8px; color: var(--text-1);
  padding: 7px 10px; font-size: 13px; min-height: 38px; width: 100%; box-sizing: border-box; }
.tn-input:focus { outline: none; border-color: var(--accent, #8ab4f8); }
.tn-rel-sel, .tn-note-in { flex: 0 0 auto; width: auto; min-width: 130px; }
.tn-results { position: absolute; z-index: 5; left: 0; right: 0; top: calc(100% + 2px); margin: 0; padding: 4px;
  list-style: none; background: var(--bg-1, #16161e); border: 1px solid var(--line); border-radius: 8px;
  max-height: 220px; overflow: auto; box-shadow: 0 8px 24px rgba(0, 0, 0, .4); }
.tn-result { padding: 7px 9px; font-size: 12px; color: var(--text-1); border-radius: 6px; cursor: pointer;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.tn-result:hover { background: var(--bg-3); color: var(--text-0); }
.tn-btn { padding: 7px 14px; min-height: 38px; border-radius: 8px; border: 1px solid var(--line-honey);
  background: var(--honey-dim); color: var(--honey); font-size: 13px; cursor: pointer; }
.tn-btn:disabled { opacity: .5; cursor: default; }
.tn-btn:hover:not(:disabled) { background: var(--bg-3); }
</style>
