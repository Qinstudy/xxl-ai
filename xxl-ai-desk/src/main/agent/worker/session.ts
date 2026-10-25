/*
 * 会话级 Agent 宿主：在运行时进程内按会话创建/缓存 Agent，执行一轮对话并回传流式事件。
 * 模型构建与内置工具装配在此编排，是连接 Pi Agent 与主进程协议的中间层。
 */

import type { Agent } from '@earendil-works/pi-agent-core'
import { buildModels, type ProviderModelConfig } from './models'
import { buildToolGuide, createBuiltinToolEntries } from './tools'
import { createMcpTools } from './mcp/tools'
import { setMcpRuntimePath } from './mcp/client'
import type { ChatMode } from '@shared/ipc'
import type { HostEvent } from '@shared/agentProtocol'
import { AUTO_CONTINUE_PROMPT, contentBlocks } from '@shared/ipc'
import type { McpRuntimeConfig, SkillRuntimeConfig } from '@shared/ipc'

/* 创建/获取会话 Agent 所需的运行时配置 */
export interface AgentRuntimeOptions {
  sessionId: string
  provider: ProviderModelConfig
  modelId: string
  systemPrompt: string
  messages?: unknown[]
  /* 对话模式（plan 只读 / build 读写）与项目根目录（文件沙箱边界） */
  mode: ChatMode
  rootDir: string
  /* 运行时 PATH（MCP stdio 与 run_command 共用） */
  runtimePath: string
  /* 已生效的 MCP 服务配置 */
  mcpServers: McpRuntimeConfig[]
  /* 已启用的技能索引 */
  skills: SkillRuntimeConfig[]
  /* 浏览器类内置工具是否启用 */
  browserToolsEnabled: boolean
  /* 通用类内置工具是否启用 */
  generalToolsEnabled: boolean
  /* 会话是否已被终止（Agent 创建阶段用于提前跳出） */
  isAborted?: () => boolean
}

/* 会话级 Agent 运行时缓存：同一会话复用上下文 */
const runtimes = new Map<string, Promise<Agent>>()

/* 已请求终止的会话：覆盖「Agent 尚未创建完成就点击终止」的场景 */
const abortedSessions = new Set<string>()

/*
 * MCP 装配等待（毫秒）：连接已就绪（缓存命中）时几毫秒返回，本轮即有 MCP 工具；
 * 冷启动/慢服务不阻塞首字，未就绪则先跑，装配完成后直接追加到会话 Agent。
 */
const MCP_DISCOVERY_BUDGET = 2000

/* 在预算内等待任务完成：超时返回 null，任务继续在后台执行 */
function withinBudget<T>(task: Promise<T>, budgetMs: number): Promise<T | null> {
  let timer: ReturnType<typeof setTimeout> | undefined
  const timeout = new Promise<null>((resolve) => {
    timer = setTimeout(() => resolve(null), budgetMs)
  })
  return Promise.race([task, timeout]).finally(() => {
    if (timer) {
      clearTimeout(timer)
    }
  })
}

/* 标记会话开始新一轮（清除上一次终止标记） */
export function clearSessionAbort(sessionId: string): void {
  abortedSessions.delete(sessionId)
}

/* 会话是否已被请求终止 */
export function isSessionAborted(sessionId: string): boolean {
  return abortedSessions.has(sessionId)
}

/* 单次请求超时、重试间隔与次数 */
const REQUEST_TIMEOUT_MS = 30_000
const RETRY_DELAY_MS = 3_000
const MAX_REQUEST_RETRIES = 5

/* 仅 401 / 402 / 403 / 404 不重试，其余异常一律重试 */
const NON_RETRYABLE_STATUS = new Set([401, 402, 403, 404])

/* 运行期消息的最小结构 */
interface RuntimeMessage {
  role?: string
  content?: unknown
  stopReason?: string
  errorMessage?: string
  timestamp?: number
}

/* 原始用户提问是否已进入上下文（请求失败后判断是否需要重发） */
function hasUserText(agent: Agent, text: string): boolean {
  return (agent.state.messages as RuntimeMessage[]).some(
    (message) =>
      message.role === 'user' &&
      contentBlocks(message).some(
        (block) => (block as { type?: string; text?: string }).type === 'text' &&
          (block as { text?: string }).text === text
      )
  )
}

/* 最后一轮助手消息 */
function lastAssistant(agent: Agent): RuntimeMessage | undefined {
  const messages = agent.state.messages as RuntimeMessage[]
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    if (messages[index].role === 'assistant') {
      return messages[index]
    }
  }
  return undefined
}

