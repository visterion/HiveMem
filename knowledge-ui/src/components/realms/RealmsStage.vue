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
function sparkWidth(count: number): string {
  const max = store.maxCount || 1
  return Math.round((count / max) * 100) + '%'
}
</script>

<template>
  <div class="realms-stage">
    <div class="h-eyebrow">{{ t('realms.title') }}</div>
    <h1 class="realms-title">{{ t('realms.subtitle') }}</h1>
    <p class="realms-lead">{{ t('realms.heroLead') }}</p>
    <div class="realm-grid">
      <button v-for="r in store.realms" :key="r.id" class="realm-card" @click="open(r.id)">
        <div class="rc-top">
          <RealmHex :color="r.color" :label="r.id.charAt(0).toUpperCase()" :size="40" />
          <span :class="['priv', r.priv]">
            <HmIcon :name="r.priv === 'local' ? 'lock' : 'cloud'" :size="13" />
            {{ r.priv === 'local' ? t('realms.local') : t('realms.cloud') }}
          </span>
        </div>
        <div class="rc-name">{{ r.id }}</div>
        <div class="rc-desc">{{ r.desc }}</div>
        <div class="rc-foot">
          <span class="rc-count">{{ r.count }}</span> {{ t('realms.cells', { n: r.count }) }}
          <span class="rc-spark"><span :style="{ width: sparkWidth(r.count), background: r.color }" /></span>
        </div>
      </button>
    </div>
  </div>
</template>

<style scoped>
.realms-stage { position:relative; padding:36px 44px 60px; max-width:1100px; margin:0 auto; }
.h-eyebrow { font-family:var(--font-mono); font-size:11px; letter-spacing:.16em; text-transform:uppercase; color:var(--text-2); }
.realms-title { font-family:var(--font-display); font-weight:600; letter-spacing:-.02em; font-size:32px; margin:6px 0 4px; color:var(--text-0); }
.realms-lead { max-width:560px; margin-bottom:30px; color:var(--text-2); font-size:14px; line-height:1.55; }
.realm-grid { display:grid; grid-template-columns:repeat(auto-fill, minmax(240px, 1fr)); gap:16px; }
.realm-card { text-align:left; background:var(--bg-2); border:1px solid var(--line); border-radius:var(--radius);
  padding:18px; box-shadow:var(--shadow-1); transition:.16s; position:relative; overflow:hidden; cursor:pointer; }
.realm-card:hover { transform:translateY(-3px); border-color:var(--line-2); box-shadow:var(--shadow-2); }
.rc-top { display:flex; align-items:center; justify-content:space-between; margin-bottom:14px; }
.priv { display:inline-flex; align-items:center; gap:5px; font-size:11px; padding:3px 8px; border-radius:7px;
  background:var(--bg-3); color:var(--text-2); border:1px solid var(--line); }
.priv.local { color:var(--good); }
.rc-name { font-family:var(--font-display); font-size:18px; font-weight:600; text-transform:capitalize; color:var(--text-0); }
.rc-desc { font-size:13px; color:var(--text-2); margin-top:3px; min-height:34px; }
.rc-foot { font-size:12.5px; color:var(--text-2); margin-top:12px; display:flex; align-items:center; gap:8px; }
.rc-count { font-family:var(--font-display); font-size:20px; font-weight:700; color:var(--text-0); }
.rc-spark { flex:1; height:5px; border-radius:4px; background:var(--bg-4); overflow:hidden; margin-left:4px; }
.rc-spark span { display:block; height:100%; border-radius:4px; }
</style>
