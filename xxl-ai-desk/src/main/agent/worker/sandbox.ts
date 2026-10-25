/*
 * 文件沙箱：把 Agent 的本地文件读写限制在当前项目目录内。
 *   - 目录内：直接放行；
 *   - 软件数据目录下的 SKILL 内容：只读放行（技能是用户主动安装的可信资料，不打断会话）；
 *   - 目录外：拦截并转交主进程弹应用内对话框人工确认（允许本次 / 本会话允许 / 拒绝），
 *     「本会话允许」记入会话级白名单，后续同路径不再重复询问。
 * 路径解析对符号链接做归一化，避免用软链接绕过目录限制。
 *
 * 本模块运行在 Agent 运行时进程（utilityProcess）内，不直接依赖 Electron：
 * 越界审批通过注入的 approvalRequester 回调（跨进程请求主进程对话框）完成。
 */

import { canonicalize, isWithinRoot, resolveTarget } from '../../utils/pathGuard'
import type { ApprovalChoice, ApprovalRequest, PathAction } from '@shared/agentProtocol'

/* 越界访问的动作类型（文案区分用） */
export type { PathAction }

/* 审批询问函数：由运行时进程启动时注入；未注入时一律拒绝，避免越界静默放行 */
type ApprovalRequester = (request: ApprovalRequest) => Promise<ApprovalChoice>
let approvalRequester: ApprovalRequester | null = null

/* 注册审批询问函数（运行时进程启动时调用一次） */
export function setApprovalRequester(requester: ApprovalRequester | null): void {
  approvalRequester = requester
}

/* 会话级越界白名单：sessionId → 已允许的规范化绝对路径集合 */
const sessionAllowed = new Map<string, Set<string>>()

/* 清理会话的越界白名单（删除/清空会话时调用） */
export function clearSessionPermissions(sessionId: string): void {
  sessionAllowed.delete(sessionId)
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
  /* 会话级白名单命中：不再重复询问 */
  if (sessionAllowed.get(options.sessionId)?.has(abs)) {
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
    const set = sessionAllowed.get(options.sessionId) ?? new Set<string>()
    set.add(abs)
    sessionAllowed.set(options.sessionId, set)
    return { allowed: true, abs }
  }
  if (choice === 'once') {
    return { allowed: true, abs }
  }
  return {
    allowed: false,
    abs,
    reason: `已拦截：路径「${abs}」超出项目目录「${options.rootDir}」，用户拒绝本次${options.action === 'write' ? '写入' : '读取'}操作。`
  }
}
