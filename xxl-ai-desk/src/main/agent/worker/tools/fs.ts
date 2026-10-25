/*
 * 文件类内置工具：读取、列目录、glob 查找、内容搜索、写入与编辑。
 *   - 全部经统一沙箱闸门，越界路径触发主进程审批；
 *   - 写入类工具声明为串行执行，避免同文件并发读改写导致内容丢失。
 */

import type { AgentTool } from '@earendil-works/pi-agent-core'
import { promises as fs } from 'fs'
import { basename, dirname } from 'path'
import {
  ALL_MODES,
  BUILD_ONLY,
  MAX_OUTPUT,
  createGuard,
  describe,
  displayPath,
  globToRegExp,
  text,
  toolError,
  walkFiles,
  type ToolContext,
  type ToolEntry
} from './shared'

/* 构建文件类工具 */
export async function createFsTools(context: ToolContext): Promise<ToolEntry[]> {
  const { Type } = await import('@earendil-works/pi-ai')
  const { rootDir } = context
  const guard = createGuard(context)

  /* 读取文件：按行范围读取并加行号 */
  const readFile: AgentTool = {
    name: 'read_file',
    label: '读取文件',
    description: describe(
      '读取项目目录内的文本文件，可选 offset/limit 按行范围读取',
      'Read a text file inside the project directory, optionally by line range (offset/limit).'
    ),
    parameters: Type.Object({
      path: Type.String({ description: '文件路径，相对项目目录或绝对路径' }),
      offset: Type.Optional(Type.Number({ description: '起始行号（从 1 开始）' })),
      limit: Type.Optional(Type.Number({ description: '读取行数' }))
    }),
    execute: async (_toolCallId, params) => {
      const { path, offset, limit } = params as { path: string; offset?: number; limit?: number }
      const checked = await guard(path, 'read', 'read_file')
      if (!checked.ok) {
        return checked.result
      }
      try {
        const content = await fs.readFile(checked.abs, 'utf-8')
        const lines = content.split('\n')
        const start = Math.max(0, (offset ?? 1) - 1)
        const end = limit && limit > 0 ? start + limit : lines.length
        const slice = lines.slice(start, end)
        const numbered = slice.map((line, index) => `${start + index + 1}\t${line}`).join('\n')
        return text(numbered || '（空文件）', {
          path: displayPath(rootDir, checked.abs),
          totalLines: lines.length,
          startLine: start + 1,
          endLine: Math.min(end, lines.length)
        })
      } catch (error) {
        return toolError(`读取失败：${(error as Error).message}`)
      }
    }
  }

  /* 列出目录：目录优先、名称排序，标注 [dir]/[file] */
  const listDirectory: AgentTool = {
    name: 'list_directory',
    label: '列出目录',
    description: describe(
      '列出项目目录内某个目录的文件与子目录',
      'List files and subdirectories of a directory inside the project.'
    ),
    parameters: Type.Object({
      path: Type.Optional(Type.String({ description: '目录路径，缺省为项目根目录' }))
    }),
    execute: async (_toolCallId, params) => {
      const { path } = params as { path?: string }
      const checked = await guard(path, 'read', 'list_directory')
      if (!checked.ok) {
        return checked.result
      }
      try {
        const entries = await fs.readdir(checked.abs, { withFileTypes: true })
        const lines = entries
          .sort((a, b) => Number(b.isDirectory()) - Number(a.isDirectory()) || a.name.localeCompare(b.name))
          .map((entry) => `${entry.isDirectory() ? '[dir] ' : '[file]'} ${entry.name}`)
        return text(lines.join('\n') || '（空目录）', {
          path: displayPath(rootDir, checked.abs),
          count: entries.length
        })
      } catch (error) {
        return toolError(`列出目录失败：${(error as Error).message}`)
      }
    }
  }

  /* 查找文件：按 glob 模式匹配，返回相对路径 */
  const globFiles: AgentTool = {
    name: 'glob_files',
    label: '查找文件',
    description: describe(
      '按 glob 模式（支持 * ? 与 **）在项目目录内查找文件，返回匹配的相对路径',
      'Find files inside the project by glob pattern (supports * ? and **), returning relative paths.'
    ),
    parameters: Type.Object({
      pattern: Type.String({ description: 'glob 模式，如 src/**/*.ts' }),
      path: Type.Optional(Type.String({ description: '起始目录，缺省为项目根目录' }))
    }),
    execute: async (_toolCallId, params) => {
      const { pattern, path } = params as { pattern: string; path?: string }
      const checked = await guard(path, 'read', 'glob_files')
      if (!checked.ok) {
        return checked.result
      }
      const matcher = globToRegExp(pattern)
      const files = await walkFiles(checked.abs)
      const matched = files
        .map((file) => displayPath(rootDir, file))
        .filter((file) => matcher.test(file))
      return text(matched.join('\n') || '未找到匹配文件', { count: matched.length })
    }
  }

  /* 搜索内容：正则匹配，返回 文件:行号:内容 */
  const searchFiles: AgentTool = {
    name: 'search_files',
    label: '搜索内容',
    description: describe(
      '在项目目录内按正则搜索文件内容，返回 文件:行号:内容',
      'Search file contents inside the project by regular expression, returning file:line:content.'
    ),
    parameters: Type.Object({
      pattern: Type.String({ description: '正则表达式' }),
      path: Type.Optional(Type.String({ description: '起始目录，缺省为项目根目录' })),
      include: Type.Optional(Type.String({ description: '文件名 glob 过滤，如 *.ts' })),
      max_results: Type.Optional(Type.Number({ description: '最大匹配数，默认 100' }))
    }),
    execute: async (_toolCallId, params) => {
      const { pattern, path, include, max_results } = params as {
        pattern: string
        path?: string
        include?: string
        max_results?: number
      }
      const checked = await guard(path, 'read', 'search_files')
      if (!checked.ok) {
        return checked.result
      }
      let matcher: RegExp
      try {
        matcher = new RegExp(pattern)
      } catch (error) {
        return toolError(`正则非法：${(error as Error).message}`)
      }
      const includeMatcher = include ? globToRegExp(include) : null
      const max = max_results && max_results > 0 ? max_results : 100
      const files = await walkFiles(checked.abs)
      const output: string[] = []
      for (const file of files) {
        if (output.length >= max) {
          break
        }
        const rel = displayPath(rootDir, file)
        if (includeMatcher && !includeMatcher.test(basename(file))) {
          continue
        }
        let content: string
        try {
          content = await fs.readFile(file, 'utf-8')
        } catch {
          continue
        }
        const lines = content.split('\n')
        for (let i = 0; i < lines.length && output.length < max; i += 1) {
          if (matcher.test(lines[i])) {
            output.push(`${rel}:${i + 1}:${lines[i].trim()}`)
          }
        }
      }
      return text(output.join('\n') || '未找到匹配内容', {
        count: output.length,
        max
      })
    }
  }

  /* 写入文件：创建或覆盖，自动创建父目录 */
  const writeFile: AgentTool = {
    name: 'write_file',
    label: '写入文件',
    description: describe(
      '创建或覆盖项目目录内的文件（自动创建父目录）',
      'Create or overwrite a file inside the project directory (parent directories are created automatically).'
    ),
    parameters: Type.Object({
      path: Type.String({ description: '文件路径，相对项目目录或绝对路径' }),
      content: Type.String({ description: '写入的完整内容' })
    }),
    /* 写类工具串行执行：避免同文件并发读改写互相覆盖 */
    executionMode: 'sequential',
    execute: async (_toolCallId, params) => {
      const { path, content } = params as { path: string; content: string }
      const checked = await guard(path, 'write', 'write_file')
      if (!checked.ok) {
        return checked.result
      }
      try {
        await fs.mkdir(dirname(checked.abs), { recursive: true })
        await fs.writeFile(checked.abs, content, 'utf-8')
        return text(
          `已写入 ${displayPath(rootDir, checked.abs)}（${content.length} 字符）`,
          { path: displayPath(rootDir, checked.abs), chars: content.length }
        )
      } catch (error) {
        return toolError(`写入失败：${(error as Error).message}`)
      }
    }
  }

  /* 编辑文件：old_string → new_string 替换，可全量替换 */
  const editFile: AgentTool = {
    name: 'edit_file',
    label: '编辑文件',
    description: describe(
      '将项目目录内文件中的 old_string 替换为 new_string，replace_all 为 true 时替换全部',
      'Replace old_string with new_string in a project file; set replace_all to replace every occurrence.'
    ),
    parameters: Type.Object({
      path: Type.String({ description: '文件路径，相对项目目录或绝对路径' }),
      old_string: Type.String({ description: '待替换的原文（需精确匹配）' }),
      new_string: Type.String({ description: '替换后的新文本' }),
      replace_all: Type.Optional(Type.Boolean({ description: '是否替换全部匹配项，默认仅替换首个' }))
    }),
    /* 写类工具串行执行：避免同文件并发读改写互相覆盖 */
    executionMode: 'sequential',
    execute: async (_toolCallId, params) => {
      const { path, old_string, new_string, replace_all } = params as {
        path: string
        old_string: string
        new_string: string
        replace_all?: boolean
      }
      const checked = await guard(path, 'write', 'edit_file')
      if (!checked.ok) {
        return checked.result
      }
      if (old_string === new_string) {
        return toolError('old_string 与 new_string 相同，无需替换')
      }
      try {
        const content = await fs.readFile(checked.abs, 'utf-8')
        if (!content.includes(old_string)) {
          return toolError('未找到 old_string，替换失败')
        }
        const count = replace_all
          ? content.split(old_string).length - 1
          : 1
        const updated = replace_all
          ? content.split(old_string).join(new_string)
          : content.replace(old_string, new_string)
        await fs.writeFile(checked.abs, updated, 'utf-8')
        return text(
          `已更新 ${displayPath(rootDir, checked.abs)}（替换 ${count} 处）`,
          { path: displayPath(rootDir, checked.abs), replaced: count }
        )
      } catch (error) {
        return toolError(`编辑失败：${(error as Error).message}`)
      }
    }
  }

  return [
    {
      meta: { group: 'fs', readOnly: true, modes: ALL_MODES, maxOutput: MAX_OUTPUT },
      tool: readFile
    },
    {
      meta: { group: 'fs', readOnly: true, modes: ALL_MODES, maxOutput: 12000 },
      tool: listDirectory
    },
    {
      meta: { group: 'fs', readOnly: true, modes: ALL_MODES, maxOutput: 12000 },
      tool: globFiles
    },
    {
      meta: { group: 'fs', readOnly: true, modes: ALL_MODES, maxOutput: MAX_OUTPUT },
      tool: searchFiles
    },
    {
      meta: { group: 'fs', readOnly: false, modes: BUILD_ONLY, requiresApproval: true, maxOutput: 2000 },
      tool: writeFile
    },
    {
      meta: { group: 'fs', readOnly: false, modes: BUILD_ONLY, requiresApproval: true, maxOutput: 2000 },
      tool: editFile
    }
  ]
}
