<!--
  Agent 交付物产物在线实时预览弹窗（C端对话页与B端对话管理明细抽屉共用）
  支持：.html 沙箱隔离渲染/源码切换；.csv 结构化表格/原始文本切换；.md 富文本渲染/源码切换；.sql 代码浏览、复制与一键下载
-->
<template>
  <el-dialog
    v-model="visible"
    class="artifact-preview-dialog"
    width="860px"
    top="5vh"
    append-to-body
    destroy-on-close
  >
    <template #header>
      <div class="preview-dialog-header">
        <div class="header-left">
          <span class="ext-pill" :class="'badge-' + normalizedExt">{{ normalizedExt.toUpperCase() }}</span>
          <span class="file-title" :title="displayFileName">{{ displayFileName }}</span>
          <span v-if="displaySizeBytes != null" class="file-size">{{ formatFileSize(displaySizeBytes) }}</span>
        </div>
        <div class="header-actions" v-if="preview">
          <el-button size="small" icon="CopyDocument" @click="copyContent">
            {{ t('business.chat.artifactCopy') }}
          </el-button>
          <el-button type="primary" size="small" icon="Download" @click="$emit('download')">
            {{ t('business.chat.artifactDownload') }}
          </el-button>
        </div>
      </div>
    </template>

    <div v-loading="loading" class="preview-body">
      <template v-if="preview">
        <el-alert
          v-if="preview.truncated"
          type="warning"
          show-icon
          :closable="false"
          :title="t('business.chat.artifactTruncatedTip')"
          class="truncated-alert"
        />

        <!-- 1、HTML 交付物：隔离沙箱实时渲染 / 源码视图 -->
        <template v-if="normalizedExt === 'html'">
          <el-tabs v-model="htmlTab" class="preview-tabs">
            <el-tab-pane :label="t('business.chat.artifactTabRender')" name="render">
              <iframe class="artifact-html-frame" sandbox="" referrerpolicy="no-referrer" :srcdoc="preview.content"></iframe>
            </el-tab-pane>
            <el-tab-pane :label="t('business.chat.artifactTabSource')" name="source">
              <pre class="artifact-code-view"><code>{{ preview.content }}</code></pre>
            </el-tab-pane>
          </el-tabs>
        </template>

        <!-- 2、CSV 交付物：结构化表格视图 / 原始文本视图 -->
        <template v-else-if="normalizedExt === 'csv'">
          <div class="csv-toolbar" v-if="csvParsed.headers.length > 0">
            <span class="csv-count">{{ t('business.chat.artifactCsvRows', [csvParsed.rows.length]) }}</span>
          </div>
          <el-tabs v-model="csvTab" class="preview-tabs">
            <el-tab-pane :label="t('business.chat.artifactTabTable')" name="table">
              <el-table
                v-if="csvParsed.headers.length > 0"
                :data="csvParsed.rows"
                border
                stripe
                size="small"
                max-height="480"
                style="width: 100%"
              >
                <el-table-column type="index" width="56" align="center" />
                <el-table-column
                  v-for="(col, cIdx) in csvParsed.headers"
                  :key="cIdx"
                  :prop="String(cIdx)"
                  :label="col"
                  min-width="130"
                  show-overflow-tooltip
                />
              </el-table>
              <el-empty v-else :description="t('common.emptyData')" :image-size="60" />
            </el-tab-pane>
            <el-tab-pane :label="t('business.chat.artifactTabSource')" name="source">
              <pre class="artifact-code-view"><code>{{ preview.content }}</code></pre>
            </el-tab-pane>
          </el-tabs>
        </template>

        <!-- 3、Markdown 交付物：富文本渲染 / 源码视图 -->
        <template v-else-if="normalizedExt === 'md'">
          <el-tabs v-model="mdTab" class="preview-tabs">
            <el-tab-pane :label="t('business.chat.artifactTabRender')" name="render">
              <div class="artifact-md-render">
                <MarkdownView :content="preview.content" />
              </div>
            </el-tab-pane>
            <el-tab-pane :label="t('business.chat.artifactTabSource')" name="source">
              <pre class="artifact-code-view"><code>{{ preview.content }}</code></pre>
            </el-tab-pane>
          </el-tabs>
        </template>

        <!-- 4、SQL 及文本交付物：等宽代码视图 -->
        <template v-else>
          <pre class="artifact-code-view"><code>{{ preview.content }}</code></pre>
        </template>
      </template>
      <el-empty v-else-if="!loading" :description="t('common.emptyData')" :image-size="60" />
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { t } from '@/i18n'
import MarkdownView from './MarkdownView.vue'
import type { ArtifactItem, ArtifactPreviewDTO } from '@/modules/business/session/types'

const visible = defineModel<boolean>('visible', { default: false })

const props = defineProps<{
  loading?: boolean
  preview?: ArtifactPreviewDTO
  artifact?: ArtifactItem
}>()