/* 在正文追加一条系统备注；appendToLast=true 时并入最后一条助手消息 */
function appendAssistantNote(agent: Agent, note: string, appendToLast: boolean): void {
  const messages = agent.state.messages as RuntimeMessage[]
  if (appendToLast) {
    const last = lastAssistant(agent)
    if (last && Array.isArray(last.content)) {
      last.content.push({ type: 'text', text: note, error: true })
      return
    }
  }
  messages.push({
    role: 'assistant',
    content: [{ type: 'text', text: note, error: true }],
    stopReason: 'stop',
    timestamp: Date.now()
  })
}

/* 重试退避 */
function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/* 是否用户主动终止 */
function isAbortError(error: unknown): boolean {
  return /aborted|cancell?ed/i.test((error as Error)?.message ?? '')
}

/* 按配置动态加载 Pi Agent，装配模型与内置工具，注入系统提示与历史消息 */
async function createAgent(options: AgentRuntimeOptions): Promise<Agent> {
  const { Agent: AgentClass } = await import('@earendil-works/pi-agent-core')
  const models = await buildModels([{ ...options.provider, sessionId: options.sessionId }])
  const model = models.getModel(options.provider.id, options.modelId)
  if (!model) {
    throw new Error(`模型未找到：${options.provider.id}/${options.modelId}`)
  }
  const entries = await createBuiltinToolEntries({
    mode: options.mode,
    rootDir: options.rootDir,
    sessionId: options.sessionId,
    skills: options.skills,
    browserToolsEnabled: options.browserToolsEnabled,
    generalToolsEnabled: options.generalToolsEnabled
  })
  const tools = entries.map((entry) => entry.tool)
  /* MCP 工具：先等一个短预算（已连接/缓存命中即刻就绪），未就绪也不阻塞首字 */
  const mcpTask = createMcpTools({
    servers: options.mcpServers,
    mode: options.mode,
    runtimePath: options.runtimePath,
    isAborted: options.isAborted
  })
  const readyMcpTools = await withinBudget(mcpTask, MCP_DISCOVERY_BUDGET)
  /* 历史消息随 initialState 注入：Agent 会在其前自动补上系统指令（直接覆盖 state.messages 会把系统指令冲掉） */
  const agent = new AgentClass({
    initialState: {
      systemPrompt: `${options.systemPrompt}${buildToolGuide(entries)}`,
      model,
      tools: [...tools, ...(readyMcpTools ?? [])],
      messages: (options.messages ?? []) as never[]
    },
    streamFn: (model, context, options) =>
      models.streamSimple(model, context, { ...options, timeoutMs: REQUEST_TIMEOUT_MS })
  })
  /*
   * 后台装配完成：直接替换运行中 Agent 的工具集（Pi 官方支持，下一次请求生效并自动告知模型），
   * 无需重建 Agent，会话上下文不丢。
   */
  if (readyMcpTools === null) {
    void mcpTask
      .then((lateMcpTools) => {
        agent.state.tools = [...tools, ...lateMcpTools]
      })
      .catch(() => undefined)
  }
  return agent
}

/* 获取（或创建）会话 Agent */
export function getAgent(options: AgentRuntimeOptions): Promise<Agent> {
  let runtime = runtimes.get(options.sessionId)
  if (!runtime) {
    runtime = createAgent(options)
    runtimes.set(options.sessionId, runtime)
  }
  return runtime
}

/* 释放会话 Agent（删除会话/切换模型时） */
export function evictAgent(sessionId: string): void {
  runtimes.delete(sessionId)
}

/* 中断会话当前生成：先登记终止标记（Agent 未就绪也不会丢），再中止运行中的 Agent */
export async function abortAgent(sessionId: string): Promise<void> {
  abortedSessions.add(sessionId)
  const runtime = runtimes.get(sessionId)
  if (runtime) {
    try {
      const agent = await runtime
      agent.abort()
    } catch {
      /* Agent 创建失败无需中止 */
    }
  }
}

/* 清空全部运行时（退出时） */
export function resetAgents(): void {
  runtimes.clear()
}

/* 从异常对象中提取 HTTP 状态码 */
function statusOf(error: unknown): number | undefined {
  const value = error as {
    status?: unknown
    statusCode?: unknown
    code?: unknown
    response?: { status?: unknown }
    error?: { status?: unknown }
  }
  const candidates = [
    value?.status,
    value?.statusCode,
    value?.code,
    value?.response?.status,
    value?.error?.status
  ]
  for (const candidate of candidates) {
    const status = Number(candidate)
    if (Number.isInteger(status)) {
      return status
    }
  }
  return undefined
}

