<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQueenStore } from '../stores/queen'
import { realmColorFor } from '../composables/realmMeta'
import HmIcon from '../components/shell/HmIcon.vue'

const store = useQueenStore()
const { t } = useI18n()
const detailOpen = ref(false)
let timer: number | null = null

function fmtCost(micros: number | null) { return micros == null ? '—' : `$${(micros / 1_000_000).toFixed(4)}` }
function fmtDuration(ms: number | null) { return ms == null ? '—' : `${(ms / 1000).toFixed(1)}s` }
function fmtTime(iso: string | null) { return iso ? new Date(iso).toLocaleString() : '—' }

const STATUS: Record<string, { label: string; color: string }> = {
  done: { label: 'done', color: 'var(--good)' },
  running: { label: 'running', color: 'var(--honey)' },
  failed: { label: 'error', color: 'var(--danger)' },
  skip: { label: 'skip', color: 'var(--text-2)' },
}
function statusMeta(s: string) { return STATUS[s] ?? { label: s, color: 'var(--text-2)' } }
function statusStyle(s: string) {
  const c = statusMeta(s).color
  return { color: c, borderColor: `color-mix(in srgb, ${c} 35%, transparent)`, background: `color-mix(in srgb, ${c} 8%, transparent)` }
}
function kindIcon(type: string | null): string {
  switch (type) {
    case 'tunnel': return 'graph'
    case 'cell': return 'reader'
    case 'stale': return 'history'
    default: return 'sparkle'
  }
}
function propTitle(desc: string | null): string {
  if (!desc) return '—'
  const i = desc.indexOf(':')
  return i > 0 ? desc.slice(0, i) : desc
}

const sumCost = computed(() =>
  store.costAvailable ? store.runs.reduce((s, r) => s + (r.costMicros ?? 0), 0) : null)
const detailRun = computed(() => store.selectedRun?.run as Record<string, any> | undefined)

async function openRun(id: string) { await store.selectRun(id); detailOpen.value = true }
function closeDetail() { detailOpen.value = false }

onMounted(async () => {
  await store.refresh()
  timer = window.setInterval(() => store.refresh(), 10_000)
})
onUnmounted(() => { if (timer) clearInterval(timer) })
</script>

