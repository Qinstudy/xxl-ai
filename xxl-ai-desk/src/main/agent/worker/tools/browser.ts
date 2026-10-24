/*
 * 浏览器类内置工具：在右侧「浏览器」面板打开页面、读取当前页面正文。
 *   - 打开默认复用当前标签页，new_tab=true 时才新建；面板由主进程转发渲染层展开；
 *   - 读取经 worker → 主进程 → 渲染层 webview 的请求/响应通路完成。
 */

import type { AgentTool } from '@earendil-works/pi-agent-core'
import type { BrowserReadParams, BrowserReadResult } from '@shared/ipc'
import {
  ALL_MODES,
  MAX_OUTPUT,
  describe,
  text,
  toolError,
  type ToolContext,
  type ToolEntry
} from './shared'

/* 打开回调（worker 注入）：newTab=true 时强制新建标签页 */
let browserOpener: ((url: string, newTab: boolean) => void) | null = null

/* 读取回调（worker 注入）：请求渲染层提取当前页正文 */
let browserReader: ((params: BrowserReadParams) => Promise<BrowserReadResult>) | null = null

/* 注入打开回调（运行时进程启动时调用一次） */
export function setBrowserOpener(opener: (url: string, newTab: boolean) => void): void {
  browserOpener = opener
}

/* 注入读取回调（运行时进程启动时调用一次） */
export function setBrowserReader(reader: (params: BrowserReadParams) => Promise<BrowserReadResult>): void {
  browserReader = reader
}

/* 构建浏览器类工具 */
export async function createBrowserTools(_context: ToolContext): Promise<ToolEntry[]> {
  const { Type } = await import('@earendil-works/pi-ai')

  /* 打开浏览器：默认复用当前标签页，必要时新建 */
  const openBrowser: AgentTool = {
    name: 'open_browser',
    label: '打开浏览器',
    description: describe(
      '在右侧「浏览器」面板打开网页。默认复用当前标签页；new_tab=true 时新建标签页。url 支持完整地址（如 http://localhost:3000）、域名（如 baidu.com）或搜索关键词',
      'Open a web page in the right-side Browser panel. Reuses the current tab by default; set new_tab=true to open a new tab. url accepts a full address, a domain, or a search keyword.'
    ),
    parameters: Type.Object({
      url: Type.String({ description: '要打开的网址、域名或搜索关键词' }),
      new_tab: Type.Optional(Type.Boolean({ description: '是否新建标签页，默认复用当前标签页' }))
    }),
    /* 与 read_browser 串行：避免打开与读取同批并行导致读到空白页 */
    executionMode: 'sequential',
    execute: async (_toolCallId, params) => {
      const { url, new_tab } = params as { url?: string; new_tab?: boolean }
      const raw = url?.trim() ?? ''
      if (!raw) {
        return toolError('url 不能为空')
      }
      /* 仅允许网页地址：显式带其它协议（file/javascript 等）一律拒绝 */
      const scheme = /^([a-z][a-z0-9+.-]*):/i.exec(raw)?.[1]?.toLowerCase()
      if (scheme && scheme !== 'http' && scheme !== 'https') {
        return toolError(`不支持打开该协议：${scheme}（仅支持 http/https 或域名）`)
      }
      if (!browserOpener) {
        return toolError('浏览器不可用：请在桌面客户端中使用该工具')
      }
      const newTab = new_tab === true
      browserOpener(raw, newTab)
      return text(
        `已在右侧「浏览器」${newTab ? '新标签页' : '当前标签页'}打开：${raw}`,
        { url: raw, newTab },
        2000
      )
    }
  }

  /* 读取网页：提取当前标签页正文（可选 CSS 选择器与字符上限） */
  const readBrowser: AgentTool = {
    name: 'read_browser',
    label: '读取网页',
    description: describe(
      '读取右侧「浏览器」当前标签页的标题、地址与正文文本。可选 selector 指定 CSS 选择器，max_chars 限制返回字符数（默认 20000）。页面loading时会等待加载完成',
      'Read the title, URL, and visible text of the current tab in the right-side Browser panel. Optional selector picks a CSS subtree; max_chars limits returned characters (default 20000). Waits for loading to finish.'
    ),
    parameters: Type.Object({
      selector: Type.Optional(Type.String({ description: 'CSS 选择器，缺省读取整个页面正文' })),
      max_chars: Type.Optional(Type.Number({ description: '返回正文字符上限，默认 20000' }))
    }),
    execute: async (_toolCallId, params) => {
      const { selector, max_chars } = params as { selector?: string; max_chars?: number }
      if (!browserReader) {
        return toolError('浏览器不可用：请在桌面客户端中使用该工具')
      }
      const max = Math.min(Math.max(Math.floor(max_chars ?? MAX_OUTPUT), 1000), 50000)
      let result: BrowserReadResult
      try {
        result = await browserReader({ selector: selector?.trim() || undefined, maxChars: max })
      } catch (error) {
        return toolError(`读取网页失败：${(error as Error).message}`)
      }
      if (!result.ok) {
        return toolError(result.error || '读取网页失败：请先在右侧浏览器打开页面')
      }
      const header = [
        `标题：${result.title || '（无标题）'}`,
        `地址：${result.url || ''}`
      ].join('\n')
      const body = result.text?.trim() || '（未提取到正文）'
      return text(
        `${header}\n\n${body}${result.truncated ? '\n...(已截断)' : ''}`,
        {
          url: result.url ?? '',
          title: result.title ?? '',
          chars: body.length,
          truncated: Boolean(result.truncated)
        },
        max + 1000
      )
    }
  }

  return [
    {
      meta: { group: 'ui', readOnly: true, modes: ALL_MODES, maxOutput: 2000 },
      tool: openBrowser
    },
    {
      meta: { group: 'ui', readOnly: true, modes: ALL_MODES, maxOutput: MAX_OUTPUT + 1000 },
      tool: readBrowser
    }
  ]
}
