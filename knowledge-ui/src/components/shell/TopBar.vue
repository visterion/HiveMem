<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { usePrefsStore } from '../../stores/prefs'
import { useLayout } from '../../composables/useLayout'
import HmIcon from './HmIcon.vue'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const prefs = usePrefsStore()
const { isMobile } = useLayout()
const title = computed(() => t((route.meta?.title as string) || 'nav.search'))
defineProps<{ canFilter?: boolean }>()
defineEmits<{ (e: 'toggle-panel'): void }>()
</script>

<template>
  <div class="topbar">
    <button v-if="canFilter" class="tb-filter" data-test="mobile-filter" @click="$emit('toggle-panel')">
      <HmIcon name="scans" :size="18" />
    </button>
    <div class="crumbs">
      <span class="root">HiveMem</span><span class="sep"><HmIcon name="chevron" :size="14" /></span><b>{{ title }}</b>
    </div>
    <div class="topbar-actions">
      <button v-if="isMobile" class="tb-icon" data-test="tb-settings"
              :title="t('nav.settings')" :aria-label="t('nav.settings')"
              @click="router.push({ name: 'settings' })">
        <HmIcon name="settings" :size="16" />
      </button>
      <div class="toggle">
        <button :class="{ on: prefs.theme === 'dark' }" data-test="theme-dark" @click="prefs.setTheme('dark')"><HmIcon name="moon" :size="15" /></button>
        <button :class="{ on: prefs.theme === 'light' }" data-test="theme-light" @click="prefs.setTheme('light')"><HmIcon name="sun" :size="15" /></button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.topbar { height:calc(56px + env(safe-area-inset-top)); flex:none; display:flex; align-items:center; gap:14px;
  padding:env(safe-area-inset-top) 20px 0;
  border-bottom:1px solid var(--line); background:color-mix(in srgb, var(--bg-0) 80%, transparent);
  backdrop-filter:blur(8px); z-index:10; overflow:hidden; }
.crumbs { display:flex; align-items:center; gap:9px; font-size:13.5px; color:var(--text-1);
  min-width:0; flex:1 1 auto; overflow:hidden; }
.crumbs > span:first-child { flex:none; } .crumbs .sep { flex:none; }
.crumbs .sep { color:var(--text-3); display:inline-flex; }
@media (max-width: 959px) {
  /* Hide the root segment AND its separator so the page title gets the space.
     NOTE: `.sep:first-of-type` would NOT match here — :first-of-type is per tag
     name, and the first <span> sibling is the root segment, not the separator. */
  .crumbs > .root, .crumbs > .sep { display:none; }
}
.crumbs b { color:var(--text-0); font-weight:600; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.topbar-actions { margin-left:auto; display:flex; align-items:center; gap:8px; flex:none; }
.toggle { display:inline-flex; background:var(--bg-3); border:1px solid var(--line); border-radius:9px; padding:3px; gap:2px; }
.toggle button { padding:4px 9px; border-radius:6px; font-size:12.5px; color:var(--text-2); display:grid; place-items:center;
  background:none; border:none; cursor:pointer; }
.toggle button.on { background:var(--bg-0); color:var(--honey); box-shadow:var(--shadow-1); }
.tb-filter { background:var(--bg-3); border:1px solid var(--line); border-radius:9px; width:38px; height:34px;
  display:grid; place-items:center; color:var(--text-1); cursor:pointer; flex:none; }
.tb-icon { background:var(--bg-3); border:1px solid var(--line); border-radius:9px; width:34px; height:34px;
  display:grid; place-items:center; color:var(--text-1); cursor:pointer; flex:none; }
.tb-icon:hover { color:var(--text-0); }
</style>
