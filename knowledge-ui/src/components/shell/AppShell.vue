<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import IconRail from './IconRail.vue'
import TopBar from './TopBar.vue'
import TweaksPanel from './TweaksPanel.vue'
import Reader from '../Reader.vue'

const route = useRoute()
const full = computed(() => !!route.meta?.full)
</script>

<template>
  <div class="app">
    <IconRail />
    <div class="view-wrap">
      <router-view name="panel" />
      <div :class="['stage', { full }]">
        <div class="hexfield" />
        <TopBar />
        <div class="stage-body">
          <router-view />
        </div>
      </div>
      <router-view name="inspector" />
    </div>
    <TweaksPanel />
    <Reader />
  </div>
</template>

<style scoped>
.view-wrap { display:contents; }
.stage { grid-column:3; position:relative; overflow:hidden; min-width:0; display:flex; flex-direction:column; }
.stage.full { grid-column:3 / 5; }
.stage-body { flex:1; overflow-y:auto; position:relative; min-height:0; }
</style>
