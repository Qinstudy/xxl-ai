/*
 * 对话历史窗口：限制送给模型的上下文规模，并剔除历史思考内容。
 *   - 最多保留最近 50 条消息；
 *   - 起始位置向前对齐到真实用户消息，避免从半轮开始；
 *   - 总字符超过 12 万时从最旧内容继续丢弃；
 *   - thinking 不回传（仅正文 / 工具调用 / 工具结果进入上下文）。
 * @author xuxueli 2026-10-26
 */

import { AUTO_CONTINUE_PROMPT, THINKING_ONLY_NOTE } from './defaults'

/* 历史窗口上限 */
const HISTORY_MAX_MESSAGES = 50
const HISTORY_MAX_CHARS = 120_000

/* 运行期消息最小结构 */
interface HistoryMessage {
  role?: string
  content?: unknown
  stopReason?: string
  errorMessage?: string
}

/* 消息内容块 */
export function contentBlocks(message: { content?: unknown }): unknown[] {
  return Array.isArray(message.content) ? message.content : []
}

/* 是否为真实用户轮次（自动续写消息不算新一轮） */
function isTurnStart(message: HistoryMessage): boolean {
  if (message.role !== 'user') {
    return false
  }
  const first = contentBlocks(message)[0] as { type?: string; text?: string } | undefined
  return !(first?.type === 'text' && first.text === AUTO_CONTINUE_PROMPT)
}

/*
 * 去掉 assistant 的思考块：
 *   - 有正文/工具调用：只剔除 thinking；
 *   - 只有 thinking：不静默丢弃，转为 error + 正文异常说明。
 */
function stripThinking(message: HistoryMessage): HistoryMessage | null {
  if (message.role !== 'assistant') {
    return message
  }
  const blocks = contentBlocks(message)
  if (blocks.length === 0) {
    return message
  }
  const kept = blocks.filter((block) => (block as { type?: string })?.type !== 'thinking')
  if (kept.length === 0) {
    return {
      ...message,
      stopReason: 'error',
      errorMessage: THINKING_ONLY_NOTE,
      content: [{ type: 'text', text: THINKING_ONLY_NOTE, error: true }]
    }
  }
  return kept.length === blocks.length ? message : { ...message, content: kept }
}

/* 按消息条数与字符上限裁剪历史 */
export function trimHistory(messages: unknown[]): unknown[] {
  const cleaned = (messages as HistoryMessage[])
    .map(stripThinking)
    .filter((message): message is HistoryMessage => message !== null)

  /* 最近 50 条；若起点在半轮中间，向后对齐到真实用户消息 */
  let result = cleaned.slice(-HISTORY_MAX_MESSAGES)
  const start = result.findIndex(isTurnStart)
  if (start > 0) {
    result = result.slice(start)
  }

  /* 字符超限：继续从最旧轮次丢弃 */
  while (result.length > 1 && JSON.stringify(result).length > HISTORY_MAX_CHARS) {
    const nextTurn = result.findIndex((message, index) => index > 0 && isTurnStart(message))
    result = result.slice(nextTurn > 0 ? nextTurn : 1)
  }
  return result
}
