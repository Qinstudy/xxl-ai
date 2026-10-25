/*
 * workspace 模块 - 底部终端公共入口：独立导出，避免应用外壳引入右侧面板等其它重型组件。
 * @author xuxueli 2026-10-25
 */
import { defineAsyncComponent } from 'vue'

export const WorkspaceTerminalPanel = defineAsyncComponent(
  () => import('./components/terminal/TerminalPanel.vue')
)
