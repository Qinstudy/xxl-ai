/* 布局状态：左侧会话栏折叠/展开 + 宽度拖拽 */

import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useLayoutStore = defineStore('layout', () => {
  /* 子菜单栏宽度：三个板块共用同一宽度（图标栏宽度另由 --desk-rail-width 计） */
  const PANEL_DEFAULT_WIDTH = 240
  const PANEL_MIN_WIDTH = 180
  const PANEL_MAX_WIDTH = 520
  const PANEL_STORAGE_KEY = 'desk-panel-width'

  /* 侧栏是否折叠（折叠后仅保留左侧图标栏，内容区铺满） */
  const sidebarCollapsed = ref(false)

  /* 系统设置子菜单当前选中项（独立于首页/插件，供侧栏与设置页共享） */
  const settingsTab = ref('general')

  /* 底部终端面板是否展示（默认隐藏） */
  const terminalVisible = ref(false)

  /* 右侧侧栏（侧边任务）是否展示（默认隐藏） */
  const rightPanelVisible = ref(false)

  /* 右侧侧栏当前工具：'' 表示未选择（展示工具菜单），files/browser 为具体面板 */
  const rightPanelTool = ref<'' | 'files' | 'browser'>('')

  /* 右侧侧栏是否放大占满正文区 */
  const rightPanelMaximized = ref(false)

  /* 各工具的默认面板宽度（首次打开时套用，之后沿用用户拖拽宽度） */
  const RIGHT_PANEL_TOOL_WIDTH: Record<'files' | 'browser', number> = {
    files: 560,
    browser: 480
  }

  /* 子菜单栏宽度（三板块共用；持久化到 localStorage，越界回落默认值） */
  const storedPanel = Number(localStorage.getItem(PANEL_STORAGE_KEY))
  const panelWidth = ref(
    Number.isFinite(storedPanel) && storedPanel >= PANEL_MIN_WIDTH && storedPanel <= PANEL_MAX_WIDTH
      ? Math.round(storedPanel)
      : PANEL_DEFAULT_WIDTH
  )

  /* 右侧侧栏（侧边任务）宽度约束与默认值 */
  const RIGHT_PANEL_DEFAULT_WIDTH = 320
  const RIGHT_PANEL_MIN_WIDTH = 240
  const RIGHT_PANEL_MAX_WIDTH = 720
  const RIGHT_PANEL_STORAGE_KEY = 'desk-right-panel-width'

  /* 右侧侧栏宽度（持久化到 localStorage，下次启动沿用） */
  const storedRight = Number(localStorage.getItem(RIGHT_PANEL_STORAGE_KEY))
  const rightPanelWidth = ref(
    Number.isFinite(storedRight) &&
      storedRight >= RIGHT_PANEL_MIN_WIDTH &&
      storedRight <= RIGHT_PANEL_MAX_WIDTH
      ? storedRight
      : RIGHT_PANEL_DEFAULT_WIDTH
  )

  /* 切换侧栏折叠状态 */
  function toggleSidebar(): void {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  /* 展开侧栏（图标栏点击入口时还原完整子菜单栏） */
  function expandSidebar(): void {
    sidebarCollapsed.value = false
  }

  /* 设置系统设置子菜单选中项 */
  function setSettingsTab(tab: string): void {
    settingsTab.value = tab
  }

  /* 切换底部终端面板显隐 */
  function toggleTerminal(): void {
    terminalVisible.value = !terminalVisible.value
  }

  /* 打开底部终端面板（已打开则保持） */
  function openTerminal(): void {
    terminalVisible.value = true
  }

  /* 切换右侧侧栏显隐（保留放大态，重新打开时维持全屏） */
  function toggleRightPanel(): void {
    rightPanelVisible.value = !rightPanelVisible.value
  }

  /* 打开右侧侧栏并定位到指定工具（宽度不足时套用该工具默认宽度） */
  function openRightPanelTool(tool: 'files' | 'browser'): void {
    const defaultWidth = RIGHT_PANEL_TOOL_WIDTH[tool]
    if (!rightPanelVisible.value || rightPanelWidth.value < defaultWidth) {
      setRightPanelWidth(defaultWidth)
    }
    rightPanelTool.value = tool
    rightPanelVisible.value = true
  }

  /* 切换右侧侧栏放大态（放大后占满正文区） */
  function toggleRightPanelMaximized(): void {
    rightPanelMaximized.value = !rightPanelMaximized.value
  }

  /* 设置子菜单栏宽度（夹取到合理区间并持久化） */
  function setPanelWidth(width: number): void {
    const next = Math.min(PANEL_MAX_WIDTH, Math.max(PANEL_MIN_WIDTH, Math.round(width)))
    panelWidth.value = next
    localStorage.setItem(PANEL_STORAGE_KEY, String(next))
  }

  /* 恢复子菜单栏默认宽度（双击拖拽手柄触发） */
  function resetPanelWidth(): void {
    panelWidth.value = PANEL_DEFAULT_WIDTH
    localStorage.setItem(PANEL_STORAGE_KEY, String(PANEL_DEFAULT_WIDTH))
  }

  /* 设置右侧侧栏宽度（夹取到合理区间并持久化） */
  function setRightPanelWidth(width: number): void {
    const next = Math.min(RIGHT_PANEL_MAX_WIDTH, Math.max(RIGHT_PANEL_MIN_WIDTH, Math.round(width)))
    rightPanelWidth.value = next
    localStorage.setItem(RIGHT_PANEL_STORAGE_KEY, String(next))
  }

  return {
    sidebarCollapsed,
    panelWidth,
    settingsTab,
    terminalVisible,
    rightPanelVisible,
    rightPanelTool,
    rightPanelMaximized,
    rightPanelWidth,
    toggleSidebar,
    expandSidebar,
    setSettingsTab,
    setPanelWidth,
    resetPanelWidth,
    toggleTerminal,
    openTerminal,
    toggleRightPanel,
    openRightPanelTool,
    toggleRightPanelMaximized,
    setRightPanelWidth
  }
})
