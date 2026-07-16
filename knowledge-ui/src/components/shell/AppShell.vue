<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useLayout } from '../../composables/useLayout'
import { useUiStore } from '../../stores/ui'
import IconRail from './IconRail.vue'
import TopBar from './TopBar.vue'
import TweaksPanel from './TweaksPanel.vue'
import Reader from '../Reader.vue'
import PwaReloadPrompt from './PwaReloadPrompt.vue'
import UploadFab from './UploadFab.vue'

const route = useRoute()
const ui = useUiStore()
const { isMobile } = useLayout()
const full = computed(() => !!route.meta?.full)
const hasPanel = computed(() => !!(route.matched[0]?.components as Record<string, unknown> | undefined)?.panel)

// Drawer always starts closed on mobile; every route change closes it again
// (the search tab re-opens it explicitly via IconRail's re-tap toggle).
watch(() => route.fullPath, () => {
  if (isMobile.value) ui.setDrawer(false)
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
    <PwaReloadPrompt />
    <UploadFab />
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
