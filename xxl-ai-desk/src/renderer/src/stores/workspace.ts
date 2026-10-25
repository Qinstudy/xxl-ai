/* 会话工作区状态：按会话隔离的对话草稿/滚动、工具面板与底部终端 */

import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { api } from '@renderer/api'
import { useShellStore } from './shell'

/* 新建对话草稿态使用的固定工作区 key（首次发送创建会话后迁移给真实会话） */
export const DRAFT_SESSION_KEY = '__draft__'

/* 工具面板当前工具 */
export type WorkspaceTool = 'files' | 'browser'

/* 终端标签（运行时状态：id 与主进程 PTY 对应，切换会话时保留，重启后不持久化） */
export interface TerminalTabState {
  /* 终端实例 ID */
  id: string
  /* 标签标题（目录名） */
  title: string
}

/* 单个会话的工作区状态（仅存 UI 状态，不含 PTY / webview 运行时） */
export interface WorkspaceState {
  /* 右侧工具面板 */
  tool: {
    visible: boolean /* 是否展示 */
    tool: WorkspaceTool /* 当前工具 */
    width: number /* 面板宽度 */
    maximized: boolean /* 是否放大占满正文 */
    files: {
      selectedPath: string /* 选中文件路径 */
      preview: boolean /* 是否预览 */
      keyword: string /* 文件树过滤关键字 */
      expanded: Record<string, boolean> /* 目录展开态 */
    }
  }
  /* 底部终端 */
  terminal: {
    visible: boolean /* 是否展示 */
    height: number /* 面板高度 */
    tabs: TerminalTabState[] /* 终端标签（运行时，不持久化） */
    activeId: string /* 当前激活标签（运行时，不持久化） */
  }
  updateTime: number /* 最近更新时间 */
  version: number /* 持久化结构版本 */
}

/* 持久化键前缀与结构版本 */
const STORAGE_PREFIX = 'desk:workspace:'
const STORAGE_VERSION = 1
/* 防抖写入间隔（毫秒） */
const PERSIST_DELAY = 300

/* 默认尺寸与边界 */
const TOOL_WIDTH_DEFAULT = 520
const TOOL_WIDTH_MIN = 240
const TERMINAL_HEIGHT_DEFAULT = 240
const TERMINAL_HEIGHT_MIN = 120
const TERMINAL_HEIGHT_RESERVED = 240
/* 会话正文最大宽度与图标栏宽度：用于计算工具面板最大宽度 */
const CHAT_MAX_WIDTH = 944
const RAIL_WIDTH = 44

/* 创建默认工作区状态 */
function createState(): WorkspaceState {
  return {
    tool: {
      visible: false,
      tool: 'files',
      width: TOOL_WIDTH_DEFAULT,
      maximized: false,
      files: { selectedPath: '', preview: false, keyword: '', expanded: {} }
    },
    terminal: {
      visible: false,
      height: TERMINAL_HEIGHT_DEFAULT,
      tabs: [],
      activeId: ''
    },
    updateTime: Date.now(),
    version: STORAGE_VERSION
  }
}

/* 数值夹取 */
function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, Math.round(value)))
}

/* 计算工具面板最大宽度：窗口宽度扣除图标栏、左侧子菜单栏与会话正文 */
function toolWidthMax(): number {
  const panelWidth = useShellStore().panelWidth
  return Math.max(820, window.innerWidth - RAIL_WIDTH - panelWidth - CHAT_MAX_WIDTH)
}

/* 计算终端最大高度：为正文预留空间 */
function terminalHeightMax(): number {
  return Math.max(TERMINAL_HEIGHT_MIN, window.innerHeight - TERMINAL_HEIGHT_RESERVED)
}

