/*
 * Agent 域跨进程契约：Agent 记忆（按项目维度保存，一个项目一条）。
 */

/* 记忆自动生成阈值：距上次生成超过该轮数（用户消息数）后异步生成 */
export const AGENT_MEMORY_ROUND_LIMIT = 30

/* 记忆固定长度上限（字符） */
export const AGENT_MEMORY_MAX_CHARS = 2000

/* Agent 指令长度上限（字符） */
export const AGENT_INSTRUCTION_MAX_CHARS = 2000

/* 项目记忆：无记忆时 content / lastMessageId / updateTime 为空串 */
export interface AgentMemoryDTO {
  /* 项目主键（记忆唯一键） */
  projectId: string
  /* 项目名 */
  projectName: string
  /* 记忆正文（Markdown，固定四段） */
  content: string
  /* 上次生成时纳入的最后一条消息 ID（用于计算轮次） */
  lastMessageId: string
  /* 更新时间（ISO），未生成过为空串 */
  updateTime: string
}

/* 保存记忆入参（手动编辑） */
export interface AgentMemorySaveInput {
  /* 项目主键 */
  projectId: string
  /* 记忆正文 */
  content: string
}
