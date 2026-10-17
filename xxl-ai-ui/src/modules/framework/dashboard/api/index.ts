import { request } from '@/utils/request'
import type { AgentMsgShareItem, SessionMsgTrendItem, DashboardStats } from '../types'
import type { Response } from '@/types'

/**
 * 名称：首页 Dashboard API
 * 能力：提供首页指标卡片、Agent 会话消息趋势与占比接口。
 */

/**
 * 首页：指标卡片（Agent / Skill / MCP / 供应商模型 数量）。
 * @returns 各项统计数量。
 */
export function getStats(): Promise<Response<DashboardStats>> {
  return request({
    url: '/dashboard/stats',
    method: 'get'
  })
}

/**
 * 首页：Agent 会话消息趋势折线图。
 * @param days 统计天数。
 * @returns 每日会话消息量列表。
 */
export function getSessionMsgTrend(days: number): Promise<Response<SessionMsgTrendItem[]>> {
  return request({
    url: '/dashboard/sessionMsgTrend',
    method: 'get',
    params: { days }
  })
}

/**
 * 首页：Agent 会话消息占比饼图。
 * @param days 统计天数。
 * @returns 各 Agent 会话消息量列表。
 */
export function getSessionMsgShare(days: number): Promise<Response<AgentMsgShareItem[]>> {
  return request({
    url: '/dashboard/sessionMsgShare',
    method: 'get',
    params: { days }
  })
}
