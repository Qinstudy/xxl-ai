/*
 * 会话级浏览器标签页管理：标签状态按会话隔离，webview 由 workspace 的 BrowserHost 承载。
 *   - 同一会话复用一套标签页与 webview，跨会话互不影响；
 *   - 后台会话保留 warm 宿主（上限 3，LRU），Agent 动作可在后台静默执行；
 *   - 宿主被淘汰时仅销毁 webview，标签页与地址保留，按需重建。
 * @author xuxueli 2026-10-25
 */

import { computed, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@renderer/api'
import { t } from '@renderer/i18n'
import { createBrowserTab, useBrowserSessionStore, type BrowserTab } from '@renderer/stores/browserSession'
import { normalizeBrowserUrl } from '@shared/browserUrl'
import { enqueueSessionTask } from './browserActionQueue'
import {
  getSessionHost,
  hasSessionHost,
  markWarm,
  registerSessionHostHooks,
  removeWarmSession
} from './browserSessionHost'
import {
  buildClickScript,
  buildFillScript,
  buildProbeScript,
  buildReadScript,
  buildSnapshotScript
} from '../utils/browserScripts'
import type {
  BrowserAction,
  BrowserActionResult,
  BrowserConsoleEntry,
  BrowserFillField,
  BrowserSnapshotNode,
  BrowserTabInfo
} from '@shared/ipc'

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
  remove(): void
  capturePage(): Promise<{ getSize(): { width: number; height: number }; toDataURL(): string }>
  executeJavaScript(code: string): Promise<unknown>
}

/* 事件负载 */
interface FailLoadEvent extends Event {
  errorCode: number
}
interface NavigateEvent extends Event {
  url: string
}
interface TitleEvent extends Event {
  title: string
}
interface ConsoleMessageEvent extends Event {
  level: number
  message: string
  sourceId?: string
  line?: number
}

/* 单会话浏览器标签上限 */
const MAX_TABS = 8
/* 单标签页诊断上限，避免长期运行无限增长 */
const MAX_DIAGNOSTICS = 100

/* ============ 运行时资源（模块级单例） ============ */

/* 标签页 ID → webview 元素 */
const webviews = new Map<string, WebviewElement>()
/* guest 是否已就绪 */
const readyTabs = new Set<string>()
/* guest 未就绪时暂存的待导航地址（dom-ready 后再 loadURL） */
const pendingNav = new Map<string, string>()
/* 控制台告警/错误与加载失败（供 Agent 诊断，按标签页维护） */
const consoleLogs = new Map<string, BrowserConsoleEntry[]>()
const failedLoads = new Map<string, string[]>()
/* 地址栏聚焦时不覆盖用户正在编辑的输入 */
let inputFocused = false

/* 浏览器状态 store */
function browserStore() {
  return useBrowserSessionStore()
}

/* 取某会话的标签状态 */
function stateOf(sessionId: string) {
  return browserStore().sliceOf(sessionId)
}

/* 当前激活标签页（兜底取首个） */
function activeTabOf(state: ReturnType<typeof stateOf>): BrowserTab | undefined {
  return state.tabs.find((tab) => tab.id === state.activeId) ?? state.tabs[0]
}

/* 按 id 查找标签页 */
function findTab(sessionId: string, id: string): BrowserTab | undefined {
  return stateOf(sessionId).tabs.find((tab) => tab.id === id)
}

/* 简单延时 */
function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/* ============ 宿主与生命周期 ============ */

/* 应用标签页可见性：仅激活标签展示，其余保留 webview 但隐藏 */
function applyVisibility(sessionId: string): void {
  const state = stateOf(sessionId)
  for (const tab of state.tabs) {
    const view = webviews.get(tab.id)
    if (view) {
      view.style.display = tab.id === state.activeId ? 'flex' : 'none'
    }
  }
}

/* 销毁某会话的全部 webview（保留标签页状态，供再次使用） */
function disposeSessionWebviews(sessionId: string): void {
  const state = browserStore().sessions[sessionId]
  if (!state) {
    return
  }
  for (const tab of state.tabs) {
    const view = webviews.get(tab.id)
    if (view) {
      view.remove()
      webviews.delete(tab.id)
    }
    readyTabs.delete(tab.id)
    pendingNav.delete(tab.id)
  }
}

