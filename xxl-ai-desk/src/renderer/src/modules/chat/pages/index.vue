<script setup lang="ts">
/* 对话页：组合会话工作区（对话区 + 右侧工具面板），各区域状态按会话隔离 */
import { computed, watch } from 'vue'
import ChatRegion from '../components/ChatRegion.vue'
import { WorkspaceToolPanel } from '@renderer/modules/workspace'
import { useChatStore } from '@renderer/stores/chat'
import { DRAFT_SESSION_KEY, useWorkspaceStore } from '@renderer/stores/workspace'
import PanelResizer from '@renderer/components/PanelResizer.vue'
import { useTerminalTarget } from '@renderer/modules/workspace'
import { t } from '@renderer/i18n'

const chat = useChatStore()
const workspace = useWorkspaceStore()
/* 右侧工具面板与终端共用当前项目定位 */
const { terminalProject } = useTerminalTarget()

/* 当前工作区 key：真实会话 id；新建对话草稿态使用固定 key */
const workspaceKey = computed(() => chat.currentId || DRAFT_SESSION_KEY)
/* 当前会话的工作区切片 */
const workspaceState = computed(() => workspace.sliceOf(workspaceKey.value))

/* 确保当前会话的工作区状态已创建（含本地持久化恢复） */
watch(workspaceKey, (key) => workspace.ensure(key), { immediate: true })

/* 工具面板宽度双向绑定：拖拽写入当前会话切片（含夹取） */
const toolWidth = computed({
  get: () => workspaceState.value.tool.width,
  set: (width: number) => workspace.setToolWidth(workspaceKey.value, width)
})
</script>

<template>
  <div class="chat-page">
    <!-- 工作区：左对话内容 + 右侧工具面板 -->
    <div class="chat-workspace">
      <!-- 工具面板隐藏时正文始终显示（放大态一并隐藏）；仅「显示且放大」时让位给工具面板 -->
      <div
        v-show="!workspaceState.tool.visible || !workspaceState.tool.maximized"
        class="chat-region"
      >
        <ChatRegion />
      </div>

      <!-- 右侧工具面板：文件/浏览器，可拖拽宽度、可放大占满正文区 -->
      <aside
        v-show="workspaceState.tool.visible"
        class="chat-side-panel"
        :class="{ maximized: workspaceState.tool.maximized }"
        :style="
          workspaceState.tool.maximized ? undefined : { width: `${workspaceState.tool.width}px` }
        "
      >
        <!-- 左边缘拖拽手柄：拖拽调整侧栏宽度，双击恢复默认宽度（放大态下不可拖拽） -->
        <PanelResizer
          v-if="!workspaceState.tool.maximized"
          v-model:size="toolWidth"
          edge="right"
          :title="t('layout.resetPanelWidth')"
          @reset="workspace.resetToolWidth(workspaceKey)"
        />
        <WorkspaceToolPanel
          :key="workspaceKey"
          :session-id="workspaceKey"
          :root="terminalProject?.path"
          :project-name="terminalProject?.name"
        />
      </aside>
    </div>
  </div>
</template>

<style scoped lang="scss">
.chat-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-width: 0;
}

/* 工作区：左对话内容（自适应）+ 右侧工具面板 */
.chat-workspace {
  flex: 1;
  min-height: 0;
  display: flex;
}

/* 对话区容器：由 ChatRegion 撑满 */
.chat-region {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
}

/* 右侧工具面板：可拖拽宽度；放大态占满正文区 */
.chat-side-panel {
  position: relative;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  border-left: 1px solid var(--desk-border);
  background: var(--desk-sidebar);
}

.chat-side-panel.maximized {
  flex: 1;
  min-width: 0;
}

</style>
