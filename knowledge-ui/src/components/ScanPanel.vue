<script setup lang="ts">
import { computed } from 'vue'
import { useCellStore } from '../stores/cell'
import { useReaderStore } from '../stores/reader'
import { useCanvasStore } from '../stores/canvas'
import { cellLabel } from '../api/cellLabel'

const cellStore = useCellStore()
const reader = useReaderStore()
const canvas = useCanvasStore()
const d = computed(() => cellStore.current)

function openReader() { if (d.value) reader.openReader(d.value.cell.id) }
async function jumpTo(id: string) {
  const previousFocus = canvas.focusedId
  canvas.setHover(null)

  try {
    const result = cellStore.load(id)
    if (result && typeof (result as PromiseLike<unknown>).then === 'function') {
      await result
    }
    canvas.setFocus(id)
  } catch {
    canvas.setFocus(previousFocus)
    canvas.setHover(null)
  }
}
function close() {
  cellStore.clear()
  canvas.setFocus(null)
  canvas.setHover(null)
}
</script>

<template>
  <transition name="slide-r">
    <aside v-if="d" class="scan">
      <header>
        <strong>{{ cellLabel(d.cell) }}</strong>
        <v-btn data-testid="scan-panel-close" icon="mdi-close" size="small" variant="text" @click="close" />
      </header>
      <div class="body">
        <div class="chips">
          <v-chip size="x-small" color="primary" variant="tonal">{{ d.cell.realm }}</v-chip>
          <v-chip v-if="d.cell.signal" size="x-small" variant="outlined">{{ d.cell.signal }}</v-chip>
          <v-chip v-if="d.cell.topic" size="x-small" variant="outlined">{{ d.cell.topic }}</v-chip>
          <span class="imp">{{ '★'.repeat(d.cell.importance) }}</span>
        </div>
        <section v-if="d.cell.summary">
          <div class="label">SUMMARY</div><p>{{ d.cell.summary }}</p>
        </section>
        <section v-if="d.cell.key_points?.length">
          <div class="label">KEY POINTS</div>
          <ul><li v-for="k in d.cell.key_points" :key="k">{{ k }}</li></ul>
        </section>
        <section v-if="d.cell.insight">
          <div class="label">INSIGHT</div><blockquote>{{ d.cell.insight }}</blockquote>
        </section>
        <section v-if="d.cell.content">
          <div class="label">TEXT</div><pre class="content">{{ d.cell.content }}</pre>
        </section>
        <section v-if="d.tunnels.length">
          <div class="label">TUNNELS ({{ d.tunnels.length }})</div>
          <div v-for="t in d.tunnels" :key="t.id" class="tunnel"
               @click="jumpTo(t.to_cell === d.cell.id ? t.from_cell : t.to_cell)">
            <span :class="['dot', t.relation]" />
            <span class="rel">{{ t.relation }}</span>
            <span class="note">{{ t.note || '' }}</span>
          </div>
        </section>
        <section v-if="d.facts.length">
          <div class="label">FACTS ({{ d.facts.length }})</div>
          <div v-for="f in d.facts" :key="f.id" class="fact">
            <span class="pred">{{ f.predicate }}</span> → {{ f.object }}
          </div>
        </section>
        <v-btn block color="primary" class="mt-3" @click="openReader">Open reader</v-btn>
      </div>
    </aside>
  </transition>
</template>

<style scoped>
.scan { position:fixed; top:0; right:0; bottom:0; width:360px; background:#0e0e1c; border-left:1px solid #1a1a24; display:flex; flex-direction:column; z-index:8; }
.content { white-space:pre-wrap; word-break:break-word; max-height:240px; overflow:auto; font-size:11px; color:#aaa; background:#0a0a14; padding:8px; border-radius:4px; margin:0; }
header { display:flex; align-items:center; justify-content:space-between; padding:10px 14px; border-bottom:1px solid #1a1a24; gap:8px; }
header strong { flex:1; font-size:14px; }
.body { flex:1; overflow-y:auto; padding:10px 14px; font-size:12px; }
.chips { display:flex; gap:4px; flex-wrap:wrap; margin-bottom:10px; align-items:center; }
.imp { color:#ffd24d; margin-left:6px; }
section { margin:10px 0; }
.label { color:#888; font-size:10px; letter-spacing:0.1em; font-weight:bold; margin-bottom:4px; }
blockquote { border-left:3px solid #4dc4ff; padding-left:8px; color:#4dc4ff; font-style:italic; }
.tunnel { display:flex; gap:6px; align-items:center; padding:4px 0; cursor:pointer; }
.tunnel:hover { background:#1a1a2a; }
.dot { width:8px; height:8px; border-radius:50%; }
.dot.related_to { background:#5a5a5a; }
.dot.builds_on  { background:#4dc4ff; }
.dot.contradicts{ background:#ff4d4d; }
.dot.refines    { background:#4dff9c; }
.rel { color:#aaa; font-size:10px; }
.note { color:#ccc; }
.fact { padding:2px 0; color:#ccc; }
.pred { color:#4dc4ff; }
.slide-r-enter-from, .slide-r-leave-to { transform:translateX(20px); opacity:0; }
.slide-r-enter-active, .slide-r-leave-active { transition:transform 180ms ease, opacity 180ms ease; }
</style>
