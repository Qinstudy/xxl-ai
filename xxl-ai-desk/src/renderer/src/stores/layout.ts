/* 布局状态：左侧栏折叠/宽度、底部终端、右侧侧栏（侧边任务） */

import { defineStore } from 'pinia'
import { ref } from 'vue'
import { createPersistedWidth } from '@renderer/utils/persistedWidth'

export const useLayoutStore = defineStore('layout', () => {
  /* 左侧子菜单栏宽度：三板块共用（拖拽调整、持久化、双击复位） */
  const panel = createPersistedWidth('desk-panel-width', { default: 240, min: 180, max: 520 })

  /* 底部终端面板高度（拖拽调整、持久化、双击复位） */
  const terminal = createPersistedWidth('desk-terminal-height', {
    default: 240,
    min: 120,
    max: 600
  })

  /* 左侧栏是否折叠（折叠后仅保留图标栏，内容区铺满） */
  const sidebarCollapsed = ref(false)

  /* 底部终端面板是否展示（默认隐藏） */
  const terminalVisible = ref(false)

  /* 右侧侧栏（侧边任务）是否展示（默认隐藏） */
  const rightPanelVisible = ref(false)

  /* 右侧侧栏当前工具：默认文件，顶部 Tab 切换 files / browser */
  const rightPanelTool = ref<'files' | 'browser'>('files')

  /* 右侧侧栏是否放大占满正文区 */
  const rightPanelMaximized = ref(false)

  /* 待浏览器打开的地址请求（Agent 工具 open_browser / 页面 window.open 触发）：id 递增以支持重复打开同一地址 */
  const browserOpenRequest = ref<{ id: number; url: string; newTab: boolean } | null>(null)
  let browserOpenSeq = 0

  /* 右侧侧栏宽度（拖拽调整、持久化）：默认宽度按文件面板实用尺寸设定，避免选中工具时二次变宽 */
  /* 存储键升级 v2：让旧版本遗留的窄默认值（320）失效，首次打开即为合适宽度 */
  /* 最大宽度与会话区联动：会话内容最大 880px（左右内边距 64px），
     窗口多出的空间优先留给右侧；小窗口保底 820px，不设固定封顶 */
  const CHAT_MAX_WIDTH = 944
  const RAIL_WIDTH = 44
  const rightPanel = createPersistedWidth('desk-right-panel-width-v2', {
    default: 520,
    min: 240,
    max: () =>
      Math.max(820, window.innerWidth - RAIL_WIDTH - panel.width.value - CHAT_MAX_WIDTH)
  })

  /* 切换侧栏折叠状态 */
  function toggleSidebar(): void {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  /* 展开侧栏（图标栏点击入口时还原完整子菜单栏） */
  function expandSidebar(): void {
    sidebarCollapsed.value = false
  }

  /* 切换底部终端面板显隐 */
  function toggleTerminal(): void {
    terminalVisible.value = !terminalVisible.value
  }

  /* 打开底部终端面板（已打开则保持） */
  function openTerminal(): void {
    terminalVisible.value = true
  }

  /* 设置底部终端高度：夹取到窗口可用高度内（为正文预留空间）后持久化 */
  function setTerminalHeight(height: number): void {
    const max = Math.max(120, window.innerHeight - 240)
    terminal.setWidth(Math.min(height, max))
  }

  /* 恢复底部终端默认高度并持久化 */
  function resetTerminalHeight(): void {
    terminal.resetWidth()
  }

  /* 切换右侧侧栏显隐（保留放大态，重新打开时维持全屏） */
  function toggleRightPanel(): void {
    rightPanelVisible.value = !rightPanelVisible.value
  }

  /* 打开右侧侧栏并定位到指定工具（宽度保持当前值，不在选中工具时变宽） */
  function openRightPanelTool(tool: 'files' | 'browser'): void {
    rightPanelTool.value = tool
    rightPanelVisible.value = true
  }

  /* 在右侧「浏览器」面板打开地址（自动展开侧栏并切到浏览器工具；newTab 控制是否新建标签页） */
  function openBrowser(url: string, options?: { newTab?: boolean }): void {
    const value = url.trim()
    if (!value) {
      return
    }
    browserOpenRequest.value = {
      id: (browserOpenSeq += 1),
      url: value,
      newTab: options?.newTab === true
    }
    openRightPanelTool('browser')
  }

  /* 切换右侧侧栏放大态（放大后占满正文区） */
  function toggleRightPanelMaximized(): void {
    rightPanelMaximized.value = !rightPanelMaximized.value
  }

  return {
    sidebarCollapsed,
    panelWidth: panel.width,
    setPanelWidth: panel.setWidth,
    resetPanelWidth: panel.resetWidth,
    terminalVisible,
    terminalHeight: terminal.width,
    setTerminalHeight,
    resetTerminalHeight,
    rightPanelVisible,
    rightPanelTool,
    rightPanelMaximized,
    browserOpenRequest,
    rightPanelWidth: rightPanel.width,
    resetRightPanelWidth: rightPanel.resetWidth,
    toggleSidebar,
    expandSidebar,
    toggleTerminal,
    openTerminal,
    toggleRightPanel,
    openRightPanelTool,
    openBrowser,
    toggleRightPanelMaximized,
    setRightPanelWidth: rightPanel.setWidth
  }
})
