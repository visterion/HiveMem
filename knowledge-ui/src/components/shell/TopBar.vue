<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { setLocale } from '../../i18n'
import { usePrefsStore } from '../../stores/prefs'
import HmIcon from './HmIcon.vue'

const route = useRoute()
const { t, locale } = useI18n()
const prefs = usePrefsStore()
const title = computed(() => t((route.meta?.title as string) || 'nav.search'))
</script>

<template>
  <div class="topbar">
    <div class="crumbs">
      <span>HiveMem</span><span class="sep"><HmIcon name="chevron" :size="14" /></span><b>{{ title }}</b>
    </div>
    <div class="topbar-actions">
      <div class="toggle">
        <button :class="{ on: locale === 'de' }" data-test="lang-de" @click="setLocale('de')">DE</button>
        <button :class="{ on: locale === 'en' }" data-test="lang-en" @click="setLocale('en')">EN</button>
      </div>
      <div class="toggle">
        <button :class="{ on: prefs.theme === 'dark' }" data-test="theme-dark" @click="prefs.setTheme('dark')"><HmIcon name="moon" :size="15" /></button>
        <button :class="{ on: prefs.theme === 'light' }" data-test="theme-light" @click="prefs.setTheme('light')"><HmIcon name="sun" :size="15" /></button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.topbar { height:56px; flex:none; display:flex; align-items:center; gap:14px; padding:0 20px;
  border-bottom:1px solid var(--line); background:color-mix(in srgb, var(--bg-0) 80%, transparent);
  backdrop-filter:blur(8px); z-index:10; }
.crumbs { display:flex; align-items:center; gap:9px; font-size:13.5px; color:var(--text-1); }
.crumbs .sep { color:var(--text-3); display:inline-flex; } .crumbs b { color:var(--text-0); font-weight:600; }
.topbar-actions { margin-left:auto; display:flex; align-items:center; gap:8px; }
.toggle { display:inline-flex; background:var(--bg-3); border:1px solid var(--line); border-radius:9px; padding:3px; gap:2px; }
.toggle button { padding:4px 9px; border-radius:6px; font-size:12.5px; color:var(--text-2); display:grid; place-items:center;
  background:none; border:none; cursor:pointer; }
.toggle button.on { background:var(--bg-0); color:var(--honey); box-shadow:var(--shadow-1); }
</style>
