<script setup lang="ts">
/*
 * 顶栏工作区操作：右侧工具面板显隐开关（按当前会话隔离）
 * @author xuxueli 2026-10-25
 */
import { computed, watch } from 'vue'
import { useChatStore } from '@renderer/stores/chat'
import { DRAFT_SESSION_KEY, useWorkspaceStore } from '@renderer/stores/workspace'
import { SidePanelIcon } from '@renderer/components/icons'
import { t } from '@renderer/i18n'

const chat = useChatStore()
const workspace = useWorkspaceStore()

/* 当前工作区 key：真实会话 id；新建对话草稿态使用固定 key */
const workspaceKey = computed(() => chat.currentId || DRAFT_SESSION_KEY)
/* 当前会话的工作区切片 */
const workspaceState = computed(() => workspace.sliceOf(workspaceKey.value))

watch(workspaceKey, (key) => workspace.ensure(key), { immediate: true })

/* 当前会话工具面板的显隐状态 */
const toolPanelVisible = computed(() => workspaceState.value.toolPanel.visible)

/* 切换当前会话的工具面板显隐 */
function toggleToolPanel(): void {
  workspace.toggleTool(workspaceKey.value)
}
</script>

<template>
  <button
    class="icon-button"
    :class="{ active: toolPanelVisible }"
    :title="toolPanelVisible ? t('workspace.hideToolPanel') : t('workspace.showToolPanel')"
    @click="toggleToolPanel"
  >
    <SidePanelIcon class="panel-icon" />
  </button>
</template>

<style scoped lang="scss">
/* 面板开关激活态：高亮当前已展开的面板 */
.icon-button.active {
  background: var(--desk-primary-soft);
  color: var(--desk-text);
}

/* 面板图标尺寸 */
.panel-icon {
  width: 16px;
  height: 16px;
}
</style>
