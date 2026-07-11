<script setup lang="ts">
import { computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { useReaderStore } from '../stores/reader'
import { useCellStore } from '../stores/cell'
import DocInfoTab from './readers/DocInfoTab.vue'
import EmlTab from './readers/EmlTab.vue'
import DocumentViewer from './readers/DocumentViewer.vue'
import { buildAttachmentTabs } from './readers/attachmentTabs'
import { useHistoryClose } from '../composables/useHistoryClose'

const reader = useReaderStore()
const cellStore = useCellStore()
const { t } = useI18n()
// useRoute() returns undefined when no router is installed (some unit tests mount
// Reader standalone) — guard every access instead of assuming a route exists.
const route = useRoute()

const attachments = computed(() => buildAttachmentTabs(cellStore.current?.cell.attachments))

// A scan's single attachment is the document itself, so label its tab with the document's
// short title (topic) instead of the raw machine filename. Extra attachments keep their names.
function tabLabel(a: { id: string; title: string }, index: number): string {
  const topic = cellStore.current?.cell.topic?.trim()
  if (index === 0 && topic) return topic
  return a.title
}

function kindOf(tab: string) { return attachments.value.find(a => a.id === tab)?.kind }
function urlOf(tab: string) { return attachments.value.find(a => a.id === tab)?.url ?? '' }
function filenameOf(tab: string) { return attachments.value.find(a => a.id === tab)?.title ?? '' }

const { arm, requestClose, disarm } = useHistoryClose(() => reader.close())

// Arm the history-close sentinel when the reader opens; clean it up if the reader
// is closed out-of-band (e.g. a route guard) without going through requestClose.
// The pushed URL carries a deep-link query param so the address bar is shareable:
// ?doc=<id> on the scans route (the reader shows a scanned document there),
// ?cell=<id> everywhere else (e.g. the search route's fullscreen reader).
watch(() => reader.open, (open) => {
  if (!open) { disarm(); return }
  if (route && reader.cellId) {
    const u = new URL(location.href)
    const param = route.name === 'scans' ? 'doc' : 'cell'
    u.searchParams.set(param, reader.cellId)
    arm(u.pathname + u.search)
  } else {
    arm()
  }
}, { immediate: true })

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
          <v-tab value="info">{{ t('reader.info') }}</v-tab>
          <v-tab v-for="(a, i) in attachments" :key="a.id" :value="a.id">{{ tabLabel(a, i) }}</v-tab>
        </v-tabs>
        <v-spacer />
        <v-btn icon="mdi-pencil" variant="text" disabled :title="t('reader.editorTooltip')" />
      </header>
      <main class="reader-body">
        <DocInfoTab v-if="reader.activeTab === 'info'" />
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
/* position:relative makes this the containing block for DocumentViewer's absolutely-
   positioned .dv (inset:0). Without it, .dv resolves to the fixed .reader-shell and paints
   over the header, hiding the back button + tabs. */
.reader-body { position:relative; flex:1; overflow-y:auto; padding:0 20px; }
.download-fallback { padding: 40px; text-align: center; }
.download-fallback a { color: #8ab4f8; }
</style>
