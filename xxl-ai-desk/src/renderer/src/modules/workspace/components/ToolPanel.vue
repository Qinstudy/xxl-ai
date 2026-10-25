<script setup lang="ts">
/*
 * 会话工作区右侧「工具」面板：顶部横向 Tab（文件 / 浏览器）切换工具，
 * 选定后承载对应面板；显隐、宽度、当前工具与放大态均按会话隔离。
 */
import { computed, ref, watch } from 'vue'
import { useWorkspaceStore, type WorkspaceTool } from '@renderer/stores/workspace'
import { t } from '@renderer/i18n'
import FilePanel from './file/FilePanel.vue'
import BrowserPanel from './browser/BrowserPanel.vue'

const props = defineProps<{ sessionId: string; root?: string; projectName?: string }>()

/* 会话工作区 store：提供本会话的面板显隐/宽度/当前工具/放大态 */
const workspace = useWorkspaceStore()

/* 当前会话的工具面板状态 */
const toolState = computed(() => workspace.sliceOf(props.sessionId).tool)
/* 当前工具类型（模板与监听统一取值） */
const activeTool = computed<WorkspaceTool>(() => toolState.value.tool)

/* 确保会话工作区已创建 */
watch(() => props.sessionId, (sessionId) => workspace.ensure(sessionId), { immediate: true })

/* 面板首次打开后保持挂载（切换工具用 v-show），保留文件树/浏览器页面状态 */
const filesOpened = ref(false)
const browserOpened = ref(false)

/* 标记已打开过的工具，使其面板首次打开后保持挂载（切换用 v-show） */
watch(
  activeTool,
  (tool) => {
    if (tool === 'files') {
      filesOpened.value = true
    } else if (tool === 'browser') {
      browserOpened.value = true
    }
  },
  { immediate: true }
)

/* 打开指定工具（顶部 Tab 点击） */
function openTool(tool: WorkspaceTool): void {
  workspace.openTool(props.sessionId, tool)
}

/* 关闭工具面板（保留放大态，重新打开时维持） */
function closePanel(): void {
  workspace.setToolVisible(props.sessionId, false)
}

/* 切换放大态（放大后占满正文区） */
function toggleMaximized(): void {
  workspace.toggleToolMaximized(props.sessionId)
}
</script>

<template>
  <div class="tool-panel">
    <div class="tool-panel-header">
      <!-- 工具 Tab：横向摆放，点击直接切换到对应工具内容 -->
      <div class="tool-tabs">
        <button
          class="tool-tab"
          :class="{ active: activeTool === 'files' }"
          @click="openTool('files')"
        >
          <el-icon><Folder /></el-icon>
          <span>{{ t('panel.files') }}</span>
        </button>
        <button
          class="tool-tab"
          :class="{ active: activeTool === 'browser' }"
          @click="openTool('browser')"
        >
          <svg class="tool-tab-icon globe" viewBox="0 0 24 24" aria-hidden="true">
            <circle cx="12" cy="12" r="9" />
            <path d="M3 12h18" />
            <path d="M12 3c2.5 2.6 4 5.6 4 9s-1.5 6.4-4 9c-2.5-2.6-4-5.6-4-9s1.5-6.4 4-9z" />
          </svg>
          <span>{{ t('panel.browser') }}</span>
        </button>
      </div>
      <div class="tool-actions">
        <button
          class="sidebar-toggle"
          :title="toolState.maximized ? t('panel.restore') : t('panel.maximize')"
          @click="toggleMaximized"
        >
          <el-icon v-if="toolState.maximized"><ScaleToOriginal /></el-icon>
          <el-icon v-else><FullScreen /></el-icon>
        </button>
        <button class="sidebar-toggle" :title="t('panel.close')" @click="closePanel">
          <el-icon><Close /></el-icon>
        </button>
      </div>
    </div>

    <div class="tool-panel-body">
      <FilePanel
        v-if="filesOpened"
        v-show="activeTool === 'files'"
        :session-id="props.sessionId"
        :root="props.root"
        :project-name="props.projectName"
      />
      <BrowserPanel v-if="browserOpened" v-show="activeTool === 'browser'" :session-id="props.sessionId" />
    </div>
  </div>
</template>

<style scoped lang="scss">
.tool-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.tool-panel-header {
  height: 40px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 0 8px 0 16px;
  border-bottom: 1px solid var(--desk-border);
}

/* 工具 Tab：横向并排，选中项浅底高亮 */
.tool-tabs {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  -webkit-app-region: no-drag;
}

.tool-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 26px;
  padding: 0 10px;
  border: none;
  border-radius: 7px;
  background: transparent;
  color: var(--desk-text-secondary);
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
  transition:
    background 0.15s ease,
    color 0.15s ease;
}

.tool-tab:hover {
  background: var(--desk-primary-soft);
  color: var(--desk-text);
}

.tool-tab.active {
  background: var(--desk-primary-soft);
  color: var(--desk-text);
  font-weight: 600;
}

.tool-tab-icon.globe {
  width: 15px;
  height: 15px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.tool-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
}

.tool-panel-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.tool-panel-body > * {
  flex: 1;
  min-height: 0;
}
</style>
