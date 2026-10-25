/*
 * 跨进程共享默认值：应用设置、系统指令与会话占位标题。
 *   - 主进程初始化与渲染进程初始状态共用同一份，避免两处默认值漂移；
 *   - 会话标题不再用「新对话」作为状态哨兵，统一以空串表示未命名。
 * @author xuxueli 2026-10-25
 */

import { DEFAULT_SHORTCUTS, type AppSettings } from './contracts/app'

/* 默认系统指令（模型侧文案，不参与界面 i18n） */
export const DEFAULT_SYSTEM_PROMPT = '你是 XXL-AI-Desk 智能助手，回答简洁、准确、有条理。'

/* 新建会话的占位标题：空串表示「尚未由首条提问命名」 */
export const NEW_CHAT_TITLE = ''

/* 历史版本占位标题（仅用于读取旧数据时兼容识别，不再写入） */
const LEGACY_NEW_CHAT_TITLE = '新对话'

/* 是否仍为未命名会话（含历史数据兼容） */
export function isNewChatTitle(title: string): boolean {
  const value = (title ?? '').trim()
  return value.length === 0 || value === LEGACY_NEW_CHAT_TITLE
}

/* 默认应用设置（主进程与渲染进程共用） */
export const DEFAULT_APP_SETTINGS: AppSettings = {
  theme: 'light',
  language: 'zh',
  providerId: '',
  modelId: '',
  systemPrompt: DEFAULT_SYSTEM_PROMPT,
  appName: '',
  slogan: '',
  shortcuts: DEFAULT_SHORTCUTS,
  runtimeNodeMode: 'builtin',
  runtimeNodePath: '',
  runtimePythonPath: '',
  updateAutoCheck: true,
  builtinToolBrowserEnabled: true,
  builtinToolGeneralEnabled: true
}

/* 生成一份可安全修改的默认设置副本（含嵌套 shortcuts） */
export function createDefaultSettings(): AppSettings {
  return { ...DEFAULT_APP_SETTINGS, shortcuts: { ...DEFAULT_APP_SETTINGS.shortcuts } }
}
