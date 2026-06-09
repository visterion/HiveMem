<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { setLocale } from '../../i18n'
import { usePrefsStore, type Density, type Hive } from '../../stores/prefs'
import HmIcon from './HmIcon.vue'

const { t, locale } = useI18n()
const prefs = usePrefsStore()
const open = ref(false)
const accents = ['#F4B740', '#46D6E0', '#E0A434', '#9BCB3C']
const densities: Density[] = ['compact', 'regular', 'comfy']
const hives: Hive[] = ['subtil', 'spürbar', 'stark']
</script>

<template>
  <div class="tweaks">
    <button class="tw-fab" data-test="tweaks-toggle" @click="open = !open"><HmIcon name="sparkle" :size="18" /></button>
    <div v-if="open" class="tw-panel">
      <div class="tw-sec">{{ t('tweaks.appearance') }}</div>
      <div class="tw-row"><span>{{ t('tweaks.theme') }}</span>
        <div class="tw-seg">
          <button :class="{ on: prefs.theme === 'dark' }" @click="prefs.setTheme('dark')">dark</button>
          <button :class="{ on: prefs.theme === 'light' }" @click="prefs.setTheme('light')">light</button>
        </div>
      </div>
      <div class="tw-row"><span>{{ t('tweaks.language') }}</span>
        <div class="tw-seg">
          <button :class="{ on: locale === 'de' }" @click="setLocale('de')">DE</button>
          <button :class="{ on: locale === 'en' }" @click="setLocale('en')">EN</button>
        </div>
      </div>
      <div class="tw-row"><span>{{ t('tweaks.accent') }}</span>
        <div class="tw-swatches">
          <button v-for="a in accents" :key="a" :data-test="'accent-' + a"
                  :class="['tw-sw', { on: prefs.accent === a }]" :style="{ background: a }" @click="prefs.setAccent(a)" />
        </div>
      </div>
      <div class="tw-sec">{{ t('tweaks.layout') }}</div>
      <div class="tw-row"><span>{{ t('tweaks.density') }}</span>
        <div class="tw-seg">
          <button v-for="d in densities" :key="d" :data-test="'density-' + d"
                  :class="{ on: prefs.density === d }" @click="prefs.setDensity(d)">{{ d }}</button>
        </div>
      </div>
      <div class="tw-row"><span>{{ t('tweaks.hive') }}</span>
        <div class="tw-seg">
          <button v-for="h in hives" :key="h" :data-test="'hive-' + h"
                  :class="{ on: prefs.hive === h }" @click="prefs.setHive(h)">{{ h }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tweaks { position:fixed; right:18px; bottom:18px; z-index:120; }
.tw-fab { width:44px; height:44px; border-radius:50%; background:var(--honey); color:#1a1206;
  display:grid; place-items:center; box-shadow:var(--shadow-1); border:none; cursor:pointer; }
.tw-panel { position:absolute; right:0; bottom:54px; width:300px; background:var(--bg-2); border:1px solid var(--line-2);
  border-radius:14px; padding:14px; box-shadow:var(--shadow-2); }
.tw-sec { font-size:11px; letter-spacing:.12em; text-transform:uppercase; color:var(--text-2); font-weight:600; margin:8px 0 8px; }
.tw-row { display:flex; align-items:center; justify-content:space-between; gap:10px; margin-bottom:10px; font-size:13px; color:var(--text-1); }
.tw-seg { display:inline-flex; background:var(--bg-3); border:1px solid var(--line); border-radius:8px; padding:2px; gap:2px; }
.tw-seg button { padding:4px 8px; border-radius:6px; font-size:12px; color:var(--text-2); background:none; border:none; cursor:pointer; }
.tw-seg button.on { background:var(--bg-0); color:var(--honey); }
.tw-swatches { display:inline-flex; gap:6px; }
.tw-sw { width:22px; height:22px; border-radius:7px; border:2px solid transparent; cursor:pointer; }
.tw-sw.on { border-color:var(--text-0); }
</style>
