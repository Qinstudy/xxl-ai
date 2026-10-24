/*
 * MCP 工具装配（运行时进程侧）：按 Pi 官方推荐方式把 MCP Tool 包装为 AgentTool。
 *   - 参数：MCP inputSchema 经 Type.Unsafe 直接作为工具参数（补齐 type/properties）；
 *   - 结果：toLlmContent 将 MCP 结果转为模型可读的文本/图片内容；
 *   - 失败：MCP 用 result.isError 表达工具失败，协议/网络错误包装为工具错误；
 *   - 模式：Build 全量注册，Plan 仅注册 readOnlyHint 工具；
 *   - 单服务连接失败仅跳过该服务，不影响本轮对话。
 */

import type { AgentTool } from '@earendil-works/pi-agent-core'
import type { Tool } from '@earendil-works/pi-mcp'
import log from 'electron-log/main'
import type { ChatMode, McpRuntimeConfig } from '@shared/ipc'
import { callMcpTool, getMcpConnection, loadPiMcp } from './client'
import { buildToolName, uniqueToolName } from './naming'

/*
 * 构建 MCP 工具集合：逐个服务连接并列举工具，失败服务跳过。
 * 复用运行时连接缓存，连接与工具列表在会话间共享。
 */
export async function createMcpTools(options: {
  servers: McpRuntimeConfig[]
  mode: ChatMode
  runtimePath: string
  /* 会话是否已被终止：创建阶段提前跳出，避免终止后仍等待连接 */
  isAborted?: () => boolean
}): Promise<AgentTool[]> {
  const { servers, mode } = options
  if (servers.length === 0) {
    return []
  }
  const [{ Type }, mcp] = await Promise.all([import('@earendil-works/pi-ai'), loadPiMcp()])

  const tools: AgentTool[] = []
  const usedNames = new Set<string>()

  for (const server of servers) {
    /* 已被终止：不再继续连接后续服务 */
    if (options.isAborted?.()) {
      break
    }
    let discovered: Tool[]
    try {
      const connection = await getMcpConnection(server)
      discovered = connection.tools
    } catch (error) {
      log.warn(`[mcp] 服务连接失败，已跳过：${server.name} ${(error as Error).message}`)
      continue
    }

    for (const tool of discovered) {
      /* Plan 模式只读：仅暴露显式声明只读的工具 */
      if (mode === 'plan' && tool.annotations?.readOnlyHint !== true) {
        continue
      }

      /* 同名工具追加序号，保证 Agent 工具名唯一 */
      const name = uniqueToolName(buildToolName(server.name, tool.name), usedNames)
      usedNames.add(name)

      tools.push({
        name,
        label: tool.title || tool.name,
        description: tool.description || `来自 MCP 服务「${server.name}」的工具`,
        /* 模型供应商要求对象 schema；部分服务未提供 properties，补齐避免被拒 */
        parameters: Type.Unsafe({
          ...tool.inputSchema,
          type: 'object',
          properties: (tool.inputSchema?.properties as Record<string, unknown>) ?? {}
        }) as never,
        /* MCP 工具可能有副作用，统一串行执行 */
        executionMode: 'sequential',
        execute: async (_toolCallId, params, signal) => {
          try {
            const result = await callMcpTool(
              server,
              tool.name,
              (params ?? {}) as Record<string, unknown>,
              signal
            )
            /* Pi 推荐：MCP 失败通过 isError 透传，内容统一走 toLlmContent */
            return {
              content: mcp.toLlmContent(result),
              details: undefined,
              isError: result.isError === true
            }
          } catch (error) {
            /* 用户终止：给出明确结果，避免被误判为服务异常 */
            const message = signal?.aborted
              ? `[已终止] MCP 工具调用被用户终止（${server.name}/${tool.name}）`
              : `MCP 工具调用失败（${server.name}/${tool.name}）：${(error as Error).message}`
            return {
              content: [{ type: 'text' as const, text: message }],
              details: undefined,
              isError: true
            }
          }
        }
      })
    }
  }

  return tools
}
