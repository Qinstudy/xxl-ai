/*
 * 浏览器类内置工具：在右侧「浏览器」面板打开、读取与操作页面。
 *   - 打开默认复用当前标签页，new_tab=true 时才新建；
 *   - 读取 / 快照 / 点击 / 填写 / 等待 / 截图 / 诊断 / 标签页统一经动作桥完成；
 *   - 元素 ref 由 snapshot_browser 生成，点击与填写优先使用 ref，避免猜测 CSS。
 */

import type { AgentTool } from '@earendil-works/pi-agent-core'
import type { BrowserAction, BrowserActionResult } from '@shared/ipc'
import {
  ALL_MODES,
  BUILD_ONLY,
  MAX_OUTPUT,
  describe,
  text,
  toolError,
  type ToolContext,
  type ToolEntry
} from './shared'

/* Pi 的 Type 构建器（从动态导入模块取类型，避免静态依赖） */
type TypeBuilder = (typeof import('@earendil-works/pi-ai'))['Type']

/* 打开回调（worker 注入）：newTab=true 时强制新建标签页 */
let browserOpener: ((url: string, newTab: boolean) => void) | null = null

/* 动作回调（worker 注入）：请求渲染层执行页面动作并回传结果 */
let browserActionInvoker: ((action: BrowserAction) => Promise<BrowserActionResult>) | null = null

/* 注入打开回调（运行时进程启动时调用一次） */
export function setBrowserOpener(opener: (url: string, newTab: boolean) => void): void {
  browserOpener = opener
}

/* 注入动作回调（运行时进程启动时调用一次） */
export function setBrowserActionInvoker(
  invoker: (action: BrowserAction) => Promise<BrowserActionResult>
): void {
  browserActionInvoker = invoker
}

/* 调用页面动作并统一处理不可用场景 */
async function runBrowserAction(action: BrowserAction): Promise<BrowserActionResult> {
  if (!browserActionInvoker) {
    return { ok: false, error: '浏览器不可用：请在桌面客户端中使用该工具' }
  }
  try {
    return await browserActionInvoker(action)
  } catch (error) {
    return { ok: false, error: `浏览器动作失败：${(error as Error).message}` }
  }
}

/* 页面标题 / 地址摘要 */
function pageHeader(result: BrowserActionResult): string {
  return [`标题：${result.title || '（无标题）'}`, `地址：${result.url || ''}`].join('\n')
}

/* 快照节点 → 可读文本 */
function snapshotText(result: BrowserActionResult): string {
  const nodes = result.nodes ?? []
  const lines = nodes.map((node) => {
    const flags = [
      node.disabled ? 'disabled' : '',
      node.checked ? 'checked' : '',
      node.value ? `value="${node.value.slice(0, 80)}"` : '',
      node.href ? node.href : ''
    ]
      .filter(Boolean)
      .join(' ')
    return `- [${node.ref}] ${node.role} "${node.name}"${flags ? ` ${flags}` : ''}`
  })
  const truncatedTip =
    (result.totalNodes ?? 0) > nodes.length
      ? `\n（共 ${result.totalNodes} 个可交互元素，仅返回前 ${nodes.length} 个）`
      : ''
  const textTip = result.text ? `\n\n页面文本摘要：\n${result.text}` : ''
  return `${pageHeader(result)}\n\n可交互元素：\n${lines.join('\n') || '（未发现可交互元素）'}${truncatedTip}${textTip}`
}

