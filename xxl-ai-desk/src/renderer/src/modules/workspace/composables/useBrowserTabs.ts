/*
 * 内嵌浏览器标签页：webview 生命周期、导航历史、地址栏与标签页状态。
 *   每个标签页一个独立 webview（切换用 v-show 保留页面状态与历史）。
 * @author xuxueli 2026-10-25
 */

import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '@renderer/api'
import { useLayoutStore } from '@renderer/stores/layout'
import { t } from '@renderer/i18n'
import type { BrowserReadParams, BrowserReadResult } from '@shared/ipc'
import { normalizeBrowserUrl } from '@shared/browserUrl'

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

/* 工具名 → 空等：读取前等待面板/页面就绪 */
function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/*
 * 面板读取实现注册表：由 BrowserPanel 挂载时写入、卸载时清理。
 * IPC 读取监听放在常驻的 RightPanel，避免「面板刚打开 / open 与 read 同批并行」时漏事件。
 */
let panelReader: ((params: BrowserReadParams) => Promise<BrowserReadResult>) | null = null

/* 注册当前面板的读取实现 */
export function setBrowserPanelReader(reader: typeof panelReader): void {
  panelReader = reader
}

/* 等待面板就绪后读取（面板尚未挂载时短暂等待，覆盖首次打开场景） */
export async function readBrowserFromPanel(
  params: BrowserReadParams,
  timeout = 6000
): Promise<BrowserReadResult> {
  const started = Date.now()
  while (!panelReader && Date.now() - started < timeout) {
    await sleep(100)
  }
  if (!panelReader) {
    return { ok: false, error: '右侧浏览器尚未就绪，请稍后重试' }
  }
  return panelReader(params)
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
      }
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

  /*
   * 读取当前标签页正文：注入脚本提取标题、地址与可见文本。
   * 选择器由参数序列化后内联，避免拼接注入；返回结果按 maxChars 截断。
   */
  async function readActiveTab(params: BrowserReadParams): Promise<BrowserReadResult> {
    const started = Date.now()
    /* 先等标签页创建 + 导航目标写入（open_browser 的请求由 IPC 异步转达） */
    let tab = activeTab.value
    let view = tab ? webviews.get(tab.id) : undefined
    while ((!tab?.hasPage || !view) && Date.now() - started < 12000) {
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
    await waitForPageReady(tab, view, Math.max(1000, 12000 - (Date.now() - started)))
    const max = Math.min(Math.max(Math.floor(params.maxChars ?? 20000), 1000), 50000)
    const selector = params.selector ? JSON.stringify(params.selector) : 'null'
    const script = `(() => {
      const selector = ${selector};
      const root = selector ? document.querySelector(selector) : (document.body || document.documentElement);
      if (!root) {
        return { title: document.title, url: location.href, missing: true };
      }
      return {
        title: document.title,
        url: location.href,
        text: (root.innerText || root.textContent || '')
      };
    })()`
    try {
      const raw = (await view.executeJavaScript(script)) as {
        title?: string
        url?: string
        text?: string
        missing?: boolean
      }
      if (raw?.missing) {
        return { ok: false, error: `未找到选择器匹配的元素：${params.selector ?? ''}` }
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

  /* 面板挂载后注册读取实现（常驻的 RightPanel 负责接收 IPC 读取请求） */
  onMounted(() => {
    setBrowserPanelReader(readActiveTab)
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
    setBrowserPanelReader(null)
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
