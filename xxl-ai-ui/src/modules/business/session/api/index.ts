/**
 * 对话模块 接口封装（管理端：按业务空间查看对话列表与消息明细）
 *      - C端公开对话接口见 chat 模块；
 *      - 数据模型与后端 session 模块一致。
 */
import { request } from '@/utils/request'
import type { Response, PageModel } from '@/types'
import type { SessionMsg, SessionDTO, SessionListQuery } from '../types'

// ==================== 管理端（对话管理） ====================

/** 分页查询空间内对话列表（可按 Agent、标题、访客ID 过滤） */
export function pageSession(query: SessionListQuery): Promise<Response<PageModel<SessionDTO>>> {
  return request({ url: '/session/pageList', method: 'get', params: query })
}

/** 分页查询对话消息明细（校验空间与 Agent 归属，页面内按时间正序） */
export function listSessionMsg(sessionId: number, agentId?: number, offset = 0, pagesize = 10): Promise<Response<PageModel<SessionMsg>>> {
  return request({ url: '/session/msgList', method: 'get', params: { sessionId, agentId, offset, pagesize } })
}
