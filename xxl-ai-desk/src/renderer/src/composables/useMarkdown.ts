/* Markdown 渲染 + HTML 消毒 */

import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import { t } from '@renderer/i18n'
import { escapeHtml, highlightCode } from './useHighlight'

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true
})

/* HTML 消毒配置：在默认允许的协议外，额外放行本地图片协议 desk-file */
const PURIFY_OPTIONS = {
  ALLOWED_URI_REGEXP:
    /^(?:(?:https?|desk-file|data|blob|mailto|tel):|[^a-z]|[a-z+.\-]+(?:[^a-z+.\-:]|$))/i
}

/* 单块代码高亮长度上限：超过则跳过着色，避免超大代码块卡顿 */
const MAX_HIGHLIGHT_LENGTH = 100000

/* 复制按钮（GitHub 风格的代码块头部图标） */
const COPY_ICON =
  '<svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">' +
  '<path fill="currentColor" d="M0 6.75C0 5.784.784 5 1.75 5h1.5a.75.75 0 0 1 0 1.5h-1.5a.25.25 0 0 0-.25.25v7.5c0 .138.112.25.25.25h7.5a.25.25 0 0 0 .25-.25v-1.5a.75.75 0 0 1 1.5 0v1.5A1.75 1.75 0 0 1 9.25 16h-7.5A1.75 1.75 0 0 1 0 14.25Z"/>' +
  '<path fill="currentColor" d="M5 1.75C5 .784 5.784 0 6.75 0h7.5C15.216 0 16 .784 16 1.75v7.5A1.75 1.75 0 0 1 14.25 11h-7.5A1.75 1.75 0 0 1 5 9.25Zm1.75-.25a.25.25 0 0 0-.25.25v7.5c0 .138.112.25.25.25h7.5a.25.25 0 0 0 .25-.25v-7.5a.25.25 0 0 0-.25-.25Z"/>' +
  '</svg>'

/*
 * 代码块渲染：GitHub 风格「语言标签 + 复制按钮」头部 + 高亮正文。
 * 复制交互由 useCodeCopy 的事件委托处理。
 */
md.renderer.rules.fence = (tokens, index) => {
  const token = tokens[index]
  const info = token.info ? token.info.trim() : ''
  /* 信息串首词为语言标识（如 ```ts） */
  const language = info.split(/\s+/)[0] ?? ''
  const code = token.content
  /* 超长代码块跳过着色，仅做 HTML 转义 */
  const body =
    code.length > MAX_HIGHLIGHT_LENGTH ? escapeHtml(code) : highlightCode(code, language)
  /* 头部语言标签，未知时展示 text */
  const label = language || 'text'
  return (
    '<div class="code-block">' +
    '<div class="code-block-head">' +
    `<span class="code-lang">${escapeHtml(label)}</span>` +
    `<button class="code-copy" type="button">${COPY_ICON}` +
    `<span class="code-copy-text">${t('common.copy')}</span></button>` +
    '</div>' +
    `<pre class="hljs"><code class="language-${escapeHtml(language)}">${body}</code></pre>` +
    '</div>'
  )
}

/* 本地绝对路径 → 本地文件协议 URL：逐段编码，避免 # ? 等字符截断路径 */
function toLocalFileUrl(path: string): string {
  const normalized = path.replace(/\\/g, '/')
  const absolute = normalized.startsWith('/') ? normalized : `/${normalized}`
  const encoded = absolute
    .split('/')
    .map((segment) => encodeURIComponent(segment))
    .join('/')
  return `desk-file://local${encoded}`
}

/* 图片地址归一化：远程/内联直接使用；本地绝对路径与 file:// 转为本地文件协议；相对路径按基准目录解析 */
function normalizeImageSrc(src: string, baseDir?: string): string {
  const value = src.trim()
  if (!value) {
    return ''
  }
  /* 远程图片与内联数据直接保留 */
  if (/^(?:https?:|data:|blob:)/i.test(value)) {
    return value
  }
  /* 协议相对地址按 https 处理 */
  if (value.startsWith('//')) {
    return `https:${value}`
  }
  /* file:// 与本地绝对路径（POSIX / Windows 盘符 / UNC）统一走本地文件协议 */
  if (/^file:\/\//i.test(value)) {
    return toLocalFileUrl(decodeURIComponent(value.replace(/^file:\/\//i, '')))
  }
  if (value.startsWith('/') || /^[a-zA-Z]:[\\/]/.test(value) || value.startsWith('\\\\')) {
    return toLocalFileUrl(value)
  }
  /* 相对路径：以传入的基准目录（项目根目录 / 当前文件所在目录）解析 */
  if (baseDir) {
    return toLocalFileUrl(`${baseDir.replace(/[\\/]+$/, '')}/${value}`)
  }
  return value
}

/* 图片渲染：归一化地址并补充懒加载，避免长文图片阻塞首屏 */
md.renderer.rules.image = (tokens, index, _options, env) => {
  const token = tokens[index]
  const alt = token.content || ''
  const title = String(token.attrGet('title') ?? '')
  const src = normalizeImageSrc(
    String(token.attrGet('src') ?? ''),
    env?.baseDir as string | undefined
  )
  /* 无可用地址时不渲染，避免出现破图占位 */
  if (!src) {
    return ''
  }
  const titleAttr = title ? ` title="${escapeHtml(title)}"` : ''
  return `<img src="${escapeHtml(src)}" alt="${escapeHtml(alt)}"${titleAttr} loading="lazy">`
}

/* 渲染 Markdown 为消毒后的 HTML，空文本返回空串；baseDir 用于解析相对图片路径 */
export function renderMarkdown(text: string, baseDir?: string): string {
  if (!text) {
    return ''
  }
  return DOMPurify.sanitize(md.render(text, { baseDir }), PURIFY_OPTIONS)
}
