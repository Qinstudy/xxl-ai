/*
 * MCP.so 社区适配器：在线检索 MCP 服务列表与详情。
 *   - 站点为服务端渲染 HTML，使用 cheerio 解析卡片与 pre 配置片段；
 *   - 仅访问 mcp.so 固定路径，slug 白名单校验，避免任意 URL 抓取。
 */

import { load } from 'cheerio'
import type {
  McpCategory,
  McpDiscoverDetail,
  McpDiscoverItem,
  McpDiscoverPage
} from '@shared/ipc'

/* 桌面端 UA：与站点普通浏览器请求保持一致 */
const USER_AGENT =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36'

/* 站点根地址（中文站） */
const BASE_URL = 'https://mcp.so'

/* 请求超时（毫秒） */
const TIMEOUT = 15000

/* 单页最大条目 */
const PAGE_SIZE = 24

/* 抓取 HTML（超时中断，失败抛出可读错误） */
async function fetchHtml(url: string): Promise<string> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), TIMEOUT)
  try {
    const response = await fetch(url, {
      signal: controller.signal,
      headers: {
        'User-Agent': USER_AGENT,
        'Accept-Language': 'zh-CN,zh;q=0.9',
        Accept: 'text/html,application/xhtml+xml'
      }
    })
    if (!response.ok) {
      throw new Error(`社区访问失败（HTTP ${response.status}）`)
    }
    return await response.text()
  } catch (error) {
    if ((error as Error).name === 'AbortError') {
      throw new Error('社区访问超时，请稍后重试')
    }
    throw error as Error
  } finally {
    clearTimeout(timer)
  }
}

/* 文本归一化：压缩空白 */
function compact(value: string | undefined): string {
  return (value ?? '').replace(/\s+/g, ' ').trim()
}

/* 卡片节点 → 条目 */
function toItem(
  slug: string,
  name: string,
  description: string,
  author: string,
  category: string,
  logo: string,
  remote: boolean,
  stars: number
): McpDiscoverItem {
  return {
    source: 'mcpso',
    slug,
    name,
    description,
    author,
    category,
    remote,
    stars,
    detailUrl: `${BASE_URL}/zh/servers/${slug}`,
    logo
  }
}

/* 星标数解析：2.5K / 1.2k / 3M 转数字 */
function parseCount(value: string): number {
  const matched = value.trim().match(/^([\d.]+)\s*([KkMm]?)$/)
  if (!matched) {
    return 0
  }
  const base = Number(matched[1])
  if (!Number.isFinite(base)) {
    return 0
  }
  const unit = matched[2].toLowerCase()
  if (unit === 'k') {
    return Math.round(base * 1000)
  }
  if (unit === 'm') {
    return Math.round(base * 1000000)
  }
  return Math.round(base)
}

