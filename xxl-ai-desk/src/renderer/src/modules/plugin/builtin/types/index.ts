/*
 * 内置工具目录（插件模块内静态目录）：
 *   - 描述内置工具的名称、分类、权限与适用模式，供「插件 → 内置工具」页面只读展示；
 *   - 工具名必须与 agent/worker/tools 下的内置工具保持一致；
 *   - 开关类分类的启用状态来自应用设置，不在本文件落库。
 *
 * @author xuxueli 2026-10-25
 */

import type { ChatMode } from '@shared/ipc'

/* 内置工具分类：fs 文件 / shell 终端 / browser 浏览器（可开关）/ general 通用（可开关） */
export type BuiltinToolCategoryKey = 'fs' | 'shell' | 'browser' | 'general'

/* 工具目录项 */
export interface BuiltinToolCatalogItem {
  /* 工具名（与内置工具注册表一致） */
  name: string
  /* 所属分类 */
  category: BuiltinToolCategoryKey
  /* 工具名称文案 key */
  titleKey: string
  /* 工具说明文案 key */
  descriptionKey: string
  /* 是否只读工具 */
  readOnly: boolean
  /* 允许出现的对话模式 */
  modes: ChatMode[]
}

/* 分类定义 */
export interface BuiltinToolCategory {
  /* 分类标识 */
  key: BuiltinToolCategoryKey
  /* 分类标题文案 key */
  titleKey: string
  /* 分类说明文案 key */
  descriptionKey: string
  /* 是否支持开关（false 表示固定启用） */
  switchable: boolean
}

/* 内置工具分类列表（展示顺序即渲染顺序） */
export const BUILTIN_TOOL_CATEGORIES: BuiltinToolCategory[] = [
  {
    key: 'fs',
    titleKey: 'plugin.builtinTool.category.fs',
    descriptionKey: 'plugin.builtinTool.categoryDesc.fs',
    switchable: false
  },
  {
    key: 'shell',
    titleKey: 'plugin.builtinTool.category.shell',
    descriptionKey: 'plugin.builtinTool.categoryDesc.shell',
    switchable: false
  },
  {
    key: 'browser',
    titleKey: 'plugin.builtinTool.category.browser',
    descriptionKey: 'plugin.builtinTool.categoryDesc.browser',
    switchable: true
  },
  {
    key: 'general',
    titleKey: 'plugin.builtinTool.category.general',
    descriptionKey: 'plugin.builtinTool.categoryDesc.general',
    switchable: true
  }
]

/* 内置工具目录（顺序即页面展示顺序） */
export const BUILTIN_TOOL_CATALOG: BuiltinToolCatalogItem[] = [
  {
    name: 'read_file',
    category: 'fs',
    titleKey: 'chat.tool.readFile',
    descriptionKey: 'plugin.builtinTool.desc.readFile',
    readOnly: true,
    modes: ['plan', 'build']
  },
  {
    name: 'list_directory',
    category: 'fs',
    titleKey: 'chat.tool.listDir',
    descriptionKey: 'plugin.builtinTool.desc.listDir',
    readOnly: true,
    modes: ['plan', 'build']
  },
  {
    name: 'glob_files',
    category: 'fs',
    titleKey: 'chat.tool.glob',
    descriptionKey: 'plugin.builtinTool.desc.glob',
    readOnly: true,
    modes: ['plan', 'build']
  },
  {
    name: 'search_files',
    category: 'fs',
    titleKey: 'chat.tool.search',
    descriptionKey: 'plugin.builtinTool.desc.search',
    readOnly: true,
    modes: ['plan', 'build']
  },
  {
    name: 'write_file',
    category: 'fs',
    titleKey: 'chat.tool.writeFile',
    descriptionKey: 'plugin.builtinTool.desc.writeFile',
    readOnly: false,
    modes: ['build']
  },
  {
    name: 'edit_file',
    category: 'fs',
    titleKey: 'chat.tool.editFile',
    descriptionKey: 'plugin.builtinTool.desc.editFile',
    readOnly: false,
    modes: ['build']
  },
  {
    name: 'run_command',
    category: 'shell',
    titleKey: 'chat.tool.command',
    descriptionKey: 'plugin.builtinTool.desc.command',
    readOnly: false,
    modes: ['build']
  },
  {
    name: 'open_browser',
    category: 'browser',
    titleKey: 'chat.tool.openBrowser',
    descriptionKey: 'plugin.builtinTool.desc.openBrowser',
    readOnly: true,
    modes: ['plan', 'build']
  },
  {
    name: 'read_browser',
    category: 'browser',
    titleKey: 'chat.tool.readBrowser',
    descriptionKey: 'plugin.builtinTool.desc.readBrowser',
    readOnly: true,
    modes: ['plan', 'build']
  },
  {
    name: 'snapshot_browser',
    category: 'browser',
    titleKey: 'chat.tool.snapshotBrowser',
    descriptionKey: 'plugin.builtinTool.desc.snapshotBrowser',
    readOnly: true,
    modes: ['plan', 'build']
  },
  {
    name: 'click_browser',
    category: 'browser',
    titleKey: 'chat.tool.clickBrowser',
    descriptionKey: 'plugin.builtinTool.desc.clickBrowser',
    readOnly: false,
    modes: ['build']
  },
  {
    name: 'fill_browser',
    category: 'browser',
    titleKey: 'chat.tool.fillBrowser',
    descriptionKey: 'plugin.builtinTool.desc.fillBrowser',
    readOnly: false,
    modes: ['build']
  },
  {
    name: 'wait_browser',
    category: 'browser',
    titleKey: 'chat.tool.waitBrowser',
    descriptionKey: 'plugin.builtinTool.desc.waitBrowser',
    readOnly: true,
    modes: ['plan', 'build']
  },
  {
    name: 'screenshot_browser',
    category: 'browser',
    titleKey: 'chat.tool.screenshotBrowser',
    descriptionKey: 'plugin.builtinTool.desc.screenshotBrowser',
    readOnly: true,
    modes: ['plan', 'build']
  },
  {
    name: 'inspect_browser',
    category: 'browser',
    titleKey: 'chat.tool.inspectBrowser',
    descriptionKey: 'plugin.builtinTool.desc.inspectBrowser',
    readOnly: true,
    modes: ['plan', 'build']
  },
  {
    name: 'browser_tabs',
    category: 'browser',
    titleKey: 'chat.tool.browserTabs',
    descriptionKey: 'plugin.builtinTool.desc.browserTabs',
    readOnly: true,
    modes: ['plan', 'build']
  },
  {
    name: 'get_current_time',
    category: 'general',
    titleKey: 'chat.tool.time',
    descriptionKey: 'plugin.builtinTool.desc.time',
    readOnly: true,
    modes: ['plan', 'build']
  }
]
