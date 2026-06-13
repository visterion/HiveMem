<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '../stores/auth'
import { setLocale, type Locale } from '../i18n'

const { t, locale } = useI18n()
const auth = useAuthStore()
const mock = ref(localStorage.getItem('hivemem_mock') === 'true')

const ROUTING = [
  { key: 'forceLocal', on: true },
  { key: 'autoSummarizer', on: true },
  { key: 'ocr', on: true },
  { key: 'consumption', on: true },
  { key: 'queenCron', on: true },
  { key: 'cloudLlm', on: false },
] as const

const lang = computed(() => locale.value as Locale)

function toggleMock() {
  mock.value = !mock.value
  localStorage.setItem('hivemem_mock', String(mock.value))
  window.location.reload()
}
function logout() { void auth.logout() }
</script>

<template>
  <div class="page">
    <div class="set-wrap">
      <div class="h-eyebrow">{{ t('settings.sovEyebrow') }}</div>
      <h1 class="h-display" style="font-size:28px;margin:4px 0 22px">{{ t('settings.sovTitle') }}</h1>

      <div class="set-section">
        <div class="set-section-title">{{ t('settings.accountSection') }}</div>
        <div class="setting-row">
          <div>
            <div class="set-nm">{{ t('settings.signedInAs', { name: auth.identity ?? '—' }) }}</div>
            <div class="set-sub">{{ t('settings.role', { role: auth.role ?? '—' }) }}</div>
          </div>
        </div>
        <div class="setting-row">
          <div><div class="set-nm">{{ t('settings.language') }}</div></div>
          <div class="lang-seg">
            <button :class="{ on: lang === 'de' }" data-test="lang-de" @click="setLocale('de')">DE</button>
            <button :class="{ on: lang === 'en' }" data-test="lang-en" @click="setLocale('en')">EN</button>
          </div>
        </div>
        <div class="setting-row">
          <div><div class="set-nm">{{ t('settings.mockMode') }}</div></div>
          <button type="button" :class="['switch', { on: mock }]" data-test="mock-switch" @click="toggleMock"><span /></button>
        </div>
        <div class="setting-row no-border">
          <button class="btn ghost" data-test="logout" @click="logout">{{ t('settings.logout') }}</button>
        </div>
      </div>

      <div class="set-section">
        <div class="set-section-title">{{ t('settings.routingSection') }}</div>
        <div class="set-note">{{ t('settings.routingNote') }}</div>
        <div v-for="item in ROUTING" :key="item.key" class="setting-row">
          <div>
            <div class="set-nm">{{ t('settings.routing.' + item.key + '.nm') }}</div>
            <div class="set-sub">{{ t('settings.routing.' + item.key + '.sub') }}</div>
          </div>
          <span class="status-badge" :class="item.on ? 'on' : 'off'">
            {{ item.on ? t('settings.statusOn') : t('settings.statusOff') }}
          </span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page { flex:1; overflow-y:auto; min-height:0; height:100%; }
.set-wrap { padding:34px 44px; max-width:720px; margin:0 auto; }
.h-eyebrow { font-size:11.5px; letter-spacing:.14em; text-transform:uppercase; color:var(--text-2); font-weight:600; }
.h-display { font-family:var(--font-display); font-weight:600; letter-spacing:-.02em; color:var(--text-0); }

.set-section { margin-bottom:34px; }
.set-section-title { font-family:var(--font-display); font-size:16px; font-weight:600; color:var(--text-0); margin-bottom:6px; }
.set-note { font-size:12.5px; color:var(--text-2); margin-bottom:6px; }

.setting-row { display:flex; align-items:center; justify-content:space-between; padding:16px 0; border-bottom:1px solid var(--line); }
.setting-row.no-border { border-bottom:none; }
.set-nm { font-size:15px; font-weight:500; color:var(--text-0); }
.set-sub { font-size:13px; color:var(--text-2); margin-top:2px; }

.switch { width:42px; height:24px; border-radius:13px; background:var(--bg-4); position:relative; transition:.18s; flex:none; border:none; cursor:pointer; }
.switch span { position:absolute; top:3px; left:3px; width:18px; height:18px; border-radius:50%; background:var(--text-2); transition:.18s; }
.switch.on { background:var(--honey); }
.switch.on span { left:21px; background:#1a1206; }

.lang-seg { display:inline-flex; gap:4px; }
.lang-seg button { padding:5px 12px; border-radius:8px; border:1px solid var(--line-2); background:var(--bg-3); color:var(--text-1); font-size:12.5px; cursor:pointer; }
.lang-seg button.on { background:var(--honey-dim); color:var(--honey); border-color:var(--line-honey); }

.status-badge { font-size:11px; padding:3px 10px; border-radius:7px; font-weight:500; border:1px solid; white-space:nowrap; }
.status-badge.on { color:var(--honey); border-color:var(--line-honey); background:var(--honey-dim); }
.status-badge.off { color:var(--text-2); border-color:var(--line-2); background:var(--bg-3); }

.btn { display:inline-flex; align-items:center; justify-content:center; gap:8px; padding:11px 16px; border-radius:11px;
  font-size:14px; font-weight:600; background:var(--honey); color:#1a1206; border:none; cursor:pointer; }
.btn.ghost { background:var(--bg-3); color:var(--text-0); border:1px solid var(--line-2); }
.btn.ghost:hover { background:var(--bg-4); }
</style>
