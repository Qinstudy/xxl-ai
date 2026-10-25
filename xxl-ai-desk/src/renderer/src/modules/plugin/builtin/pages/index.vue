<script setup lang="ts">
/*
 * 内置工具页：按分类展示内置工具能力；文件操作固定启用，浏览器支持开关启用。
 * @author xuxueli 2026-10-25
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useSettingsStore } from '@renderer/stores/settings'
import { ipcErrorMessage } from '@renderer/utils/ipcError'
import {
  BUILTIN_TOOL_CATALOG,
  BUILTIN_TOOL_CATEGORIES,
  type BuiltinToolCategoryKey
} from '../types'
import { t } from '@renderer/i18n'
import type { ChatMode } from '@shared/ipc'

const settings = useSettingsStore()

/* 分类 + 其下工具（顺序取自共享目录） */
const groups = computed(() =>
  BUILTIN_TOOL_CATEGORIES.map((category) => ({
    ...category,
    tools: BUILTIN_TOOL_CATALOG.filter((tool) => tool.category === category.key)
  }))
)

/* 表格容器宽度：用于按 40% / 20% / 12% / 16% / 12% 换算像素列宽 */
const tableHost = ref<HTMLElement | null>(null)
const tableWidth = ref(0)
let resizeObserver: ResizeObserver | null = null

/* 监听表格容器宽度变化，保证各列比例稳定且不出现横向滚动 */
onMounted(() => {
  if (!tableHost.value) {
    return
  }
  resizeObserver = new ResizeObserver((entries) => {
    tableWidth.value = entries[0]?.contentRect.width ?? 0
  })
  resizeObserver.observe(tableHost.value)
})

/* 卸载时释放监听 */
onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
})

/* 列宽（首帧未知宽度时交由表格自适应，测量后按比例接管） */
const columnWidths = computed(() => {
  const total = tableWidth.value
  if (!total) {
    return {
      tool: undefined,
      identifier: undefined,
      permission: undefined,
      modes: undefined,
      status: undefined
    }
  }
  return {
    tool: Math.round(total * 0.4),
    identifier: Math.round(total * 0.2),
    permission: Math.round(total * 0.12),
    modes: Math.round(total * 0.16),
    status: Math.round(total * 0.12)
  }
})

/* 浏览器类开关状态（文件操作类固定启用） */
const browserEnabled = computed(() => settings.settings.builtinToolBrowserEnabled)

/* 分类当前是否启用 */
function isEnabled(key: BuiltinToolCategoryKey): boolean {
  return key === 'browser' ? browserEnabled.value : true
}

/* 切换浏览器类工具（保存设置后由主进程清理运行时缓存，下一轮对话生效） */
async function onToggle(key: BuiltinToolCategoryKey, value: boolean): Promise<void> {
  if (key !== 'browser') {
    return
  }
  try {
    await settings.saveSettings({ builtinToolBrowserEnabled: value })
    ElMessage.success(
      value ? t('plugin.builtinTool.enabledOn') : t('plugin.builtinTool.enabledOff')
    )
  } catch (error) {
    ElMessage.error(ipcErrorMessage(error))
  }
}

/* 适用模式文案 */
function modeLabel(modes: ChatMode[]): string {
  return modes.length > 1 ? t('plugin.builtinTool.modeAll') : t('plugin.builtinTool.modeBuild')
}
</script>

<template>
  <div class="builtin-tool-center">
    <div class="center-header">
      <div class="page-title">{{ t('plugin.builtinTool.title') }}</div>
      <div class="page-tip">{{ t('plugin.builtinTool.intro') }}</div>
    </div>

    <div ref="tableHost" class="center-body">
      <section v-for="group in groups" :key="group.key" class="tool-group">
        <div class="group-head">
          <div class="group-title-wrap">
            <span class="group-title">{{ t(group.titleKey) }}</span>
            <el-tag v-if="!group.switchable" size="small" type="info">
              {{ t('plugin.builtinTool.fixedEnabled') }}
            </el-tag>
          </div>
          <el-switch
            :model-value="isEnabled(group.key)"
            :disabled="!group.switchable"
            @update:model-value="(value: boolean) => onToggle(group.key, value)"
          />
        </div>
        <div class="group-desc">{{ t(group.descriptionKey) }}</div>

        <el-table :data="group.tools" style="width: 100%" row-key="name">
          <el-table-column :label="t('plugin.builtinTool.tool')" :width="columnWidths.tool">
            <template #default="{ row }">
              <div class="tool-name">{{ t(row.titleKey) }}</div>
              <div class="tool-desc">{{ t(row.descriptionKey) }}</div>
            </template>
          </el-table-column>
          <el-table-column :label="t('plugin.builtinTool.identifier')" :width="columnWidths.identifier">
            <template #default="{ row }">
              <span class="tool-id">{{ row.name }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('plugin.builtinTool.permission')" :width="columnWidths.permission">
            <template #default="{ row }">
              <el-tag size="small" :type="row.readOnly ? 'info' : 'warning'">
                {{ row.readOnly ? t('plugin.builtinTool.readOnly') : t('plugin.builtinTool.writable') }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('plugin.builtinTool.modes')" :width="columnWidths.modes">
            <template #default="{ row }">{{ modeLabel(row.modes) }}</template>
          </el-table-column>
          <el-table-column :label="t('common.status')" :width="columnWidths.status">
            <template #default>
              <el-tag v-if="isEnabled(group.key)" size="small" type="success">
                {{ t('common.enabled') }}
              </el-tag>
              <el-tag v-else size="small" type="info">{{ t('common.disabled') }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </div>
  </div>
</template>

<style scoped lang="scss">
.builtin-tool-center {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
  background: var(--desk-bg);
}

.center-header {
  flex-shrink: 0;
  padding: 18px 28px 16px;
  border-bottom: 1px solid var(--desk-border);
}

.page-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--desk-text);
}

.page-tip {
  margin-top: 6px;
  font-size: 12px;
  color: var(--desk-text-tertiary);
}

.center-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 16px 28px 32px;
  font-size: 14px;
  --el-font-size-base: 14px;
}

.tool-group + .tool-group {
  margin-top: 28px;
}

.group-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.group-title-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
}

.group-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--desk-text);
}

.group-desc {
  margin: 4px 0 12px;
  font-size: 12px;
  color: var(--desk-text-tertiary);
}

.tool-name {
  color: var(--desk-text);
}

.tool-desc {
  margin-top: 2px;
  font-size: 12px;
  color: var(--desk-text-tertiary);
  line-height: 1.5;
}

.tool-id {
  font-family: var(--el-font-family-mono, monospace);
  font-size: 12px;
  color: var(--desk-text-secondary);
}
</style>
