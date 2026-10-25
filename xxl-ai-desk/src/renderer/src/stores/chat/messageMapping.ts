/*
 * 对话消息映射：持久化原始消息 → UI 消息（纯函数，无 Vue / Pinia 依赖）。
 *   - 按「轮次」折叠助手片段，保留 思考 → 工具 → 正文 的发生顺序；
 *   - toolResult 按 toolCallId 回填工具状态与结果；
 * @author xuxueli 2026-10-25
 */

import type { MessagePart, ToolCallState, UiMessage } from '@renderer/types'
import type { StoredMessage } from '@shared/ipc'

/* 持久化的 Pi 原始消息结构（仅取渲染所需字段） */
export interface RawPart {
  type?: string
  text?: string
  thinking?: string
  redacted?: boolean
  id?: string
  name?: string
  arguments?: unknown
}

export interface RawMessage {
  role?: string
  content?: RawPart[]
  stopReason?: string
  errorMessage?: string
  toolCallId?: string
  isError?: boolean
  /* 工具结果的结构化细节与耗时（Pi 持久化在 toolResult 消息上） */
  details?: unknown
  durationMs?: number
}

/* 生成 UI 消息 id */
export function uid(): string {
  return Math.random().toString(36).slice(2) + Date.now().toString(36)
}

/* 安全解析持久化的原始消息 JSON */
export function parseData(data: string): RawMessage | null {
  try {
    return JSON.parse(data) as RawMessage
  } catch {
    return null
  }
}

/* 向助手消息追加片段：同类相邻文本合并，保持片段发生顺序（思考 → 工具 → 正文 交错） */
export function appendPart(message: UiMessage, part: MessagePart): void {
  if (part.type === 'thinking' || part.type === 'text') {
    const last = message.parts[message.parts.length - 1]
    if (last && last.type === part.type) {
      last.text += part.text
      return
    }
  }
  message.parts.push(part)
}

/* 回填助手消息正文：拼接全部正文片段（供复制、列表预览与滚动跟随） */
export function refreshContent(message: UiMessage): void {
  message.content = message.parts
    .filter((part): part is { type: 'text'; text: string } => part.type === 'text')
    .map((part) => part.text)
    .join('\n\n')
}

/* 原始文本内容块 → 纯文本（工具结果展示用） */
function contentToPlain(raw: RawMessage | null): string {
  const content = raw?.content
  if (!Array.isArray(content)) {
    return ''
  }
  return content
    .filter((part) => part.type === 'text')
    .map((part) => part.text ?? '')
    .join('\n')
}

/*
 * 持久化消息集合 → UI 消息（按「轮次」折叠，并保留片段发生顺序）。
 *   - 一轮对话中 Agent 会分多次产出 assistant 片段（工具调用轮），并伴随 toolResult；
 *   - 折叠为「一条用户消息 + 一条助手消息」，助手消息内部按顺序记录 思考/正文/工具 片段，
 *     据此还原完整的执行过程，而非把思考、工具堆在一起。
 */
export function mapStoredMessages(stored: StoredMessage[]): UiMessage[] {
  const result: UiMessage[] = []
  /* toolCallId → 工具片段引用，供后续 toolResult 回填状态与结果 */
  const toolParts = new Map<string, ToolCallState>()

  for (const record of stored) {
    if (record.role === 'user') {
      result.push({
        id: record.id,
        role: 'user',
        content: record.content,
        parts: [],
        addTime: record.addTime
      })
      continue
    }
    /* 工具结果：回填到对应工具片段 */
    if (record.role === 'toolResult') {
      const raw = parseData(record.data)
      const tool = raw?.toolCallId ? toolParts.get(raw.toolCallId) : undefined
      if (tool) {
        tool.status = raw?.isError ? 'error' : 'done'
        tool.result = contentToPlain(raw)
        tool.details = raw?.details
        tool.durationMs = typeof raw?.durationMs === 'number' ? raw.durationMs : undefined
      }
      continue
    }
    if (record.role !== 'assistant') continue

    const raw = parseData(record.data)
    const parts = Array.isArray(raw?.content) ? raw.content : []
    const failed = raw?.stopReason === 'error'

    /* 合并到本轮已存在的助手消息 */
    let last = result[result.length - 1]
    if (!last || last.role !== 'assistant') {
      last = {
        id: record.id,
        role: 'assistant',
        content: '',
        parts: [],
        addTime: record.addTime,
        error: failed
      }
      result.push(last)
    } else if (failed) {
      last.error = true
    }

    /* 按原始顺序还原片段，并累计本片段的结构化正文 */
    let localText = ''
    for (const part of parts) {
      if (part.type === 'thinking' && !part.redacted && part.thinking) {
        appendPart(last, { type: 'thinking', text: part.thinking })
      } else if (part.type === 'text' && part.text) {
        appendPart(last, { type: 'text', text: part.text })
        localText += part.text
      } else if (part.type === 'toolCall') {
        const tool: ToolCallState = {
          id: part.id ?? uid(),
          name: part.name ?? 'tool',
          status: 'done',
          args: part.arguments,
          startedAt: 0
        }
        last.parts.push({ type: 'tool', tool })
        toolParts.set(tool.id, tool)
      }
    }

    /* 兜底：落库正文比结构化片段多出的部分（失败轮次追加的可读错误提示）补足展示 */
    if (record.content && record.content !== localText) {
      if (record.content.startsWith(localText)) {
        const rest = record.content.slice(localText.length)
        if (rest) {
          appendPart(last, { type: 'text', text: rest })
        }
      } else if (!localText) {
        appendPart(last, { type: 'text', text: record.content })
      }
    }
  }

  /* 剔除无任何内容的空助手消息（避免空白气泡） */
  return result.filter((message) => message.role !== 'assistant' || message.parts.length > 0)
}
