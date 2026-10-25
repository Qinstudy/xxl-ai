/*
 * 内置工具公共能力：工具元信息类型、统一结果构造、路径闸门与目录遍历。
 *   - 所有文件类工具统一经 createGuard 走沙箱闸门（目录内放行、越界审批）；
 *   - 结果文本统一按工具声明的 maxOutput 截断，避免大输出撑爆上下文。
 */

import type { AgentTool } from '@earendil-works/pi-agent-core'
import { promises as fs } from 'fs'
import type { Dirent } from 'fs'
import { basename, relative, resolve, sep } from 'path'
import type { ChatMode } from '@shared/ipc'
import type { SkillRuntimeConfig } from '@shared/ipc'
import { guardPath, type PathAction } from '../sandbox'

/* 工具装配上下文 */
export interface ToolContext {
  mode: ChatMode
  rootDir: string
  sessionId: string
  /* 已启用的技能索引（正文由 read_skill 按需读取） */
  skills?: SkillRuntimeConfig[]
  /* 浏览器类内置工具是否启用（关闭时浏览器工具工厂直接返回空集） */
  browserToolsEnabled: boolean
  /* 通用类内置工具是否启用（关闭时通用工具工厂直接返回空集） */
  generalToolsEnabled: boolean
}

/* 工具分组：便于按域拆分文件与后续扩展 */
export type ToolGroup = 'fs' | 'shell' | 'browser' | 'general' | 'skill'

/* 工具元信息：能力声明与装配策略（模式过滤由 registry 统一执行） */
export interface ToolMeta {
  group: ToolGroup
  /* 是否只读：用于生成可用工具说明，以及 Plan 模式下的语义约束 */
  readOnly: boolean
  /* 允许出现的对话模式（显式声明，避免靠数组位置隐含） */
  modes: ChatMode[]
  /* 是否包含审批/拦截闸门（越界路径或危险命令） */
  requiresApproval?: boolean
  /* 单次结果最大字符数（缺省 MAX_OUTPUT） */
  maxOutput?: number
}

/* 工具条目：元信息 + Pi 工具定义 */
export interface ToolEntry {
  meta: ToolMeta
  tool: AgentTool
}

/* 单次工具结果返回的文本内容上限（字符数，缺省值） */
export const MAX_OUTPUT = 20000

/* 两种模式均可用 */
export const ALL_MODES: ChatMode[] = ['plan', 'build']

/* 仅 Build（写操作）可用 */
export const BUILD_ONLY: ChatMode[] = ['build']

/* 中英文工具描述：模型对两种语言均友好，保留中文可读性同时兼顾英文模型 */
export function describe(zh: string, en: string): string {
  return `${zh}\n${en}`
}

/* 文本结果（details 供 UI / 落库展示结构化信息） */
export function text(
  value: string,
  details: Record<string, unknown> = {},
  maxOutput = MAX_OUTPUT
): { content: { type: 'text'; text: string }[]; details: Record<string, unknown> } {
  const clipped =
    value.length > maxOutput ? `${value.slice(0, maxOutput)}\n...(已截断)` : value
  return { content: [{ type: 'text' as const, text: clipped }], details }
}

/* 错误结果（模型可见，UI 标记为失败） */
export function toolError(value: string): {
  content: { type: 'text'; text: string }[]
  details: Record<string, unknown>
  isError: true
} {
  return { content: [{ type: 'text' as const, text: value }], details: {}, isError: true }
}

/*
 * 统一路径闸门：目录内直接放行，越界转主进程审批；拒绝则返回可直接回填模型的错误。
 * 具体工具只需声明动作类型（read / write）与工具名。
 */
export function createGuard(context: ToolContext) {
  return async function guard(
    target: string | undefined,
    action: PathAction,
    tool: string
  ): Promise<
    { ok: true; abs: string } | { ok: false; result: ReturnType<typeof toolError> }
  > {
    const outcome = await guardPath({
      sessionId: context.sessionId,
      rootDir: context.rootDir,
      target,
      action,
      tool
    })
    if (!outcome.allowed) {
      return { ok: false, result: toolError(outcome.reason ?? '已拦截：超出项目目录') }
    }
    return { ok: true, abs: outcome.abs }
  }
}

/* glob 模式 → 正则：支持 * ? 与 ** */
export function globToRegExp(pattern: string): RegExp {
  const escaped = pattern
    .replace(/[.+^${}()|[\]\\]/g, '\\$&')
    .replace(/\*\*\//g, '\u0000')
    .replace(/\*\*/g, '\u0001')
    .replace(/\*/g, '[^/]*')
    .replace(/\?/g, '[^/]')
    .replace(/\u0000/g, '(?:.*/)?')
    .replace(/\u0001/g, '.*')
  return new RegExp(`^${escaped}$`)
}

/* 遍历时跳过的目录（避免扫描依赖与版本库） */
const SKIP_DIRS = new Set(['.git', 'node_modules', '.svn', '.hg'])

/* 递归收集根目录下的文件（绝对路径），达到上限即停止 */
export async function walkFiles(dir: string, max = 5000): Promise<string[]> {
  const results: string[] = []
  async function walk(current: string): Promise<void> {
    if (results.length >= max) {
      return
    }
    let entries: Dirent[]
    try {
      entries = await fs.readdir(current, { withFileTypes: true })
    } catch {
      return
    }
    for (const entry of entries) {
      if (results.length >= max) {
        return
      }
      const full = resolve(current, entry.name)
      if (entry.isDirectory()) {
        if (SKIP_DIRS.has(entry.name)) {
          continue
        }
        await walk(full)
      } else if (entry.isFile()) {
        results.push(full)
      }
    }
  }
  await walk(dir)
  return results
}

/* 绝对路径 → 相对根目录的 POSIX 展示路径 */
export function displayPath(rootDir: string, abs: string): string {
  const rel = relative(rootDir, abs)
  return rel.split(sep).join('/') || basename(abs)
}
