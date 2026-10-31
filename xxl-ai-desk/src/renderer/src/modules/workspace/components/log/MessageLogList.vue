<script setup lang="ts">
/*
 * 对话记录列表：分页读取 desk_message，点击查看正文与原始 data。
 * @author xuxueli 2026-11-01
 */
import { computed, ref } from 'vue'
import { api } from '@renderer/api'
import { t } from '@renderer/i18n'
import { usePagedLog } from '../../composables/usePagedLog'
import {
  formatDateTime,
  prettyJson,
  previewOfMessage,
  previewTag
} from '../../utils/logDisplay'
import type { StoredMessage } from '@shared/ipc'

const props = defineProps<{
  /* 当前会话 */
  sessionId: string
  /* 面板是否处于可见激活态：按需加载 + 轮询刷新 */
  active?: boolean
}>()

/* 分页、可见性刷新与轮询统一由 usePagedLog 处理 */
const { items, total, page, pageSize, loading, error, changePage } =
  usePagedLog<StoredMessage>({
    sessionId: () => props.sessionId,
    active: () => Boolean(props.active),
    fetchPage: (query) => api.sessionLog.messages(query)
  })

/* 预生成摘要：避免模板中重复解析 JSON */
const rows = computed(() =>
  items.value.map((item) => ({ item, preview: previewOfMessage(item) }))
)

/* 详情弹窗 */
const detailVisible = ref(false)
const detail = ref<StoredMessage | null>(null)

/* 打开详情 */
function openDetail(item: StoredMessage): void {
  detail.value = item
  detailVisible.value = true
}
</script>

<template>
  <div class="log-list">
    <div v-if="loading" class="log-empty">{{ t('workspace.log.loading') }}</div>
    <div v-else-if="error" class="log-empty">{{ error }}</div>
    <div v-else-if="rows.length === 0" class="log-empty">{{ t('workspace.log.empty') }}</div>
    <div v-else class="log-rows">
      <button
        v-for="row in rows"
        :key="row.item.id"
        type="button"
        class="log-row"
        @click="openDetail(row.item)"
      >
        <div class="log-row-head">
          <span class="log-role">{{ row.item.role || '—' }}</span>
          <span class="log-meta">#{{ row.item.seq }}</span>
          <span class="log-meta">{{ formatDateTime(row.item.addTime) }}</span>
        </div>
        <div class="log-text" :class="row.preview.kind">
          <span v-if="previewTag(row.preview.kind)" class="log-tag">
            {{ previewTag(row.preview.kind) }}
          </span>
          <span class="log-text-body">
            {{ row.preview.text || t('workspace.log.emptyContent') }}
          </span>
        </div>
      </button>
    </div>

    <div class="log-footer">
      <el-pagination
        :current-page="page"
        :page-size="pageSize"
        :total="total"
        layout="prev, pager, next"
        small
        @current-change="changePage"
      />
    </div>

    <el-dialog
      v-model="detailVisible"
      :title="t('workspace.log.messageDetail')"
      width="720px"
      append-to-body
      class="log-dialog"
    >
      <div v-if="detail" class="detail-body">
        <div class="detail-meta">
          <span>{{ detail.role }}</span>
          <span>#{{ detail.seq }}</span>
          <span>{{ formatDateTime(detail.addTime) }}</span>
        </div>
        <div class="detail-section">
          <div class="detail-label">{{ t('workspace.log.content') }}</div>
          <pre class="detail-pre">{{ detail.content || t('workspace.log.emptyContent') }}</pre>
        </div>
        <div class="detail-section">
          <div class="detail-label">{{ t('workspace.log.raw') }}</div>
          <pre class="detail-pre">{{ prettyJson(detail.data) }}</pre>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
@use './logList.scss';

/* 角色标签：单个列表专用，其余行样式复用 logList */
.log-role {
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--desk-selected-bg);
  color: var(--desk-text-secondary);
}

.detail-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-height: 60vh;
  overflow-y: auto;
}

.detail-meta {
  display: flex;
  gap: 12px;
  font-size: var(--desk-font-xs);
  color: var(--desk-text-tertiary);
}

.detail-section {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.detail-label {
  font-size: var(--desk-font-xs);
  color: var(--desk-text-secondary);
}

.detail-pre {
  margin: 0;
  padding: 10px;
  max-height: 40vh;
  overflow: auto;
  border-radius: 8px;
  background: var(--desk-panel-bg);
  font-family: var(--desk-font-mono, monospace);
  font-size: var(--desk-font-xs);
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
