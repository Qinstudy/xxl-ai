/*
 * 本地文件协议（desk-file）：供渲染层以 URL 加载项目目录内的本地文件（Markdown 图片等）。
 *   - 协议需在 app ready 前注册，本模块被 import 时立即执行；
 *   - 仅放行已登记项目目录内的文件，越界请求返回 403。
 * @author xuxueli 2026-10-25
 */

import { net, protocol } from 'electron'
import { normalize } from 'path'
import { isWithinRoot } from '../utils/pathGuard'
import { pathToFileURL } from 'url'
import { listProjects } from './projectService'

/* 协议名：与渲染层拼接 URL、CSP 白名单保持一致 */
export const LOCAL_FILE_SCHEME = 'desk-file'

/* 注册协议特性（必须在 app ready 前执行）：标准 URL 解析 + 安全来源 + 流式响应 */
protocol.registerSchemesAsPrivileged([
  {
    scheme: LOCAL_FILE_SCHEME,
    privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true }
  }
])

/* 目标路径是否位于某个已登记项目目录内（含软链接归一化，防路径穿越） */
function isInsideProject(target: string): boolean {
  return listProjects().some((item) => isWithinRoot(item.path, target))
}

/* 将 URL 中的路径还原为系统路径：解码失败返回空串，Windows 需去掉盘符前的斜杠 */
function resolveRequestPath(url: string): string {
  let decoded = ''
  try {
    decoded = decodeURIComponent(new URL(url).pathname)
  } catch {
    return ''
  }
  const stripped =
    process.platform === 'win32' && /^\/[a-zA-Z]:/.test(decoded) ? decoded.slice(1) : decoded
  return normalize(stripped)
}

/* 注册协议处理（app ready 后调用）：路径校验通过后以文件流返回 */
export function registerLocalFileProtocol(): void {
  protocol.handle(LOCAL_FILE_SCHEME, (request) => {
    const target = resolveRequestPath(request.url)
    if (!target) {
      return new Response('Bad Request', { status: 400 })
    }
    if (!isInsideProject(target)) {
      return new Response('Forbidden', { status: 403 })
    }
    return net.fetch(pathToFileURL(target).toString())
  })
}