defineEmits<{
  download: []
}>()

// --------------------------------- ref data ---------------------------------
const htmlTab = ref<'render' | 'source'>('render')
const csvTab = ref<'table' | 'source'>('table')
const mdTab = ref<'render' | 'source'>('render')

const normalizedExt = computed(() => (props.preview?.ext || props.artifact?.ext || 'sql').toLowerCase())
const displayFileName = computed(() => props.preview?.fileName || props.artifact?.fileName || '')
const displaySizeBytes = computed(() => props.preview?.sizeBytes ?? props.artifact?.sizeBytes)

/** CSV 预览最大渲染行数（防止大文件表格节点过多卡顿） */
const MAX_CSV_TABLE_ROWS = 1000

const csvParsed = computed(() => {
  if (normalizedExt.value !== 'csv' || !props.preview?.content) {
    return { headers: [] as string[], rows: [] as Record<string, string>[] }
  }
  const matrix = parseCsv(props.preview.content, MAX_CSV_TABLE_ROWS + 1)
  if (matrix.length === 0) {
    return { headers: [] as string[], rows: [] as Record<string, string>[] }
  }
  const headers = matrix[0].map((cell, idx) => cell || `Col ${idx + 1}`)
  const rows: Record<string, string>[] = []
  for (let i = 1; i < matrix.length; i++) {
    const rowObj: Record<string, string> = {}
    for (let c = 0; c < headers.length; c++) {
      rowObj[String(c)] = matrix[i][c] ?? ''
    }
    rows.push(rowObj)
  }
  return { headers, rows }
})

// --------------------------------- fun ---------------------------------
watch(visible, (open) => {
  if (open) {
    htmlTab.value = 'render'
    csvTab.value = 'table'
    mdTab.value = 'render'
  }
})

function formatFileSize(bytes?: number): string {
  if (bytes == null || bytes < 0) return '0 B'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`
}

async function copyContent() {
  const text = props.preview?.content || ''
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(t('business.chat.copySuccess'))
  } catch {
    const textarea = document.createElement('textarea')
    textarea.value = text
    textarea.style.position = 'fixed'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    document.body.removeChild(textarea)
    ElMessage.success(t('business.chat.copySuccess'))
  }
}

/**
 * 轻量 RFC-4180 CSV 解析器：兼容 UTF-8 BOM、引号包裹逗号与双引号转义
 */
function parseCsv(text: string, maxRows: number): string[][] {
  const clean = text.startsWith('\uFEFF') ? text.slice(1) : text
  const rows: string[][] = []
  let row: string[] = []
  let field = ''
  let inQuotes = false

  for (let i = 0; i < clean.length; i++) {
    const ch = clean[i]
    if (inQuotes) {
      if (ch === '"') {
        if (clean[i + 1] === '"') {
          field += '"'
          i++
        } else {
          inQuotes = false
        }
      } else {
        field += ch
      }
      continue
    }

    if (ch === '"') {
      inQuotes = true
    } else if (ch === ',') {
      row.push(field)
      field = ''
    } else if (ch === '\r' || ch === '\n') {
      if (ch === '\r' && clean[i + 1] === '\n') {
        i++
      }
      row.push(field)
      field = ''
      if (row.some((v) => v !== '')) {
        rows.push(row)
        if (rows.length >= maxRows) {
          return rows
        }
      }
      row = []
    } else {
      field += ch
    }
  }

  if (field !== '' || row.length > 0) {
    row.push(field)
    if (row.some((v) => v !== '')) {
      rows.push(row)
    }
  }
  return rows
}
</script>

<style scoped>
.preview-dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-right: 28px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
}

.ext-pill {
  flex-shrink: 0;
  padding: 2px 7px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 700;
  color: #fff;
  background: #64748b;
}

.badge-sql {
  background: #4f46e5;
}

.badge-html {
  background: #ea580c;
}

.badge-csv {
  background: #059669;
}

.badge-md {
  background: #0284c7;
}

.file-title {
  font-size: 15px;
  font-weight: 600;
  color: #1e293b;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-size {
  flex-shrink: 0;
  font-size: 12px;
  color: #94a3b8;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.preview-body {
  min-height: 220px;
}

.truncated-alert {
  margin-bottom: 10px;
}

.csv-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 4px;
}

.csv-count {
  font-size: 12px;
  color: #64748b;
}

.artifact-html-frame {
  width: 100%;
  height: 520px;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  background: #fff;
}

.artifact-code-view {
  margin: 0;
  padding: 14px;
  border-radius: 6px;
  background: #0f172a;
  color: #e2e8f0;
  font-family: Consolas, Monaco, 'Courier New', monospace;
  font-size: 12.5px;
  line-height: 1.65;
  max-height: 520px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
}
.artifact-md-render {
  max-height: 520px;
  overflow: auto;
  padding: 14px 18px;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  background: #fff;
}
</style>
