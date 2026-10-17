/**
 * 对话模块 类型定义（公开对话页）
 * 会话/消息数据模型见 session 模块类型。
 */

/** Agent 基础信息（公开，免登录对话页展示） */
export interface AgentChatInfo {
  id: number
  name: string
  intro?: string
  status: number
  publishStatus: number
  [key: string]: unknown
}