/* 是否为白名单内的不可重试错误（401 / 402 / 403 / 404） */
function isNonRetryable(error: unknown): boolean {
  const status = statusOf(error)
  if (status !== undefined) {
    return NON_RETRYABLE_STATUS.has(status)
  }
  const message = typeof error === 'string' ? error : (error as Error)?.message ?? ''
  if (/\b(?:HTTP|status(?:\s*code)?|API error)\s*[:=(]?\s*(401|402|403|404)\b/i.test(message)) {
    return true
  }
  if (/^\s*(?:Error:\s*)?(401|402|403|404)\b/i.test(message)) {
    return true
  }
  return /\b(401|402|403|404)\s+(Unauthorized|Payment Required|Forbidden|Not Found)\b/i.test(
    message
  )
}

/* 执行一轮对话：请求失败重试、长度截断/未知状态统一按 error 处理 */
export async function runPrompt(
  agent: Agent,
  text: string,
  sink: (event: HostEvent) => void,
  sessionId = ''
): Promise<RuntimeMessage[]> {
  const unsubscribe = agent.subscribe((event) => {
    switch (event.type) {
      case 'message_update': {
        const inner = event.assistantMessageEvent
        if (inner.type === 'text_delta') {
          sink({ type: 'delta', text: inner.delta })
        } else if (inner.type === 'thinking_delta') {
          sink({ type: 'thinking', text: inner.delta })
        }
        break
      }
      case 'tool_execution_start':
        sink({
          type: 'tool_start',
          toolCallId: event.toolCallId,
          toolName: event.toolName,
          args: event.args
        })
        break
      case 'tool_execution_end':
        sink({
          type: 'tool_end',
          toolCallId: event.toolCallId,
          toolName: event.toolName,
          isError: event.isError,
          result: toolResultText(event.result),
          details: (event.result as { details?: unknown } | undefined)?.details,
          durationMs: event.durationMs
        })
        break
      default:
        break
    }
  })

  let failures = 0
  let first = true
  const start = (agent.state.messages as RuntimeMessage[]).length
  const takeNew = (): RuntimeMessage[] =>
    (agent.state.messages as RuntimeMessage[]).slice(start)
  try {
    while (true) {
      if (sessionId && isSessionAborted(sessionId)) {
        sink({ type: 'done' })
        return takeNew()
      }

      let thrown: unknown = null
      const promptText = first ? text : AUTO_CONTINUE_PROMPT
      first = false
      try {
        await agent.prompt(promptText)
      } catch (error) {
        thrown = error
      }

      /* 用户终止：直接结束 */
      if (thrown && (isAbortError(thrown) || (sessionId && isSessionAborted(sessionId)))) {
        sink({ type: 'done' })
        return takeNew()
      }

      const last = lastAssistant(agent)
      const reason = thrown ? 'exception' : last?.stopReason

      /* 模型明确结束 */
      if (reason === 'stop' || reason === 'aborted') {
        sink({ type: 'done' })
        return takeNew()
      }

      /* 所有异常统一按 error 处理：正文备注原因 + 重试 5 次 */
      const detail = thrown
        ? (thrown as Error)?.message || String(thrown)
        : reason === 'length'
          ? '输出内容超过限制，内容已被截断'
          : reason === 'error'
            ? last?.errorMessage || '模型返回错误'
            : `模型返回未知结束状态（${reason || '未知'}）`

      /* 当前助手消息标记为 error，并写入可读原因 */
      if (!thrown && last) {
        last.stopReason = 'error'
        last.errorMessage = detail
      }

      /* 不可重试错误（401/402/403/404）：直接结束 */
      if (!thrown && isNonRetryable(detail)) {
        const note = `\n\n[系统] 不可重试的请求错误：${detail}`
        appendAssistantNote(agent, note, !thrown)
        sink({ type: 'error', message: note })
        return takeNew()
      }

      failures += 1
      const appendToLast = !thrown
      const final = failures > MAX_REQUEST_RETRIES
      const note = final
        ? `\n\n[系统] 连续异常 ${MAX_REQUEST_RETRIES + 1} 次，已结束：${detail}`
        : `\n\n[系统] 第 ${failures} 次异常：${detail}。已记录，继续重试。`
      appendAssistantNote(agent, note, appendToLast)

      if (final) {
        sink({ type: 'error', message: note })
        return takeNew()
      }

      /* 非致命异常：界面标记错误备注，本轮继续 */
      sink({ type: 'notice', message: note })
      if (thrown && !hasUserText(agent, text)) {
        first = true
      }
      await sleep(RETRY_DELAY_MS)
    }
  } finally {
    unsubscribe()
  }
  return takeNew()
}

/* 汇总工具结果为纯文本（仅取文本内容块，供 UI 展开查看） */
function toolResultText(result: unknown): string {
  const content = (result as { content?: unknown })?.content
  if (typeof content === 'string') {
    return content
  }
  if (Array.isArray(content)) {
    return content
      .map((part) => {
        if (typeof part === 'string') {
          return part
        }
        return (part as { type?: string; text?: string }).type === 'text'
          ? ((part as { text?: string }).text ?? '')
          : ''
      })
      .filter((item) => item !== '')
      .join('\n')
  }
  return ''
}
