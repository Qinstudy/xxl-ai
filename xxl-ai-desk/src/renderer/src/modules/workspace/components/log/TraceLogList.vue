<script setup lang="ts">
/*
 * 模型通讯日志列表：分页读取模型 trace，支持查看完整请求 / 响应 / 工具调用与清空。
 * @author xuxueli 2026-11-01
 */
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@renderer/api'
import { t } from '@renderer/i18n'
import { ipcErrorMessage } from '@renderer/utils/ipcError'
import { usePagedLog } from '../../composables/usePagedLog'
import { formatClock, previewOfTrace, previewTag, traceStatusText } from '../../utils/logDisplay'
import TraceDetailDialog from './TraceDetailDialog.vue'
import type { LlmTraceDTO } from '@shared/ipc'

const props = defineProps<{
  /* 当前会话 */
  sessionId: string
  /* 面板是否处于可见激活态：按需加载 + 轮询刷新 */
  active?: boolean
}>()

/* 分页、可见性刷新与轮询统一由 usePagedLog 处理 */
const { items, total, page, pageSize, loading, error, changePage } =
  usePagedLog<LlmTraceDTO>({
    sessionId: () => props.sessionId,
    active: () => Boolean(props.active),
    fetchPage: (query) => api.sessionLog.traces(query)
  })

/* 预生成摘要：避免模板中重复解析 JSON */
const rows = computed(() =>
  items.value.map((item) => ({ item, preview: previewOfTrace(item) }))
)

/* 详情弹窗 */
const detailVisible = ref(false)
const detail = ref<LlmTraceDTO | null>(null)

/* 打开详情 */
function openDetail(item: LlmTraceDTO): void {
  detail.value = item
  detailVisible.value = true
}

/* 清空本会话通讯记录 */
async function clearTraces(): Promise<void> {
  try {
    await ElMessageBox.confirm(t('workspace.log.clearConfirm'), t('workspace.log.clear'), {
      type: 'warning',
      appendTo: document.body
    })
  } catch {
    return
  }
  try {
    await api.sessionLog.clearTraces(props.sessionId)
    changePage(1)
    ElMessage.success(t('workspace.log.cleared'))
  } catch (cause) {
    ElMessage.error(ipcErrorMessage(cause))
  }
}
</script>

<template>
  <div class="log-list">
    <div class="trace-toolbar">
      <span class="trace-total">{{ t('workspace.log.traceTotal', [total]) }}</span>
      <el-button size="small" :disabled="total === 0" @click="clearTraces">
        {{ t('workspace.log.clear') }}
      </el-button>
    </div>

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
          <span class="log-step">#{{ row.item.step }}</span>
          <span class="log-model">{{ row.item.modelId }}</span>
          <span class="log-status" :class="row.item.status">
            {{ traceStatusText(row.item.status) }}
          </span>
          <span class="log-meta">{{ row.item.durationMs }}ms</span>
          <span class="log-meta">{{ row.item.inputTokens }}/{{ row.item.outputTokens }}</span>
          <span class="log-meta">{{ t('workspace.log.tools') }} {{ row.item.toolCalls.length }}</span>
          <span class="log-meta">{{ formatClock(row.item.addTime) }}</span>
        </div>
        <div class="log-text" :class="row.preview.kind">
          <span v-if="previewTag(row.preview.kind)" class="log-tag">
            {{ previewTag(row.preview.kind) }}
          </span>
          <span class="log-text-body">{{ row.preview.text }}</span>
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

    <TraceDetailDialog v-model="detailVisible" :trace="detail" />
  </div>
</template>

<style scoped lang="scss">
@use './logList.scss';

/* 顶部工具条：总条数 + 清空操作 */
.trace-toolbar {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 12px 2px;
}

.trace-total {
  font-size: var(--desk-font-xs);
  color: var(--desk-text-tertiary);
}

/* 步骤号：略强于普通元信息 */
.log-step {
  font-weight: 600;
  color: var(--desk-text-secondary);
}

/* 模型 ID：超长省略，避免挤压同行其它字段 */
.log-model {
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 状态标签：默认成功色，失败 / 中止单独着色 */
.log-status {
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--desk-selected-bg);
}

.log-status.error {
  color: var(--el-color-danger);
}

.log-status.aborted {
  color: var(--el-color-warning);
}
</style>
