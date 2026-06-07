<script setup lang="ts">
import { computed, getCurrentInstance } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import HmIcon from '../components/shell/HmIcon.vue'
const _route = useRoute() as any
const instance = getCurrentInstance()
const { t } = useI18n()
const title = computed(() => {
  const route = _route ?? (instance?.proxy as any)?.$route
  return t((route?.meta?.title as string) || 'nav.search')
})
</script>
<template>
  <div class="stage-body">
    <div class="empty fade-in">
      <div>
        <div class="hexbig"><HmIcon name="sparkle" :size="40" /></div>
        <div class="h-display" style="font-size:24px;margin-bottom:6px">{{ title }}</div>
        <div>{{ t('common.comingSoon') }}</div>
      </div>
    </div>
  </div>
</template>
<style scoped>
.stage-body { flex:1; overflow-y:auto; min-height:0; }
.empty { display:grid; place-items:center; height:100%; text-align:center; color:var(--text-2); }
.hexbig { width:86px; height:94px; margin:0 auto 18px; display:grid; place-items:center; color:var(--honey); opacity:.8; }
.h-display { font-family:var(--font-display); font-weight:600; letter-spacing:-0.02em; color:var(--text-0); }
</style>
