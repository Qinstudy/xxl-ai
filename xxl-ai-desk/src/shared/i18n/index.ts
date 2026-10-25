/*
 * 跨进程 i18n：语言包与取词函数，供 renderer 的 vue-i18n 与主进程 / Worker 共用。
 *   - 语言包统一放在 shared/i18n/locales；
 *   - 主进程侧使用 translate()，renderer 侧继续使用 vue-i18n 的 t()。
 * @author xuxueli 2026-10-26
 */

import zh from './locales/zh.json'
import en from './locales/en.json'

/* 应用语言 */
export type AppLanguage = 'zh' | 'en'

/* 插值参数 */
export type TranslateParams = Array<string | number> | Record<string, string | number>

/* 按点号路径读取词条 */
function lookup(source: unknown, key: string): string | undefined {
  let current = source as Record<string, unknown>
  for (const segment of key.split('.')) {
    if (!current || typeof current !== 'object') {
      return undefined
    }
    current = current[segment] as Record<string, unknown>
  }
  return typeof current === 'string' ? current : undefined
}

/* 替换 {0} / {name} 占位符 */
function interpolate(text: string, params?: TranslateParams): string {
  if (!params) {
    return text
  }
  return text.replace(/\{(\w+)\}/g, (match, name: string) => {
    if (Array.isArray(params)) {
      const value = params[Number(name)]
      return value === undefined ? match : String(value)
    }
    const value = params[name]
    return value === undefined ? match : String(value)
  })
}

/* 取词：目标语言缺失时回退中文，再回退 key */
export function translate(
  language: string,
  key: string,
  params?: TranslateParams
): string {
  const target = language === 'en' ? en : zh
  const text = lookup(target, key) ?? lookup(zh, key)
  return text === undefined ? key : interpolate(text, params)
}

/* 是否为自动续写提示（兼容中英文） */
export function isAutoContinuePrompt(value: string): boolean {
  return value === translate('zh', 'runtime.continuePrompt') ||
    value === translate('en', 'runtime.continuePrompt')
}
