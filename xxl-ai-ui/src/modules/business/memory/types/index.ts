/**
 * 长期记忆 类型定义
 */
import type { ListQuery } from '@/types'

/** 长期记忆（对应后端 Memory / MemoryDTO） */
export interface Memory {
  id?: number
  /** AgentID */
  agentId?: number
  /** Agent名称 */
  agentName?: string
  /** 访客标识 */
  visitorId?: string
  /** 记忆内容(Markdown，按维度分节) */
  content?: string
  /** 最后归档处理的消息ID */
  lastMsgId?: number
  addTime?: string
  updateTime?: string
  [key: string]: unknown
}

/** 搜索栏表单查询参数 */
export interface MemoryQuery {
  pageNum: number
  pageSize: number
  /** Agent ID（<=0 表示全部） */
  agentId?: number
  /** 访客标识（模糊） */
  visitorId?: string
}

/** 列表接口请求参数 */
export type MemoryListQuery = ListQuery<MemoryQuery>
