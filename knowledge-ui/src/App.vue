<script setup lang="ts">
import { onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useTheme } from 'vuetify'
import { useAuthStore } from './stores/auth'
import { useUiStore } from './stores/ui'
import { useCanvasStore } from './stores/canvas'
import { usePrefsStore } from './stores/prefs'
import AppShell from './components/shell/AppShell.vue'

const { t } = useI18n()
const auth = useAuthStore()
const ui = useUiStore()
const canvas = useCanvasStore()
const prefs = usePrefsStore()
const vTheme = useTheme()
watch(() => prefs.theme, (v) => {
  vTheme.global.name.value = v === 'light' ? 'hivememLight' : 'hivememDark'
}, { immediate: true })

onMounted(async () => {
  try { await auth.init() } catch { /* httpClient redirects to /login on 401 */ }
})
</script>

<template>
  <v-app>
    <v-main>
      <AppShell v-if="auth.isAuthenticated" />
      <div v-else class="splash">{{ t('common.connecting') }}</div>
    </v-main>
    <v-snackbar v-if="ui.toast" :color="ui.toast.kind" :model-value="!!ui.toast" timeout="8000">
      {{ ui.toast.text }}
      <template #actions>
        <v-btn variant="text" @click="canvas.loadTopLevel(); ui.toast = null">{{ t('common.reload') }}</v-btn>
      </template>
    </v-snackbar>
  </v-app>
</template>

<style scoped>
.splash { display:flex; align-items:center; justify-content:center; height:100vh; color:var(--honey); }
</style>
