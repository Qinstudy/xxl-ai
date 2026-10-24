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
import type { McpRuntimeConfig } from '@shared/ipc'

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
  /* 会话是否已被终止（Agent 创建阶段用于提前跳出） */
  isAborted?: () => boolean
}

/* 会话级 Agent 运行时缓存：同一会话复用上下文 */
const runtimes = new Map<string, Promise<Agent>>()

/* 已请求终止的会话：覆盖「Agent 尚未创建完成就点击终止」的场景 */
const abortedSessions = new Set<string>()

/* 标记会话开始新一轮（清除上一次终止标记） */
export function clearSessionAbort(sessionId: string): void {
  abortedSessions.delete(sessionId)
}

/* 会话是否已被请求终止 */
export function isSessionAborted(sessionId: string): boolean {
  return abortedSessions.has(sessionId)
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
    sessionId: options.sessionId
  })
  const tools = entries.map((entry) => entry.tool)
  /* MCP 工具：随会话装配（未开启的服务不下发，连接失败的服务自动跳过） */
  const mcpTools = await createMcpTools({
    servers: options.mcpServers,
    mode: options.mode,
    runtimePath: options.runtimePath,
    isAborted: options.isAborted
  })
  /* 历史消息随 initialState 注入：Agent 会在其前自动补上系统指令（直接覆盖 state.messages 会把系统指令冲掉） */
  const agent = new AgentClass({
    initialState: {
      systemPrompt: `${options.systemPrompt}${buildToolGuide(entries)}`,
      model,
      tools: [...tools, ...mcpTools],
      messages: (options.messages ?? []) as never[]
    },
    streamFn: models.streamSimple.bind(models)
  })
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

/* 执行一轮对话，流式回调事件 */
export async function runPrompt(
  agent: Agent,
  text: string,
  sink: (event: HostEvent) => void
): Promise<void> {
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

  try {
    await agent.prompt(text)
    const failed = findLastError(agent)
    if (failed !== undefined) {
      sink({ type: 'error', message: failed })
    } else {
      sink({ type: 'done' })
    }
  } catch (error) {
    /* 用户终止导致的异常按正常结束处理，避免 UI 误报调用失败 */
    if (/aborted|cancell?ed/i.test((error as Error).message)) {
      sink({ type: 'done' })
    } else {
      sink({ type: 'error', message: (error as Error).message })
    }
  } finally {
    unsubscribe()
  }
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

/* 检查最后一轮是否以错误结束，返回错误信息（无错误返回 undefined） */
function findLastError(agent: Agent): string | undefined {
  const messages = agent.state.messages
  for (let i = messages.length - 1; i >= 0; i -= 1) {
    const message = messages[i] as { role?: string; stopReason?: string; errorMessage?: string }
    if (message.role === 'assistant') {
      if (message.stopReason === 'error') {
        return message.errorMessage || ''
      }
      return undefined
    }
  }
  return undefined
}
