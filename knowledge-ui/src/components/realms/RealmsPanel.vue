<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useRealmsStore } from '../../stores/realms'
import RealmHex from './RealmHex.vue'
import HmIcon from '../shell/HmIcon.vue'

const store = useRealmsStore()
const router = useRouter()
const { t } = useI18n()

onMounted(() => store.loadRealms())
function open(id: string) { router.push({ path: '/', query: { realm: id } }) }
</script>

<template>
  <div class="panel">
    <div class="panel-head">
      <div>
        <div class="panel-title">{{ t('realms.title') }}</div>
        <div class="panel-sub">{{ t('realms.subtitle') }}</div>
      </div>
    </div>
    <div class="panel-body">
      <div v-for="r in store.realms" :key="r.id" class="realm-li" @click="open(r.id)">
        <RealmHex :color="r.color" :label="r.id.charAt(0).toUpperCase()" :size="28" />
        <div class="rl-main">
          <div class="nm">{{ r.id }}</div>
          <div class="ct">{{ t('realms.cells', { n: r.count }) }}</div>
        </div>
        <span class="lock"><HmIcon :name="r.priv === 'local' ? 'lock' : 'cloud'" :size="16" /></span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.panel { grid-column:2; width:var(--panel-w); background:var(--bg-1); border-right:1px solid var(--line);
  display:flex; flex-direction:column; z-index:20; min-height:0; }
.panel-head { padding:18px 18px 14px; }
.panel-title { font-family:var(--font-display); font-size:19px; font-weight:600; letter-spacing:-.01em; }
.panel-sub { font-size:12px; color:var(--text-2); margin-top:2px; }
.panel-body { flex:1; overflow-y:auto; padding:6px 10px 16px; min-height:0; }
.realm-li { display:flex; align-items:center; gap:11px; padding:10px 12px; border-radius:11px; cursor:pointer;
  border:1px solid transparent; }
.realm-li:hover { background:var(--bg-3); }
.rl-main { flex:1; min-width:0; }
.nm { font-size:14px; color:var(--text-0); font-weight:500; text-transform:capitalize; }
.ct { font-size:11.5px; color:var(--text-2); margin-top:2px; }
.lock { color:var(--text-2); display:flex; }
</style>
