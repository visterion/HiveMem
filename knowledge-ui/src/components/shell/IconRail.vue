<script setup lang="ts">
import { useUiStore } from '../../stores/ui'
import { useAuthStore } from '../../stores/auth'
import { useRouter } from 'vue-router'

const ui = useUiStore()
const auth = useAuthStore()
const router = useRouter()

const items: { id: Exclude<typeof ui.activePanel, null>; icon: string; role?: 'admin' }[] = [
  { id: 'search',   icon: 'mdi-magnify' },
  { id: 'realms',   icon: 'mdi-star-four-points-outline' },
  { id: 'reading',  icon: 'mdi-book-open-variant' },
  { id: 'stats',    icon: 'mdi-chart-donut', role: 'admin' },
  { id: 'history',  icon: 'mdi-history' },
  { id: 'settings', icon: 'mdi-cog' }
]

function visible(it: typeof items[number]) { return !it.role || auth.role === it.role }
</script>

<template>
  <div class="rail">
    <div class="logo">H</div>
    <template v-for="it in items" :key="it.id">
      <v-btn
        v-if="visible(it)"
        :icon="it.icon"
        variant="text"
        :color="ui.activePanel === it.id ? 'primary' : undefined"
        @click="ui.togglePanel(it.id)"
      />
    </template>
    <div class="spacer" />
    <v-btn icon="mdi-graph-outline" variant="text" @click="router.push('/graph')" />
    <v-btn icon="mdi-rotate-3d-variant" variant="text" @click="router.push('/cinema')" />
    <v-btn v-if="auth.role === 'admin'" icon="mdi-crown" variant="text" @click="router.push('/queen')" />
  </div>
</template>

<style scoped>
.rail { position:fixed; top:0; left:0; bottom:0; width:56px; background:#0b0b15; border-right:1px solid #1a1a24;
       display:flex; flex-direction:column; align-items:center; padding-top:8px; gap:4px; z-index:10; }
.logo { width:32px; height:32px; border-radius:8px; background:#4dc4ff; color:#050510;
        display:grid; place-items:center; font-weight:bold; margin-bottom:8px; }
.spacer { flex:1; }
</style>