/* 解析页面卡片为条目（并计算是否有下一页） */
function parseCards(html: string, page: number): { items: McpDiscoverItem[]; hasMore: boolean } {
  const $ = load(html)
  const items: McpDiscoverItem[] = []
  const seen = new Set<string>()
  $('a[href^="/zh/servers/"]').each((_index, element) => {
    if (items.length >= PAGE_SIZE) {
      return
    }
    const card = $(element)
    const name = compact(card.find('h3').first().text())
    /* 仅卡片节点带 h3 标题，排除侧栏「热门 Servers」纯文本链接 */
    if (!name) {
      return
    }
    const href = card.attr('href') ?? ''
    const slug = href.replace('/zh/servers/', '').replace(/[/?#].*$/, '')
    if (!slug || seen.has(slug)) {
      return
    }
    seen.add(slug)

    const author = compact(card.find('h3').first().parent().next('p').text())
    const description = compact(card.find('p.line-clamp-2').first().text())
    const category = compact(card.find('span.bg-secondary').last().text())
    const logo = card.find('img').first().attr('src') ?? ''
    const text = compact(card.text())
    /* 星标数：星形图标所在行文本（如 2.5K / 226） */
    const stars = parseCount(compact(card.find('svg.lucide-star').first().parent().text()))
    items.push(toItem(slug, name, description, author, category, logo, /远程/.test(text), stars))
  })

  /* 是否还有下一页：页面存在比当前页更大的 page 链接 */
  let maxPage = page
  $('a[href*="page="]').each((_index, element) => {
    const href = $(element).attr('href') ?? ''
    const matched = href.match(/[?&]page=(\d+)/)
    if (matched) {
      maxPage = Math.max(maxPage, Number(matched[1]))
    }
  })

  return { items, hasMore: maxPage > page && items.length > 0 }
}

/* 检索列表：关键字为空时浏览社区全部服务 */
export async function searchMcpSo(keyword: string, page: number): Promise<McpDiscoverPage> {
  const key = keyword.trim()
  const query = new URLSearchParams()
  if (key) {
    query.set('q', key)
  }
  query.set('page', String(page))
  const url = `${BASE_URL}/zh/${key ? 'search' : 'servers'}?${query.toString()}`
  const { items, hasMore } = parseCards(await fetchHtml(url), page)
  return { source: 'mcpso', items, page, hasMore, cached: false }
}

/* 按分类浏览（复用服务列表布局以拿到 Star 数；slug 白名单校验） */
export async function browseCategoryMcpSo(slug: string, page: number): Promise<McpDiscoverPage> {
  if (!/^[a-zA-Z0-9-]+$/.test(slug)) {
    throw new Error('非法的分类标识')
  }
  const url = `${BASE_URL}/zh/servers?category=${encodeURIComponent(slug)}&page=${page}`
  const { items, hasMore } = parseCards(await fetchHtml(url), page)
  return { source: 'mcpso', items, page, hasMore, cached: false }
}

/* 分类列表：仅取「MCP 服务器分类」 */
export async function listMcpSoCategories(): Promise<McpCategory[]> {
  const html = await fetchHtml(`${BASE_URL}/zh/categories`)
  const $ = load(html)
  const categories: McpCategory[] = []
  const seen = new Set<string>()
  $('a[href^="/zh/categories/"]').each((_index, element) => {
    const anchor = $(element)
    const href = anchor.attr('href') ?? ''
    /* 仅服务器分类（客户端分类标记为 type=clients） */
    if (!href.includes('type=servers')) {
      return
    }
    const matched = href.match(/\/zh\/categories\/([a-zA-Z0-9-]+)/)
    const label = compact(anchor.find('h3').first().text())
    if (!matched || !label || seen.has(matched[1])) {
      return
    }
    seen.add(matched[1])
    const count = Number(compact(anchor.find('div.text-muted-foreground span').first().text())) || 0
    categories.push({ slug: matched[1], label, count })
  })
  return categories
}

/* 详情：简介 + 可导入配置片段 */
export async function detailMcpSo(slug: string): Promise<McpDiscoverDetail> {
  if (!/^[a-zA-Z0-9._-]+$/.test(slug)) {
    throw new Error('非法的 MCP 标识')
  }
  const url = `${BASE_URL}/zh/servers/${slug}`
  const html = await fetchHtml(url)
  const $ = load(html)

  const rawName = compact($('h1').first().text()) || slug
  /* 去掉标题后缀标记（如「已精选」「已验证」） */
  const name = rawName.replace(/(已精选|已验证|Featured|Verified).*$/i, '').trim() || slug
  const description =
    compact($('meta[property="og:description"]').attr('content')) ||
    compact($('meta[name="description"]').attr('content'))

  /* 配置片段：pre 内含 mcpServers JSON 或安装命令，最多保留 6 条 */
  const snippets: string[] = []
  $('pre').each((_index, element) => {
    if (snippets.length >= 6) {
      return
    }
    const text = $(element).text().trim()
    if (!text || text.length > 4000) {
      return
    }
    if (!snippets.includes(text)) {
      snippets.push(text)
    }
  })

  /* README 摘要：正文纯文本压缩（截断避免跨进程负载过大） */
  const scope = $('main').length > 0 ? $('main') : $('body')
  const readme = compact(scope.text()).slice(0, 3000)
  const text = compact($('body').text())
  const stars = parseCount(compact($('svg.lucide-star').first().parent().text()))

  return {
    source: 'mcpso',
    slug,
    name,
    description,
    author: '',
    category: '',
    remote: /远程 MCP|Remote MCP/i.test(text),
    stars,
    detailUrl: url,
    logo: $('meta[property="og:image"]').attr('content') ?? '',
    readme,
    snippets
  }
}
