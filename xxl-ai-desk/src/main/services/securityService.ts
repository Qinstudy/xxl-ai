/*
 * 本地密钥编解码：优先使用系统钥匙串（Electron safeStorage）加密，能力不可用时退化为 base64 编码。
 *   - 新数据统一写入 enc:v2: 前缀（safeStorage 密文）或 enc:b64: 前缀（无钥匙串环境的兜底）；
 *   - 历史 safeStorage 密文（enc:v1:）与早期无前缀 base64/明文仍可读出，保证老数据不失效。
 * @author xuxueli 2026-10-25
 */

import { safeStorage } from 'electron'

/* 当前 safeStorage 密文前缀 */
const SAFE_PREFIX = 'enc:v2:'

/* 无钥匙串环境的 base64 兜底前缀（可逆编码，仅避免明文直读） */
const BASE64_PREFIX = 'enc:b64:'

/* 历史 safeStorage 密文前缀 */
const LEGACY_PREFIX = 'enc:v1:'

/* 系统钥匙串是否可用（不可用时记录告警，仅编码兜底） */
function canUseSafeStorage(): boolean {
  try {
    return safeStorage.isEncryptionAvailable()
  } catch {
    return false
  }
}

/* 编码敏感字符串（如供应商 API Key）：优先钥匙串加密，其次 base64 兜底 */
export function encryptSecret(plain: string): string {
  if (!plain) {
    return ''
  }
  if (canUseSafeStorage()) {
    try {
      return `${SAFE_PREFIX}${safeStorage.encryptString(plain).toString('base64')}`
    } catch {
      /* 钥匙串异常时退回编码兜底 */
    }
  }
  return `${BASE64_PREFIX}${Buffer.from(plain, 'utf-8').toString('base64')}`
}

/* 解码敏感字符串：兼容新密文、历史密文与无前缀 base64/明文 */
export function decryptSecret(value: string): string {
  if (!value) {
    return ''
  }
  if (value.startsWith(SAFE_PREFIX) || value.startsWith(LEGACY_PREFIX)) {
    const prefix = value.startsWith(SAFE_PREFIX) ? SAFE_PREFIX : LEGACY_PREFIX
    try {
      return safeStorage.decryptString(Buffer.from(value.slice(prefix.length), 'base64'))
    } catch {
      return ''
    }
  }
  if (value.startsWith(BASE64_PREFIX)) {
    return decodeBase64(value.slice(BASE64_PREFIX.length)) ?? ''
  }
  /* 早期数据：无前缀 base64（可无损往返则解码），否则按明文兼容 */
  return decodeBase64(value) ?? value
}

/* base64 解码：无法无损往返时返回 null（视为历史明文） */
function decodeBase64(value: string): string | null {
  try {
    const decoded = Buffer.from(value, 'base64').toString('utf-8')
    return Buffer.from(decoded, 'utf-8').toString('base64') === value ? decoded : null
  } catch {
    return null
  }
}