/* 读取本地持久化：结构非法时回落默认值 */
function readStored(key: string): WorkspaceState | null {
  try {
    const raw = localStorage.getItem(STORAGE_PREFIX + key)
    if (!raw) {
      return null
    }
    const parsed = JSON.parse(raw) as Partial<WorkspaceState>
    if (parsed.version !== STORAGE_VERSION) {
      return null
    }
    const base = createState()
    return {
      tool: {
        visible: parsed.tool?.visible === true,
        tool: parsed.tool?.tool === 'browser' ? 'browser' : 'files',
        width: clamp(Number(parsed.tool?.width) || base.tool.width, TOOL_WIDTH_MIN, toolWidthMax()),
        maximized: parsed.tool?.maximized === true,
        files: {
          selectedPath:
            typeof parsed.tool?.files?.selectedPath === 'string'
              ? parsed.tool.files.selectedPath
              : '',
          preview: parsed.tool?.files?.preview === true,
          keyword: typeof parsed.tool?.files?.keyword === 'string' ? parsed.tool.files.keyword : '',
          expanded:
            parsed.tool?.files && typeof parsed.tool.files.expanded === 'object'
              ? (parsed.tool.files.expanded as Record<string, boolean>)
              : {}
        }
      },
      terminal: {
        visible: parsed.terminal?.visible === true,
        height: clamp(
          Number(parsed.terminal?.height) || base.terminal.height,
          TERMINAL_HEIGHT_MIN,
          terminalHeightMax()
        ),
        /* 终端标签属于运行时资源：重启后不恢复，首次展示时按会话重新创建 */
        tabs: [],
        activeId: ''
      },
      updateTime: Number(parsed.updateTime) || Date.now(),
      version: STORAGE_VERSION
    }
  } catch {
    return null
  }
}

