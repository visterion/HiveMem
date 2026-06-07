<script setup lang="ts">
import { ref, computed } from 'vue'
import HmIcon from '../shell/HmIcon.vue'
interface Opt { value: string; count: number }
const props = defineProps<{ title: string; field: string; options: Opt[]; selected: Set<string>; labels?: Record<string,string>; max?: number }>()
const emit = defineEmits<{ (e:'toggle', field:string, value:string): void }>()
const open = ref(true); const showAll = ref(false)
const opts = computed(() => props.options.filter(o => o.count > 0 || props.selected.has(o.value)).slice().sort((a,b)=>b.count-a.count))
const shown = computed(() => (showAll.value || !props.max) ? opts.value : opts.value.slice(0, props.max))
</script>
<template>
  <div v-if="opts.length" class="facet">
    <button class="facet-title row" @click="open=!open"><span>{{ title }}</span><HmIcon name="chevdown" :size="14" :style="{ transform: open ? '' : 'rotate(-90deg)' }" /></button>
    <template v-if="open">
      <button v-for="o in shown" :key="o.value" :class="['facet-row', { on: selected.has(o.value) }]" @click="emit('toggle', field, o.value)">
        <span class="facet-box"><HmIcon v-if="selected.has(o.value)" name="check" :size="12" /></span>
        <span class="facet-lbl">{{ labels?.[o.value] ?? o.value }}</span>
        <span class="facet-ct">{{ o.count }}</span>
      </button>
      <button v-if="max && opts.length > max" class="facet-more" @click="showAll=!showAll">{{ showAll ? '− weniger' : `+ ${opts.length - max} mehr` }}</button>
    </template>
  </div>
</template>
<style scoped>
.facet { margin-bottom:16px; }
.facet-title { font-size:11px; letter-spacing:.1em; text-transform:uppercase; color:var(--text-2); font-weight:600; padding:6px 8px; background:none; border:none; cursor:pointer; }
.facet-title.row { display:flex; align-items:center; justify-content:space-between; width:100%; }
.facet-row { display:flex; align-items:center; gap:9px; padding:6px 8px; border-radius:8px; cursor:pointer; font-size:13.5px; color:var(--text-1); width:100%; background:none; border:none; text-align:left; }
.facet-row:hover { background:var(--bg-3); color:var(--text-0); }
.facet-row.on { color:var(--text-0); }
.facet-box { width:16px; height:16px; border-radius:5px; border:1.5px solid var(--line-2); display:grid; place-items:center; flex:none; color:var(--bg-0); }
.facet-row.on .facet-box { background:var(--honey); border-color:var(--honey); }
.facet-lbl { white-space:nowrap; overflow:hidden; text-overflow:ellipsis; min-width:0; flex:1; }
.facet-ct { margin-left:auto; font-family:var(--font-mono); font-size:11px; color:var(--text-2); flex:none; padding-left:8px; }
.facet-row.on .facet-ct { color:var(--honey); }
.facet-more { font-size:12px; color:var(--honey); padding:6px 10px; text-align:left; width:100%; background:none; border:none; cursor:pointer; }
</style>
