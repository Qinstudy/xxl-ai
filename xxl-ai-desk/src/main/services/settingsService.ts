/*
 * 应用设置服务：以 key-value 持久化主题、语言、默认模型、系统指令等配置。
 *   - 读取时逐项回退默认值；保存为部分更新（upsert）。
 */

import { getDb } from '../db'
import { settingTable } from '../db/schema'
import { DEFAULT_APP_SETTINGS, DEFAULT_SHORTCUTS, type AppSettings } from '@shared/ipc'

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
  return {
    theme: (map.get('theme') as AppSettings['theme']) || DEFAULT_APP_SETTINGS.theme,
    language: (map.get('language') as AppSettings['language']) || DEFAULT_APP_SETTINGS.language,
    providerId: map.get('providerId') || DEFAULT_APP_SETTINGS.providerId,
    modelId: map.get('modelId') || DEFAULT_APP_SETTINGS.modelId,
    systemPrompt: map.get('systemPrompt') ?? DEFAULT_APP_SETTINGS.systemPrompt,
    appName: map.get('appName') ?? DEFAULT_APP_SETTINGS.appName,
    slogan: map.get('slogan') ?? DEFAULT_APP_SETTINGS.slogan,
    shortcuts: parseShortcuts(map.get('shortcuts')),
    runtimeNodeMode: map.get('runtimeNodeMode') === 'custom' ? 'custom' : DEFAULT_APP_SETTINGS.runtimeNodeMode,
    runtimeNodePath: map.get('runtimeNodePath') ?? DEFAULT_APP_SETTINGS.runtimeNodePath,
    runtimePythonPath: map.get('runtimePythonPath') ?? DEFAULT_APP_SETTINGS.runtimePythonPath,
    updateAutoCheck: map.get('updateAutoCheck') !== 'false',
    builtinToolBrowserEnabled: map.get('builtinToolBrowserEnabled') !== 'false',
    builtinToolGeneralEnabled: map.get('builtinToolGeneralEnabled') !== 'false'
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