<template>
  <div class="page">
    <div class="hexfield" style="opacity:.5" />
    <div class="queen-wrap">
      <div class="q-head">
        <div class="q-hex">♛</div>
        <div>
          <div class="h-eyebrow">{{ t('nav.queen') }}</div>
          <h1 class="h-display" style="font-size:30px;margin:2px 0 0">{{ t('queen.activity') }}</h1>
        </div>
        <div class="q-kpis">
          <div class="kpi"><div class="kpi-v">{{ store.runs.length }}</div><div class="kpi-k">{{ t('queen.kpiRuns') }}</div></div>
          <div class="kpi"><div class="kpi-v" style="color:var(--honey)">{{ store.pending.length }}</div><div class="kpi-k">{{ t('queen.kpiProposals') }}</div></div>
          <div v-if="store.costAvailable" class="kpi"><div class="kpi-v">{{ fmtCost(sumCost) }}</div><div class="kpi-k">{{ t('queen.kpiCost') }}</div></div>
        </div>
      </div>

      <div v-if="store.unavailable" class="notice">{{ t('queen.unavailable') }}</div>

      <div class="card q-card">
        <div class="qtable">
          <div class="qrow qhead">
            <span>{{ t('queen.started') }}</span><span>{{ t('queen.agent') }}</span><span>{{ t('queen.trigger') }}</span>
            <span>{{ t('queen.status') }}</span><span>{{ t('queen.duration') }}</span><span>{{ t('queen.cost') }}</span>
          </div>
          <button v-for="r in store.runs" :key="r.id" type="button" class="qrow qrow-btn" @click="openRun(r.id)">
            <span class="q-time">{{ fmtTime(r.startedAt) }}</span>
            <span class="q-agent"><span class="bee-dot" /> {{ r.agent }}</span>
            <span><span class="chip">{{ r.trigger ?? '—' }}</span></span>
            <span><span class="qstatus" :style="statusStyle(r.status)">{{ statusMeta(r.status).label }}</span></span>
            <span class="q-mono">{{ fmtDuration(r.durationMs) }}</span>
            <span class="q-mono">{{ fmtCost(r.costMicros) }}<span v-if="(r.llmCalls ?? 0) > 0" class="q-found"> · {{ r.llmCalls }} {{ t('queen.llmCalls') }}</span></span>
          </button>
          <div v-if="store.runs.length === 0" class="q-empty">{{ t('queen.noRuns') }}</div>
        </div>
      </div>

      <div class="q-prop-head">
        <h2 class="h-display" style="font-size:22px;margin:0">{{ t('queen.openProposals') }}</h2>
        <span class="panel-sub">{{ t('queen.queenSub') }}</span>
      </div>
      <div v-if="store.pending.length === 0" class="card q-noprop">{{ t('queen.noProposals') }}</div>
      <div v-else class="prop-grid">
        <div v-for="p in store.pending" :key="p.id" class="prop-card">
          <div class="prop-top">
            <span class="prop-bee"><HmIcon :name="kindIcon(p.type)" :size="15" /></span>
            <div class="prop-head-text">
              <div class="prop-title">{{ propTitle(p.description) }}</div>
              <div class="prop-bee-name">{{ p.type || 'queen' }}</div>
            </div>
            <span class="prop-pending">{{ t('queen.pending') }}</span>
          </div>
          <div v-if="p.realm" class="prop-cell"><span class="rdot" :style="{ background: realmColorFor(p.realm) }" /> {{ p.realm }}</div>
          <p class="prop-detail">{{ p.description }}</p>
          <div class="prop-actions">
            <button class="btn" style="flex:1" @click="store.approve(p.id, true)"><HmIcon name="check" :size="15" /> {{ t('queen.accept') }}</button>
            <button class="btn ghost" style="flex:1" @click="store.approve(p.id, false)"><HmIcon name="close" :size="15" /> {{ t('queen.reject') }}</button>
          </div>
        </div>
      </div>
    </div>

    <div v-if="detailOpen" class="q-detail-backdrop" @click="closeDetail">
      <div class="q-detail card fade-in" @click.stop>
        <button class="q-detail-close" @click="closeDetail"><HmIcon name="close" :size="18" /></button>
        <div class="h-eyebrow">{{ t('queen.run', { id: detailRun?.id ?? '' }) }}</div>
        <div v-if="detailRun" style="margin-top:8px">
          <span class="qstatus" :style="statusStyle(String(detailRun.status ?? ''))">{{ statusMeta(String(detailRun.status ?? '')).label }}</span>
        </div>
        <p class="prop-detail" style="margin-top:12px">{{ detailRun?.summary ?? '' }}</p>
        <p v-if="detailRun?.error" class="q-err">{{ detailRun.error }}</p>
        <div class="section-label">{{ t('queen.events') }}</div>
        <div class="q-events">
          <div v-for="(e, i) in (store.selectedRun?.events ?? [])" :key="i" class="q-event">
            <span class="q-mono">{{ fmtTime((e as any).at ?? null) }}</span>
            <span>{{ e.type }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page { flex:1; overflow-y:auto; min-height:0; height:100%; position:relative; }
.queen-wrap { position:relative; padding:34px 44px 60px; max-width:1180px; margin:0 auto; }
.h-eyebrow { font-size:11.5px; letter-spacing:.14em; text-transform:uppercase; color:var(--text-2); font-weight:600; }
.h-display { font-family:var(--font-display); font-weight:600; letter-spacing:-.02em; color:var(--text-0); }
.panel-sub { font-size:12px; color:var(--text-2); }
.notice { margin:18px 0; padding:14px 18px; border-radius:12px; background:var(--bg-2); border:1px solid var(--line); color:var(--text-1); }

.q-head { display:flex; align-items:center; gap:14px; }
.q-hex { width:44px; height:44px; display:grid; place-items:center; border-radius:12px;
  background:var(--honey-dim); color:var(--honey); font-size:22px; flex:none; }
.q-kpis { margin-left:auto; display:flex; gap:10px; }
.kpi { background:var(--bg-2); border:1px solid var(--line); border-radius:12px; padding:10px 16px; text-align:center; min-width:76px; }
.kpi-v { font-family:var(--font-display); font-size:22px; font-weight:700; }
.kpi-k { font-size:11px; color:var(--text-2); text-transform:uppercase; letter-spacing:.06em; margin-top:2px; }

.card { background:var(--bg-2); border:1px solid var(--line); border-radius:14px; box-shadow:var(--shadow-1); }
.q-card { margin-top:28px; overflow:hidden; }
.qtable { display:flex; flex-direction:column; }
.qrow { display:grid; grid-template-columns:1.4fr 1.3fr .8fr .8fr .7fr 1.3fr; align-items:center; gap:12px;
  padding:14px 20px; border-bottom:1px solid var(--line); font-size:13.5px; text-align:left; }
.qrow:last-child { border-bottom:none; }
.qrow-btn { background:none; border:none; border-bottom:1px solid var(--line); cursor:pointer; color:var(--text-0); width:100%; }
.qrow-btn:hover { background:var(--bg-3); }
.qhead { font-size:11px; letter-spacing:.08em; text-transform:uppercase; color:var(--text-2); background:var(--bg-1); }
.q-empty { color:var(--text-2); }
.q-time { color:var(--text-1); }
.q-agent { display:inline-flex; align-items:center; gap:8px; font-weight:500; }
.bee-dot { width:8px; height:8px; border-radius:50%; background:var(--honey); box-shadow:0 0 7px var(--honey); flex:none; }
.q-mono { font-family:var(--font-mono); font-size:12px; color:var(--text-1); }
.q-found { color:var(--honey); }
.qstatus { font-size:12px; padding:3px 10px; border-radius:7px; border:1px solid; font-weight:500; text-transform:capitalize; }
.chip { font-size:11px; padding:2px 8px; border-radius:6px; font-weight:500; background:var(--bg-4); color:var(--text-1);
  border:1px solid var(--line); display:inline-flex; align-items:center; gap:5px; white-space:nowrap; }

.q-prop-head { display:flex; align-items:baseline; gap:12px; margin:36px 0 16px; }
.q-noprop { padding:28px; color:var(--text-2); }
.prop-grid { display:grid; grid-template-columns:repeat(auto-fill, minmax(330px, 1fr)); gap:16px; }
.prop-card { background:var(--bg-2); border:1px solid var(--line); border-radius:14px; padding:18px; box-shadow:var(--shadow-1); }
.prop-top { display:flex; align-items:flex-start; gap:12px; }
.prop-head-text { flex:1; min-width:0; }
.prop-bee { width:36px; height:36px; border-radius:10px; background:var(--honey-dim); color:var(--honey); display:grid; place-items:center; flex:none; }
.prop-title { font-weight:600; font-size:15px; }
.prop-bee-name { font-size:12px; color:var(--text-2); margin-top:2px; }
.prop-pending { margin-left:auto; font-size:11px; padding:3px 9px; border-radius:7px; background:var(--honey-dim); color:var(--honey); font-weight:500; }
.prop-cell { display:flex; align-items:center; gap:8px; font-size:13px; color:var(--text-1); margin:14px 0 8px; }
.rdot { width:9px; height:9px; border-radius:50%; flex:none; }
.prop-detail { font-size:13.5px; line-height:1.55; color:var(--text-1); margin:0 0 16px; }
.prop-actions { display:flex; gap:9px; }
.btn { display:inline-flex; align-items:center; justify-content:center; gap:8px; padding:11px 16px; border-radius:11px;
  font-size:14px; font-weight:600; background:var(--honey); color:#1a1206; transition:.14s; border:none; cursor:pointer; }
.btn:hover { background:var(--honey-bright); }
.btn.ghost { background:var(--bg-3); color:var(--text-0); border:1px solid var(--line-2); }
.btn.ghost:hover { background:var(--bg-4); }

.q-detail-backdrop { position:fixed; inset:0; z-index:200; background:rgba(5,6,9,0.78); backdrop-filter:blur(10px);
  display:flex; align-items:center; justify-content:center; padding:40px; animation:fadeIn .2s; }
.q-detail { position:relative; width:100%; max-width:520px; max-height:80vh; overflow-y:auto; padding:26px; }
.q-detail-close { position:absolute; top:16px; right:16px; background:none; border:none; color:var(--text-2); cursor:pointer; }
.q-detail-close:hover { color:var(--text-0); }
.section-label { font-size:11px; letter-spacing:.1em; text-transform:uppercase; color:var(--text-2); font-weight:600; margin:18px 0 8px; }
.q-events { display:flex; flex-direction:column; gap:8px; }
.q-event { display:flex; gap:12px; font-size:13px; color:var(--text-1); }
.q-err { color:var(--danger); font-size:13px; margin:8px 0 0; }
</style>
