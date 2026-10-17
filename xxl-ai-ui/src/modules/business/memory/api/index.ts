/**
 * 长期记忆 接口封装（管理端：按业务空间查看 Agent×访客 的长期记忆）
 */
import { request } from '@/utils/request'
import type { Response, PageModel } from '@/types'
import type { Memory, MemoryListQuery } from '../types'

/** 分页查询空间内记忆列表（可按 Agent、访客ID 过滤） */
export function pageMemory(query: MemoryListQuery): Promise<Response<PageModel<Memory>>> {
  return request({ url: '/memory/pageList', method: 'get', params: query })
}

/** 修改记忆内容 */
export function updateMemory(data: { id: number; content: string }): Promise<Response<string>> {
  return request({ url: '/memory/update', method: 'post', data })
}

/** 批量删除记忆 */
export function delMemory(ids: number | number[]): Promise<Response<string>> {
  return request({ url: '/memory/delete', method: 'post', data: Array.isArray(ids) ? ids : [ids] })
}
