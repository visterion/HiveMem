<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'

defineProps<{
  cellCount?: number
  factCount?: number
  realmCount?: number
}>()

const q = ref('')
const { t } = useI18n()
</script>

<template>
  <div class="hive-hud">
    <div class="hive-hud__logo">HiveMem</div>
    <div class="hive-hud__stats">
      {{ t('search.stats', { cells: cellCount ?? 0, facts: factCount ?? 0, realms: realmCount ?? 0 }) }}
    </div>
    <div class="hive-hud__search">
      <v-text-field
        v-model="q"
        variant="solo-filled"
        density="compact"
        rounded="lg"
        :placeholder="t('search.hudPlaceholder')"
        hide-details
        disabled
        :title="t('search.comingSoon')"
        prepend-inner-icon="mdi-magnify"
        class="hive-hud__field"
      />
    </div>
  </div>
</template>

<style scoped>
.hive-hud {
  position: fixed;
  inset: 0;
  pointer-events: none;
  z-index: 12;
}
.hive-hud__logo {
  position: absolute;
  top: 1rem; left: 1.2rem;
  font-weight: 300;
  letter-spacing: 0.2em;
  font-size: 1.2rem;
  color: #00BFFF;
  background: rgba(10,10,26,0.7);
  border: 1px solid rgba(0,191,255,0.15);
  padding: 0.35rem 0.8rem;
  border-radius: 0.4rem;
  pointer-events: auto;
}
.hive-hud__stats {
  position: absolute;
  top: 1rem; right: 1.2rem;
  color: #9eb6d9;
  background: rgba(10,10,26,0.7);
  border: 1px solid rgba(0,191,255,0.15);
  padding: 0.35rem 0.8rem;
  border-radius: 0.4rem;
  font-size: 0.9rem;
  pointer-events: auto;
}
.hive-hud__search {
  position: absolute;
  left: 50%; bottom: 1.4rem;
  transform: translateX(-50%);
  width: min(420px, 70vw);
  pointer-events: auto;
}
.hive-hud__field :deep(.v-field) {
  background: rgba(10,10,26,0.7);
  border: 1px solid rgba(0,191,255,0.15);
}
</style>
