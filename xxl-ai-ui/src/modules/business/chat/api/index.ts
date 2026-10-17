/**
 * 对话模块 接口封装（C端公开对话：免登录，按发布 URL 直接访问）
 *      - 会话元数据（创建/列表/改名/删除、消息列表）与对话流式入口均收敛在 /chat 下；
 *      - 管理端对话查询见 session 模块接口。
 */
import { request } from '@/utils/request'
import type { Response } from '@/types'
import type { AgentChatInfo } from '../types'
import type { Session, SessionMsg } from '@/modules/business/session/types'

const BASE = import.meta.env.VITE_APP_BASE_API ?? ''

// ==================== 会话元数据 ====================

/** 创建对话 */
export function createSession(uuid: string, visitorId: string): Promise<Response<Session>> {
  return request({ url: '/chat/session/create', method: 'get', params: { uuid, visitorId } })
}

/** 对话列表（按访客隔离） */
export function listSession(uuid: string, visitorId: string): Promise<Response<Session[]>> {
  return request({ url: '/chat/session/list', method: 'get', params: { uuid, visitorId } })
}

/** 修改对话标题 */
export function renameSession(sessionId: number, title: string): Promise<Response<string>> {
  return request({ url: '/chat/session/rename', method: 'get', params: { sessionId, title } })
}

/** 删除对话 */
export function deleteSession(sessionId: number): Promise<Response<string>> {
  return request({ url: '/chat/session/delete', method: 'get', params: { sessionId } })
}

/** 消息列表（公开访问，按对话ID） */
export function listMsg(sessionId: number): Promise<Response<SessionMsg[]>> {
  return request({ url: '/chat/session/msgList', method: 'get', params: { sessionId } })
}

// ==================== 对话流式（运行时） ====================

/** Load Agent 基础信息 */
export function loadAgentInfo(uuid: string): Promise<Response<AgentChatInfo>> {
  return request({ url: '/chat/load', method: 'get', params: { uuid } })
}

/**
 * 发送消息（SSE 流式）
 * 经原生 fetch 拉取流，返回可读流 reader 由调用方逐行解析
 */
export async function sendStream(
  uuid: string,
  visitorId: string,
  sessionId: number,
  content: string
): Promise<ReadableStreamDefaultReader<Uint8Array> | null> {
  const response = await fetch(`${BASE}/chat/send`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ uuid, visitorId, sessionId, content })
  })
  if (!response.ok || !response.body) {
    throw new Error(`请求失败，HTTP ${response.status}`)
  }
  return response.body.getReader()
}

/**
 * 断线续传（SSE 流式）
 * 携带 msgId（助手消息ID）+ 已收到的 lastEventId，从断点之后继续读取结果流
 */
export async function resumeStream(
  msgId: number,
  lastEventId?: string
): Promise<ReadableStreamDefaultReader<Uint8Array> | null> {
  const params = new URLSearchParams({ msgId: String(msgId) })
  if (lastEventId) params.set('lastEventId', lastEventId)
  const response = await fetch(`${BASE}/chat/resume?${params.toString()}`, { method: 'POST' })
  if (!response.ok || !response.body) {
    throw new Error(`请求失败，HTTP ${response.status}`)
  }
  return response.body.getReader()
}
