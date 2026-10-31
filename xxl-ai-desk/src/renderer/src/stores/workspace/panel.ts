/* 工具面板布局动作：显隐、尺寸、当前工具与布局模式 */

import { LAYOUT } from '@renderer/config/layout'
import {
  clamp,
  toolWidthMax,
  type ToolPanelMode,
  type WorkspaceState,
  type WorkspaceTool
} from './state'

/* 布局动作依赖的会话状态读取函数 */
type StateOf = (key: string) => WorkspaceState

export function createPanelActions(stateOf: StateOf) {
  /* 设置工具面板显隐 */
  function setToolVisible(key: string, visible: boolean): void {
    stateOf(key).toolPanel.visible = visible
  }

  /* 切换工具面板显隐 */
  function toggleTool(key: string): void {
    const state = stateOf(key)
    state.toolPanel.visible = !state.toolPanel.visible
  }

  /* 打开工具面板并定位到指定工具 */
  function openTool(key: string, tool: WorkspaceTool): void {
    const state = stateOf(key)
    state.toolPanel.activeTool = tool
    state.toolPanel.visible = true
  }

  /* 设置工具面板宽度（夹取到窗口可用范围） */
  function setToolWidth(key: string, width: number): void {
    stateOf(key).toolPanel.width = clamp(width, LAYOUT.toolPanel.minWidth, toolWidthMax())
  }

  /* 恢复工具面板默认宽度 */
  function resetToolWidth(key: string): void {
    stateOf(key).toolPanel.width = clamp(
      LAYOUT.toolPanel.defaultWidth,
      LAYOUT.toolPanel.minWidth,
      toolWidthMax()
    )
  }

  /* 设置工具面板布局模式 */
  function setToolMode(key: string, mode: ToolPanelMode): void {
    stateOf(key).toolPanel.mode = mode
  }

  /* 在 dock / focus 之间切换工具面板布局模式 */
  function toggleToolFocus(key: string): void {
    const state = stateOf(key)
    state.toolPanel.mode = state.toolPanel.mode === 'focus' ? 'dock' : 'focus'
  }

  /* 打开终端（工具面板切到终端 Tab） */
  function openTerminal(key: string): void {
    openTool(key, 'terminal')
  }

  return {
    setToolVisible,
    toggleTool,
    openTool,
    setToolWidth,
    resetToolWidth,
    setToolMode,
    toggleToolFocus,
    openTerminal
  }
}
