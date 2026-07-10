<script setup lang="ts">
import { ref, computed, watchEffect } from 'vue'
import { useI18n } from 'vue-i18n'
import PostalMime from 'postal-mime'

const props = defineProps<{ url: string }>()
const parsed = ref<any>(null)
const err = ref('')
const { t } = useI18n()

watchEffect(async () => {
  const url = props.url
  // Reset state on every attachment switch so a previous error/body never sticks.
  err.value = ''
  parsed.value = null
  if (!url) return
  try {
    const res = await fetch(url)
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const raw = await res.text()
    const result = await new PostalMime().parse(raw)
    if (url !== props.url) return // stale response — a newer attachment is loading
    parsed.value = result
  } catch (e: any) {
    if (url !== props.url) return
    err.value = e?.message ?? t('reader.eml.parseError')
  }
})

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

// Security: email HTML is attacker-controlled (stored XSS vector). It is rendered
// inside a sandboxed iframe WITHOUT allow-scripts / allow-same-origin, so injected
// markup can never execute JS or touch the app's cookie-authed origin. Links still
// open via allow-popups (escaping the sandbox into a normal tab).
const bodyDoc = computed(() => {
  if (!parsed.value) return ''
  const html = parsed.value.html
    ? String(parsed.value.html)
    : `<pre style="white-space:pre-wrap;font-family:inherit;margin:0">${escapeHtml(String(parsed.value.text ?? ''))}</pre>`
  return `<!doctype html><html><head><meta charset="utf-8"><base target="_blank">`
    + `<style>body{margin:0;font-family:system-ui,sans-serif;font-size:15px;line-height:1.55;color:#e8e8ea;background:transparent}`
    + `blockquote{border-left:2px solid #666;padding-left:10px;color:#999}`
    + `img{max-width:100%}a{color:#8ab4f8}</style></head><body>${html}</body></html>`
})
</script>

<template>
  <article class="eml">
    <v-alert v-if="err" type="error" variant="tonal">{{ err }}</v-alert>
    <template v-else-if="parsed">
      <header>
        <div><strong>{{ t('reader.eml.from') }}</strong> {{ parsed.from?.address }}</div>
        <div><strong>{{ t('reader.eml.to') }}</strong> {{ parsed.to?.map((r: any) => r.address).join(', ') }}</div>
        <div><strong>{{ t('reader.eml.subject') }}</strong> {{ parsed.subject }}</div>
        <div><strong>{{ t('reader.eml.date') }}</strong> {{ parsed.date }}</div>
      </header>
      <iframe
        class="body-frame"
        sandbox="allow-popups allow-popups-to-escape-sandbox"
        referrerpolicy="no-referrer"
        :srcdoc="bodyDoc"
        :title="parsed.subject || 'email'"
      ></iframe>
    </template>
  </article>
</template>

<style scoped>
.eml { max-width: 780px; margin: 0 auto; font-family: system-ui, sans-serif; padding: 20px 0; color: #e8e8ea; display: flex; flex-direction: column; }
header { border-bottom: 1px solid #2a2a3a; padding-bottom: 12px; margin-bottom: 18px; font-size: 13px; color: #aaa; }
header strong { color: #eee; margin-right: 4px; }
.body-frame { width: 100%; min-height: 60vh; flex: 1; border: 0; background: transparent; }
</style>
