<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import HmIcon from '../shell/HmIcon.vue'
const props = defineProps<{ sort: string; options?: [string, string][] }>()
const emit = defineEmits<{ (e:'change', v:string): void }>()
const { t } = useI18n()
const open = ref(false)
const opts = computed<[string,string][]>(() => props.options ?? [
  ['newest', t('scans.newestFirst')], ['oldest', t('scans.oldestFirst')], ['title', t('scans.titleAZ')],
])
const cur = computed(() => opts.value.find(o => o[0] === props.sort))
function pick(k:string){ emit('change', k); open.value = false }
</script>
<template>
  <div class="sortmenu" @mouseleave="open=false">
    <button class="sort-btn" @click="open=!open"><HmIcon name="sort" :size="15" /> {{ cur ? cur[1] : t('scans.sortBy') }} <HmIcon name="chevdown" :size="13" /></button>
    <div v-if="open" class="sort-pop">
      <button v-for="[k,lbl] in opts" :key="k" :class="{ on: sort===k }" @click="pick(k)">{{ lbl }}</button>
    </div>
  </div>
</template>
<style scoped>
.sortmenu { position:relative; }
.sort-btn { display:inline-flex; align-items:center; gap:7px; font-size:13px; color:var(--text-1); padding:7px 11px; border-radius:9px; border:1px solid var(--line); background:var(--bg-3); cursor:pointer; }
.sort-pop { position:absolute; top:calc(100% + 6px); right:0; background:var(--bg-3); border:1px solid var(--line-2); border-radius:11px; padding:5px; min-width:168px; box-shadow:var(--shadow-2); z-index:50; }
.sort-pop button { display:block; width:100%; text-align:left; padding:8px 11px; border-radius:7px; font-size:13px; color:var(--text-1); background:none; border:none; cursor:pointer; }
.sort-pop button.on { color:var(--honey); }
</style>
