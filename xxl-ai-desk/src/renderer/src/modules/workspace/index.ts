/*
 * workspace 模块对外公共 API：供对话页装配工具面板；终端走独立轻量入口 terminal.ts。
 * @author xuxueli 2026-10-25
 */

export { default as WorkspaceToolPanel } from './components/ToolPanel.vue'
export { default as BrowserHost } from './components/browser/BrowserHost.vue'
export { default as WorkspacePanelActions } from './components/WorkspacePanelActions.vue'
export { default as ShellTerminalHost } from './components/ShellTerminalHost.vue'
export { useTerminalTarget } from './composables/useTerminalTarget'
export { cleanupBrowserSessions } from './composables/browserRuntime'
