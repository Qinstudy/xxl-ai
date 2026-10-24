/*
 * 终端类内置工具：在项目目录内执行 shell 命令。
 *   - 危险命令（破坏系统 / 提权 / 管道执行远端脚本）先拦截，再交模型解释原因；
 *   - 串行执行，用户终止时同步杀掉子进程；输出统一截断，避免大 buffer 常驻。
 * 说明：本闸门是护栏而非完整沙箱，命令本身仍具备当前用户权限。
 */

import type { AgentTool } from '@earendil-works/pi-agent-core'
import { exec } from 'child_process'
import {
  BUILD_ONLY,
  MAX_OUTPUT,
  describe,
  text,
  type ToolContext,
  type ToolEntry
} from './shared'

/* 运行时 PATH（主进程随每轮对话下发，供命令使用设置的 Node / Python 环境） */
let runtimePath: string | undefined

/* 设置运行时 PATH（每轮对话开始时由运行时进程调用） */
export function setRuntimePath(value?: string): void {
  runtimePath = value?.trim() || undefined
}

/* 危险命令规则：命中即拦截，返回可读原因 */
const DANGEROUS_RULES: Array<{ pattern: RegExp; reason: string }> = [
  {
    /* rm -rf /、rm -rf ~、rm -rf /* 等根目录/家目录递归删除 */
    pattern: /\brm\s+(-[a-z]*[rf][a-z]*\s+)+(\/|~|\$HOME|\/\*|\.\.)(\s|$|\/)/i,
    reason: '递归删除根目录或家目录'
  },
  {
    /* 格式化文件系统 / 写裸设备 */
    pattern: /\bmkfs(\.\w+)?\b|\bdd\b[^\n]*\bof=\/dev\//i,
    reason: '格式化磁盘或写入块设备'
  },
  {
    /* 关机、重启、停机 */
    pattern: /\b(shutdown|reboot|poweroff|halt)\b/i,
    reason: '关机或重启系统'
  },
  {
    /* 全局放开权限 / 改根目录属主 */
    pattern: /\bchmod\s+(-[a-z]+\s+)*0?777\s+\/(\s|$)/i,
    reason: '放开根目录写权限'
  },
  {
    /* 提权执行：交由用户手动确认（允许前置空白） */
    pattern: /(^|[;&|]\s*)\s*sudo\b/i,
    reason: '需要提权（sudo）'
  },
  {
    /* 重定向覆盖系统目录 */
    pattern: />\s*\/(etc|usr|bin|sbin|boot|dev|var|System)\//i,
    reason: '覆盖系统目录文件'
  },
  {
    /* 下载后直接交给 shell 执行 */
    pattern: /\b(curl|wget)\b[^\n]*\|\s*(sudo\s+)?(ba|z|k)?sh\b/i,
    reason: '下载内容直接交给 shell 执行'
  },
  {
    /* fork 炸弹 */
    pattern: /:\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:/,
    reason: 'fork 炸弹'
  }
]

/*
 * 检测危险命令：命中返回原因，未命中返回 null。
 * 纯函数，便于单测；规则保持保守，避免误伤常规开发命令。
 */
export function detectDangerousCommand(command: string): string | null {
  const value = command.trim()
  if (!value) {
    return null
  }
  for (const rule of DANGEROUS_RULES) {
    if (rule.pattern.test(value)) {
      return rule.reason
    }
  }
  return null
}

/* 构建终端类工具 */
export async function createShellTools(context: ToolContext): Promise<ToolEntry[]> {
  const { Type } = await import('@earendil-works/pi-ai')
  const { rootDir } = context

  /* 执行命令：项目目录内执行 shell 并汇总 stdout/stderr，仅 Build 模式 */
  const runCommand: AgentTool = {
    name: 'run_command',
    label: '执行命令',
    description: describe(
      '在项目目录内执行 shell 命令并返回输出（仅 Build 模式可用）。危险命令（删除根目录、提权、管道执行远端脚本等）会被拦截',
      'Run a shell command inside the project directory and return its output (Build mode only). Destructive commands (root deletion, privilege escalation, piping remote scripts to a shell) are blocked.'
    ),
    parameters: Type.Object({
      command: Type.String({ description: '要执行的命令' }),
      timeout: Type.Optional(Type.Number({ description: '超时毫秒数，默认 60000' }))
    }),
    /* 与其他工具串行，避免命令与文件写入交错造成不确定结果 */
    executionMode: 'sequential',
    execute: async (_toolCallId, params, signal) => {
      const { command, timeout } = params as { command: string; timeout?: number }
      /* 危险命令先拦截：把原因回填给模型，由模型决定改用安全方式 */
      const danger = detectDangerousCommand(command)
      if (danger) {
        return {
          content: [
            {
              type: 'text' as const,
              text: `已拦截危险命令（${danger}）：${command}\n如确需执行，请用户自行在终端中运行。`
            }
          ],
          details: { blocked: true, reason: danger },
          isError: true
        }
      }
      return new Promise<ReturnType<typeof text>>((resolvePromise) => {
        exec(
          command,
          {
            cwd: rootDir,
            /* 使用设置中配置的 Node / Python 运行环境 */
            env: runtimePath ? { ...process.env, PATH: runtimePath } : process.env,
            timeout: timeout && timeout > 0 ? timeout : 60000,
            /* 输出最终会被截断到 20k 字符，限制收集上限避免大 buffer 常驻 */
            maxBuffer: 1024 * 1024 * 2,
            /* 用户点击终止时同步杀掉子进程，避免长时间占用 */
            signal
          },
          (error, stdout, stderr) => {
            const parts: string[] = []
            if (stdout) {
              parts.push(stdout)
            }
            if (stderr) {
              parts.push(`[stderr]\n${stderr}`)
            }
            if (error) {
              parts.push(signal?.aborted ? '[已终止] 命令被用户终止' : `[exit] ${error.message}`)
            }
            const rawCode = error ? (error as NodeJS.ErrnoException).code : undefined
            const exitCode =
              typeof rawCode === 'number' ? rawCode : error ? 1 : 0
            resolvePromise(
              text(parts.join('\n') || '（命令无输出）', {
                exitCode,
                timedOut: Boolean(error && (error as { killed?: boolean }).killed),
                aborted: Boolean(signal?.aborted)
              }, MAX_OUTPUT)
            )
          }
        )
      })
    }
  }

  return [
    {
      meta: {
        group: 'shell',
        readOnly: false,
        modes: BUILD_ONLY,
        requiresApproval: true,
        maxOutput: MAX_OUTPUT
      },
      tool: runCommand
    }
  ]
}
