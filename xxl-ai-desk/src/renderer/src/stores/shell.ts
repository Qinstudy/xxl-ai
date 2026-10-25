/* 应用外壳状态：左侧栏折叠与宽度（会话内工具/终端状态见 workspace store） */

import { defineStore } from 'pinia'
import { ref } from 'vue'
import { createPersistedWidth } from '@renderer/utils/persistedWidth'

export const useShellStore = defineStore('shell', () => {
  /* 左侧子菜单栏宽度：三板块共用（拖拽调整、持久化、双击复位） */
  const panel = createPersistedWidth('desk-panel-width', { default: 240, min: 180, max: 520 })

  /* 左侧栏是否折叠（折叠后仅保留图标栏，内容区铺满） */
  const sidebarCollapsed = ref(false)

  /* 切换侧栏折叠状态 */
  function toggleSidebar(): void {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  /* 展开侧栏（图标栏点击入口时还原完整子菜单栏） */
  function expandSidebar(): void {
    sidebarCollapsed.value = false
  }

  return {
    sidebarCollapsed,
    panelWidth: panel.width,
    setPanelWidth: panel.setWidth,
    resetPanelWidth: panel.resetWidth,
    toggleSidebar,
    expandSidebar
  }
})
