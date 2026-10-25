<script setup lang="ts">
/*
* 顶栏工作区操作：底部终端 / 右侧工具面板显隐开关（按当前会话隔离）
* @author xuxueli 2026-10-25
*/
import { computed, watch } from 'vue'
import { useChatStore } from '@renderer/stores/chat'
import { DRAFT_SESSION_KEY, useWorkspaceStore } from '@renderer/stores/workspace'
import { t } from '@renderer/i18n'

const chat = useChatStore()
const workspace = useWorkspaceStore()

/* 当前工作区 key：真实会话 id；新建对话草稿态使用固定 key */
const workspaceKey = computed(() => chat.currentId || DRAFT_SESSION_KEY)
/* 当前会话的工作区切片 */
const workspaceState = computed(() => workspace.sliceOf(workspaceKey.value))

watch(workspaceKey, (key) => workspace.ensure(key), { immediate: true })

/* 当前会话终端/工具面板的显隐状态 */
const terminalVisible = computed(() => workspaceState.value.terminal.visible)
const toolVisible = computed(() => workspaceState.value.tool.visible)

/* 切换当前会话的终端显隐 */
function toggleTerminal(): void {
  workspace.toggleTerminal(workspaceKey.value)
}

/* 切换当前会话的工具面板显隐 */
function toggleTool(): void {
  workspace.toggleTool(workspaceKey.value)
}
</script>

<template>
  <button
    class="sidebar-toggle"
    :class="{ active: terminalVisible }"
    :title="terminalVisible ? t('chat.hideTerminal') : t('chat.showTerminal')"
    @click="toggleTerminal"
  >
    <svg class="desk-svg-icon panel-icon" viewBox="0 0 24 24" aria-hidden="true">
      <rect x="3" y="4.5" width="18" height="15" rx="2.5" />
      <path d="M7.5 10l2.5 2.5-2.5 2.5" />
      <path d="M12.5 15H17" />
    </svg>
  </button>
  <button
    class="sidebar-toggle"
    :class="{ active: toolVisible }"
    :title="toolVisible ? t('chat.hideSidePanel') : t('chat.showSidePanel')"
    @click="toggleTool"
  >
    <svg class="desk-svg-icon panel-icon" viewBox="0 0 24 24" aria-hidden="true">
      <rect x="3" y="4.5" width="18" height="15" rx="2.5" />
      <path d="M15 4.5v15" />
    </svg>
  </button>
</template>

<style scoped lang="scss">
/* 面板开关激活态：高亮当前已展开的面板 */
.sidebar-toggle.active {
  background: var(--desk-primary-soft);
  color: var(--desk-text);
}

/* 面板图标尺寸 */
.panel-icon {
  width: 16px;
  height: 16px;
}
</style>
