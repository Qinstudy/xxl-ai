<script setup lang="ts">
/*
 * 文件查看器：文本编辑（语法高亮 + 行号 + ⌘/Ctrl+S 保存）、Markdown / 图片预览与只读展示。
 * @author xuxueli 2026-10-25
 */
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { renderMarkdown } from '@renderer/composables/useMarkdown'
import { onCodeCopyClick } from '@renderer/composables/useCodeCopy'
import { onContentLinkClick } from '@renderer/composables/useContentLink'
import { escapeHtml, highlightCode, languageOf } from '@renderer/composables/useHighlight'
import { parentDir } from '../../utils/path'
import { t } from '@renderer/i18n'
import type { FileContent } from '@shared/ipc'

const props = defineProps<{
  root?: string
  file: FileContent | null
  loading: boolean
  error: string
  /* 可编辑：文本文件且未被截断 */
  editable: boolean
  /* 是否预览（预览态且可预览时生效） */
  showPreview: boolean
  isImage: boolean
  isMarkdown: boolean
  saving: boolean
}>()
const draft = defineModel<string>('draft', { required: true })
const emit = defineEmits<{ save: [] }>()

/* Markdown 预览点击：链接交给系统浏览器，其余交给代码复制事件委托 */
function onContentClick(event: MouseEvent): void {
  if (onContentLinkClick(event)) {
    return
  }
  void onCodeCopyClick(event)
}

/* 编辑区着色长度上限：超过则退化为纯文本，保证大文件编辑流畅 */
const MAX_EDITOR_HIGHLIGHT = 100000

/* 文本域 / 行号栏 / 高亮层元素引用（用于滚动同步与按键处理） */
const editorRef = ref<HTMLTextAreaElement | null>(null)
const gutterRef = ref<HTMLElement | null>(null)
const highlightRef = ref<HTMLElement | null>(null)

/* 代码行数：编辑态取草稿，预览/只读取文件内容 */
const lineCount = computed(() => {
  if (!props.file) {
    return 0
  }
  const text = props.editable ? draft.value : props.file.content
  return text.split('\n').length
})

/* 高亮输入节流：连续输入时延后重算，避免逐键全量着色造成卡顿 */
const highlightSource = ref('')
let highlightTimer: ReturnType<typeof setTimeout> | null = null

watch(
  draft,
  (value, previous) => {
    /* 首次（含文件切换后的初始值）立即着色，避免高亮层短暂空白 */
    if (previous === undefined) {
      highlightSource.value = value
      return
    }
    if (highlightTimer) {
      clearTimeout(highlightTimer)
    }
    highlightTimer = setTimeout(() => {
      highlightTimer = null
      highlightSource.value = value
    }, 120)
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  if (highlightTimer) {
    clearTimeout(highlightTimer)
    highlightTimer = null
  }
})

/* 编辑区语法高亮：按文件名推断语言，末尾补换行以对齐最后一行高度 */
const highlightedDraft = computed(() => {
  if (!props.file) {
    return ''
  }
  /* 超大文件跳过着色，避免重算造成卡顿 */
  if (highlightSource.value.length > MAX_EDITOR_HIGHLIGHT) {
    return `${escapeHtml(highlightSource.value)}\n`
  }
  return `${highlightCode(highlightSource.value, languageOf(props.file.name))}\n`
})

/* 编辑器按键：⌘/Ctrl+S 保存；Tab 插入两个空格 */
function onEditorKeydown(event: KeyboardEvent): void {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's') {
    event.preventDefault()
    emit('save')
    return
  }
  if (event.key === 'Tab') {
    event.preventDefault()
    const el = editorRef.value
    if (!el) {
      return
    }
    const { selectionStart, selectionEnd } = el
    draft.value = `${draft.value.slice(0, selectionStart)}  ${draft.value.slice(selectionEnd)}`
    void nextTick(() => {
      el.selectionStart = el.selectionEnd = selectionStart + 2
    })
  }
}

/* 编辑区滚动 → 同步行号栏与高亮层的偏移，保持内容对齐 */
function syncGutter(): void {
  const editor = editorRef.value
  if (!editor) {
    return
  }
  if (gutterRef.value) {
    gutterRef.value.scrollTop = editor.scrollTop
  }
  if (highlightRef.value) {
    highlightRef.value.scrollTop = editor.scrollTop
    highlightRef.value.scrollLeft = editor.scrollLeft
  }
}
</script>