/* 确保会话浏览器状态与 warm 宿主存在 */
export function ensureBrowserSession(sessionId: string): void {
  if (!sessionId) {
    return
  }
  browserStore().ensure(sessionId)
  browserStore().touch(sessionId)
  markWarm(sessionId)
  if (hasSessionHost(sessionId)) {
    syncSessionWebviews(sessionId)
  }
}

/* 为会话内所有标签创建 webview（宿主就绪时调用） */
function syncSessionWebviews(sessionId: string): void {
  const state = stateOf(sessionId)
  for (const tab of state.tabs) {
    ensureWebview(tab.id, sessionId)
  }
  applyVisibility(sessionId)
}

/* 宿主层回调：宿主就绪时恢复 webview，淘汰时销毁 webview */
registerSessionHostHooks({
  onHostReady: (sessionId) => ensureBrowserSession(sessionId),
  onEvict: (sessionId) => disposeSessionWebviews(sessionId)
})

/* 释放会话浏览器资源（删除会话时调用） */
export function disposeBrowserSession(sessionId: string): void {
  disposeSessionWebviews(sessionId)
  browserStore().remove(sessionId)
  removeWarmSession(sessionId)
}

/* 清理不在有效会话列表内的浏览器状态（项目级联删除后调用） */
export function cleanupBrowserSessions(validSessionIds: string[]): void {
  const valid = new Set(validSessionIds)
  for (const sessionId of Object.keys(browserStore().sessions)) {
    if (!valid.has(sessionId)) {
      disposeBrowserSession(sessionId)
    }
  }
  browserStore().cleanup(validSessionIds)
}

/* ============ webview 创建与导航 ============ */

/* 同步前进/后退可用态 */
function syncNav(sessionId: string, id: string): void {
  const tab = findTab(sessionId, id)
  const view = webviews.get(id)
  if (tab && view) {
    tab.canBack = view.canGoBack()
    tab.canForward = view.canGoForward()
  }
}

/* 导航完成：同步地址栏与前进/后退态（忽略初始 about:blank） */
function onNavigated(sessionId: string, id: string, url: string | undefined): void {
  const tab = findTab(sessionId, id)
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
    const activeId = stateOf(sessionId).activeId
    if (!(activeId === id && inputFocused)) {
      tab.input = url
    }
  }
  syncNav(sessionId, id)
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
async function navigate(sessionId: string, id: string, url: string): Promise<void> {
  const view = ensureWebview(id, sessionId)
  const tab = findTab(sessionId, id)
  if (tab) {
    tab.hasPage = true
    /* 立即记录目标地址，标签名/「外部打开」无需等待 did-navigate */
    tab.currentUrl = url
  }
  if (!view) {
    /* 宿主尚未挂载：排队，宿主就绪后由 dom-ready 消费 */
    pendingNav.set(id, url)
    return
  }
  if (readyTabs.has(id)) {
    await loadUrl(id, url)
  } else {
    pendingNav.set(id, url)
  }
}

/* 创建标签页的 webview（懒创建、幂等） */
function ensureWebview(id: string, sessionId: string): WebviewElement | null {
  const existing = webviews.get(id)
  if (existing) {
    return existing
  }
  const host = getSessionHost(sessionId)
  if (!host) {
    return null
  }
  const tab = findTab(sessionId, id)
  if (!tab) {
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
    const current = findTab(sessionId, id)
    if (current) {
      current.loading = true
    }
  })
  el.addEventListener('did-stop-loading', () => {
    const current = findTab(sessionId, id)
    if (current) {
      current.loading = false
      syncNav(sessionId, id)
    }
  })
  el.addEventListener('did-fail-load', (event) => {
    /* -3 为用户主动中断，不视为失败 */
    if ((event as FailLoadEvent).errorCode !== -3) {
      const current = findTab(sessionId, id)
      if (current) {
        current.loading = false
      }
      const fail = event as FailLoadEvent & { validatedURL?: string; errorDescription?: string }
      const url = fail.validatedURL || current?.currentUrl || ''
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
    onNavigated(sessionId, id, (event as NavigateEvent).url)
  })
  el.addEventListener('did-navigate-in-page', (event) => {
    onNavigated(sessionId, id, (event as NavigateEvent).url)
  })
  el.addEventListener('page-title-updated', (event) => {
    const current = findTab(sessionId, id)
    const title = (event as TitleEvent).title
    if (current && title) {
      current.title = title
    }
  })
  host.appendChild(el)
  webviews.set(id, el)
  /* 恢复该标签页上次停留的地址（宿主被淘汰重建后的还原） */
  if (tab.hasPage && tab.currentUrl) {
    pendingNav.set(id, tab.currentUrl)
  }
  return el
}

