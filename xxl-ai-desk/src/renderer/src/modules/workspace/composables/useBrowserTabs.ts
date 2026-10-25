/*
 * 内嵌浏览器标签页：webview 生命周期、导航历史、地址栏与标签页状态。
 *   每个标签页一个独立 webview（切换用 v-show 保留页面状态与历史）。
 * @author xuxueli 2026-10-25
 */

import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '@renderer/api'
import { useLayoutStore } from '@renderer/stores/layout'
import { t } from '@renderer/i18n'
import type {
  BrowserAction,
  BrowserActionResult,
  BrowserConsoleEntry,
  BrowserFillField,
  BrowserSnapshotNode,
  BrowserTabInfo
} from '@shared/ipc'
import { normalizeBrowserUrl } from '@shared/browserUrl'
import {
  buildClickScript,
  buildFillScript,
  buildProbeScript,
  buildReadScript,
  buildSnapshotScript
} from '../utils/browserScripts'

/* Electron <webview> 的最小能力声明（渲染进程无官方类型） */
export interface WebviewElement extends HTMLElement {
  loadURL(url: string): Promise<void>
  getURL(): string
  canGoBack(): boolean
  canGoForward(): boolean
  goBack(): void
  goForward(): void
  reload(): void
  stop(): void
  focus(): void
  executeJavaScript(code: string): Promise<unknown>
  capturePage(): Promise<{ toDataURL(): string; getSize(): { width: number; height: number } }>
}

/* 标签页（可序列化状态，webview 元素另存于 Map） */
export interface BrowserTab {
  id: string
  input: string
  currentUrl: string
  title: string
  loading: boolean
  hasPage: boolean
  canBack: boolean
  canForward: boolean
}

/* webview 事件负载（以最小结构声明，避免 any） */
interface FailLoadEvent extends Event {
  errorCode: number
}
interface NavigateEvent extends Event {
  url: string
}
interface TitleEvent extends Event {
  title: string
}

/* 标签页自增序号，用于生成唯一 id */
let seq = 0

/* webview console-message 事件负载 */
interface ConsoleMessageEvent extends Event {
  level: number
  message: string
  sourceId?: string
  line?: number
}

/* 工具名 → 空等：读取前等待面板/页面就绪 */
function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/*
 * 面板动作实现注册表：由 BrowserPanel 挂载时写入、卸载时清理。
 * IPC 动作监听放在常驻的 RightPanel，避免「面板刚打开 / open 与动作同批并行」时漏事件。
 */
let panelActionRunner: ((action: BrowserAction) => Promise<BrowserActionResult>) | null = null

/* 注册当前面板的动作实现 */
export function setBrowserPanelActionRunner(runner: typeof panelActionRunner): void {
  panelActionRunner = runner
}

/* 等待面板就绪后执行动作（面板尚未挂载时短暂等待，覆盖首次打开场景） */
export async function runBrowserActionFromPanel(
  action: BrowserAction,
  timeout = 6000
): Promise<BrowserActionResult> {
  const started = Date.now()
  while (!panelActionRunner && Date.now() - started < timeout) {
    await sleep(100)
  }
  if (!panelActionRunner) {
    return { ok: false, error: '右侧浏览器尚未就绪，请稍后重试' }
  }
  return panelActionRunner(action)
}

/* 新建标签页状态（url 非空时直接导航） */
function makeTab(url = ''): BrowserTab {
  return {
    id: `tab-${++seq}`,
    input: url,
    currentUrl: '',
    title: '',
    loading: false,
    hasPage: Boolean(url),
    canBack: false,
    canForward: false
  }
}