/* 打开浏览器 / 历史导航：默认复用当前标签页，必要时新建 */
function createOpenBrowserTool(Type: TypeBuilder): AgentTool {
  return {
    name: 'open_browser',
    label: '打开浏览器',
    description: describe(
      '在右侧「浏览器」面板打开网页，或对当前页面执行刷新 / 后退 / 前进。默认复用当前标签页；new_tab=true 时新建标签页。url 支持完整地址（如 http://localhost:3000）、域名（如 baidu.com）或搜索关键词',
      'Open a web page in the right-side Browser panel, or reload / go back / go forward. Reuses the current tab by default; set new_tab=true to open a new tab. url accepts a full address, a domain, or a search keyword.'
    ),
    parameters: Type.Object({
      action: Type.Optional(
        Type.Union(
          [
            Type.Literal('goto'),
            Type.Literal('reload'),
            Type.Literal('back'),
            Type.Literal('forward')
          ],
          { description: '导航动作，默认 goto；reload / back / forward 不需要 url' }
        )
      ),
      url: Type.Optional(Type.String({ description: 'goto 时要打开的网址、域名或搜索关键词' })),
      new_tab: Type.Optional(Type.Boolean({ description: '是否新建标签页，默认复用当前标签页' }))
    }),
    /* 与其它浏览器动作串行：避免打开与读取同批并行导致读到空白页 */
    executionMode: 'sequential',
    execute: async (_toolCallId, params) => {
      const { action, url, new_tab } = params as {
        action?: 'goto' | 'reload' | 'back' | 'forward'
        url?: string
        new_tab?: boolean
      }
      const command = action ?? 'goto'
      if (command !== 'goto') {
        const result = await runBrowserAction({ type: 'history', action: command })
        if (!result.ok) {
          return toolError(result.error || '导航失败')
        }
        return text(
          `${result.summary || '已执行导航'}\n${pageHeader(result)}`,
          { action: command, url: result.url ?? '' },
          2000
        )
      }
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
}

/* 读取网页正文：提取当前标签页正文（可选 CSS 选择器与字符上限） */
function createReadBrowserTool(Type: TypeBuilder): AgentTool {
  return {
    name: 'read_browser',
    label: '读取网页',
    description: describe(
      '读取右侧「浏览器」当前标签页的标题、地址与正文文本。可选 selector 指定 CSS 选择器，max_chars 限制返回字符数（默认 20000）。页面 loading 时会等待加载完成',
      'Read the title, URL, and visible text of the current tab in the right-side Browser panel. Optional selector picks a CSS subtree; max_chars limits returned characters (default 20000). Waits for loading to finish.'
    ),
    parameters: Type.Object({
      selector: Type.Optional(Type.String({ description: 'CSS 选择器，缺省读取整个页面正文' })),
      max_chars: Type.Optional(Type.Number({ description: '返回正文字符上限，默认 20000' }))
    }),
    execute: async (_toolCallId, params) => {
      const { selector, max_chars } = params as { selector?: string; max_chars?: number }
      const max = Math.min(Math.max(Math.floor(max_chars ?? MAX_OUTPUT), 1000), 50000)
      const result = await runBrowserAction({
        type: 'read',
        selector: selector?.trim() || undefined,
        maxChars: max
      })
      if (!result.ok) {
        return toolError(result.error || '读取网页失败：请先在右侧浏览器打开页面')
      }
      const body = result.text?.trim() || '（未提取到正文）'
      return text(
        `${pageHeader(result)}\n\n${body}${result.truncated ? '\n...(已截断)' : ''}`,
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
}

/* 页面快照：列出可交互元素与稳定 ref */
function createSnapshotBrowserTool(Type: TypeBuilder): AgentTool {
  return {
    name: 'snapshot_browser',
    label: '读取页面结构',
    description: describe(
      '获取当前页面的可交互元素清单（按钮 / 链接 / 输入框等，带 ref）与文本摘要。操作页面前先调用它，再用 ref 点击或填写',
      'List interactive elements (buttons, links, inputs, etc.) with stable refs plus a text summary. Use it before clicking or filling, then pass the ref.'
    ),
    parameters: Type.Object({
      max_nodes: Type.Optional(Type.Number({ description: '返回元素上限，默认 120，最多 300' }))
    }),
    execute: async (_toolCallId, params) => {
      const { max_nodes } = params as { max_nodes?: number }
      const result = await runBrowserAction({ type: 'snapshot', maxNodes: max_nodes })
      if (!result.ok) {
        return toolError(result.error || '读取页面结构失败')
      }
      return text(
        snapshotText(result),
        {
          url: result.url ?? '',
          title: result.title ?? '',
          nodes: result.nodes?.length ?? 0,
          totalNodes: result.totalNodes ?? 0
        },
        MAX_OUTPUT
      )
    }
  }
}

/* 点击页面元素：ref 优先，selector 兜底 */
function createClickBrowserTool(Type: TypeBuilder): AgentTool {
  return {
    name: 'click_browser',
    label: '点击页面元素',
    description: describe(
      '点击当前页面元素。优先使用 snapshot_browser 返回的 ref，也可传 CSS selector。右键与双击可通过 button / click_count 指定',
      'Click an element in the current page. Prefer the ref from snapshot_browser; a CSS selector also works. Use button / click_count for right-click or double-click.'
    ),
    parameters: Type.Object({
      ref: Type.Optional(Type.String({ description: 'snapshot_browser 返回的元素 ref，如 e12' })),
      selector: Type.Optional(Type.String({ description: 'CSS 选择器（ref 缺失时使用）' })),
      button: Type.Optional(
        Type.Union([Type.Literal('left'), Type.Literal('right')], {
          description: '鼠标按键，默认 left'
        })
      ),
      click_count: Type.Optional(Type.Number({ description: '点击次数，默认 1，最多 3' }))
    }),
    /* 点击可能触发导航：串行执行，避免与其它页面动作并发 */
    executionMode: 'sequential',
    execute: async (_toolCallId, params) => {
      const { ref, selector, button, click_count } = params as {
        ref?: string
        selector?: string
        button?: 'left' | 'right'
        click_count?: number
      }
      if (!ref && !selector) {
        return toolError('ref 与 selector 至少提供一个')
      }
      const result = await runBrowserAction({
        type: 'click',
        ref: ref?.trim() || undefined,
        selector: selector?.trim() || undefined,
        button,
        clickCount: click_count
      })
      if (!result.ok) {
        return toolError(result.error || '点击失败')
      }
      return text(
        `${result.summary || '已点击'}\n${pageHeader(result)}`,
        { url: result.url ?? '', title: result.title ?? '' },
        2000
      )
    }
  }
}

/* 填写表单：支持文本 / 下拉 / 复选与提交 */
function createFillBrowserTool(Type: TypeBuilder): AgentTool {
  return {
    name: 'fill_browser',
    label: '填写页面表单',
    description: describe(
      '批量填写当前页面表单字段（输入框 / 下拉框 / 复选框 / contenteditable）。字段使用 snapshot_browser 的 ref 或 CSS selector；submit=true 时提交所属表单',
      'Fill multiple form fields in the current page (inputs, selects, checkboxes, contenteditable) using refs from snapshot_browser or CSS selectors. Set submit=true to submit the containing form.'
    ),
    parameters: Type.Object({
      fields: Type.Array(
        Type.Object({
          ref: Type.Optional(Type.String({ description: '元素 ref' })),
          selector: Type.Optional(Type.String({ description: 'CSS 选择器（ref 缺失时使用）' })),
          value: Type.Optional(Type.String({ description: '输入值或下拉选项值' })),
          checked: Type.Optional(Type.Boolean({ description: '复选框 / 单选框目标状态' }))
        }),
        { description: '待填写字段列表' }
      ),
      submit: Type.Optional(Type.Boolean({ description: '填写后是否提交表单，默认 false' }))
    }),
    executionMode: 'sequential',
    execute: async (_toolCallId, params) => {
      const { fields, submit } = params as {
        fields: Array<{ ref?: string; selector?: string; value?: string; checked?: boolean }>
        submit?: boolean
      }
      if (!fields || fields.length === 0) {
        return toolError('fields 不能为空')
      }
      const result = await runBrowserAction({ type: 'fill', fields, submit: submit === true })
      if (!result.ok) {
        return toolError(result.error || '填写失败')
      }
      const missing =
        result.missing && result.missing.length > 0
          ? `\n未找到字段：${result.missing.join('、')}`
          : ''
      return text(
        `${result.summary || `已填写 ${result.filled ?? 0} 个字段`}\n${pageHeader(result)}${missing}`,
        {
          url: result.url ?? '',
          title: result.title ?? '',
          filled: result.filled ?? 0,
          missing: result.missing ?? []
        },
        3000
      )
    }
  }
}

/* 等待页面条件：选择器 / 文本 / URL */
function createWaitBrowserTool(Type: TypeBuilder): AgentTool {
  return {
    name: 'wait_browser',
    label: '等待页面状态',
    description: describe(
      '等待页面满足条件：出现 selector、包含 text 或地址包含 url，任一命中即返回；用于等待异步加载与跳转',
      'Wait until the page matches a selector, contains text, or the URL contains a string. Returns when any condition matches; useful for async loading and navigation.'
    ),
    parameters: Type.Object({
      selector: Type.Optional(Type.String({ description: '等待出现的 CSS 选择器' })),
      text: Type.Optional(Type.String({ description: '等待出现的页面文本' })),
      url: Type.Optional(Type.String({ description: '等待地址包含的字符串' })),
      timeout_ms: Type.Optional(Type.Number({ description: '超时毫秒数，默认 8000，最多 30000' }))
    }),
    execute: async (_toolCallId, params) => {
      const { selector, text: expectedText, url, timeout_ms } = params as {
        selector?: string
        text?: string
        url?: string
        timeout_ms?: number
      }
      if (!selector && !expectedText && !url) {
        return toolError('selector / text / url 至少提供一个')
      }
      const result = await runBrowserAction({
        type: 'wait',
        selector: selector?.trim() || undefined,
        text: expectedText?.trim() || undefined,
        url: url?.trim() || undefined,
        timeoutMs: timeout_ms
      })
      if (!result.ok) {
        return toolError(result.error || '等待失败')
      }
      const status = result.matched
        ? `条件已满足（等待 ${result.waitedMs ?? 0}ms）`
        : `等待超时（${result.waitedMs ?? 0}ms），条件未满足`
      return text(
        `${status}\n${pageHeader(result)}`,
        { matched: Boolean(result.matched), waitedMs: result.waitedMs ?? 0, url: result.url ?? '' },
        2000
      )
    }
  }
}

/* 页面截图：返回图片供视觉模型核对 */
function createScreenshotBrowserTool(Type: TypeBuilder): AgentTool {
  return {
    name: 'screenshot_browser',
    label: '页面截图',
    description: describe(
      '截取右侧「浏览器」当前可见页面并返回截图，用于核对布局、样式与视觉结果。文本模型可能无法查看图片',
      'Capture the visible page in the right-side Browser panel for layout and visual verification. Text-only models may not be able to view the image.'
    ),
    parameters: Type.Object({}),
    execute: async () => {
      const result = await runBrowserAction({ type: 'screenshot' })
      if (!result.ok || !result.screenshot) {
        return toolError(result.error || '截图失败')
      }
      const match = /^data:(image\/[a-z+]+);base64,(.+)$/i.exec(result.screenshot)
      if (!match) {
        return toolError('截图数据格式异常')
      }
      const [, mimeType, data] = match
      return {
        content: [
          {
            type: 'text' as const,
            text: `已截取当前页面（${result.screenshotWidth ?? 0}x${result.screenshotHeight ?? 0}）`
          },
          { type: 'image' as const, data, mimeType }
        ],
        details: {
          url: result.url ?? '',
          title: result.title ?? '',
          width: result.screenshotWidth ?? 0,
          height: result.screenshotHeight ?? 0
        }
      }
    }
  }
}

/* 页面诊断：控制台告警/错误与加载失败 */
function createInspectBrowserTool(Type: TypeBuilder): AgentTool {
  return {
    name: 'inspect_browser',
    label: '页面诊断',
    description: describe(
      '读取当前页面的控制台 warning / error 与加载失败记录，用于排查前端报错、接口失败与白屏问题',
      'Read console warnings / errors and failed load records for the current page, useful for debugging frontend errors and blank pages.'
    ),
    parameters: Type.Object({}),
    execute: async () => {
      const result = await runBrowserAction({ type: 'diagnostics' })
      if (!result.ok) {
        return toolError(result.error || '读取诊断信息失败')
      }
      const consoleLines = (result.console ?? []).map(
        (entry) =>
          `[${entry.level}] ${entry.message}${entry.source ? ` (${entry.source}:${entry.line ?? 0})` : ''}`
      )
      const loadLines = result.failedLoads ?? []
      const sections = [
        pageHeader(result),
        `\n控制台（${consoleLines.length}）：\n${consoleLines.join('\n') || '（无 warning / error）'}`,
        `\n加载失败（${loadLines.length}）：\n${loadLines.join('\n') || '（无）'}`
      ]
      return text(
        sections.join('\n'),
        { url: result.url ?? '', console: consoleLines.length, failedLoads: loadLines.length },
        8000
      )
    }
  }
}

/* 标签页管理：列出 / 切换 / 关闭 */
function createTabsBrowserTool(Type: TypeBuilder): AgentTool {
  return {
    name: 'browser_tabs',
    label: '管理浏览器标签页',
    description: describe(
      '管理右侧浏览器标签页：list 列出、select 切换、close 关闭。select / close 需要 tab_id（来自 list）',
      'Manage right-side browser tabs: list, select, or close. select / close require a tab_id from list.'
    ),
    parameters: Type.Object({
      operation: Type.Union([Type.Literal('list'), Type.Literal('select'), Type.Literal('close')], {
        description: '操作类型'
      }),
      tab_id: Type.Optional(Type.String({ description: '标签页 ID（select / close 必填）' }))
    }),
    execute: async (_toolCallId, params) => {
      const { operation, tab_id } = params as {
        operation: 'list' | 'select' | 'close'
        tab_id?: string
      }
      const result = await runBrowserAction({
        type: 'tabs',
        operation,
        tabId: tab_id?.trim() || undefined
      })
      if (!result.ok) {
        return toolError(result.error || '标签页操作失败')
      }
      const lines = (result.tabs ?? []).map(
        (tab) => `- ${tab.id}${tab.active ? '（当前）' : ''} ${tab.title || '（无标题）'} ${tab.url || ''}`
      )
      return text(`标签页（${lines.length}）：\n${lines.join('\n') || '（无）'}`, {
        activeTabId: result.activeTabId ?? '',
        tabs: result.tabs?.length ?? 0
      }, 4000)
    }
  }
}

/* 工具定义：元信息 + 构建器（顺序即装配顺序） */
const BROWSER_TOOL_DEFINITIONS: Array<{
  meta: ToolEntry['meta']
  create: (Type: TypeBuilder) => AgentTool
}> = [
  {
    meta: { group: 'browser', readOnly: true, modes: ALL_MODES, maxOutput: 2000 },
    create: createOpenBrowserTool
  },
  {
    meta: { group: 'browser', readOnly: true, modes: ALL_MODES, maxOutput: MAX_OUTPUT + 1000 },
    create: createReadBrowserTool
  },
  {
    meta: { group: 'browser', readOnly: true, modes: ALL_MODES, maxOutput: MAX_OUTPUT },
    create: createSnapshotBrowserTool
  },
  {
    meta: { group: 'browser', readOnly: false, modes: BUILD_ONLY, maxOutput: 2000 },
    create: createClickBrowserTool
  },
  {
    meta: { group: 'browser', readOnly: false, modes: BUILD_ONLY, maxOutput: 3000 },
    create: createFillBrowserTool
  },
  {
    meta: { group: 'browser', readOnly: true, modes: ALL_MODES, maxOutput: 2000 },
    create: createWaitBrowserTool
  },
  {
    meta: { group: 'browser', readOnly: true, modes: ALL_MODES, maxOutput: 2000 },
    create: createScreenshotBrowserTool
  },
  {
    meta: { group: 'browser', readOnly: true, modes: ALL_MODES, maxOutput: 8000 },
    create: createInspectBrowserTool
  },
  {
    meta: { group: 'browser', readOnly: true, modes: ALL_MODES, maxOutput: 4000 },
    create: createTabsBrowserTool
  }
]

/* 浏览器类工具构建 */
export async function createBrowserTools(context: ToolContext): Promise<ToolEntry[]> {
  /* 内置工具开关关闭时不装配任何浏览器工具（保留开关语义在浏览器域内） */
  if (!context.browserToolsEnabled) {
    return []
  }
  const { Type } = await import('@earendil-works/pi-ai')
  return BROWSER_TOOL_DEFINITIONS.map(({ meta, create }) => ({
    meta,
    tool: create(Type)
  }))
}
