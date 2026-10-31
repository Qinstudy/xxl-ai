/*
 * 应用设置服务：以 key-value 持久化外观、主题预设、语言、默认模型、系统指令等配置。
 *   - 读取时逐项回退默认值；保存为部分更新（upsert）。
 *   - 兼容旧版：外观曾存于 theme 键，新版本改读 appearance，旧值仅作为外观回退。
 */

import { getDb } from '../db'
import { settingTable } from '../db/schema'
import {
  APPEARANCES,
  DEFAULT_APP_SETTINGS,
  DEFAULT_SHORTCUTS,
  THEME_PRESETS,
  type AppSettings
} from '@shared/ipc'
import { translate } from '@shared/i18n'

/* 外观取值集合（用于配置合法性校验） */
const APPEARANCE_VALUES = new Set<string>(APPEARANCES)
/* 主题预设集合 */
const THEME_VALUES = new Set<string>(THEME_PRESETS)

/* 归一化外观：非法或旧值回退默认 */
function normalizeAppearance(value: string | undefined): AppSettings['appearance'] {
  return APPEARANCE_VALUES.has(value ?? '')
    ? (value as AppSettings['appearance'])
    : DEFAULT_APP_SETTINGS.appearance
}

/* 归一化主题预设：非法值回退默认预设 */
function normalizeTheme(value: string | undefined): AppSettings['themePreset'] {
  return THEME_VALUES.has(value ?? '')
    ? (value as AppSettings['themePreset'])
    : DEFAULT_APP_SETTINGS.themePreset
}

/* 解析快捷键配置（JSON 存储，缺省项回退默认值） */
function parseShortcuts(raw: string | undefined): AppSettings['shortcuts'] {
  if (!raw) {
    return { ...DEFAULT_SHORTCUTS }
  }
  try {
    const parsed = JSON.parse(raw)
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return { ...DEFAULT_SHORTCUTS, ...parsed }
    }
  } catch {
    /* 解析失败回退默认值 */
  }
  return { ...DEFAULT_SHORTCUTS }
}

/* 读取全部设置（缺省值兜底） */
export function getSettings(): AppSettings {
  const rows = getDb().select().from(settingTable).all()
  const map = new Map(rows.map((row) => [row.key, row.value]))
  const language = (map.get('language') as AppSettings['language']) || DEFAULT_APP_SETTINGS.language
  /* 兼容旧版：appearance 未写入时读取旧 theme 键（light/dark/system） */
  const legacyTheme = map.get('theme')
  return {
    appearance: normalizeAppearance(map.get('appearance') ?? legacyTheme),
    themePreset: normalizeTheme(map.get('themePreset')),
    language,
    providerId: map.get('providerId') || DEFAULT_APP_SETTINGS.providerId,
    modelId: map.get('modelId') || DEFAULT_APP_SETTINGS.modelId,
    systemPrompt: map.get('systemPrompt') ?? translate(language, 'runtime.defaultSystemPrompt'),
    appName: map.get('appName') ?? DEFAULT_APP_SETTINGS.appName,
    slogan: map.get('slogan') ?? DEFAULT_APP_SETTINGS.slogan,
    shortcuts: parseShortcuts(map.get('shortcuts')),
    runtimeNodeMode: map.get('runtimeNodeMode') === 'custom' ? 'custom' : DEFAULT_APP_SETTINGS.runtimeNodeMode,
    runtimeNodePath: map.get('runtimeNodePath') ?? DEFAULT_APP_SETTINGS.runtimeNodePath,
    runtimePythonPath: map.get('runtimePythonPath') ?? DEFAULT_APP_SETTINGS.runtimePythonPath,
    updateAutoCheck: map.get('updateAutoCheck') !== 'false',
    builtinToolBrowserEnabled: map.get('builtinToolBrowserEnabled') !== 'false',
    builtinToolGeneralEnabled: map.get('builtinToolGeneralEnabled') !== 'false',
    builtinToolInteractionEnabled: map.get('builtinToolInteractionEnabled') !== 'false'
  }
}

/* 保存部分设置（key-value upsert；对象值序列化为 JSON 文本） */
export function saveSettings(patch: Partial<AppSettings>): AppSettings {
  const db = getDb()
  for (const [key, value] of Object.entries(patch)) {
    if (value === undefined || value === null) {
      continue
    }
    const text = typeof value === 'object' ? JSON.stringify(value) : String(value)
    db.insert(settingTable)
      .values({ key, value: text })
      .onConflictDoUpdate({ target: settingTable.key, set: { value: text } })
      .run()
  }
  return getSettings()
}