export const useWorkspaceStore = defineStore('workspace', () => {
  /* 会话 key → 工作区状态 */
  const slices = ref<Record<string, WorkspaceState>>({})
  /* 各会话持久化防抖定时器 */
  const persistTimers = new Map<string, ReturnType<typeof setTimeout>>()

  /* 写入本地持久化：终端标签属运行时资源，不落盘 */
  function persist(key: string): void {
    const timer = persistTimers.get(key)
    if (timer) {
      clearTimeout(timer)
      persistTimers.delete(key)
    }
    const state = slices.value[key]
    if (!state) {
      return
    }
    const snapshot: WorkspaceState = {
      ...state,
      terminal: { ...state.terminal, tabs: [], activeId: '' }
    }
    try {
      localStorage.setItem(STORAGE_PREFIX + key, JSON.stringify(snapshot))
    } catch {
      /* 本地存储不可用时忽略（仅影响恢复，不影响当前使用） */
    }
  }

  /* 调度持久化：同一会话的连续修改合并为一次写入 */
  function schedulePersist(key: string): void {
    const timer = persistTimers.get(key)
    if (timer) {
      clearTimeout(timer)
    }
    persistTimers.set(
      key,
      setTimeout(() => persist(key), PERSIST_DELAY)
    )
  }

  /* 任意状态变更后按 key 调度持久化（草稿输入与滚动也走这里） */
  watch(
    slices,
    () => {
      for (const key of Object.keys(slices.value)) {
        schedulePersist(key)
      }
    },
    { deep: true }
  )

  /* 取或创建某会话的工作区状态（优先读取本地持久化） */
  function ensure(key: string): WorkspaceState {
    const existing = slices.value[key]
    if (existing) {
      return existing
    }
    const state = readStored(key) ?? createState()
    slices.value[key] = state
    return state
  }

  /* 读取某会话的工作区状态（不存在时创建默认值） */
  function sliceOf(key: string): WorkspaceState {
    return slices.value[key] ?? ensure(key)
  }

  /* 设置工具面板显隐 */
  function setToolVisible(key: string, visible: boolean): void {
    sliceOf(key).tool.visible = visible
  }

  /* 切换工具面板显隐 */
  function toggleTool(key: string): void {
    const state = sliceOf(key)
    state.tool.visible = !state.tool.visible
  }

  /* 打开工具面板并定位到指定工具 */
  function openTool(key: string, tool: WorkspaceTool): void {
    const state = sliceOf(key)
    state.tool.tool = tool
    state.tool.visible = true
  }

  /* 设置工具面板宽度（夹取到窗口可用范围） */
  function setToolWidth(key: string, width: number): void {
    sliceOf(key).tool.width = clamp(width, TOOL_WIDTH_MIN, toolWidthMax())
  }

  /* 恢复工具面板默认宽度 */
  function resetToolWidth(key: string): void {
    sliceOf(key).tool.width = clamp(TOOL_WIDTH_DEFAULT, TOOL_WIDTH_MIN, toolWidthMax())
  }

  /* 切换工具面板放大态 */
  function toggleToolMaximized(key: string): void {
    const state = sliceOf(key)
    state.tool.maximized = !state.tool.maximized
  }

  /* 设置终端显隐 */
  function setTerminalVisible(key: string, visible: boolean): void {
    sliceOf(key).terminal.visible = visible
  }

  /* 切换终端显隐 */
  function toggleTerminal(key: string): void {
    const state = sliceOf(key)
    state.terminal.visible = !state.terminal.visible
  }

  /* 打开终端（已打开则保持） */
  function openTerminal(key: string): void {
    sliceOf(key).terminal.visible = true
  }

  /* 设置终端高度（夹取到窗口可用范围） */
  function setTerminalHeight(key: string, height: number): void {
    sliceOf(key).terminal.height = clamp(height, TERMINAL_HEIGHT_MIN, terminalHeightMax())
  }

  /* 恢复终端默认高度 */
  function resetTerminalHeight(key: string): void {
    sliceOf(key).terminal.height = clamp(
      TERMINAL_HEIGHT_DEFAULT,
      TERMINAL_HEIGHT_MIN,
      terminalHeightMax()
    )
  }

  /* 设置终端标签列表（终端运行时资源，不持久化） */
  function setTerminalTabs(key: string, tabs: TerminalTabState[]): void {
    const state = sliceOf(key)
    state.terminal.tabs = tabs
    if (!tabs.some((tab) => tab.id === state.terminal.activeId)) {
      state.terminal.activeId = tabs[tabs.length - 1]?.id ?? ''
    }
  }

  /* 新增终端标签并激活 */
  function addTerminalTab(key: string, tab: TerminalTabState): void {
    const state = sliceOf(key)
    state.terminal.tabs.push(tab)
    state.terminal.activeId = tab.id
  }

  /* 移除终端标签：激活项关闭后回落到最后一个标签 */
  function removeTerminalTab(key: string, id: string): void {
    const state = sliceOf(key)
    state.terminal.tabs = state.terminal.tabs.filter((tab) => tab.id !== id)
    if (state.terminal.activeId === id) {
      state.terminal.activeId = state.terminal.tabs[state.terminal.tabs.length - 1]?.id ?? ''
    }
  }

  /* 切换当前终端标签 */
  function setActiveTerminal(key: string, id: string): void {
    sliceOf(key).terminal.activeId = id
  }

  /* 新建会话后迁移草稿工作区：草稿切片复制给真实会话，草稿切片复位 */
  function adoptDraft(sessionId: string): void {
    const draft = slices.value[DRAFT_SESSION_KEY]
    if (!draft) {
      return
    }
    /* 草稿页已打开的终端：PTY 归属由草稿迁移到新会话，避免首次发送后终端丢失 */
    if (draft.terminal.tabs.length > 0) {
      void api.terminal.reassign(DRAFT_SESSION_KEY, sessionId)
    }
    slices.value[sessionId] = {
      tool: {
        ...draft.tool,
        files: { ...draft.tool.files, expanded: { ...draft.tool.files.expanded } }
      },
      terminal: { ...draft.terminal, tabs: [...draft.terminal.tabs] },
      updateTime: Date.now(),
      version: STORAGE_VERSION
    }
    slices.value[DRAFT_SESSION_KEY] = createState()
    persist(DRAFT_SESSION_KEY)
  }

  /* 删除会话工作区：清理内存、定时器与本地持久化 */
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

  /* 清理已失效会话的工作区状态（应用启动时按会话列表校准） */
  function cleanup(validSessionIds: string[]): void {
    const valid = new Set([...validSessionIds, DRAFT_SESSION_KEY])
    for (const key of Object.keys(slices.value)) {
      if (!valid.has(key)) {
        remove(key)
      }
    }
    try {
      for (let index = localStorage.length - 1; index >= 0; index -= 1) {
        const storageKey = localStorage.key(index)
        if (storageKey && storageKey.startsWith(STORAGE_PREFIX)) {
          const key = storageKey.slice(STORAGE_PREFIX.length)
          if (!valid.has(key)) {
            localStorage.removeItem(storageKey)
          }
        }
      }
    } catch {
      /* 本地存储不可用时忽略 */
    }
  }

  return {
    slices,
    ensure,
    sliceOf,
    setToolVisible,
    toggleTool,
    openTool,
    setToolWidth,
    resetToolWidth,
    toggleToolMaximized,
    setTerminalVisible,
    toggleTerminal,
    openTerminal,
    setTerminalHeight,
    resetTerminalHeight,
    setTerminalTabs,
    addTerminalTab,
    removeTerminalTab,
    setActiveTerminal,
    adoptDraft,
    cleanup,
    remove
  }
})
