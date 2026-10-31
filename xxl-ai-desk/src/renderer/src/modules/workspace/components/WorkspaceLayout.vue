<script setup lang="ts">
/*
 * 会话工作区布局：左侧默认 slot（对话区） + 右侧工具面板，工具面板可拖拽宽度、可 focus 占满正文
 * @author xuxueli 2026-10-31
 */
import { computed, watch } from 'vue'
import { useWorkspaceStore } from '@renderer/stores/workspace'
import PanelResizer from '@renderer/layout/components/PanelResizer/index.vue'
import WorkspaceToolPanel from './WorkspaceToolPanel.vue'
import { t } from '@renderer/i18n'

const props = defineProps<{
  /* 会话工作区 key（真实会话 id 或草稿 key） */
  sessionId: string
  /* 当前项目根目录 */
  root?: string
  /* 当前项目名称 */
  projectName?: string
}>()

const workspace = useWorkspaceStore()
const toolPanel = computed(() => workspace.sliceOf(props.sessionId).toolPanel)

/* 确保当前会话的工作区状态已创建（含本地持久化恢复） */
watch(() => props.sessionId, (key) => workspace.ensure(key), { immediate: true })

/* 工具面板宽度双向绑定：拖拽写入当前会话切片（含夹取） */
const toolWidth = computed({
  get: () => toolPanel.value.width,
  set: (width: number) => workspace.setToolWidth(props.sessionId, width)
})

/* 工具面板是否占满正文 */
const focused = computed(() => toolPanel.value.mode === 'focus')
</script>

<template>
  <div class="workspace-layout">
    <div class="workspace-stage">
      <!-- 工具面板隐藏或 focus 时，正文不展示；其余情况正文自适应 -->
      <div v-show="!toolPanel.visible || !focused" class="workspace-main">
        <slot />
      </div>

      <!-- 右侧工具面板：文件/浏览器，可拖拽宽度、可 focus 占满正文区 -->
      <aside
        v-show="toolPanel.visible"
        class="workspace-tool-panel"
        :class="{ focused }"
        :style="focused ? undefined : { width: `${toolPanel.width}px` }"
      >
        <!-- 左边缘拖拽手柄：拖拽调整侧栏宽度，双击恢复默认宽度（focus 态下不可拖拽） -->
        <PanelResizer
          v-if="!focused"
          v-model:size="toolWidth"
          side="right"
          :title="t('layout.resetPanelWidth')"
          @reset="workspace.resetToolWidth(props.sessionId)"
        />
        <WorkspaceToolPanel
          :key="props.sessionId"
          :session-id="props.sessionId"
          :root="props.root"
          :project-name="props.projectName"
        />
      </aside>
    </div>
  </div>
</template>

<style scoped lang="scss">
.workspace-layout {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-width: 0;
}

/* 正文：左侧内容（自适应）+ 右侧工具面板 */
.workspace-stage {
  flex: 1;
  min-height: 0;
  display: flex;
}

/* 正文内容容器：由默认 slot 撑满 */
.workspace-main {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
}

/* 右侧工具面板：可拖拽宽度；focus 态占满正文区 */
.workspace-tool-panel {
  position: relative;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  border-left: 1px solid var(--desk-border);
  background: var(--desk-sidebar);
}

.workspace-tool-panel.focused {
  flex: 1;
  min-width: 0;
}
</style>