<template>
  <div class="file-viewer">
    <div v-if="!root" class="viewer-empty">{{ t('panel.needProject') }}</div>
    <div v-else-if="loading" class="viewer-empty">
      <el-icon class="spin"><Loading /></el-icon>
    </div>
    <div v-else-if="error" class="viewer-empty error">{{ error }}</div>
    <div v-else-if="!file" class="viewer-empty">{{ t('panel.noSelection') }}</div>
    <template v-else>
      <div v-if="file.truncated" class="viewer-banner">{{ t('panel.truncated') }}</div>
      <div v-if="showPreview && isImage && file.dataUrl" class="viewer-image">
        <img :src="file.dataUrl" :alt="file.name" />
      </div>
      <div
        v-else-if="showPreview && isMarkdown"
        class="viewer-markdown markdown-body"
        @click="onContentClick"
        v-html="renderMarkdown(file.content, parentDir(file.path))"
      ></div>
      <div v-else-if="file.binary" class="viewer-empty">{{ t('panel.binary') }}</div>
      <!-- 可编辑：带行号的纯文本编辑器（⌘/Ctrl+S 保存），高亮层置于文本域下方 -->
      <div v-else-if="editable" class="editor">
        <div ref="gutterRef" class="editor-gutter">
          <span v-for="n in lineCount" :key="n">{{ n }}</span>
        </div>
        <div class="editor-code">
          <pre ref="highlightRef" class="editor-highlight hljs" aria-hidden="true"><code v-html="highlightedDraft"></code></pre>
          <textarea
            ref="editorRef"
            v-model="draft"
            class="editor-input"
            wrap="off"
            spellcheck="false"
            @scroll="syncGutter"
            @keydown="onEditorKeydown"
          ></textarea>
        </div>
      </div>
      <!-- 只读（如超大文件被截断）：纯文本展示 -->
      <div v-else class="code-scroll">
        <div class="code-inner">
          <div class="code-gutter">
            <span v-for="n in lineCount" :key="n">{{ n }}</span>
          </div>
          <pre class="code-pre">{{ file.content }}</pre>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped lang="scss">
.file-viewer {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--desk-bg);
}

.viewer-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  font-size: 13px;
  color: var(--desk-text-tertiary);
  text-align: center;
}

.viewer-empty.error {
  color: var(--desk-danger);
}

.viewer-banner {
  flex-shrink: 0;
  padding: 6px 12px;
  background: var(--desk-bg-elevated);
  border-bottom: 1px solid var(--desk-border);
  font-size: 12px;
  color: var(--desk-text-tertiary);
}

/* 代码阅读区：行号固定左侧，横向滚动仅在代码区 */
.code-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
  background: var(--desk-code-bg);
}

.code-inner {
  display: flex;
  align-items: stretch;
  min-width: max-content;
  min-height: 100%;
}

.code-gutter {
  position: sticky;
  left: 0;
  z-index: 1;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  min-width: 48px;
  padding: 12px 12px 12px 16px;
  background: var(--desk-code-bg);
  border-right: 1px solid var(--desk-code-border);
  text-align: right;
  color: var(--desk-text-tertiary);
  font-family: 'SFMono-Regular', ui-monospace, Menlo, Consolas, monospace;
  font-size: 13px;
  line-height: 22px;
  font-variant-numeric: tabular-nums;
  user-select: none;
}

.code-gutter span {
  display: block;
  height: 22px;
}

.code-pre {
  flex: 1;
  margin: 0;
  padding: 12px 18px;
  color: var(--desk-code-text);
  font-family: 'SFMono-Regular', ui-monospace, Menlo, Consolas, monospace;
  font-size: 13px;
  line-height: 22px;
  white-space: pre;
}

/* 可编辑代码区：行号 + 高亮层 + 透明文本域三者叠加（GitHub 代码底色） */
.editor {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: stretch;
  background: var(--desk-code-bg);
}

.editor-gutter {
  flex-shrink: 0;
  overflow: hidden;
  min-width: 52px;
  padding: 12px 14px 12px 16px;
  background: var(--desk-code-bg);
  border-right: 1px solid var(--desk-code-border);
  text-align: right;
  color: var(--desk-text-tertiary);
  font-family: 'SFMono-Regular', ui-monospace, Menlo, Consolas, monospace;
  font-size: 13px;
  line-height: 22px;
  font-variant-numeric: tabular-nums;
  user-select: none;
}

.editor-gutter span {
  display: block;
  height: 22px;
}

/* 代码容器：高亮层与透明文本域完全重叠，保证字符逐一对齐 */
.editor-code {
  position: relative;
  flex: 1;
  min-width: 0;
}

.editor-highlight,
.editor-input {
  position: absolute;
  inset: 0;
  margin: 0;
  padding: 12px 18px;
  border: none;
  font-family: 'SFMono-Regular', ui-monospace, Menlo, Consolas, monospace;
  font-size: 13px;
  line-height: 22px;
  white-space: pre;
  tab-size: 2;
  overflow: auto;
}

.editor-highlight {
  background: transparent;
  color: var(--desk-code-text);
  overflow: hidden;
  pointer-events: none;
}

.editor-input {
  outline: none;
  resize: none;
  background: transparent;
  color: transparent;
  caret-color: var(--desk-text);
}

.editor-input::placeholder {
  color: var(--desk-text-tertiary);
}

.editor-input::selection {
  background: var(--desk-selection);
}

.editor-code:focus-within {
  box-shadow: inset 0 0 0 1px var(--desk-code-border);
}

/* Markdown 预览 */
.viewer-markdown {
  flex: 1;
  overflow: auto;
  padding: 16px 20px;
  font-size: 14px;
}

/* 图片预览：居中展示，自适应容器 */
.viewer-image {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
  overflow: auto;
  background: var(--desk-bg-elevated);
}

.viewer-image img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
  border-radius: var(--desk-radius-sm);
}

.spin {
  animation: viewer-spin 1s linear infinite;
}

@keyframes viewer-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
