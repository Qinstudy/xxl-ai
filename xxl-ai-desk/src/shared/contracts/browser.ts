/*
 * 内嵌浏览器契约：Agent 工具与右侧浏览器面板之间的读取请求 / 结果。
 *   - 主进程把读取请求转发渲染层，由当前标签页 webview 执行脚本提取正文；
 *   - 结果统一以 ok/error 表达，避免跨进程异常丢失。
 */

/* 读取网页参数 */
export interface BrowserReadParams {
  /* CSS 选择器（缺省读取 document.body） */
  selector?: string
  /* 返回正文字符上限（渲染层会夹取到安全区间） */
  maxChars?: number
}

/* 读取网页结果 */
export interface BrowserReadResult {
  /* 是否读取成功 */
  ok: boolean
  /* 页面标题 */
  title?: string
  /* 当前地址 */
  url?: string
  /* 提取到的正文 */
  text?: string
  /* 是否因超过上限被截断 */
  truncated?: boolean
  /* 失败原因（ok=false 时） */
  error?: string
}
