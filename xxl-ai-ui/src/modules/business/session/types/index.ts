/**
 * 对话模块 类型定义（公开对话数据 + 管理端对话管理）
 */
import type { ListQuery } from '@/types'

/** 对话（对应后端 Session 实体） */
export interface Session {
  id: number
  /** AgentID */
  agentId?: number
  /** 访客标识 */
  visitorId: string
  /** 对话标题 */
  title: string
  addTime?: string
  updateTime?: string
  [key: string]: unknown
}

/** 对话消息（对应后端 SessionMsg 实体） */
export interface SessionMsg {
  id?: number
  /** 对话ID */
  sessionId?: number
  /** 角色：user-用户、assistant-助手 */
  role: string
  /** 思考过程（推理模型 reasoning_content，可空） */
  reasoning?: string
  /** 消息内容 */
  content: string
  /** 状态：0-生成中、1-完成、2-失败（含主动终止；生成中可据消息ID断点续传） */
  status?: number
  addTime?: string
  [key: string]: unknown
}

/** 管理端对话列表项（含所属 Agent 名称） */
export interface SessionDTO {
  id: number
  /** AgentID */
  agentId?: number
  /** Agent名称 */
  agentName?: string
  /** 访客标识 */
  visitorId: string
  /** 对话标题 */
  title: string
  addTime?: string
  updateTime?: string
  [key: string]: unknown
}

/** 管理端对话列表搜索栏参数 */
export interface SessionQuery {
  pageNum: number
  pageSize: number
  /** Agent ID（<=0 表示全部） */
  agentId?: number
  /** 对话标题（模糊） */
  title?: string
  /** 访客标识（模糊） */
  visitorId?: string
}

/** 管理端对话列表接口请求参数 */
export type SessionListQuery = ListQuery<SessionQuery>
