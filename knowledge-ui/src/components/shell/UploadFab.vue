<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useUploadsStore } from '../../stores/uploads'

const uploads = useUploadsStore()
const router = useRouter()
const { t } = useI18n()

const fileInput = ref<HTMLInputElement | null>(null)
const cameraInput = ref<HTMLInputElement | null>(null)
const menu = ref(false)

const activeCount = computed(() => uploads.jobs.filter(j => j.status === 'queued' || j.status === 'uploading').length)

function onPick(e: Event) {
  const input = e.target as HTMLInputElement
  if (input.files && input.files.length) {
    uploads.enqueue(input.files)
    router.push({ name: 'upload' })
  }
  input.value = '' // allow re-picking the same file
  menu.value = false
}
</script>

<template>
  <div class="upload-fab">
    <input ref="fileInput" data-test="upload-fab-file" type="file" hidden
           accept="image/*,application/pdf" multiple @change="onPick" />
    <input ref="cameraInput" data-test="upload-fab-camera" type="file" hidden
           accept="image/*" capture="environment" @change="onPick" />
    <v-menu v-model="menu" location="top">
      <template #activator="{ props }">
        <v-btn v-bind="props" icon color="primary" size="large" data-test="upload-fab"
               :aria-label="t('upload.add')">
          <v-badge :model-value="activeCount > 0" :content="activeCount" color="info">
            <v-icon>mdi-plus</v-icon>
          </v-badge>
        </v-btn>
      </template>
      <v-list>
        <v-list-item prepend-icon="mdi-camera" :title="t('upload.takePhoto')" @click="cameraInput?.click()" />
        <v-list-item prepend-icon="mdi-file-upload" :title="t('upload.chooseFile')" @click="fileInput?.click()" />
        <v-list-item prepend-icon="mdi-format-list-bulleted" :title="t('upload.viewAll')"
                     @click="router.push({ name: 'upload' }); menu = false" />
      </v-list>
    </v-menu>
  </div>
</template>

<style scoped>
.upload-fab { position: fixed; right: 20px; z-index: 42;
  bottom: calc(20px + env(safe-area-inset-bottom)); }
@media (max-width: 959px) { .upload-fab { bottom: calc(72px + env(safe-area-inset-bottom)); } }
</style>
