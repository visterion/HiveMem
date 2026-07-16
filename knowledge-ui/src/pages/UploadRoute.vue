<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useUploadsStore } from '../stores/uploads'

const uploads = useUploadsStore()
const router = useRouter()
const { t } = useI18n()

function openCell(cellId: string) {
  router.push({ name: 'search', query: { cell: cellId } })
}
function relogin() { window.location.href = '/login' }
</script>

<template>
  <div class="upload-page">
    <header class="up-head">
      <h1>{{ t('upload.title') }}</h1>
      <p class="up-note">{{ t('upload.inbox') }}</p>
      <p class="up-note">{{ t('upload.keepOpen') }}</p>
    </header>

    <div v-if="uploads.authError" class="up-banner" data-test="upload-relogin">
      <span>{{ t('common.connectError') }}</span>
      <v-btn size="small" color="primary" @click="relogin">{{ t('upload.reLogin') }}</v-btn>
    </div>

    <p v-if="!uploads.jobs.length" class="up-empty" data-test="upload-empty">{{ t('upload.empty') }}</p>

    <ul v-else class="up-list">
      <li v-for="job in uploads.jobs" :key="job.id" class="up-job" data-test="upload-job">
        <div class="up-job-main">
          <span class="up-name">{{ job.fileName }}</span>
          <span class="up-status" :class="job.status">{{ t('upload.status.' + job.status) }}</span>
        </div>
        <v-progress-linear v-if="job.status === 'uploading'" :model-value="job.progress * 100" height="4" />
        <div v-if="job.status === 'done' && job.result" class="up-job-actions">
          <span v-if="job.result.deduplicated" class="up-dedupe">{{ t('upload.deduped') }}</span>
          <v-btn variant="text" size="small" @click="openCell(job.result.cellId)">{{ t('upload.openCell') }}</v-btn>
        </div>
        <div v-else-if="job.status === 'error'" class="up-job-actions">
          <span class="up-err">{{ job.errorKey ? t(job.errorKey) : job.error }}</span>
          <v-btn v-if="job.retryable" variant="text" size="small" @click="uploads.retry(job.id)">{{ t('upload.retry') }}</v-btn>
        </div>
      </li>
    </ul>

    <div v-if="uploads.jobs.some(j => j.status === 'done')" class="up-foot">
      <v-btn variant="text" size="small" @click="uploads.clearDone()">{{ t('upload.clearDone') }}</v-btn>
    </div>
  </div>
</template>

<style scoped>
.upload-page { max-width: 720px; margin: 0 auto; padding: 24px 16px 96px; }
.up-head h1 { font-family: var(--font-display); font-size: 22px; margin-bottom: 6px; }
.up-note { color: var(--text-2); font-size: 13px; margin: 2px 0; }
.up-banner { display:flex; align-items:center; gap:12px; justify-content:space-between;
  background: var(--bg-3); border:1px solid var(--line-2); border-radius:10px; padding:10px 14px; margin:14px 0; }
.up-empty { color: var(--text-2); margin-top: 32px; text-align:center; }
.up-list { list-style:none; margin:16px 0 0; padding:0; display:flex; flex-direction:column; gap:8px; }
.up-job { background: var(--bg-2); border:1px solid var(--line); border-radius:10px; padding:10px 14px; }
.up-job-main { display:flex; justify-content:space-between; gap:12px; }
.up-name { font-weight:600; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.up-status { font-size:12px; color: var(--text-2); text-transform:uppercase; }
.up-status.done { color: var(--honey); }
.up-status.error { color: #e0674f; }
.up-job-actions { display:flex; align-items:center; gap:10px; margin-top:6px; }
.up-err { color:#e0674f; font-size:12.5px; }
.up-dedupe { color: var(--text-2); font-size:12.5px; }
</style>
