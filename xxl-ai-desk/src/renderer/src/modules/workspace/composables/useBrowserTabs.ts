/*
 * 内嵌浏览器标签页：webview 生命周期、导航历史、地址栏与标签页状态。
 *   每个标签页一个独立 webview（切换用 v-show 保留页面状态与历史）。
 * @author xuxueli 2026-10-25
 */

import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '@renderer/api'
import { t } from '@renderer/i18n'

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

/* 规范化地址：完整 URL 直用；形如域名补 https；其余走搜索引擎 */
function normalize(raw: string): string {
  const value = raw.trim()
  if (!value) {
    return ''
  }
  if (/^https?:\/\//i.test(value)) {
    return value
  }
  if (
    /^localhost(:\d+)?(\/.*)?$/i.test(value) ||
    /^\d{1,3}(\.\d{1,3}){3}(:\d+)?(\/.*)?$/.test(value)
  ) {
    return `http://${value}`
  }
  if (/^[\w-]+(\.[\w-]+)+(:\d+)?(\/.*)?$/.test(value)) {
    return `https://${value}`
  }
  return `https://www.bing.com/search?q=${encodeURIComponent(value)}`
}

export function useBrowserTabs() {
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
    const url = normalize(tab.input)
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

  let offOpenTab: (() => void) | null = null

  /* 挂载：监听主进程转发的页面内新窗口请求 */
  onMounted(() => {
    offOpenTab = api.browser.onOpenTab((url) => {
      void addTab(url)
    })
  })

  /* 卸载：取消监听并销毁全部 webview 与缓存 */
  onBeforeUnmount(() => {
    offOpenTab?.()
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
