/*
 * 内置工具清单（跨进程共享的纯数据）：
 *   - 名称 / 分组 / 只读属性 / 适用模式为主进程 worker 与渲染层共同事实源；
 *   - 「插件 → 内置工具」页面的展示白名单与文案仍由模块内 catalog 维护。
 * @author xuxueli 2026-10-25
 */

import type { ChatMode } from './contracts/session'

/* 工具分组 */
export type BuiltinToolGroup = 'fs' | 'shell' | 'browser' | 'general'

/* 清单条目 */
export interface BuiltinToolManifestItem {
  /* 工具名（与 worker 注册表一致） */
  name: string
  /* 所属分组 */
  group: BuiltinToolGroup
  /* 是否只读 */
  readOnly: boolean
  /* 允许出现的对话模式 */
  modes: ChatMode[]
}

/* 两种模式均可用 */
const ALL: ChatMode[] = ['plan', 'build']
/* 仅 Build */
const BUILD: ChatMode[] = ['build']

/* 内置工具清单（与 agent/worker/tools 注册表保持一致） */
export const BUILTIN_TOOL_MANIFEST: BuiltinToolManifestItem[] = [
  { name: 'read_file', group: 'fs', readOnly: true, modes: ALL },
  { name: 'list_directory', group: 'fs', readOnly: true, modes: ALL },
  { name: 'glob_files', group: 'fs', readOnly: true, modes: ALL },
  { name: 'search_files', group: 'fs', readOnly: true, modes: ALL },
  { name: 'write_file', group: 'fs', readOnly: false, modes: BUILD },
  { name: 'edit_file', group: 'fs', readOnly: false, modes: BUILD },
  { name: 'run_command', group: 'shell', readOnly: false, modes: BUILD },
  { name: 'open_browser', group: 'browser', readOnly: true, modes: ALL },
  { name: 'read_browser', group: 'browser', readOnly: true, modes: ALL },
  { name: 'snapshot_browser', group: 'browser', readOnly: true, modes: ALL },
  { name: 'click_browser', group: 'browser', readOnly: false, modes: BUILD },
  { name: 'fill_browser', group: 'browser', readOnly: false, modes: BUILD },
  { name: 'wait_browser', group: 'browser', readOnly: true, modes: ALL },
  { name: 'screenshot_browser', group: 'browser', readOnly: true, modes: ALL },
  { name: 'inspect_browser', group: 'browser', readOnly: true, modes: ALL },
  { name: 'browser_tabs', group: 'browser', readOnly: true, modes: ALL },
  { name: 'get_current_time', group: 'general', readOnly: true, modes: ALL }
]

/* 工具名 → 清单项 */
export const BUILTIN_TOOL_MAP: Record<string, BuiltinToolManifestItem> = Object.fromEntries(
  BUILTIN_TOOL_MANIFEST.map((tool) => [tool.name, tool])
)
