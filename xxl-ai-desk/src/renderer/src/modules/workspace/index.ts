/*
 * workspace 模块对外公共 API：供对话页装配工具面板与壳级浏览器宿主。
 * @author xuxueli 2026-10-25
 */

export { default as WorkspaceToolPanel } from './components/WorkspaceToolPanel.vue'
export { default as WorkspaceLayout } from './components/WorkspaceLayout.vue'
export { default as BrowserHost } from './components/hosts/BrowserHost.vue'
export { default as WorkspacePanelToggle } from './components/WorkspacePanelToggle.vue'
export { useTerminalTarget } from './composables/useTerminalTarget'
export { cleanupBrowserSessions } from './composables/browserRuntime'
