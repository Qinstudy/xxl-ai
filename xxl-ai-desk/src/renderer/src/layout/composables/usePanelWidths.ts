/*
 * 左右面板宽度约束：统一侧栏与工具面板的最大宽度、夹取与拖拽写入
 * @author xuxueli 2026-10-31
 */
import { computed } from 'vue'
import { useLayoutStore } from '@renderer/stores/layout'
import { useWorkspaceStore } from '@renderer/stores/workspace'
import { LAYOUT, sidebarWidthMax, toolPanelWidthMax } from '@renderer/config/layout'
import { useViewportWidth } from './useViewportWidth'

/* 按会话返回面板状态与宽度约束 */
export function usePanelWidths(sessionId: () => string) {
  const layout = useLayoutStore()
  const workspace = useWorkspaceStore()
  const viewportWidth = useViewportWidth()

  /* 当前会话工具面板状态 */
  const toolPanel = computed(() => workspace.sliceOf(sessionId()).toolPanel)
  const focused = computed(() => toolPanel.value.mode === 'focus')
  /* 工具面板是否 dock：决定侧栏是否为它会话区最小宽度预留空间 */
  const toolDocked = computed(() => toolPanel.value.visible && !focused.value)

  /* 左侧栏最大宽度与实际宽度 */
  const maxSidebarWidth = computed(() => sidebarWidthMax(viewportWidth.value, toolDocked.value))
  const sidebarWidth = computed({
    get: () =>
      Math.min(maxSidebarWidth.value, Math.max(LAYOUT.sidebar.min, layout.sidebarWidth)),
    set: (value: number) =>
      layout.setSidebarWidth(
        Math.min(maxSidebarWidth.value, Math.max(LAYOUT.sidebar.min, value))
      )
  })

  /* 左侧栏实际占用：折叠时为 0 */
  const effectiveSidebarWidth = computed(() =>
    layout.sidebarCollapsed ? 0 : Math.min(layout.sidebarWidth, maxSidebarWidth.value)
  )

  /* 工具面板最大宽度与实际宽度 */
  const maxToolWidth = computed(() =>
    toolPanelWidthMax(viewportWidth.value, effectiveSidebarWidth.value)
  )
  const toolWidth = computed({
    get: () =>
      Math.min(maxToolWidth.value, Math.max(LAYOUT.toolPanel.minWidth, toolPanel.value.width)),
    set: (value: number) =>
      workspace.setToolWidth(
        sessionId(),
        Math.min(maxToolWidth.value, Math.max(LAYOUT.toolPanel.minWidth, value))
      )
  })

  /* 双击复位 */
  function resetSidebarWidth(): void {
    sidebarWidth.value = LAYOUT.sidebar.default
  }
  function resetToolWidth(): void {
    workspace.resetToolWidth(sessionId())
  }

  return {
    toolPanel,
    focused,
    sidebarWidth,
    toolWidth,
    resetSidebarWidth,
    resetToolWidth
  }
}
