/*
 * chat 模块对外公共 API：仅供应用外壳（App / AppTopbar）复用，
 * 模块内部页面/组件仍通过相对路径引用，避免外部直接依赖内部文件。
 * @author xuxueli 2026-10-25
 */

export { default as ChatSidebar } from './components/Sidebar.vue'
export { default as ChatTopbarActions } from './components/ChatTopbarActions.vue'
export { useSessionActions } from './composables/useSessionActions'
