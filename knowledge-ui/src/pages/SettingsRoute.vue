<script setup lang="ts">
import { ref } from 'vue'
interface Row { nm: string; sub: string; on: boolean }
const rows = ref<Row[]>([
  { nm: 'Lokale Modelle erzwingen', sub: 'legal & medical verlassen nie diese Maschine', on: true },
  { nm: 'Auto-Summarizer', sub: 'Lange Zellen werden zusammengefasst & eingebettet', on: true },
  { nm: 'OCR für Scans (Tesseract)', sub: 'Scan-only-PDFs durchsuchbar machen', on: true },
  { nm: 'Consumption-Folder', sub: 'SMB-Ordner überwachen & auto-trennen', on: true },
  { nm: 'Queen periodisch (cron)', sub: 'Nächtlicher Lauf um 04:00', on: true },
  { nm: 'Cloud-LLM-Calls erlauben', sub: 'Nur für nicht-sensible Realms', on: false },
])
</script>
<template>
  <div class="stage-body">
    <div style="padding:34px 44px;max-width:720px;margin:0 auto">
      <div class="h-eyebrow">Souveränität &amp; Routing</div>
      <h1 class="h-display" style="font-size:28px;margin:4px 0 22px">Souveränität &amp; Routing</h1>
      <div v-for="(r, i) in rows" :key="i" class="setting-row">
        <div><div class="set-nm">{{ r.nm }}</div><div class="set-sub">{{ r.sub }}</div></div>
        <button type="button" :class="['switch', { on: r.on }]" @click="r.on = !r.on"><span /></button>
      </div>
    </div>
  </div>
</template>
<style scoped>
.stage-body { flex:1; overflow-y:auto; min-height:0; }
.h-eyebrow { font-size:11.5px; letter-spacing:.14em; text-transform:uppercase; color:var(--text-2); font-weight:600; }
.h-display { font-family:var(--font-display); font-weight:600; letter-spacing:-.02em; color:var(--text-0); }
.setting-row { display:flex; align-items:center; justify-content:space-between; padding:16px 0; border-bottom:1px solid var(--line); }
.set-nm { font-size:15px; font-weight:500; } .set-sub { font-size:13px; color:var(--text-2); margin-top:2px; }
.switch { width:42px; height:24px; border-radius:13px; background:var(--bg-4); position:relative; transition:.18s; flex:none; border:none; cursor:pointer; }
.switch span { position:absolute; top:3px; left:3px; width:18px; height:18px; border-radius:50%; background:var(--text-2); transition:.18s; }
.switch.on { background:var(--honey); } .switch.on span { left:21px; background:#1a1206; }
</style>
