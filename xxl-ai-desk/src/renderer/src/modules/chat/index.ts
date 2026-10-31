/*
 * chat 模块对外公共 API：仅供布局域（Layout / Navbar）复用，
 * 模块内部页面/组件仍通过相对路径引用，避免外部直接依赖内部文件。
 * @author xuxueli 2026-10-25
 */

export { default as ChatApprovalDialog } from './components/ApprovalDialog.vue'
export { default as ChatSidebarPanel } from './components/ChatSidebarPanel.vue'
export { default as ChatTopbarTitle } from './components/ChatTopbarTitle.vue'
export { default as ChatSearchAction } from './components/ChatSearchAction.vue'
export { default as ChatCommandPalette } from './components/CommandPalette.vue'
export { useCommandPalette } from './composables/useCommandPalette'
export { useSessionActions } from './composables/useSessionActions'
