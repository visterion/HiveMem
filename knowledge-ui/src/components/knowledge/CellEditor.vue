<script setup lang="ts">
import { ref, shallowRef, onMounted, onBeforeUnmount, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { EditorView, keymap } from '@codemirror/view'
import { EditorState } from '@codemirror/state'
import { markdown } from '@codemirror/lang-markdown'
import { basicSetup } from 'codemirror'
import { computeLineDiff } from '../../composables/diffPreview'

const props = defineProps<{ content: string; saving?: boolean }>()
const emit = defineEmits<{
  (e: 'save', content: string): void
  (e: 'cancel'): void
}>()

const { t } = useI18n()
const host = ref<HTMLElement | null>(null)
const view = shallowRef<EditorView | null>(null)
const draft = ref(props.content ?? '')
const showDiff = ref(false)

const diff = computed(() => computeLineDiff(props.content ?? '', draft.value))

function doSave() {
  // Pull the freshest doc straight from the editor in case the update listener
  // hasn't flushed yet (e.g. save fired from a keybinding mid-composition).
  if (view.value) draft.value = view.value.state.doc.toString()
  if (!diff.value.changed) { emit('cancel'); return }
  emit('save', draft.value)
}

onMounted(() => {
  if (!host.value) return
  const saveKeymap = keymap.of([
    { key: 'Mod-s', preventDefault: true, run: () => { doSave(); return true } },
    { key: 'Escape', run: () => { emit('cancel'); return true } },
  ])
  const updateListener = EditorView.updateListener.of((u) => {
    if (u.docChanged) draft.value = u.state.doc.toString()
  })
  view.value = new EditorView({
    parent: host.value,
    state: EditorState.create({
      doc: props.content ?? '',
      extensions: [basicSetup, markdown(), saveKeymap, updateListener, EditorView.lineWrapping],
    }),
  })
  view.value.focus()
})

onBeforeUnmount(() => { view.value?.destroy(); view.value = null })
</script>

<template>
  <div class="ce" data-test="cell-editor">
    <div ref="host" class="ce-cm" data-test="cell-editor-cm" />
    <div v-if="showDiff && diff.changed" class="ce-diff" data-test="cell-editor-diff">
      <pre v-for="(p, i) in diff.parts" :key="i" :class="['ce-line', p.type]">{{ p.value.replace(/\n$/, '') }}</pre>
    </div>
    <div class="ce-bar">
      <span class="ce-stat" data-test="cell-editor-stat">
        <span class="add">+{{ diff.added }}</span>
        <span class="del">−{{ diff.removed }}</span>
      </span>
      <button class="ce-btn" data-test="cell-editor-toggle-diff" :disabled="!diff.changed" @click="showDiff = !showDiff">
        {{ showDiff ? t('editor.hideDiff') : t('editor.showDiff') }}
      </button>
      <span class="ce-spacer" />
      <button class="ce-btn" data-test="cell-editor-cancel" @click="emit('cancel')">{{ t('editor.cancel') }}</button>
      <button class="ce-btn primary" data-test="cell-editor-save" :disabled="saving || !diff.changed" @click="doSave">
        {{ saving ? t('editor.saving') : t('editor.save') }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.ce { display: flex; flex-direction: column; gap: 10px; }
.ce-cm { border: 1px solid var(--line); border-radius: 10px; overflow: hidden; background: var(--bg-0); }
.ce-cm :deep(.cm-editor) { max-height: 52vh; font-family: var(--font-mono); font-size: 13px; }
.ce-cm :deep(.cm-editor.cm-focused) { outline: none; }
.ce-cm :deep(.cm-scroller) { overflow: auto; }
.ce-diff { border: 1px solid var(--line); border-radius: 10px; padding: 8px; background: var(--bg-0); max-height: 30vh; overflow: auto; }
.ce-line { margin: 0; font-family: var(--font-mono); font-size: 12px; line-height: 1.5; white-space: pre-wrap; padding: 0 6px; }
.ce-line.add { background: rgba(80, 200, 120, .14); color: #8fe7ad; }
.ce-line.del { background: rgba(230, 90, 90, .14); color: #ef9a9a; }
.ce-line.context { color: var(--text-2); }
.ce-bar { display: flex; align-items: center; gap: 8px; }
.ce-stat { display: inline-flex; gap: 8px; font-size: 12px; font-variant-numeric: tabular-nums; }
.ce-stat .add { color: #8fe7ad; }
.ce-stat .del { color: #ef9a9a; }
.ce-spacer { flex: 1; }
.ce-btn { padding: 6px 12px; min-height: 36px; font-size: 13px; border-radius: 8px; border: 1px solid var(--line);
  background: var(--bg-4); color: var(--text-1); cursor: pointer; }
.ce-btn:hover:not(:disabled) { background: var(--bg-3); color: var(--text-0); }
.ce-btn:disabled { opacity: .5; cursor: default; }
.ce-btn.primary { background: var(--honey-dim); color: var(--honey); border-color: var(--line-honey); }
</style>
