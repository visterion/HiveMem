<script setup lang="ts">
import { computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useReaderStore } from '../stores/reader'
import { useCellStore } from '../stores/cell'
import MarkdownTab from './readers/MarkdownTab.vue'
import EmlTab from './readers/EmlTab.vue'
import DocumentViewer from './readers/DocumentViewer.vue'
import { buildAttachmentTabs } from './readers/attachmentTabs'
import { useHistoryClose } from '../composables/useHistoryClose'

const reader = useReaderStore()
const cellStore = useCellStore()
const { t } = useI18n()

const attachments = computed(() => buildAttachmentTabs(cellStore.current?.cell.attachments))

function kindOf(tab: string) { return attachments.value.find(a => a.id === tab)?.kind }
function urlOf(tab: string) { return attachments.value.find(a => a.id === tab)?.url ?? '' }
function filenameOf(tab: string) { return attachments.value.find(a => a.id === tab)?.title ?? '' }

const { arm, requestClose } = useHistoryClose(() => reader.close())

// Arm history-close sentinel when the reader opens.
watch(() => reader.open, (open) => { if (open) arm() }, { immediate: true })

// When the reader opens for a cell, make sure its attachments are loaded.
watch(() => reader.open && cellStore.currentId, (id) => {
  if (reader.open && typeof id === 'string') cellStore.ensureAttachments(id)
}, { immediate: true })
</script>

<template>
  <v-dialog v-model="reader.open" fullscreen transition="dialog-bottom-transition" persistent>
    <div class="reader-shell" v-if="cellStore.current">
      <header>
        <v-btn icon="mdi-arrow-left" variant="text" @click="requestClose()" />
        <v-tabs v-model="reader.activeTab" density="compact" color="primary">
          <v-tab value="markdown">{{ t('reader.markdown') }}</v-tab>
          <v-tab v-for="a in attachments" :key="a.id" :value="a.id">{{ a.title }}</v-tab>
        </v-tabs>
        <v-spacer />
        <v-btn icon="mdi-pencil" variant="text" disabled :title="t('reader.editorTooltip')" />
      </header>
      <main class="reader-body">
        <MarkdownTab v-if="reader.activeTab === 'markdown'" :content="cellStore.current.cell.content" />
        <DocumentViewer
          v-else-if="kindOf(reader.activeTab) === 'pdf' || kindOf(reader.activeTab) === 'image'"
          :url="urlOf(reader.activeTab)"
          :kind="kindOf(reader.activeTab) as 'pdf' | 'image'"
          :filename="filenameOf(reader.activeTab)"
        />
        <EmlTab v-else-if="kindOf(reader.activeTab) === 'eml'" :url="urlOf(reader.activeTab)" />
        <div v-else-if="kindOf(reader.activeTab) === 'other'" class="download-fallback">
          <a :href="urlOf(reader.activeTab)" download>{{ t('reader.download') }}</a>
        </div>
      </main>
    </div>
  </v-dialog>
</template>

<style scoped>
.reader-shell { position:fixed; inset:0; background:#0a0a14; display:flex; flex-direction:column; }
header { display:flex; align-items:center; padding:6px 10px; background:#12121e; border-bottom:1px solid #1a1a24; }
.reader-body { flex:1; overflow-y:auto; padding:0 20px; }
.download-fallback { padding: 40px; text-align: center; }
.download-fallback a { color: #8ab4f8; }
</style>
