/*
 * 文件沙箱：把 Agent 的本地文件读写限制在当前项目目录内。
 *   - 目录内：直接放行；
 *   - 软件数据目录下的 SKILL 内容：只读放行（技能是用户主动安装的可信资料，不打断会话）；
 *   - 目录外：拦截并转交主进程弹应用内对话框人工确认（允许本次 / 本会话允许 / 拒绝），
 *     「本会话允许」记入会话级信任，本会话内后续越界访问不再重复询问。
 * 路径解析对符号链接做归一化，避免用软链接绕过目录限制。
 *
 * 本模块运行在 Agent 运行时进程（utilityProcess）内，不直接依赖 Electron：
 * 越界审批通过注入的 approvalRequester 回调（跨进程请求主进程对话框）完成。
 */

import { canonicalize, isWithinRoot, resolveTarget } from '../../utils/pathGuard'
import type { ApprovalChoice, ApprovalRequest, PathAction } from '@shared/agentProtocol'
import { translate } from '@shared/i18n'

/* 越界访问的动作类型（文案区分用） */
export type { PathAction }

/* 审批询问函数：由运行时进程启动时注入；未注入时一律拒绝，避免越界静默放行 */
type ApprovalRequester = (request: ApprovalRequest) => Promise<ApprovalChoice>
let approvalRequester: ApprovalRequester | null = null

/* 注册审批询问函数（运行时进程启动时调用一次） */
export function setApprovalRequester(requester: ApprovalRequester | null): void {
  approvalRequester = requester
}

/* 会话级越界放行：用户在弹窗选择「本会话允许」后，该会话内所有越界访问不再询问 */
const sessionTrusted = new Set<string>()

/* 清理会话的越界放行状态（删除/清空会话时调用） */
export function clearSessionPermissions(sessionId: string): void {
  sessionTrusted.delete(sessionId)
}

/* 请求越界审批：转交主进程弹原生对话框；未注册或异常时按拒绝处理 */
async function requestApproval(request: ApprovalRequest): Promise<ApprovalChoice> {
  if (!approvalRequester) {
    return 'deny'
  }
  try {
    return await approvalRequester(request)
  } catch {
    return 'deny'
  }
}

/* 判断目标是否越界：越界返回规范化绝对路径，目录内返回 null（供 shell 等自定义路径识别复用） */
export function outsidePath(rootDir: string, target: string): string | null {
  const root = canonicalize(rootDir)
  const abs = canonicalize(target)
  return isWithinRoot(root, abs) ? null : abs
}

/*
 * 目录内直接放行的判定与越界确认：
 *   返回 allowed=false 时，reason 为可直接回填给模型的拦截说明。
 */
export async function guardPath(options: {
  sessionId: string
  rootDir: string
  target?: string
  action: PathAction
  tool: string
  /* 应用语言：拦截文案使用对应语言 */
  language?: string
  /* 只读放行目录（如已启用技能目录）：读取越界到这些目录时直接放行 */
  readRoots?: string[]
}): Promise<{ allowed: boolean; abs: string; reason?: string }> {
  const root = canonicalize(options.rootDir)
  const abs = canonicalize(resolveTarget(options.rootDir, options.target))
  if (isWithinRoot(root, abs)) {
    return { allowed: true, abs }
  }
  /* SKILL 内容（数据目录下已启用技能目录）：读取直接放行，不弹审批；写入仍按越界流程 */
  if (
    options.action === 'read' &&
    (options.readRoots ?? []).some((item) => isWithinRoot(canonicalize(item), abs))
  ) {
    return { allowed: true, abs }
  }
  /* 会话已授权全部越界访问：不再重复询问 */
  if (sessionTrusted.has(options.sessionId)) {
    return { allowed: true, abs }
  }
  const choice = await requestApproval({
    sessionId: options.sessionId,
    tool: options.tool,
    action: options.action,
    abs,
    root: options.rootDir
  })
  if (choice === 'session') {
    sessionTrusted.add(options.sessionId)
    return { allowed: true, abs }
  }
  if (choice === 'once') {
    return { allowed: true, abs }
  }
  return {
    allowed: false,
    abs,
    reason: translate(options.language ?? 'zh', 'runtime.tool.pathDenied', [
      abs,
      options.rootDir,
      options.action === 'write'
        ? translate(options.language ?? 'zh', 'runtime.tool.actionWrite')
        : options.action === 'command'
          ? translate(options.language ?? 'zh', 'runtime.tool.actionCommand')
          : translate(options.language ?? 'zh', 'runtime.tool.actionRead')
    ])
  }
}
