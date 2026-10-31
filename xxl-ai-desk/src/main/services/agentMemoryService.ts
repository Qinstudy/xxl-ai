/*
 * Agent 记忆服务：按项目维度保存记忆，一轮对话结束后检查轮次并异步压缩生成。
 *   - 唯一 getDb 出口；模型调用经 agent/host 门面转运行时进程，worker 不访问数据库。
 *   - 生成失败只记录日志并保留旧记忆，不阻断对话主流程。
 */

import { randomUUID } from 'crypto'
import log from 'electron-log/main'
import { and, asc, eq, gt } from 'drizzle-orm'
import { getDb } from '../db'
import { agentMemoryTable, messageTable, projectTable } from '../db/schema'
import {
  AGENT_MEMORY_MAX_CHARS,
  AGENT_MEMORY_ROUND_LIMIT,
  type AgentMemoryDTO,
  type AgentMemorySaveInput,
  type AppSettings,
  type ProviderDTO,
  type SessionDTO
} from '@shared/ipc'
import { getSettings } from './settingsService'
import { getProvider } from './providerService'
import { getProject, listSessionIdsByProject } from './projectService'
import { getSession } from './sessionService'
import { evictAgent, generateMemory } from '../agent/host/runtimeHost'
import type { ProviderModelConfig } from '@shared/agentProtocol'

/* 生成中的项目：同一项目同时只跑一个记忆任务，避免多会话并发覆盖 */
const generatingProjects = new Set<string>()

/* 生成入参最大转录长度（字符）：超长从最旧内容截断，控制压缩请求成本 */
const TRANSCRIPT_MAX_CHARS = 60_000

/* 行记录 → DTO（无记忆时字段为空串） */
function toDTO(row: {
  projectId: string
  projectName: string
  content?: string | null
  lastMessageId?: string | null
  updateTime?: string | null
}): AgentMemoryDTO {
  return {
    projectId: row.projectId,
    projectName: row.projectName,
    content: row.content ?? '',
    lastMessageId: row.lastMessageId ?? '',
    updateTime: row.updateTime ?? ''
  }
}

/* 项目记忆列表：以项目为主表左连记忆，未生成过记忆的项目 content 为空 */
export function listAgentMemories(): AgentMemoryDTO[] {
  const rows = getDb()
    .select({
      projectId: projectTable.id,
      projectName: projectTable.name,
      content: agentMemoryTable.content,
      lastMessageId: agentMemoryTable.lastMessageId,
      updateTime: agentMemoryTable.updateTime
    })
    .from(projectTable)
    .leftJoin(agentMemoryTable, eq(agentMemoryTable.projectId, projectTable.id))
    .all()
  return rows
    .map(toDTO)
    .sort((a, b) => (b.updateTime || '').localeCompare(a.updateTime || ''))
}

/* 读取单个项目的记忆正文（未生成返回空串） */
export function getAgentMemory(projectId: string): string {
  if (!projectId) {
    return ''
  }
  const row = getDb()
    .select()
    .from(agentMemoryTable)
    .where(eq(agentMemoryTable.projectId, projectId))
    .get()
  return row?.content ?? ''
}

/* 保存记忆：以项目为唯一键幂等 upsert */
function upsertMemory(projectId: string, content: string, lastMessageId: string): AgentMemoryDTO {
  const db = getDb()
  const now = new Date().toISOString()
  const existing = db
    .select()
    .from(agentMemoryTable)
    .where(eq(agentMemoryTable.projectId, projectId))
    .get()
  const record = {
    id: existing?.id ?? randomUUID(),
    projectId,
    content,
    lastMessageId,
    addTime: existing?.addTime ?? now,
    updateTime: now
  }
  db.insert(agentMemoryTable)
    .values(record)
    .onConflictDoUpdate({ target: agentMemoryTable.projectId, set: record })
    .run()
  return toDTO({ ...record, projectName: getProject(projectId)?.name ?? '' })
}

/* 手动保存记忆（内容截断到固定上限） */
export function saveAgentMemory(input: AgentMemorySaveInput): AgentMemoryDTO {
  const project = getProject(input.projectId)
  if (!project) {
    throw new Error('项目不存在')
  }
  const existing = getDb()
    .select()
    .from(agentMemoryTable)
    .where(eq(agentMemoryTable.projectId, project.id))
    .get()
  const saved = upsertMemory(
    project.id,
    (input.content ?? '').slice(0, AGENT_MEMORY_MAX_CHARS),
    existing?.lastMessageId ?? ''
  )
  evictProjectAgents(project.id)
  return saved
}

/* 删除项目记忆（保留项目本身） */
export function removeAgentMemory(projectId: string): void {
  getDb().delete(agentMemoryTable).where(eq(agentMemoryTable.projectId, projectId)).run()
  evictProjectAgents(projectId)
}

/* 会话运行时目标：优先会话绑定，缺失时回退设置默认，再回退供应商首个模型 */
function resolveTarget(
  session: SessionDTO,
  settings: AppSettings
): { provider: ProviderDTO; modelId: string } | null {
  const providerId = session.providerId || settings.providerId
  const provider = providerId ? getProvider(providerId) : null
  if (!provider || !provider.enabled) {
    return null
  }
  const modelId =
    session.modelId && provider.models.includes(session.modelId)
      ? session.modelId
      : settings.modelId && provider.models.includes(settings.modelId)
        ? settings.modelId
        : (provider.models[0] ?? '')
  return modelId ? { provider, modelId } : null
}

