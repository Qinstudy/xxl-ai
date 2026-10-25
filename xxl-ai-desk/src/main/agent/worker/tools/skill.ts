/*
 * 技能类内置工具：读取已启用技能的 SKILL.md 与附属文件。
 *   - 只读，Plan / Build 均可用；
 *   - 仅允许访问用户已安装的技能目录，拒绝路径穿越与目录外访问。
 */

import type { AgentTool } from '@earendil-works/pi-agent-core'
import { promises as fs } from 'fs'
import { isAbsolute, relative, resolve } from 'path'
import { ALL_MODES, describe, text, toolError, type ToolContext, type ToolEntry } from './shared'
import type { SkillRuntimeConfig } from '@shared/ipc'

/* 解析技能文件：归一化真实路径并限制在技能目录内 */
async function resolveSkillFile(skill: SkillRuntimeConfig, relativePath: string): Promise<string> {
  const root = await fs.realpath(skill.dir)
  const target = resolve(root, relativePath)
  const rel = relative(root, target)
  if (!rel || rel.startsWith('..') || isAbsolute(rel)) {
    throw new Error('仅可读取当前技能目录内的文件')
  }
  const real = await fs.realpath(target)
  const realRel = relative(root, real)
  if (realRel.startsWith('..') || isAbsolute(realRel)) {
    throw new Error('仅可读取当前技能目录内的文件')
  }
  return real
}

/* 构建技能类工具 */
export async function createSkillTools(context: ToolContext): Promise<ToolEntry[]> {
  const skills = context.skills ?? []
  if (skills.length === 0) {
    return []
  }
  const { Type } = await import('@earendil-works/pi-ai')

  /* 按名称或目录名查找技能（忽略大小写） */
  function findSkill(name: string): SkillRuntimeConfig | undefined {
    const key = name.trim().toLowerCase()
    return skills.find(
      (item) => item.name.toLowerCase() === key || item.id.toLowerCase() === key
    )
  }

  /* 读取技能说明：默认 SKILL.md，可读取技能目录内其他文件 */
  const readSkill: AgentTool = {
    name: 'read_skill',
    label: '读取技能说明',
    description: describe(
      '读取已启用技能的 SKILL.md 完整说明或技能目录内的附属文件；任务与技能描述匹配时，先调用本工具再执行。',
      'Read the full SKILL.md or a supporting file of an enabled skill before following it.'
    ),
    parameters: Type.Object({
      name: Type.String({ description: '技能名称或目录名' }),
      path: Type.Optional(
        Type.String({ description: '技能目录内的相对路径，默认 SKILL.md' })
      )
    }),
    execute: async (_toolCallId, params) => {
      const { name, path } = params as { name: string; path?: string }
      const skill = findSkill(name)
      if (!skill) {
        const available = skills.map((item) => item.name).join('、')
        return toolError(`未找到技能「${name}」。当前可用技能：${available || '无'}`)
      }
      const relativePath = (path ?? '').trim() || 'SKILL.md'
      try {
        const file = await resolveSkillFile(skill, relativePath)
        const buffer = await fs.readFile(file)
        /* 含 NUL 视为二进制，避免把乱码写入上下文 */
        if (buffer.includes(0)) {
          return toolError(`技能文件「${relativePath}」为二进制文件，无法读取`)
        }
        const content = buffer.toString('utf-8')
        return text(
          `技能：${skill.name}\n目录：${skill.dir}\n文件：${relativePath}\n\n${content}`,
          { skill: skill.id, path: relativePath }
        )
      } catch (error) {
        return toolError(`读取技能文件失败：${(error as Error).message}`)
      }
    }
  }

  return [
    {
      meta: {
        group: 'skill',
        readOnly: true,
        modes: ALL_MODES,
        maxOutput: 20000
      },
      tool: readSkill
    }
  ]
}
