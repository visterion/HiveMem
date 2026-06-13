<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useLayout } from '../../composables/useLayout'
import { useUiStore } from '../../stores/ui'
import IconRail from './IconRail.vue'
import TopBar from './TopBar.vue'
import TweaksPanel from './TweaksPanel.vue'
import Reader from '../Reader.vue'

const route = useRoute()
const ui = useUiStore()
const { isMobile } = useLayout()
const full = computed(() => !!route.meta?.full)
const hasPanel = computed(() => !!(route.matched[0]?.components as Record<string, unknown> | undefined)?.panel)

// On mobile, auto-open the panel drawer for panel-primary routes (search); close otherwise.
watch(() => route.fullPath, () => {
  if (!isMobile.value) return
  ui.setDrawer(route.meta?.mobilePrimary === 'panel')
}, { immediate: true })
// Leaving mobile (resize to desktop) clears the drawer flag so desktop is unaffected.
watch(isMobile, m => { if (!m) ui.setDrawer(false) })
</script>

<template>
  <div class="app" :class="{ 'is-mobile': isMobile, 'drawer-open': isMobile && ui.mobileDrawerOpen }">
    <IconRail />
    <div class="view-wrap">
      <router-view name="panel" />
      <div :class="['stage', { full }]">
        <div class="hexfield" />
        <TopBar :can-filter="isMobile && hasPanel" @toggle-panel="ui.toggleDrawer()" />
        <div class="stage-body">
          <router-view />
        </div>
      </div>
      <router-view name="inspector" />
    </div>
    <div v-if="isMobile && ui.mobileDrawerOpen" class="drawer-scrim" @click="ui.setDrawer(false)" />
    <TweaksPanel />
    <Reader />
  </div>
</template>

<style scoped>
.view-wrap { display:contents; }
.stage { grid-column:3; position:relative; overflow:hidden; min-width:0; display:flex; flex-direction:column; }
.stage.full { grid-column:3 / 5; }
.stage-body { flex:1; overflow-y:auto; position:relative; min-height:0; }
@media (max-width: 959px) {
  .stage, .stage.full { grid-column: 1; }
  .drawer-scrim { position:fixed; inset:0; background:rgba(5,6,9,0.5); z-index:45; }
}
</style>
