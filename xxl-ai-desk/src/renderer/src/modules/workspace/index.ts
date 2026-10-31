/*
 * workspace 模块对外公共 API：供对话页装配工具面板；终端走独立轻量入口 terminal.ts。
 * @author xuxueli 2026-10-25
 */

export { default as WorkspaceToolPanel } from './components/WorkspaceToolPanel.vue'
export { default as WorkspaceLayout } from './components/WorkspaceLayout.vue'
export { default as BrowserHost } from './components/hosts/BrowserHost.vue'
export { default as WorkspacePanelToggles } from './components/WorkspacePanelToggles.vue'
export { default as TerminalHost } from './components/hosts/TerminalHost.vue'
export { useTerminalTarget } from './composables/useTerminalTarget'
export { cleanupBrowserSessions } from './composables/browserRuntime'
