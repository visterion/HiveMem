<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '../../stores/auth'
import HmIcon from './HmIcon.vue'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const auth = useAuthStore()

interface NavItem { name: string; icon: string; role?: string }
const primary: NavItem[] = [
  { name: 'search', icon: 'search' },
  { name: 'hive', icon: 'reader' },
  { name: 'graph', icon: 'graph' },
  { name: 'realms', icon: 'realms' },
  { name: 'photos', icon: 'photos' },
  { name: 'scans', icon: 'scans' },
  { name: 'timemachine', icon: 'history' },
]
const bottom: NavItem[] = [
  { name: 'queen', icon: 'queen', role: 'admin' },
  { name: 'settings', icon: 'settings' },
]
function visible(it: NavItem) { return !it.role || auth.role === it.role }
function go(name: string) { router.push({ name }) }
function isActive(name: string) { return route.name === name }
</script>

<template>
  <div class="rail">
    <div class="logo"><span class="hex" /><b>H</b></div>
    <div class="rail-group">
      <template v-for="it in primary" :key="it.name">
        <button v-if="visible(it)"
                :class="['rail-btn', { active: isActive(it.name) }]" @click="go(it.name)">
          <HmIcon :name="it.icon" :size="21" />
          <span class="rail-tip">{{ t('nav.' + it.name) }}</span>
        </button>
      </template>
    </div>
    <div class="rail-spacer" />
    <div class="rail-group">
      <template v-for="it in bottom" :key="it.name">
        <button v-if="visible(it)"
                :class="['rail-btn', { active: isActive(it.name), 'rail-btn--desktop-only': it.name === 'settings' }]" @click="go(it.name)">
          <HmIcon :name="it.icon" :size="21" />
          <span class="rail-tip">{{ t('nav.' + it.name) }}</span>
        </button>
      </template>
    </div>
  </div>
</template>

<style scoped>
.rail { grid-column:1; background:var(--bg-1); border-right:1px solid var(--line);
  display:flex; flex-direction:column; align-items:center; padding:14px 0 12px; gap:4px; z-index:30; }
.logo { width:40px; height:44px; margin-bottom:14px; display:grid; place-items:center; color:var(--bg-0); position:relative; }
.logo .hex { position:absolute; inset:0; background:linear-gradient(155deg,var(--honey-bright),var(--honey-deep));
  clip-path:polygon(50% 0,100% 25%,100% 75%,50% 100%,0 75%,0 25%); box-shadow:0 0 22px -4px var(--honey-glow); }
.logo b { position:relative; font-family:var(--font-display); font-weight:700; font-size:20px; }
.rail-group { display:flex; flex-direction:column; gap:4px; align-items:center; }
.rail-spacer { flex:1; }
.rail-btn { width:46px; height:42px; border-radius:11px; display:grid; place-items:center; color:var(--text-2);
  position:relative; transition:color .15s, background .15s; background:none; border:none; cursor:pointer; }
.rail-btn:hover { color:var(--text-0); background:var(--bg-3); }
.rail-btn.active { color:var(--honey); background:var(--honey-dim); }
.rail-btn.active::before { content:''; position:absolute; left:-14px; top:50%; transform:translateY(-50%);
  width:3px; height:20px; border-radius:3px; background:var(--honey); box-shadow:0 0 10px var(--honey-glow); }
.rail-tip { position:absolute; left:calc(100% + 12px); top:50%; transform:translateY(-50%) translateX(-4px);
  background:var(--bg-3); color:var(--text-0); border:1px solid var(--line-2); padding:5px 10px; border-radius:8px;
  font-size:12.5px; white-space:nowrap; opacity:0; pointer-events:none; transition:opacity .14s, transform .14s;
  z-index:60; box-shadow:var(--shadow-1); }
.rail-btn:hover .rail-tip { opacity:1; transform:translateY(-50%) translateX(0); }

@media (max-width: 959px) {
  .rail {
    grid-column: 1 / -1;
    position: fixed; left: 0; right: 0; bottom: 0; top: auto;
    flex-direction: row; align-items: center; gap: 2px;
    height: auto; padding: 6px 8px calc(6px + env(safe-area-inset-bottom));
    border-right: none; border-top: 1px solid var(--line);
    overflow-x: auto; overflow-y: hidden; z-index: 40;
    -webkit-overflow-scrolling: touch;
  }
  .rail .logo { display: none; }
  .rail-spacer { display: none; }
  .rail-group { flex-direction: row; gap: 2px; }
  .rail-btn { min-width: 44px; height: 44px; flex: none; }
  .rail-tip { display: none; }
  .rail-btn--desktop-only { display: none; }
}
</style>