/* ============ 页面动作执行 ============ */

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
  sessionId: string,
  timeout = 12000
): Promise<{ tab: BrowserTab; view: WebviewElement } | BrowserActionResult> {
  const started = Date.now()
  const state = stateOf(sessionId)
  /* 先等标签页创建 + 宿主挂载 + 导航目标写入（open_browser 的请求由 IPC 异步转达） */
  let tab = activeTabOf(state)
  let view = tab ? webviews.get(tab.id) : undefined
  while ((!tab?.hasPage || !view) && Date.now() - started < timeout) {
    await sleep(120)
    tab = activeTabOf(stateOf(sessionId))
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
async function settlePage(sessionId: string, tabId: string, quietMs = 250): Promise<void> {
  const started = Date.now()
  while (Date.now() - started < 3000) {
    const tab = findTab(sessionId, tabId)
    if (!tab?.loading) {
      break
    }
    await sleep(100)
  }
  await sleep(quietMs)
}

/* 读取当前标签页正文：注入脚本提取标题、地址与可见文本 */
async function readActiveTab(
  sessionId: string,
  action: { selector?: string; maxChars?: number }
): Promise<BrowserActionResult> {
  const page = await resolveActivePage(sessionId)
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
async function snapshotActiveTab(
  sessionId: string,
  action: { maxNodes?: number }
): Promise<BrowserActionResult> {
  const page = await resolveActivePage(sessionId)
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
async function clickActiveTab(
  sessionId: string,
  action: { ref?: string; selector?: string; button?: 'left' | 'right'; clickCount?: number }
): Promise<BrowserActionResult> {
  const page = await resolveActivePage(sessionId)
  if ('ok' in page) {
    return page
  }
  const { tab, view } = page
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
  await settlePage(sessionId, tab.id)
  return {
    ok: true,
    title: tab.title,
    url: tab.currentUrl,
    summary: `已${button === 'right' ? '右键点击' : '点击'}${action.ref || action.selector || '目标元素'}`
  }
}

/* 填写表单：支持文本、下拉、复选/单选与 contenteditable，可选择提交 */
async function fillActiveTab(
  sessionId: string,
  action: { fields: BrowserFillField[]; submit?: boolean }
): Promise<BrowserActionResult> {
  const page = await resolveActivePage(sessionId)
  if ('ok' in page) {
    return page
  }
  const { tab, view } = page
  if (!action.fields || action.fields.length === 0) {
    return { ok: false, error: 'fields 不能为空' }
  }
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
    await settlePage(sessionId, tab.id)
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
async function waitActiveTab(
  sessionId: string,
  action: { selector?: string; text?: string; url?: string; timeoutMs?: number }
): Promise<BrowserActionResult> {
  const page = await resolveActivePage(sessionId)
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
async function screenshotActiveTab(sessionId: string): Promise<BrowserActionResult> {
  const page = await resolveActivePage(sessionId)
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
function diagnosticsActiveTab(sessionId: string): BrowserActionResult {
  const state = stateOf(sessionId)
  const tab = activeTabOf(state)
  return {
    ok: true,
    title: tab?.title ?? '',
    url: tab?.currentUrl ?? '',
    console: tab ? (consoleLogs.get(tab.id) ?? []) : [],
    failedLoads: tab ? (failedLoads.get(tab.id) ?? []) : []
  }
}

/* 标签页列表（含当前激活项） */
function tabInfos(sessionId: string): BrowserTabInfo[] {
  const state = stateOf(sessionId)
  return state.tabs.map((tab) => ({
    id: tab.id,
    title: tabLabel(tab),
    url: tab.currentUrl,
    active: tab.id === state.activeId
  }))
}

/* 标签页显示名：优先页面标题，其次主机名，最后「新标签页」 */
export function tabLabel(tab: BrowserTab): string {
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

/* 标签页管理：列出 / 切换 / 关闭 */
async function manageTabs(
  sessionId: string,
  action: { operation: 'list' | 'select' | 'close'; tabId?: string }
): Promise<BrowserActionResult> {
  if (action.operation !== 'list') {
    if (!action.tabId) {
      return { ok: false, error: 'tabId 不能为空' }
    }
    if (!findTab(sessionId, action.tabId)) {
      return { ok: false, error: `标签页不存在：${action.tabId}` }
    }
    if (action.operation === 'select') {
      await selectTab(sessionId, action.tabId)
    } else {
      closeTab(sessionId, action.tabId)
    }
  }
  return {
    ok: true,
    tabs: tabInfos(sessionId),
    activeTabId: stateOf(sessionId).activeId
  }
}

/* 历史导航：后退 / 前进 / 刷新 */
async function historyActiveTab(
  sessionId: string,
  action: { action: 'back' | 'forward' | 'reload' }
): Promise<BrowserActionResult> {
  const state = stateOf(sessionId)
  const tab = activeTabOf(state)
  const view = tab ? webviews.get(tab.id) : undefined
  if (!tab) {
    return { ok: false, error: '浏览器尚未就绪' }
  }
  if (action.action === 'back') {
    if (view?.canGoBack()) {
      view.goBack()
    }
  } else if (action.action === 'forward') {
    if (view?.canGoForward()) {
      view.goForward()
    }
  } else {
    view?.reload()
  }
  await settlePage(sessionId, tab.id)
  return {
    ok: true,
    title: tab.title,
    url: tab.currentUrl,
    summary: action.action === 'back' ? '已后退' : action.action === 'forward' ? '已前进' : '已刷新页面'
  }
}

/* 统一动作分发：所有 Agent 浏览器工具经此执行（按会话路由、会话内串行） */
export async function executeBrowserAction(
  sessionId: string,
  action: BrowserAction
): Promise<BrowserActionResult> {
  if (!sessionId) {
    return { ok: false, error: '缺少会话标识，无法定位浏览器' }
  }
  return enqueueSessionTask(sessionId, () => runBrowserAction(sessionId, action))
}

/* 执行单个浏览器动作（内部实现） */
async function runBrowserAction(
  sessionId: string,
  action: BrowserAction
): Promise<BrowserActionResult> {
  ensureBrowserSession(sessionId)
  switch (action.type) {
    case 'read':
      return readActiveTab(sessionId, action)
    case 'snapshot':
      return snapshotActiveTab(sessionId, action)
    case 'click':
      return clickActiveTab(sessionId, action)
    case 'fill':
      return fillActiveTab(sessionId, action)
    case 'wait':
      return waitActiveTab(sessionId, action)
    case 'screenshot':
      return screenshotActiveTab(sessionId)
    case 'diagnostics':
      return diagnosticsActiveTab(sessionId)
    case 'tabs':
      return manageTabs(sessionId, action)
    case 'history':
      return historyActiveTab(sessionId, action)
    default:
      return { ok: false, error: '不支持的浏览器动作' }
  }
}

/* ============ 面板操作（会话绑定） ============ */

/* 新建标签页（可带初始 URL），并激活；达到上限时复用当前标签页 */
async function addTab(sessionId: string, url = ''): Promise<void> {
  const state = stateOf(sessionId)
  if (state.tabs.length >= MAX_TABS) {
    ElMessage.warning(t('panel.tabLimit'))
    const current = activeTabOf(state)
    if (current && url) {
      state.activeId = current.id
      await navigate(sessionId, current.id, url)
    }
    return
  }
  const tab = createBrowserTab(url)
  state.tabs.push(tab)
  state.activeId = tab.id
  ensureBrowserSession(sessionId)
  await nextTick()
  ensureWebview(tab.id, sessionId)
  applyVisibility(sessionId)
  if (url) {
    await navigate(sessionId, tab.id, url)
  } else {
    webviews.get(tab.id)?.focus()
  }
}

/* 切换标签页 */
async function selectTab(sessionId: string, id: string): Promise<void> {
  stateOf(sessionId).activeId = id
  applyVisibility(sessionId)
  await nextTick()
  syncNav(sessionId, id)
}

/* 关闭标签页；始终保留至少一个 */
function closeTab(sessionId: string, id: string): void {
  const state = stateOf(sessionId)
  const index = state.tabs.findIndex((tab) => tab.id === id)
  if (index < 0) {
    return
  }
  webviews.get(id)?.remove()
  webviews.delete(id)
  readyTabs.delete(id)
  pendingNav.delete(id)
  consoleLogs.delete(id)
  failedLoads.delete(id)
  state.tabs.splice(index, 1)
  if (state.tabs.length === 0) {
    const tab = createBrowserTab()
    state.tabs.push(tab)
    state.activeId = tab.id
    ensureWebview(tab.id, sessionId)
  } else if (state.activeId === id) {
    state.activeId = state.tabs[Math.min(index, state.tabs.length - 1)].id
  }
  applyVisibility(sessionId)
}

/* 打开外部请求的地址 */
async function openRequested(sessionId: string, url: string, newTab: boolean): Promise<void> {
  ensureBrowserSession(sessionId)
  const state = stateOf(sessionId)
  if (newTab) {
    await addTab(sessionId, url)
    return
  }
  const blank = state.tabs.find((tab) => !tab.hasPage && !tab.currentUrl)
  if (blank) {
    state.activeId = blank.id
    applyVisibility(sessionId)
    await nextTick()
    await navigate(sessionId, blank.id, url)
    return
  }
  const current = activeTabOf(state)
  if (current) {
    await navigate(sessionId, current.id, url)
    return
  }
  await addTab(sessionId, url)
}

/* 记录浏览器打开请求（Agent / 页面 window.open）：仅入对应会话，不切换视图 */
export function openBrowserRequest(sessionId: string, url: string, newTab: boolean): void {
  const value = normalizeBrowserUrl(url)
  if (!value || !sessionId) {
    return
  }
  ensureBrowserSession(sessionId)
  void openRequested(sessionId, value, newTab)
}

/* 浏览器面板使用的会话绑定 API */
export function useBrowserPanel(sessionId: string) {
  ensureBrowserSession(sessionId)

  const state = computed(() => stateOf(sessionId))
  const tabs = computed(() => state.value.tabs)
  const activeId = computed(() => state.value.activeId)
  const activeTab = computed(() => activeTabOf(state.value))

  /* 地址栏文本双向绑定到当前标签页 */
  const addressInput = computed({
    get: () => activeTab.value?.input ?? '',
    set: (value: string) => {
      if (activeTab.value) {
        activeTab.value.input = value
      }
    }
  })

  /* 导航到地址栏地址 */
  async function go(): Promise<void> {
    const tab = activeTabOf(state.value)
    if (!tab) {
      return
    }
    const url = normalizeBrowserUrl(tab.input)
    if (!url) {
      return
    }
    await navigate(sessionId, tab.id, url)
  }

  /* 后退 */
  function back(): void {
    const view = webviews.get(state.value.activeId)
    if (view?.canGoBack()) {
      view.goBack()
    }
  }

  /* 前进 */
  function forward(): void {
    const view = webviews.get(state.value.activeId)
    if (view?.canGoForward()) {
      view.goForward()
    }
  }

  /* 刷新 / 停止 */
  function reloadOrStop(): void {
    const view = webviews.get(state.value.activeId)
    if (!view) {
      void go()
      return
    }
    if (activeTabOf(state.value)?.loading) {
      view.stop()
    } else {
      view.reload()
    }
  }

  /* 在系统浏览器中打开当前页 */
  function openExternal(): void {
    const url = activeTabOf(state.value)?.currentUrl
    if (url) {
      void api.shell.openExternal(url)
    }
  }

  /* 更多操作分发 */
  function onMore(command: string): void {
    if (command === 'external') {
      openExternal()
    } else if (command === 'reload') {
      webviews.get(state.value.activeId)?.reload()
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

  return {
    tabs,
    activeId,
    activeTab,
    addressInput,
    tabLabel,
    addTab: (url = '') => addTab(sessionId, url),
    selectTab: (id: string) => selectTab(sessionId, id),
    closeTab: (id: string) => closeTab(sessionId, id),
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
