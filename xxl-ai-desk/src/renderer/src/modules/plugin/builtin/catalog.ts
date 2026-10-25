/*
 * 内置工具展示目录（插件模块内静态白名单）：
 *   - 名称、分组、只读与适用模式取自 shared/toolManifest（跨进程单一事实源）；
 *   - 本文件只维护「是否展示 + 文案 key」，不按工具工厂输出自动展开。
 * @author xuxueli 2026-10-25
 */

import { BUILTIN_TOOL_MANIFEST } from '@shared/ipc'
import type { BuiltinToolGroup, BuiltinToolManifestItem } from '@shared/ipc'

/* 内置工具分类：fs 文件 / shell 终端 / browser 浏览器（可开关）/ general 通用（可开关） */
export type BuiltinToolCategoryKey = BuiltinToolGroup

/* 工具目录项 */
export interface BuiltinToolCatalogItem extends BuiltinToolManifestItem {
  /* 所属分类（与 group 同值，沿用页面既有字段名） */
  category: BuiltinToolCategoryKey
  /* 工具名称文案 key */
  titleKey: string
  /* 工具说明文案 key */
  descriptionKey: string
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

/* 工具展示文案：仅白名单内的工具会出现在页面 */
const DISPLAY: Record<string, { titleKey: string; descriptionKey: string }> = {
  read_file: { titleKey: 'chat.tool.readFile', descriptionKey: 'plugin.builtinTool.desc.readFile' },
  list_directory: {
    titleKey: 'chat.tool.listDir',
    descriptionKey: 'plugin.builtinTool.desc.listDir'
  },
  glob_files: { titleKey: 'chat.tool.glob', descriptionKey: 'plugin.builtinTool.desc.glob' },
  search_files: { titleKey: 'chat.tool.search', descriptionKey: 'plugin.builtinTool.desc.search' },
  write_file: {
    titleKey: 'chat.tool.writeFile',
    descriptionKey: 'plugin.builtinTool.desc.writeFile'
  },
  edit_file: { titleKey: 'chat.tool.editFile', descriptionKey: 'plugin.builtinTool.desc.editFile' },
  run_command: {
    titleKey: 'chat.tool.command',
    descriptionKey: 'plugin.builtinTool.desc.command'
  },
  open_browser: {
    titleKey: 'chat.tool.openBrowser',
    descriptionKey: 'plugin.builtinTool.desc.openBrowser'
  },
  read_browser: {
    titleKey: 'chat.tool.readBrowser',
    descriptionKey: 'plugin.builtinTool.desc.readBrowser'
  },
  snapshot_browser: {
    titleKey: 'chat.tool.snapshotBrowser',
    descriptionKey: 'plugin.builtinTool.desc.snapshotBrowser'
  },
  click_browser: {
    titleKey: 'chat.tool.clickBrowser',
    descriptionKey: 'plugin.builtinTool.desc.clickBrowser'
  },
  fill_browser: {
    titleKey: 'chat.tool.fillBrowser',
    descriptionKey: 'plugin.builtinTool.desc.fillBrowser'
  },
  wait_browser: {
    titleKey: 'chat.tool.waitBrowser',
    descriptionKey: 'plugin.builtinTool.desc.waitBrowser'
  },
  screenshot_browser: {
    titleKey: 'chat.tool.screenshotBrowser',
    descriptionKey: 'plugin.builtinTool.desc.screenshotBrowser'
  },
  inspect_browser: {
    titleKey: 'chat.tool.inspectBrowser',
    descriptionKey: 'plugin.builtinTool.desc.inspectBrowser'
  },
  browser_tabs: {
    titleKey: 'chat.tool.browserTabs',
    descriptionKey: 'plugin.builtinTool.desc.browserTabs'
  },
  get_current_time: {
    titleKey: 'chat.tool.time',
    descriptionKey: 'plugin.builtinTool.desc.time'
  }
}

/* 内置工具目录：按清单顺序生成，缺失文案的工具不展示（产品白名单） */
export const BUILTIN_TOOL_CATALOG: BuiltinToolCatalogItem[] = BUILTIN_TOOL_MANIFEST.flatMap(
  (tool) => {
    const display = DISPLAY[tool.name]
    return display ? [{ ...tool, category: tool.group, ...display }] : []
  }
)
