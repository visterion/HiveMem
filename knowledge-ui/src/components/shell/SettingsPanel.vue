<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '../../stores/auth'
import { setLocale, i18n, type Locale } from '../../i18n'

const { t } = useI18n()
const auth = useAuthStore()
const mock = ref(localStorage.getItem('hivemem_mock') === 'true')
const locale = computed<Locale>({
  get: () => i18n.global.locale.value as Locale,
  set: (v) => setLocale(v)
})

function toggleMock(v: boolean | null) {
  localStorage.setItem('hivemem_mock', String(v))
  window.location.reload()
}

function logoutAndReload() {
  auth.logout()
  window.location.reload()
}
</script>

<template>
  <v-list density="compact">
    <v-list-item
      :title="t('settings.signedInAs', { name: auth.identity ?? '…' })"
      :subtitle="t('settings.role', { role: auth.role ?? 'none' })"
    />
  </v-list>
  <div class="lang-row">
    <span class="lang-label">{{ t('settings.language') }}</span>
    <v-btn-toggle v-model="locale" density="compact" mandatory>
      <v-btn value="de" size="small">DE</v-btn>
      <v-btn value="en" size="small">EN</v-btn>
    </v-btn-toggle>
  </div>
  <v-switch v-model="mock" :label="t('settings.mockMode')" color="primary" @update:model-value="toggleMock" />
  <v-btn block color="error" variant="tonal" @click="logoutAndReload">{{ t('settings.logout') }}</v-btn>
</template>

<style scoped>
.lang-row { display:flex; align-items:center; justify-content:space-between; padding:8px 0; }
.lang-label { font-size:12px; letter-spacing:0.05em; color:#aaa; }
</style>
