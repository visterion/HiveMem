<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useUiStore } from '../../stores/ui'
const ui = useUiStore()
const { t } = useI18n()
</script>

<template>
  <v-snackbar :model-value="ui.swUpdateReady" :timeout="-1" location="bottom" data-test="pwa-reload">
    {{ t('pwa.updateReady') }}
    <template #actions>
      <v-btn variant="text" @click="ui.applySwUpdate()">{{ t('pwa.reload') }}</v-btn>
    </template>
  </v-snackbar>
</template>
