/*
 * 会话日志契约：对话记录与模型通讯 trace 的分页查询、明细结构。
 *   - 仅开发者模式开启时写入 trace；对话记录直接读取 desk_message。
 *
 * @author xuxueli 2026-11-01
 */

/* 分页查询入参 */
export interface LogPageQuery {
  /* 会话主键 */
  sessionId: string
  /* 页码（从 1 开始） */
  page: number
  /* 每页条数（上限 100） */
  pageSize: number
}

/* 分页结果 */
export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  pageSize: number
}

/* 对话记录直接复用 StoredMessage（分页查询在 sessionLogService） */

/* 单次工具调用明细 */
export interface ToolCallTraceDTO {
  /* 工具调用 ID（与模型返回的 toolCall 对应） */
  toolCallId: string
  /* 工具名 */
  toolName: string
  /* 调用参数 */
  args: unknown
  /* 工具返回文本（已截断） */
  result: string
  /* 是否执行失败 */
  isError: boolean
  /* 执行耗时（毫秒） */
  durationMs: number
}

/* 一次模型调用的完整 trace */
export interface LlmTraceInput {
  /* 一轮对话的运行标识（同一 run 内 step 递增） */
  runId: string
  /* 本轮第几次模型调用（从 1 开始） */
  step: number
  /* 供应商主键 */
  providerId: string
  /* 模型 ID */
  modelId: string
  /* 来源：对话 / 记忆生成 */
  phase: 'chat' | 'memory'
  /* 结果状态 */
  status: 'success' | 'error' | 'aborted'
  /* HTTP 状态码（未知为 0） */
  httpStatus: number
  /* 请求到响应的耗时（毫秒） */
  durationMs: number
  /* 输入 tokens */
  inputTokens: number
  /* 输出 tokens */
  outputTokens: number
  /* 推理 tokens */
  reasoningTokens: number
  /* 请求体 JSON（已截断） */
  request: string
  /* 响应体 JSON（已截断） */
  response: string
  /* 工具调用明细 */
  toolCalls: ToolCallTraceDTO[]
  /* 错误信息 */
  error: string
}

/* 落库后的 trace（含主键与时间） */
export interface LlmTraceDTO extends LlmTraceInput {
  /* 主键 */
  id: string
  /* 会话主键 */
  sessionId: string
  /* 写入时间 */
  addTime: string
}