/* 浏览器标签页组合式函数：标签页状态、导航、页面读取与外部请求消费 */
export function useBrowserTabs() {
  /* 布局 store：承接 Agent 工具 / 页面 window.open 的打开请求 */
  const layout = useLayoutStore()

  /* 标签页列表与当前激活标签页 id（首个标签默认激活） */
  const tabs = ref<BrowserTab[]>([makeTab()])
  const activeId = ref(tabs.value[0].id)

  /* 非响应式：标签页 id → webview 元素 / 宿主容器 / guest 是否已就绪 */
  const webviews = new Map<string, WebviewElement>()
  const containers = new Map<string, HTMLElement>()
  const readyTabs = new Set<string>()
  /* guest 未就绪时暂存的待导航地址（dom-ready 后再 loadURL） */
  const pendingNav = new Map<string, string>()
  /* 控制台告警/错误与加载失败（供 Agent 诊断，按标签页维护） */
  const consoleLogs = new Map<string, BrowserConsoleEntry[]>()
  const failedLoads = new Map<string, string[]>()
  /* 单标签页诊断上限，避免长期运行无限增长 */
  const MAX_DIAGNOSTICS = 100
  /* 地址栏聚焦时不覆盖用户正在编辑的输入 */
  let inputFocused = false

  /* 当前激活标签页（兜底取首个） */
  const activeTab = computed(
    () => tabs.value.find((tab) => tab.id === activeId.value) ?? tabs.value[0]
  )

  /* 地址栏文本双向绑定到当前标签页 */
  const addressInput = computed({
    get: () => activeTab.value?.input ?? '',
    set: (value: string) => {
      if (activeTab.value) {
        activeTab.value.input = value
      }
    }
  })

  /* 标签页显示名：优先页面标题，其次主机名，最后「新标签页」 */
  function tabLabel(tab: BrowserTab): string {
    if (tab.title) {
      return tab.title
    }
    if (tab.currentUrl) {
      try {
        return new URL(tab.currentUrl).host
      } catch {
        return tab.currentUrl
      }
    }
    return t('panel.untitled')
  }

  /* 按 id 查找标签页 */
  function findTab(id: string): BrowserTab | undefined {
    return tabs.value.find((tab) => tab.id === id)
  }

  /* 同步前进/后退可用态 */
  function syncNav(id: string): void {
    const tab = findTab(id)
    const view = webviews.get(id)
    if (tab && view) {
      tab.canBack = view.canGoBack()
      tab.canForward = view.canGoForward()
    }
  }

  /* 导航完成：同步地址栏与前进/后退态（忽略初始 about:blank） */
  function onNavigated(id: string, url: string | undefined): void {
    const tab = findTab(id)
    if (!tab) {
      return
    }
    if (url && url !== 'about:blank') {
      /* 新页面：清空上一个页面的诊断，避免误读 */
      if (tab.currentUrl !== url) {
        consoleLogs.delete(id)
        failedLoads.delete(id)
      }
      tab.currentUrl = url
      if (!(activeId.value === id && inputFocused)) {
        tab.input = url
      }
    }
    syncNav(id)
  }

  /* 用 loadURL 导航（guest 就绪后才可靠） */
  async function loadUrl(id: string, url: string): Promise<void> {
    const view = webviews.get(id)
    if (!view) {
      return
    }
    try {
      await view.loadURL(url)
    } catch {
      /* 加载失败由页面自身呈现 */
    }
  }

  /* 导航到指定 URL：guest 未就绪时先排队，dom-ready 后再 loadURL */
  async function navigate(id: string, url: string): Promise<void> {
    const view = ensureWebview(id)
    if (!view) {
      return
    }
    const tab = findTab(id)
    if (tab) {
      tab.hasPage = true
      /* 立即记录目标地址，标签名/「外部打开」无需等待 did-navigate */
      tab.currentUrl = url
    }
    if (readyTabs.has(id)) {
      await loadUrl(id, url)
    } else {
      pendingNav.set(id, url)
    }
  }

  /* 创建标签页的 webview（懒创建、幂等） */
  function ensureWebview(id: string): WebviewElement | null {
    const existing = webviews.get(id)
    if (existing) {
      return existing
    }
    const container = containers.get(id)
    if (!container) {
      return null
    }
    const el = document.createElement('webview') as WebviewElement
    /* 允许 window.open / target=_blank 触发新窗口请求，由主进程转成新标签页 */
    el.setAttribute('allowpopups', '')
    el.setAttribute('style', 'width:100%;height:100%;border:0;display:flex;background:#fff')
    /* 先挂 about:blank 完成 guest 附加，后续用 loadURL 导航 */
    el.setAttribute('src', 'about:blank')
    el.addEventListener('dom-ready', () => {
      readyTabs.add(id)
      /* guest 就绪后执行等待中的导航（新标签刚创建时的导航走这里） */
      const queued = pendingNav.get(id)
      if (queued) {
        pendingNav.delete(id)
        void loadUrl(id, queued)
      }
    })
    el.addEventListener('did-start-loading', () => {
      const tab = findTab(id)
      if (tab) {
        tab.loading = true
      }
    })
    el.addEventListener('did-stop-loading', () => {
      const tab = findTab(id)
      if (tab) {
        tab.loading = false
        syncNav(id)
      }
    })
    el.addEventListener('did-fail-load', (event) => {
      /* -3 为用户主动中断，不视为失败 */
      if ((event as FailLoadEvent).errorCode !== -3) {
        const tab = findTab(id)
        if (tab) {
          tab.loading = false
        }
        const fail = event as FailLoadEvent & { validatedURL?: string; errorDescription?: string }
        const url = fail.validatedURL || tab?.currentUrl || ''
        const list = failedLoads.get(id) ?? []
        list.push(`${url}（${fail.errorDescription || `错误码 ${fail.errorCode}`}）`)
        failedLoads.set(id, list.slice(-MAX_DIAGNOSTICS))
      }
    })
    el.addEventListener('console-message', (event) => {
      const consoleEvent = event as ConsoleMessageEvent
      /* 仅保留 warning（2）与 error（3）以上，并过滤 Electron 自身的开发期安全提示 */
      if (consoleEvent.level < 2 || consoleEvent.message.includes('Electron Security Warning')) {
        return
      }
      const list = consoleLogs.get(id) ?? []
      list.push({
        level: consoleEvent.level >= 3 ? 'error' : 'warning',
        /* 单条消息限长，避免超长堆栈占满 IPC 与上下文 */
        message: consoleEvent.message.slice(0, 1000),
        source: consoleEvent.sourceId,
        line: consoleEvent.line
      })
      consoleLogs.set(id, list.slice(-MAX_DIAGNOSTICS))
    })
    el.addEventListener('did-navigate', (event) => {
      onNavigated(id, (event as NavigateEvent).url)
    })
    el.addEventListener('did-navigate-in-page', (event) => {
      onNavigated(id, (event as NavigateEvent).url)
    })
    el.addEventListener('page-title-updated', (event) => {
      const tab = findTab(id)
      const title = (event as TitleEvent).title
      if (tab && title) {
        tab.title = title
      }
    })
    container.appendChild(el)
    webviews.set(id, el)
    return el
  }

  /* 记录/清理标签页宿主容器（v-for 函数 ref 回调） */
  function setContainer(id: string, el: unknown): void {
    if (el instanceof HTMLElement) {
      containers.set(id, el)
      /* 容器就绪即预热 webview，提前拉起 guest 进程 */
      ensureWebview(id)
    } else {
      containers.delete(id)
    }
  }

  /* 提交当前标签页地址栏 */
  async function go(): Promise<void> {
    const tab = activeTab.value
    if (!tab) {
      return
    }
    const url = normalizeBrowserUrl(tab.input)
    if (!url) {
      return
    }
    await navigate(tab.id, url)
  }

  /* 新建标签页（可带初始 URL），并激活 */
  async function addTab(url = ''): Promise<void> {
    const tab = makeTab(url)
    tabs.value.push(tab)
    activeId.value = tab.id
    await nextTick()
    if (url) {
      await navigate(tab.id, url)
    } else {
      webviews.get(tab.id)?.focus()
    }
  }

  /*
   * 打开外部请求的地址：
   *   - newTab=true（页面 window.open 等）→ 新建标签页；
   *   - 否则复用当前标签页（若当前是空白页则直接用该页），保证 Agent 连续操作在同一页完成。
   */
  async function openRequested(url: string, newTab: boolean): Promise<void> {
    if (newTab) {
      await addTab(url)
      return
    }
    const blank = tabs.value.find((tab) => !tab.hasPage && !tab.currentUrl)
    if (blank) {
      activeId.value = blank.id
      await nextTick()
      await navigate(blank.id, url)
      return
    }
    const current = activeTab.value
    if (current) {
      await navigate(current.id, url)
      return
    }
    await addTab(url)
  }

  /* 切换标签页 */
  async function selectTab(id: string): Promise<void> {
    activeId.value = id
    await nextTick()
    syncNav(id)
  }

  /* 关闭标签页；始终保留至少一个 */
  function closeTab(id: string): void {
    const index = tabs.value.findIndex((tab) => tab.id === id)
    if (index < 0) {
      return
    }
    webviews.get(id)?.remove()
    webviews.delete(id)
    readyTabs.delete(id)
    pendingNav.delete(id)
    containers.delete(id)
    consoleLogs.delete(id)
    failedLoads.delete(id)
    tabs.value.splice(index, 1)
    if (tabs.value.length === 0) {
      const tab = makeTab()
      tabs.value.push(tab)
      activeId.value = tab.id
    } else if (activeId.value === id) {
      activeId.value = tabs.value[Math.min(index, tabs.value.length - 1)].id
    }
  }

  /* 后退 */
  function back(): void {
    const view = webviews.get(activeId.value)
    if (view?.canGoBack()) {
      view.goBack()
    }
  }

  /* 前进 */
  function forward(): void {
    const view = webviews.get(activeId.value)
    if (view?.canGoForward()) {
      view.goForward()
    }
  }

  /* 刷新 / 停止 */
  function reloadOrStop(): void {
    const view = webviews.get(activeId.value)
    if (!view) {
      void go()
      return
    }
    if (activeTab.value?.loading) {
      view.stop()
    } else {
      view.reload()
    }
  }

  /* 在系统浏览器中打开当前页 */
  function openExternal(): void {
    const url = activeTab.value?.currentUrl
    if (url) {
      void api.shell.openExternal(url)
    }
  }

  /* 更多操作分发 */
  function onMore(command: string): void {
    if (command === 'external') {
      openExternal()
    } else if (command === 'reload') {
      webviews.get(activeId.value)?.reload()
    }
  }

  /* 地址栏聚焦态：聚焦期间不覆盖用户输入 */
  function onAddressFocus(): void {
    inputFocused = true
  }

  /* 地址栏失焦：恢复用导航地址刷新输入框 */
  function onAddressBlur(): void {
    inputFocused = false
  }

  /* 读取 webview 当前地址（未就绪时返回空串，避免抛错中断等待） */
  function currentUrlOf(view: WebviewElement): string {
    try {
      return view.getURL() || ''
    } catch {
      return ''
    }
  }

  /*
   * 等待页面真正可读：webview 已 dom-ready、不在加载中、且已离开 about:blank。
   * open_browser 与 read_browser 可能同批并行，必须等导航落地后再取正文。
   */
  async function waitForPageReady(
    tab: BrowserTab,
    view: WebviewElement,
    timeout = 12000
  ): Promise<void> {
    const started = Date.now()
    while (Date.now() - started < timeout) {
      const url = currentUrlOf(view)
      if (readyTabs.has(tab.id) && !tab.loading && url && url !== 'about:blank') {
        return
      }
      await sleep(150)
    }
  }

  /* 解析当前可操作的页面：等待标签页创建、导航落定与加载完成 */
  async function resolveActivePage(
    timeout = 12000
  ): Promise<{ tab: BrowserTab; view: WebviewElement } | BrowserActionResult> {
    const started = Date.now()
    /* 先等标签页创建 + 导航目标写入（open_browser 的请求由 IPC 异步转达） */
    let tab = activeTab.value
    let view = tab ? webviews.get(tab.id) : undefined
    while ((!tab?.hasPage || !view) && Date.now() - started < timeout) {
      await sleep(120)
      tab = activeTab.value
      view = tab ? webviews.get(tab.id) : undefined
    }
    if (!tab?.hasPage) {
      return { ok: false, error: '右侧浏览器尚未打开页面，请先使用 open_browser 打开页面' }
    }
    if (!view) {
      return { ok: false, error: '浏览器尚未就绪，请稍后重试' }
    }
    await waitForPageReady(tab, view, Math.max(1000, timeout - (Date.now() - started)))
    return { tab, view }
  }

  /* 动作后等待页面加载结束并留出渲染稳定时间 */
  async function settlePage(tabId: string, quietMs = 250): Promise<void> {
    const started = Date.now()
    while (Date.now() - started < 3000) {
      const tab = findTab(tabId)
      if (!tab?.loading) {
        break
      }
      await sleep(100)
    }
    await sleep(quietMs)
  }

  /* 读取当前标签页正文：注入脚本提取标题、地址与可见文本 */
  async function readActiveTab(action: { selector?: string; maxChars?: number }): Promise<BrowserActionResult> {
    const page = await resolveActivePage()
    if ('ok' in page) {
      return page
    }
    const { tab, view } = page
    const max = Math.min(Math.max(Math.floor(action.maxChars ?? 20000), 1000), 50000)
    const script = buildReadScript(action.selector)
    try {
      const raw = (await view.executeJavaScript(script)) as {
        title?: string
        url?: string
        text?: string
        missing?: boolean
      }
      if (raw?.missing) {
        return { ok: false, error: `未找到选择器匹配的元素：${action.selector ?? ''}` }
      }
      const full = raw?.text ?? ''
      return {
        ok: true,
        title: raw?.title ?? tab.title,
        url: raw?.url ?? tab.currentUrl,
        text: full.slice(0, max),
        truncated: full.length > max
      }
    } catch (error) {
      return { ok: false, error: `读取页面失败：${(error as Error).message}` }
    }
  }

  /* 页面快照：提取可交互元素并分配稳定 ref，供 click / fill 使用 */
  async function snapshotActiveTab(action: { maxNodes?: number }): Promise<BrowserActionResult> {
    const page = await resolveActivePage()
    if ('ok' in page) {
      return page
    }
    const { tab, view } = page
    const maxNodes = Math.min(Math.max(Math.floor(action.maxNodes ?? 120), 10), 300)
    const script = buildSnapshotScript(maxNodes)
    try {
      const raw = (await view.executeJavaScript(script)) as {
        title?: string
        url?: string
        nodes?: BrowserSnapshotNode[]
        totalNodes?: number
        text?: string
        truncated?: boolean
      }
      return {
        ok: true,
        title: raw?.title ?? tab.title,
        url: raw?.url ?? tab.currentUrl,
        nodes: raw?.nodes ?? [],
        totalNodes: raw?.totalNodes ?? 0,
        text: raw?.text ?? '',
        truncated: Boolean(raw?.truncated)
      }
    } catch (error) {
      return { ok: false, error: `读取页面结构失败：${(error as Error).message}` }
    }
  }

  /* 点击元素：优先使用快照 ref，其次 CSS 选择器 */
  async function clickActiveTab(action: {
    ref?: string
    selector?: string
    button?: 'left' | 'right'
    clickCount?: number
  }): Promise<BrowserActionResult> {
    const page = await resolveActivePage()
    if ('ok' in page) {
      return page
    }
    const { tab, view } = page
    const ref = action.ref ? JSON.stringify(action.ref) : 'null'
    const selector = action.selector ? JSON.stringify(action.selector) : 'null'
    const button = action.button === 'right' ? 'right' : 'left'
    const count = Math.min(Math.max(Math.floor(action.clickCount ?? 1), 1), 3)
    const script = buildClickScript({ ref: action.ref, selector: action.selector, button, count })
    try {
      const raw = (await view.executeJavaScript(script)) as { ok?: boolean; error?: string }
      if (!raw?.ok) {
        return { ok: false, error: raw?.error || '点击失败：未找到目标元素' }
      }
    } catch (error) {
      return { ok: false, error: `点击失败：${(error as Error).message}` }
    }
    await settlePage(tab.id)
    return { ok: true, title: tab.title, url: tab.currentUrl, summary: `已${button === 'right' ? '右键点击' : '点击'}${action.ref || action.selector || '目标元素'}` }
  }

  /* 填写表单：支持文本、下拉、复选/单选与 contenteditable，可选择提交 */
  async function fillActiveTab(action: {
    fields: BrowserFillField[]
    submit?: boolean
  }): Promise<BrowserActionResult> {
    const page = await resolveActivePage()
    if ('ok' in page) {
      return page
    }
    const { tab, view } = page
    if (!action.fields || action.fields.length === 0) {
      return { ok: false, error: 'fields 不能为空' }
    }
    const fields = JSON.stringify(action.fields)
    const script = buildFillScript(action.fields, action.submit === true)
    try {
      const raw = (await view.executeJavaScript(script)) as {
        ok?: boolean
        filled?: number
        missing?: string[]
        error?: string
      }
      if (!raw?.ok) {
        return { ok: false, error: raw?.error || '填写失败', filled: raw?.filled, missing: raw?.missing }
      }
      await settlePage(tab.id)
      return {
        ok: true,
        title: tab.title,
        url: tab.currentUrl,
        filled: raw.filled ?? 0,
        missing: raw.missing ?? [],
        summary: `已填写 ${raw.filled ?? 0} 个字段${action.submit ? '并提交表单' : ''}`
      }
    } catch (error) {
      return { ok: false, error: `填写失败：${(error as Error).message}` }
    }
  }

  /* 等待页面条件：选择器 / 文本 / URL，任一命中即返回 */
  async function waitActiveTab(action: {
    selector?: string
    text?: string
    url?: string
    timeoutMs?: number
  }): Promise<BrowserActionResult> {
    const page = await resolveActivePage()
    if ('ok' in page) {
      return page
    }
    const { tab, view } = page
    if (!action.selector && !action.text && !action.url) {
      return { ok: false, error: '至少提供 selector / text / url 之一' }
    }
    const timeout = Math.min(Math.max(Math.floor(action.timeoutMs ?? 8000), 500), 30000)
    const started = Date.now()
    /* 渲染层轮询：URL 直接在标签页状态判断，选择器/文本在页面内探测，整页跳转不会中断等待 */
    while (Date.now() - started < timeout) {
      if (action.url && tab.currentUrl.includes(action.url)) {
        return {
          ok: true,
          matched: true,
          waitedMs: Date.now() - started,
          title: tab.title,
          url: tab.currentUrl
        }
      }
      if (action.selector || action.text) {
        try {
          const raw = (await view.executeJavaScript(
            buildProbeScript({ selector: action.selector, text: action.text })
          )) as { matched?: boolean; title?: string; url?: string }
          if (raw?.matched) {
            return {
              ok: true,
              matched: true,
              waitedMs: Date.now() - started,
              title: raw.title ?? tab.title,
              url: raw.url ?? tab.currentUrl
            }
          }
        } catch {
          /* 页面跳转中脚本上下文可能被销毁：继续轮询，下一轮会落到新页面 */
        }
      }
      await sleep(200)
    }
    return {
      ok: true,
      matched: Boolean(action.url && tab.currentUrl.includes(action.url)),
      waitedMs: Date.now() - started,
      title: tab.title,
      url: tab.currentUrl
    }
  }

  /* 页面截图：返回 data URL 供视觉模型查看 */
  async function screenshotActiveTab(): Promise<BrowserActionResult> {
    const page = await resolveActivePage()
    if ('ok' in page) {
      return page
    }
    const { tab, view } = page
    try {
      const image = await view.capturePage()
      const size = image.getSize()
      return {
        ok: true,
        title: tab.title,
        url: tab.currentUrl,
        screenshot: image.toDataURL(),
        screenshotWidth: size.width,
        screenshotHeight: size.height
      }
    } catch (error) {
      return { ok: false, error: `截图失败：${(error as Error).message}` }
    }
  }

  /* 页面诊断：当前标签页的告警/错误与加载失败记录 */
  function diagnosticsActiveTab(): BrowserActionResult {
    const tab = activeTab.value
    return {
      ok: true,
      title: tab?.title ?? '',
      url: tab?.currentUrl ?? '',
      console: tab ? (consoleLogs.get(tab.id) ?? []) : [],
      failedLoads: tab ? (failedLoads.get(tab.id) ?? []) : []
    }
  }

  /* 标签页列表（含当前激活项） */
  function tabInfos(): BrowserTabInfo[] {
    return tabs.value.map((tab) => ({
      id: tab.id,
      title: tabLabel(tab),
      url: tab.currentUrl,
      active: tab.id === activeId.value
    }))
  }

  /* 标签页管理：列出 / 切换 / 关闭 */
  async function manageTabs(action: {
    operation: 'list' | 'select' | 'close'
    tabId?: string
  }): Promise<BrowserActionResult> {
    if (action.operation !== 'list') {
      if (!action.tabId) {
        return { ok: false, error: 'tabId 不能为空' }
      }
      if (!findTab(action.tabId)) {
        return { ok: false, error: `标签页不存在：${action.tabId}` }
      }
      if (action.operation === 'select') {
        await selectTab(action.tabId)
      } else {
        closeTab(action.tabId)
      }
    }
    return { ok: true, tabs: tabInfos(), activeTabId: activeId.value }
  }

  /* 历史导航：后退 / 前进 / 刷新 */
  async function historyActiveTab(action: {
    action: 'back' | 'forward' | 'reload'
  }): Promise<BrowserActionResult> {
    const tab = activeTab.value
    const view = tab ? webviews.get(tab.id) : undefined
    if (!tab) {
      return { ok: false, error: '浏览器尚未就绪' }
    }
    if (action.action === 'back') {
      back()
    } else if (action.action === 'forward') {
      forward()
    } else {
      view?.reload()
    }
    await settlePage(tab.id)
    return {
      ok: true,
      title: tab.title,
      url: tab.currentUrl,
      summary: action.action === 'back' ? '已后退' : action.action === 'forward' ? '已前进' : '已刷新页面'
    }
  }

  /* 统一动作分发：所有 Agent 浏览器工具经此执行 */
  async function executeBrowserAction(action: BrowserAction): Promise<BrowserActionResult> {
    switch (action.type) {
      case 'read':
        return readActiveTab(action)
      case 'snapshot':
        return snapshotActiveTab(action)
      case 'click':
        return clickActiveTab(action)
      case 'fill':
        return fillActiveTab(action)
      case 'wait':
        return waitActiveTab(action)
      case 'screenshot':
        return screenshotActiveTab()
      case 'diagnostics':
        return diagnosticsActiveTab()
      case 'tabs':
        return manageTabs(action)
      case 'history':
        return historyActiveTab(action)
      default:
        return { ok: false, error: '不支持的浏览器动作' }
    }
  }

  /* 面板挂载后注册动作实现（常驻的 RightPanel 负责接收 IPC 动作请求） */
  onMounted(() => {
    setBrowserPanelActionRunner(executeBrowserAction)
  })

  /* 已消费的打开请求序号：避免面板首次挂载时重复打开已处理过的地址 */
  let consumedRequestId = 0

  /*
   * 打开请求：Agent 工具 open_browser 与页面 window.open 统一经布局 store 转入。
   * 面板可能尚未创建（首次打开），故 immediate 兜底消费挂载前的请求。
   */
  watch(
    () => layout.browserOpenRequest,
    (request) => {
      if (!request || request.id === consumedRequestId) {
        return
      }
      consumedRequestId = request.id
      const url = normalizeBrowserUrl(request.url)
      if (url) {
        void openRequested(url, request.newTab)
      }
    },
    { immediate: true }
  )

  /* 卸载：注销读取实现并销毁全部 webview 与缓存 */
  onBeforeUnmount(() => {
    setBrowserPanelActionRunner(null)
    for (const view of webviews.values()) {
      view.remove()
    }
    webviews.clear()
    containers.clear()
    readyTabs.clear()
    pendingNav.clear()
  })

  return {
    tabs,
    activeId,
    activeTab,
    addressInput,
    tabLabel,
    setContainer,
    addTab,
    selectTab,
    closeTab,
    back,
    forward,
    reloadOrStop,
    go,
    openExternal,
    onMore,
    onAddressFocus,
    onAddressBlur
  }
}
