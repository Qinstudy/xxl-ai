<script setup lang="ts">
/*
 * 右侧「工具」面板：顶部横向 Tab（文件 / 浏览器）切换工具，
 * 选定后承载对应面板；支持放大占满正文区与收起。
 */
import { ref, watch } from 'vue'
import { useLayoutStore } from '@renderer/stores/layout'
import { t } from '@renderer/i18n'
import FilePanel from './FilePanel.vue'
import BrowserPanel from './BrowserPanel.vue'

const props = defineProps<{ root?: string; projectName?: string }>()

/* 布局 store：提供面板显隐/放大状态与当前工具 */
const layout = useLayoutStore()

/* 面板首次打开后保持挂载（切换工具用 v-show），保留文件树/浏览器页面状态 */
const filesOpened = ref(false)
const browserOpened = ref(false)

/* 标记已打开过的工具，使其面板首次打开后保持挂载（切换用 v-show） */
watch(
  () => layout.rightPanelTool,
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
function openTool(tool: 'files' | 'browser'): void {
  layout.openRightPanelTool(tool)
}
</script>

<template>
  <div class="right-panel">
    <div class="rp-header">
      <!-- 工具 Tab：横向摆放，点击直接切换到对应工具内容 -->
      <div class="rp-tabs">
        <button
          class="rp-tab"
          :class="{ active: layout.rightPanelTool === 'files' }"
          @click="openTool('files')"
        >
          <el-icon><Folder /></el-icon>
          <span>{{ t('panel.files') }}</span>
        </button>
        <button
          class="rp-tab"
          :class="{ active: layout.rightPanelTool === 'browser' }"
          @click="openTool('browser')"
        >
          <svg class="rp-tab-icon globe" viewBox="0 0 24 24" aria-hidden="true">
            <circle cx="12" cy="12" r="9" />
            <path d="M3 12h18" />
            <path d="M12 3c2.5 2.6 4 5.6 4 9s-1.5 6.4-4 9c-2.5-2.6-4-5.6-4-9s1.5-6.4 4-9z" />
          </svg>
          <span>{{ t('panel.browser') }}</span>
        </button>
      </div>
      <div class="rp-actions">
        <button
          class="sidebar-toggle"
          :title="layout.rightPanelMaximized ? t('panel.restore') : t('panel.maximize')"
          @click="layout.toggleRightPanelMaximized"
        >
          <el-icon v-if="layout.rightPanelMaximized"><ScaleToOriginal /></el-icon>
          <el-icon v-else><FullScreen /></el-icon>
        </button>
        <button class="sidebar-toggle" :title="t('panel.close')" @click="layout.toggleRightPanel">
          <el-icon><Close /></el-icon>
        </button>
      </div>
    </div>

    <div class="rp-body">
      <FilePanel
        v-if="filesOpened"
        v-show="layout.rightPanelTool === 'files'"
        :root="props.root"
        :project-name="props.projectName"
      />
      <BrowserPanel v-if="browserOpened" v-show="layout.rightPanelTool === 'browser'" />
    </div>
  </div>
</template>

<style scoped lang="scss">
.right-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.rp-header {
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
.rp-tabs {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  -webkit-app-region: no-drag;
}

.rp-tab {
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

.rp-tab:hover {
  background: var(--desk-primary-soft);
  color: var(--desk-text);
}

.rp-tab.active {
  background: var(--desk-primary-soft);
  color: var(--desk-text);
  font-weight: 600;
}

.rp-tab-icon.globe {
  width: 15px;
  height: 15px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.rp-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
}

.rp-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.rp-body > * {
  flex: 1;
  min-height: 0;
}
</style>
