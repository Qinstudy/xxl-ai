/*
 * 发现 MCP 业务：在线检索社区 MCP 信息（当前接入 mcp.so），带内存缓存与参数校验。
 *   - 抓取能力在 agent/host/community（harness），此处只做编排与缓存。
 */

import {
  browseCategoryMcpSo,
  detailMcpSo,
  listMcpSoCategories,
  searchMcpSo
} from '../agent/host/community/mcpSoAdapter'
import type {
  McpCategory,
  McpDiscoverDetail,
  McpDiscoverPage,
  McpDiscoverQuery
} from '@shared/ipc'

/* 缓存有效期（毫秒） */
const CACHE_TTL = 5 * 60 * 1000

/* 分类缓存有效期（毫秒）：分类变化不频繁 */
const CATEGORY_TTL = 30 * 60 * 1000

/* 缓存容量上限（超出按写入顺序淘汰） */
const CACHE_LIMIT = 100

/* 内存缓存：key → 结果与过期时间 */
const cache = new Map<string, { expireAt: number; value: unknown }>()

/* 读取缓存（过期视为未命中） */
function readCache<T>(key: string): T | null {
  const entry = cache.get(key)
  if (!entry) {
    return null
  }
  if (entry.expireAt <= Date.now()) {
    cache.delete(key)
    return null
  }
  return entry.value as T
}

/* 写入缓存（超容量时淘汰最早写入项） */
function writeCache(key: string, value: unknown, ttl = CACHE_TTL): void {
  if (cache.size >= CACHE_LIMIT) {
    const oldest = cache.keys().next().value
    if (oldest) {
      cache.delete(oldest)
    }
  }
  cache.set(key, { expireAt: Date.now() + ttl, value })
}

/* 社区分类列表（服务器分类，缓存 30 分钟） */
export async function listMcpCategories(): Promise<McpCategory[]> {
  const key = 'categories'
  const cached = readCache<McpCategory[]>(key)
  if (cached) {
    return cached
  }
  const result = await listMcpSoCategories()
  writeCache(key, result, CATEGORY_TTL)
  return result
}

/* 在线检索 MCP：有关键字走搜索，无关键字且指定分类时按分类浏览；结果缓存 5 分钟 */
export async function discoverMcp(query: McpDiscoverQuery): Promise<McpDiscoverPage> {
  const keyword = (query.keyword ?? '').trim().slice(0, 64)
  const category = (query.category ?? '').trim()
  const page = Math.min(Math.max(Math.floor(query.page ?? 1), 1), 20)
  const byCategory = Boolean(category) && !keyword
  const key = byCategory ? `category:${category}:${page}` : `search:${keyword}:${page}`

  const cached = readCache<McpDiscoverPage>(key)
  if (cached) {
    return { ...cached, cached: true }
  }

  const result = byCategory
    ? await browseCategoryMcpSo(category, page)
    : await searchMcpSo(keyword, page)
  writeCache(key, result)
  return result
}

/* MCP 详情（含可导入配置片段） */
export async function discoverMcpDetail(slug: string): Promise<McpDiscoverDetail> {
  const key = `detail:${slug}`
  const cached = readCache<McpDiscoverDetail>(key)
  if (cached) {
    return cached
  }
  const result = await detailMcpSo(slug)
  writeCache(key, result)
  return result
}
