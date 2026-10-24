/*
 * 浏览器读取桥（主进程侧）：把运行时进程的读取请求转发渲染层 webview，并等待结果回传。
 *   - 主进程只做请求配对与超时兜底，页面提取脚本由渲染层执行；
 *   - 窗口缺失或渲染层无响应时返回可读错误，避免工具调用永久挂起。
 */

import type { BrowserReadParams, BrowserReadResult } from '@shared/ipc'

/* 读取超时（毫秒）：页面加载等待 + 脚本执行，留出余量 */
const READ_TIMEOUT = 20000

/* 渲染层发送函数（由 IPC 网关注入） */
type ReadSender = (requestId: string, params: BrowserReadParams) => void
let sender: ReadSender | null = null

/* 待响应请求：requestId → 结算与超时定时器 */
const pending = new Map<
  string,
  { resolve: (result: BrowserReadResult) => void; timer: ReturnType<typeof setTimeout> }
>()

/* 注册渲染层发送函数（IPC 网关启动时注入） */
export function setBrowserActionSender(value: ReadSender): void {
  sender = value
}

/* 回传渲染层的读取结果（由 IPC 网关调用） */
export function resolveBrowserRead(requestId: string, result: BrowserReadResult): void {
  const entry = pending.get(requestId)
  if (!entry) {
    return
  }
  clearTimeout(entry.timer)
  pending.delete(requestId)
  entry.resolve(result)
}

/* 请求读取当前页正文；无窗口/超时统一转为失败结果 */
export function requestBrowserRead(params: BrowserReadParams): Promise<BrowserReadResult> {
  const send = sender
  if (!send) {
    return Promise.resolve({ ok: false, error: '浏览器不可用：窗口尚未就绪' })
  }
  const requestId = `br_${Date.now().toString(36)}_${Math.random().toString(36).slice(2)}`
  return new Promise<BrowserReadResult>((resolve) => {
    const timer = setTimeout(() => {
      pending.delete(requestId)
      resolve({ ok: false, error: '读取网页超时：请确认右侧浏览器已打开页面后重试' })
    }, READ_TIMEOUT)
    pending.set(requestId, { resolve, timer })
    send(requestId, params)
  })
}

/* 清空全部待响应请求（窗口销毁/退出时兜底） */
export function disposeBrowserReads(): void {
  for (const entry of pending.values()) {
    clearTimeout(entry.timer)
    entry.resolve({ ok: false, error: '窗口已关闭，读取取消' })
  }
  pending.clear()
}
