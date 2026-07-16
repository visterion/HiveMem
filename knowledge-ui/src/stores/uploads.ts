import { defineStore } from 'pinia'
import { uploadAttachment, UploadError, MAX_UPLOAD_BYTES, type UploadResult } from '../api/uploadClient'

export type UploadStatus = 'queued' | 'uploading' | 'done' | 'error'

export interface UploadJob {
  id: string
  file: File
  fileName: string
  size: number
  status: UploadStatus
  progress: number
  result?: UploadResult
  error?: string
  errorKey?: string
  authFailure?: boolean
  retryable: boolean
}

function errorKeyFor(status: number): string {
  switch (status) {
    case 401: return 'upload.errSession'
    case 403: return 'upload.errForbidden'
    case 413: return 'upload.tooLarge'
    case 503: return 'upload.errStorage'
    case 400: return 'upload.errBad'
    default: return 'upload.errGeneric'
  }
}

interface UploadsState { jobs: UploadJob[]; running: boolean; authError: boolean; seq: number }

const INBOX_REALM = 'inbox'

export const useUploadsStore = defineStore('uploads', {
  state: (): UploadsState => ({ jobs: [], running: false, authError: false, seq: 0 }),
  getters: {
    active: (s): boolean => s.jobs.some(j => j.status === 'queued' || j.status === 'uploading'),
  },
  actions: {
    enqueue(files: File[] | FileList) {
      for (const file of Array.from(files)) {
        const id = `u${++this.seq}`
        const base = { id, file, fileName: file.name, size: file.size, progress: 0 }
        if (file.size > MAX_UPLOAD_BYTES) {
          this.jobs.push({ ...base, status: 'error', error: 'File too large', errorKey: 'upload.tooLarge', retryable: false })
        } else {
          this.jobs.push({ ...base, status: 'queued', retryable: false })
        }
      }
      void this.run()
    },
    async run() {
      if (this.running) return
      this.running = true
      try {
        for (;;) {
          const job = this.jobs.find(j => j.status === 'queued')
          if (!job) break
          job.status = 'uploading'
          job.progress = 0
          try {
            job.result = await uploadAttachment(job.file, { realm: INBOX_REALM }, f => { job.progress = f })
            job.status = 'done'
            job.progress = 1
          } catch (e) {
            job.status = 'error'
            if (e instanceof UploadError) {
              job.error = e.message
              job.errorKey = errorKeyFor(e.status)
              job.retryable = e.retryable ?? true
              if (e.status === 401) {
                // Do NOT hard-redirect mid-queue — it would discard the still-queued File
                // objects (e.g. photos just taken). Fail the rest; let the user re-login.
                this.authError = true
                job.authFailure = true
                for (const rest of this.jobs) {
                  if (rest.status === 'queued') {
                    rest.status = 'error'; rest.error = 'Session expired'
                    rest.errorKey = 'upload.errSession'; rest.retryable = true; rest.authFailure = true
                  }
                }
                break
              }
            } else {
              job.retryable = true
              job.errorKey = 'upload.errGeneric'
              job.error = (e as Error)?.message ?? String(e)
            }
          }
        }
      } finally {
        this.running = false
      }
    },
    retry(id: string) {
      const job = this.jobs.find(j => j.id === id)
      if (!job || job.status !== 'error') return
      job.status = 'queued'; job.error = undefined; job.progress = 0
      this.authError = this.jobs.some(j => j.status === 'error' && j.authFailure)
      void this.run()
    },
    clearDone() {
      this.jobs = this.jobs.filter(j => j.status !== 'done')
    },
  },
})
