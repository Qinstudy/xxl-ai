/*
 * Agent 记忆生成：调用一次无工具的模型补全，把旧记忆与最近对话压缩成固定四段。
 *   - 运行在运行时进程（不访问数据库），结果回传主进程落库。
 */

import { AGENT_MEMORY_MAX_CHARS } from '@shared/ipc'
import { translate } from '@shared/i18n'
import type { MemoryGenerateInput } from '@shared/agentProtocol'
import { buildModels } from './models'

/* 维度标题（按当前语言生成，作为固定章节名） */
function dimensions(language: 'zh' | 'en'): string[] {
  return [
    translate(language, 'agent.memoryDim.preference'),
    translate(language, 'agent.memoryDim.fact'),
    translate(language, 'agent.memoryDim.decision'),
    translate(language, 'agent.memoryDim.todo')
  ]
}

/* 生成记忆：低温度、固定模板，输出截断到固定上限 */
export async function generateMemoryContent(input: MemoryGenerateInput): Promise<string> {
  const models = await buildModels([{ ...input.provider, sessionId: '' }])
  const model = models.getModel(input.provider.id, input.modelId)
  if (!model) {
    throw new Error(`模型未找到：${input.provider.id}/${input.modelId}`)
  }
  const message = await models.completeSimple(
    model,
    {
      systemPrompt: translate(input.language, 'agent.memoryGen.system', dimensions(input.language)),
      messages: [
        {
          role: 'user',
          content: translate(input.language, 'agent.memoryGen.user', [
            input.oldMemory.trim() || translate(input.language, 'agent.memoryGen.empty'),
            input.transcript
          ]),
          timestamp: Date.now()
        }
      ]
    },
    { temperature: 0.2, maxTokens: 3000, timeoutMs: 120_000 }
  )
  const text = message.content
    .filter((block) => block.type === 'text')
    .map((block) => (block as { text: string }).text)
    .join('')
    .trim()
  return text.slice(0, AGENT_MEMORY_MAX_CHARS)
}
