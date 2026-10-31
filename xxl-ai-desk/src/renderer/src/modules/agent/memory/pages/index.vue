<script setup lang="ts">
/*
 * Agent 记忆：开关启用，开启后按项目展示记忆（内容摘要 / 更新时间 / 查看编辑 / 删除）。
 * @author xuxueli 2026-10-31
 */
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useSettingsStore } from '@renderer/stores/settings'
import { useAgentStore } from '@renderer/stores/agent'
import { t } from '@renderer/i18n'
import { AGENT_MEMORY_MAX_CHARS, AGENT_MEMORY_ROUND_LIMIT, type AgentMemoryDTO } from '@shared/ipc'
import AgentPageShell from '../../components/AgentPageShell.vue'

const settings = useSettingsStore()
const agent = useAgentStore()

/* 记忆开关（全局设置） */
const memoryEnabled = computed(() => settings.settings.agentMemoryEnabled)

/* 查看 / 编辑弹窗 */
const dialogVisible = ref(false)
const activeMemory = ref<AgentMemoryDTO | null>(null)
const content = ref('')
const saving = ref(false)

/* 记忆摘要：去空白后截前 80 字，保留足够辨识度，完整内容在弹窗查看 */
function summary(text: string): string {
  if (!text) {
    return t('agent.memoryContentEmpty')
  }
  const plain = text.replace(/[#*`>_-]/g, ' ').replace(/\s+/g, ' ').trim()
  return plain.length > 80 ? `${plain.slice(0, 80)}...` : plain
}

/* 更新时间展示 */
function formatTime(value: string): string {
  return value ? new Date(value).toLocaleString() : '-'
}

/* 加载项目记忆 */
async function loadMemories(): Promise<void> {
  if (!memoryEnabled.value) {
    return
  }
  try {
    await agent.loadMemories()
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

/* 开关变化：开启后加载列表 */
async function onToggle(value: boolean): Promise<void> {
  try {
    await settings.saveSetting('agentMemoryEnabled', value)
    if (value) {
      await loadMemories()
    }
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

/* 打开查看弹窗（可编辑） */
function openMemory(row: AgentMemoryDTO): void {
  activeMemory.value = row
  content.value = row.content
  dialogVisible.value = true
}

/* 保存记忆内容 */
async function saveMemory(): Promise<void> {
  if (!activeMemory.value) {
    return
  }
  saving.value = true
  try {
    await agent.saveMemory(activeMemory.value.projectId, content.value)
    ElMessage.success(t('common.saved'))
    dialogVisible.value = false
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    saving.value = false
  }
}

/* 删除项目记忆 */
async function removeMemory(row: AgentMemoryDTO): Promise<void> {
  try {
    await ElMessageBox.confirm(
      t('agent.memoryDeleteConfirm', [row.projectName]),
      t('common.confirm'),
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await agent.removeMemory(row.projectId)
    ElMessage.success(t('common.deleted'))
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

watch(() => settings.loaded, loadMemories)
onMounted(loadMemories)
</script>

<template>
  <AgentPageShell title-key="agent.memoryTitle" tip-key="agent.memoryTip" wide>
    <div class="memory-switch">
      <span class="memory-switch-label">{{ t('agent.memoryEnabled') }}</span>
      <el-switch :model-value="memoryEnabled" @update:model-value="onToggle" />
      <span class="memory-switch-tip">
        {{ t('agent.memoryRule', [AGENT_MEMORY_ROUND_LIMIT]) }}
      </span>
    </div>

    <el-table
      v-if="memoryEnabled"
      :data="agent.memories"
      class="memory-table"
      row-key="projectId"
      style="width: 100%"
    >
      <el-table-column
        prop="projectName"
        :label="t('agent.memoryProject')"
        min-width="180"
        show-overflow-tooltip
      />
      <el-table-column :label="t('agent.memoryContent')" min-width="320" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="memory-summary">{{ summary(row.content) }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('agent.memoryUpdatedAt')" width="180">
        <template #default="{ row }">{{ formatTime(row.updateTime) }}</template>
      </el-table-column>
      <el-table-column :label="t('common.actions')" width="160" align="center">
        <template #default="{ row }">
          <div class="row-actions">
            <el-button text type="primary" @click="openMemory(row)">
              {{ t('agent.memoryView') }}
            </el-button>
            <el-button text type="danger" @click="removeMemory(row)">
              {{ t('common.delete') }}
            </el-button>
          </div>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty :description="t('agent.memoryEmpty')" :image-size="80" />
      </template>
    </el-table>
    <div v-else class="memory-off">{{ t('agent.memoryOff') }}</div>

    <el-dialog
      v-model="dialogVisible"
      :title="t('agent.memoryDialogTitle', [activeMemory?.projectName ?? ''])"
      width="900px"
      append-to-body
      class="agent-memory-dialog"
    >
      <el-input
        v-model="content"
        type="textarea"
        :rows="14"
        :maxlength="AGENT_MEMORY_MAX_CHARS"
        show-word-limit
        :placeholder="t('agent.memoryContentPlaceholder')"
      />
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="saveMemory">
          {{ t('common.save') }}
        </el-button>
      </template>
    </el-dialog>
  </AgentPageShell>
</template>

<style scoped lang="scss">
.memory-switch {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.memory-switch-label {
  font-size: var(--desk-font-base);
  color: var(--desk-text);
}

.memory-switch-tip {
  font-size: var(--desk-font-xs);
  color: var(--desk-text-secondary);
}

/* 表格字号与「插件中心 → MCP 管理」列表保持一致 */
.memory-table {
  font-size: var(--desk-font-base);
  --el-font-size-base: 14px;
}

.memory-summary {
  color: var(--desk-text-secondary);
}

.row-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  white-space: nowrap;
}

/* 操作按钮：压缩默认间距与内边距，避免两个按钮拉得很开 */
.row-actions :deep(.el-button) {
  margin-left: 0;
  padding: 4px 6px;
}

.memory-off {
  padding: 24px;
  font-size: var(--desk-font-sm);
  color: var(--desk-text-secondary);
  text-align: center;
}

/* 弹窗字号与左栏一致（teleport 到 body，用 :global 命中） */
:global(.agent-memory-dialog) {
  --el-font-size-base: 14px;
  max-width: calc(100vw - 48px);
}
</style>
