<script setup lang="ts">
/*
 * 左侧栏：常驻图标栏 + 内容区（首页 / 插件 / 系统设置 按板块切换）
 * @author xuxueli 2026-10-18
 */
import { computed } from 'vue'
import SidebarRail from './SidebarRail.vue'
import SidebarContent from './SidebarContent.vue'
import PanelResizer from '../PanelResizer/index.vue'
import { useLayoutStore } from '@renderer/stores/layout'
import { t } from '@renderer/i18n'

const layout = useLayoutStore()

/* 子菜单栏宽度双向绑定：拖拽写入 store（含夹取与持久化） */
const sidebarWidth = computed({
  get: () => layout.sidebarWidth,
  set: (value: number) => layout.setSidebarWidth(value)
})
</script>

<template>
  <aside
    class="sidebar-container"
    :class="{ collapsed: layout.sidebarCollapsed }"
    :style="{
      width: layout.sidebarCollapsed
        ? 'var(--desk-rail-width)'
        : `calc(var(--desk-rail-width) + ${layout.sidebarWidth}px)`
    }"
  >
    <SidebarRail />

    <!-- 子菜单内容区：折叠时隐藏，仅保留左侧图标栏 -->
    <div v-show="!layout.sidebarCollapsed" class="sidebar-content">
      <!-- 右边缘拖拽手柄：拖拽调整面板宽度，双击恢复默认宽度 -->
      <PanelResizer
        v-model:size="sidebarWidth"
        side="left"
        :title="t('layout.resetPanelWidth')"
        @reset="layout.resetSidebarWidth"
      />

      <SidebarContent />
    </div>
  </aside>
</template>

<style scoped lang="scss">
/* 左侧栏：图标栏 + 可折叠内容区 */
.sidebar-container {
  width: calc(var(--desk-rail-width) + var(--desk-sidebar-width));
  flex-shrink: 0;
  position: relative;
  display: flex;
  flex-direction: row;
  background: var(--desk-panel-bg);
  overflow: hidden;
  transition: width 0.2s ease;
}

/* 拖拽调整宽度期间禁用过渡 */
:global(body.resizing) .sidebar-container {
  transition: none;
}

/* 内容区：图标栏右侧，折叠时隐藏；与右侧正文之间保留间隔线 */
.sidebar-content {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-right: 1px solid var(--desk-border);
}
</style>
