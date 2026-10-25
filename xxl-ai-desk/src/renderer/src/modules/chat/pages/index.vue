<script setup lang="ts">
/* 对话页：组合会话工作区（对话区 + 右侧工具面板），各区域状态按会话隔离 */
import { computed, watch } from 'vue'
import ChatRegion from '../components/ChatRegion.vue'
import { WorkspaceToolPanel } from '@renderer/modules/workspace'
import { useChatStore } from '@renderer/stores/chat'
import { DRAFT_SESSION_KEY, useWorkspaceStore } from '@renderer/stores/workspace'
import { usePanelResize } from '@renderer/composables/usePanelResize'
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

/* 拖拽右侧栏左边缘调整宽度（统一复用 usePanelResize） */
const { startResize: startPanelResize } = usePanelResize({
  getSize: () => workspaceState.value.tool.width,
  setSize: (width) => workspace.setToolWidth(workspaceKey.value, width),
  edge: 'right'
})

/* 双击拖拽手柄：恢复当前会话工具面板默认宽度 */
function resetPanelWidth(): void {
  workspace.resetToolWidth(workspaceKey.value)
}
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
        <div
          v-if="!workspaceState.tool.maximized"
          class="side-panel-resizer"
          :title="t('layout.resetPanelWidth')"
          @mousedown="startPanelResize"
          @dblclick="resetPanelWidth"
        ></div>
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

/* 左边缘拖拽手柄：与左侧会话栏保持同宽（5px），视觉条细；下方 ::after 提供更宽命中区 */
.side-panel-resizer {
  position: absolute;
  top: 0;
  left: 0;
  z-index: 10;
  width: 5px;
  height: 100%;
  cursor: col-resize;
  -webkit-app-region: no-drag;
}

/* 透明命中区：视觉条保持 5px，实际可抓取范围更宽（近分割线即可拖动） */
.side-panel-resizer::after {
  content: '';
  position: absolute;
  top: 0;
  left: -2px;
  width: 16px;
  height: 100%;
}

.side-panel-resizer:hover,
body.resizing .side-panel-resizer {
  background: var(--desk-primary-soft);
}
</style>
