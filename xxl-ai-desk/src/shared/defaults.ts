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

/* 自动续写时追加的用户消息：仅用于驱动模型继续，不在对话界面展示 */
export const AUTO_CONTINUE_PROMPT = '[自动续写] 继续输出，不要重复已有内容。'

/* 只有 thinking、没有正文/工具调用时的异常说明 */
export const THINKING_ONLY_NOTE =
  '[系统] 本轮只有思考内容，未生成正文或工具调用，已标记为异常。'


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