/* 供应商 DTO → 运行时配置 */
function toRuntimeConfig(provider: ProviderDTO): ProviderModelConfig {
  return {
    id: provider.id,
    name: provider.name,
    baseUrl: provider.baseUrl,
    apiKey: provider.apiKey ?? '',
    headers: provider.headers,
    models: provider.models
  }
}

/* 上次生成位置之后的消息（按 seq 升序） */
function listMessagesAfter(sessionId: string, lastMessageId: string): Array<{
  id: string
  role: string
  content: string
  data: string
}> {
  const db = getDb()
  /* 记忆按项目保存，但消息 seq 是会话内序号：仅当上次消息属于当前会话时才作为起点 */
  const last = lastMessageId
    ? db
        .select({ seq: messageTable.seq })
        .from(messageTable)
        .where(and(eq(messageTable.id, lastMessageId), eq(messageTable.sessionId, sessionId)))
        .get()
    : undefined
  const conditions = [eq(messageTable.sessionId, sessionId)]
  if (last) {
    conditions.push(gt(messageTable.seq, last.seq))
  }
  return db
    .select({
      id: messageTable.id,
      role: messageTable.role,
      content: messageTable.content,
      data: messageTable.data
    })
    .from(messageTable)
    .where(and(...conditions))
    .orderBy(asc(messageTable.seq))
    .all()
}

/* 提取消息正文：只取 text 块，排除 thinking 与工具调用 / 工具结果 */
function extractMessageText(row: { content: string; data: string }): string {
  if (!row.data) {
    return row.content.trim()
  }
  try {
    const content = (JSON.parse(row.data) as { content?: unknown }).content
    if (typeof content === 'string') {
      return content.trim()
    }
    if (!Array.isArray(content)) {
      return row.content.trim()
    }
    return content
      .map((part) => {
        if (typeof part === 'string') {
          return part
        }
        const block = part as { type?: string; text?: string }
        return block.type === 'text' ? (block.text ?? '') : ''
      })
      .join('')
      .trim()
  } catch {
    /* 历史脏数据无法解析时退回归一化正文，保证记忆生成不中断 */
    return row.content.trim()
  }
}

/* 拼接用于压缩的对话转录：仅用户 / 助手正文，超长从最旧截断 */
function buildTranscript(
  rows: Array<{ role: string; content: string; data: string }>
): string {
  const transcript = rows
    .filter((row) => row.role === 'user' || row.role === 'assistant')
    .map((row) => ({ role: row.role, text: extractMessageText(row) }))
    .filter((row) => row.text)
    .map((row) => `${row.role}: ${row.text}`)
    .join('\n\n')
  return transcript.length > TRANSCRIPT_MAX_CHARS
    ? transcript.slice(-TRANSCRIPT_MAX_CHARS)
    : transcript
}

/* 清理项目下会话运行时：系统提示词在创建 Agent 时固化，记忆变更后需重建才能注入最新内容 */
function evictProjectAgents(projectId: string): void {
  listSessionIdsByProject(projectId).forEach((id) => evictAgent(id))
}

/*
 * 一轮对话结束后调用：开启记忆且距上次生成达到阈值轮数时异步生成。
 *   - 以用户消息计数「轮」，lastMessageId 之前的消息不再重复纳入；
 *   - 生成成功覆盖项目记忆并清理项目下会话运行时，使新记忆下一轮生效。
 */
export async function maybeGenerateAgentMemory(sessionId: string): Promise<void> {
  const settings = getSettings()
  if (!settings.agentMemoryEnabled) {
    return
  }
  const session = getSession(sessionId)
  /* 记忆归属项目：未归属项目的会话不生成 */
  if (!session || !session.projectId) {
    return
  }
  const project = getProject(session.projectId)
  if (!project || generatingProjects.has(project.id)) {
    return
  }
  const memory = getDb()
    .select()
    .from(agentMemoryTable)
    .where(eq(agentMemoryTable.projectId, project.id))
    .get()
  const rows = listMessagesAfter(sessionId, memory?.lastMessageId ?? '')
  const rounds = rows.filter((row) => row.role === 'user').length
  if (rounds < AGENT_MEMORY_ROUND_LIMIT) {
    return
  }
  const target = resolveTarget(session, settings)
  if (!target) {
    log.warn(`[agent-memory] 跳过生成：会话 ${sessionId} 未找到可用模型`)
    return
  }
  const transcript = buildTranscript(rows)
  if (!transcript) {
    return
  }
  const lastMessageId = rows[rows.length - 1]?.id ?? ''
  generatingProjects.add(project.id)
  try {
    const content = await generateMemory({
      provider: { ...toRuntimeConfig(target.provider), sessionId },
      modelId: target.modelId,
      language: settings.language,
      oldMemory: memory?.content ?? '',
      transcript
    })
    const saved = content.trim().slice(0, AGENT_MEMORY_MAX_CHARS)
    if (!saved) {
      throw new Error('模型未返回记忆内容')
    }
    /* 生成期间项目可能已被删除：不再写入，避免孤儿记忆 */
    if (!getProject(project.id)) {
      return
    }
    upsertMemory(project.id, saved, lastMessageId)
    /* 系统提示词在创建 Agent 时固化：新记忆需清理项目下会话运行时，下一轮重新装配 */
    evictProjectAgents(project.id)
    log.info(`[agent-memory] 项目 ${project.id} 记忆已更新（${rounds} 轮）`)
  } catch (error) {
    /* 失败隔离：保留旧记忆，下一轮达到阈值时重试 */
    log.warn(`[agent-memory] 生成失败 project=${project.id}`, error)
  } finally {
    generatingProjects.delete(project.id)
  }
}
