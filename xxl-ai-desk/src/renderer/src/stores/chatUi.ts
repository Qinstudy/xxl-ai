/* 会话级对话 UI 状态：输入草稿与消息滚动位置（与工作区工具/终端状态分离） */

import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

/* 单个会话的对话 UI 状态 */
export interface ChatUiState {
  /* 输入框草稿 */
  draft: string
  /* 消息区滚动位置 */
  scrollTop: number
  /* 最近更新时间 */
  updateTime: number
  /* 持久化结构版本 */
  version: number
}

/* 持久化键前缀与结构版本 */
const STORAGE_PREFIX = 'desk:chatui:'
const LEGACY_WORKSPACE_PREFIX = 'desk:workspace:'
const STORAGE_VERSION = 1
const PERSIST_DELAY = 300

/* 默认状态 */
function createState(): ChatUiState {
  return { draft: '', scrollTop: 0, updateTime: Date.now(), version: STORAGE_VERSION }
}

/* 读取本地持久化：优先新键，兼容旧版工作区切片中的 chat 字段 */
function readStored(sessionId: string): ChatUiState | null {
  try {
    const raw = localStorage.getItem(STORAGE_PREFIX + sessionId)
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<ChatUiState>
      if (parsed.version === STORAGE_VERSION) {
        return {
          draft: typeof parsed.draft === 'string' ? parsed.draft : '',
          scrollTop: Number.isFinite(parsed.scrollTop) ? Number(parsed.scrollTop) : 0,
          updateTime: Number(parsed.updateTime) || Date.now(),
          version: STORAGE_VERSION
        }
      }
    }
    /* 旧版：草稿/滚动曾存放在 desk:workspace:{id}.chat */
    const legacy = localStorage.getItem(LEGACY_WORKSPACE_PREFIX + sessionId)
    if (legacy) {
      const parsed = JSON.parse(legacy) as {
        version?: number
        chat?: { draft?: string; scrollTop?: number }
      }
      if (parsed.version === 1 && parsed.chat) {
        return {
          draft: typeof parsed.chat.draft === 'string' ? parsed.chat.draft : '',
          scrollTop: Number(parsed.chat.scrollTop) || 0,
          updateTime: Date.now(),
          version: STORAGE_VERSION
        }
      }
    }
    return null
  } catch {
    return null
  }
}

export const useChatUiStore = defineStore('chatUi', () => {
  /* 会话 ID → 对话 UI 状态 */
  const slices = ref<Record<string, ChatUiState>>({})
  /* 各会话持久化防抖定时器 */
  const persistTimers = new Map<string, ReturnType<typeof setTimeout>>()

  /* 写入本地持久化 */
  function persist(sessionId: string): void {
    const timer = persistTimers.get(sessionId)
    if (timer) {
      clearTimeout(timer)
      persistTimers.delete(sessionId)
    }
    const state = slices.value[sessionId]
    if (!state) {
      return
    }
    try {
      localStorage.setItem(STORAGE_PREFIX + sessionId, JSON.stringify(state))
    } catch {
      /* 本地存储不可用时忽略 */
    }
  }

  /* 状态变更后按会话防抖持久化 */
  watch(
    slices,
    () => {
      for (const sessionId of Object.keys(slices.value)) {
        const timer = persistTimers.get(sessionId)
        if (timer) {
          clearTimeout(timer)
        }
        persistTimers.set(
          sessionId,
          setTimeout(() => persist(sessionId), PERSIST_DELAY)
        )
      }
    },
    { deep: true }
  )

  /* 取或创建某会话的对话 UI 状态 */
  function ensure(sessionId: string): ChatUiState {
    let state = slices.value[sessionId]
    if (!state) {
      state = readStored(sessionId) ?? createState()
      slices.value[sessionId] = state
    }
    return state
  }

  /* 读取某会话状态（不存在时创建） */
  function sliceOf(sessionId: string): ChatUiState {
    return slices.value[sessionId] ?? ensure(sessionId)
  }

  /* 写入输入草稿 */
  function setDraft(sessionId: string, draft: string): void {
    sliceOf(sessionId).draft = draft
  }

  /* 记录消息区滚动位置 */
  function setScrollTop(sessionId: string, scrollTop: number): void {
    sliceOf(sessionId).scrollTop = Math.max(0, Math.round(scrollTop))
  }

  /* 删除会话对话 UI 状态 */
  function remove(sessionId: string): void {
    delete slices.value[sessionId]
    const timer = persistTimers.get(sessionId)
    if (timer) {
      clearTimeout(timer)
      persistTimers.delete(sessionId)
    }
    try {
      localStorage.removeItem(STORAGE_PREFIX + sessionId)
    } catch {
      /* 本地存储不可用时忽略 */
    }
  }

  /* 清理已失效会话的持久化键 */
  function cleanup(validSessionIds: string[]): void {
    const valid = new Set(validSessionIds)
    for (const sessionId of Object.keys(slices.value)) {
      if (!valid.has(sessionId)) {
        remove(sessionId)
      }
    }
    try {
      for (let index = localStorage.length - 1; index >= 0; index -= 1) {
        const key = localStorage.key(index)
        if (key && key.startsWith(STORAGE_PREFIX)) {
          const sessionId = key.slice(STORAGE_PREFIX.length)
          if (!valid.has(sessionId)) {
            localStorage.removeItem(key)
          }
        }
      }
    } catch {
      /* 本地存储不可用时忽略 */
    }
  }

  return { slices, ensure, sliceOf, setDraft, setScrollTop, remove, cleanup }
})
