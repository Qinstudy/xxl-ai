/*
 * 内置工具注册表：按域聚合工具工厂，并按对话模式统一装配。
 *   - 每个工具自带 ToolMeta（分组 / 只读 / 适用模式 / 审批 / 输出上限）；
 *   - 新增工具只需在对应域文件中追加定义，无需修改装配逻辑；
 *   - 同时生成「当前可用工具」提示，随系统指令下发给模型。
 */

import type { AgentTool } from '@earendil-works/pi-agent-core'
import { type ToolContext, type ToolEntry } from './shared'
import { createGeneralTools } from './general'
import { createFsTools } from './fs'
import { createShellTools } from './shell'
import { createBrowserTools } from './browser'
import { createSkillTools } from './skill'

/* 按域聚合的工具工厂（新增域在此登记） */
const FACTORIES: Array<(context: ToolContext) => Promise<ToolEntry[]>> = [
  createGeneralTools,
  createFsTools,
  createShellTools,
  createBrowserTools,
  createSkillTools
]

/* 构建当前模式下的工具条目（含元信息，供提示生成使用） */
export async function createBuiltinToolEntries(context: ToolContext): Promise<ToolEntry[]> {
  const groups = await Promise.all(FACTORIES.map((factory) => factory(context)))
  return groups.flat().filter((entry) => entry.meta.modes.includes(context.mode))
}

/* 构建当前模式下的 Pi 工具集合 */
export async function createBuiltinTools(context: ToolContext): Promise<AgentTool[]> {
  const entries = await createBuiltinToolEntries(context)
  return entries.map((entry) => entry.tool)
}

/* 生成可用工具提示：名称 + 读写属性 + 一句话说明，避免模型臆造工具 */
export function buildToolGuide(entries: ToolEntry[]): string {
  if (entries.length === 0) {
    return ''
  }
  const lines = entries.map(
    ({ meta, tool }) => `- ${tool.name}（${meta.readOnly ? '只读' : '可写'}）：${tool.label}`
  )
  return `\n\n当前可用工具：\n${lines.join('\n')}`
}

/* 运行时 PATH 注入（终端与 MCP stdio 共用） */
export { setRuntimePath } from './shell'
/* 浏览器打开 / 动作回调注入 */
export { setBrowserOpener, setBrowserActionInvoker } from './browser'
export type { ToolContext, ToolEntry, ToolMeta } from './shared'
