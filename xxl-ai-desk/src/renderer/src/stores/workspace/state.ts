/* 会话工作区状态结构：类型、默认值、持久化归一化与尺寸边界 */

import { LAYOUT } from '@renderer/config/layout'

/* 新建对话草稿态使用的固定工作区 key（首次发送创建会话后迁移给真实会话） */
export const DRAFT_SESSION_KEY = '__draft__'

/* 持久化键前缀与结构版本 */
export const STORAGE_PREFIX = 'desk:workspace:'
export const STORAGE_VERSION = 3

/* 工具面板当前工具 */
export type WorkspaceTool = 'files' | 'browser' | 'terminal'

/* 工具面板布局模式：dock = 与正文并排，focus = 占满正文 */
export type ToolPanelMode = 'dock' | 'focus'

/* 终端标签（运行时状态：id 与主进程 PTY 对应，切换会话时保留，重启后不持久化） */
export interface TerminalTabState {
  /* 终端实例 ID */
  id: string
  /* 标签标题（目录名） */
  title: string
}

/* 右侧工具面板布局 */
export interface ToolPanelState {
  /* 是否展示 */
  visible: boolean
  /* 当前工具 */
  activeTool: WorkspaceTool
  /* 面板宽度 */
  width: number
  /* 布局模式 */
  mode: ToolPanelMode
}

/* 终端标签运行时状态（终端作为工具面板的一个 Tab） */
export interface TerminalPanelState {
  /* 终端标签（运行时，不持久化） */
  tabs: TerminalTabState[]
  /* 当前激活标签（运行时，不持久化） */
  activeId: string
}

/* 文件面板 UI 状态 */
export interface FileExplorerState {
  /* 选中文件路径 */
  selectedPath: string
  /* 是否预览 */
  preview: boolean
  /* 文件树过滤关键字 */
  keyword: string
  /* 目录展开态 */
  expanded: Record<string, boolean>
  /* 目录树宽度（px，展示时按 10% - 90% 动态约束） */
  treeWidth: number
}

/* 单个会话的工作区状态（仅存 UI 状态，不含 PTY / webview 运行时） */
export interface WorkspaceState {
  toolPanel: ToolPanelState
  terminalPanel: TerminalPanelState
  fileExplorer: FileExplorerState
  /* 最近更新时间 */
  updateTime: number
  /* 持久化结构版本 */
  version: number
}

/* 创建默认工作区状态 */
export function createState(): WorkspaceState {
  return {
    toolPanel: {
      visible: false,
      activeTool: 'files',
      width: LAYOUT.toolPanel.defaultWidth,
      mode: 'dock'
    },
    terminalPanel: {
      tabs: [],
      activeId: ''
    },
    fileExplorer: {
      selectedPath: '',
      preview: false,
      keyword: '',
      expanded: {},
      treeWidth: defaultTreeWidth(LAYOUT.toolPanel.defaultWidth)
    },
    updateTime: Date.now(),
    version: STORAGE_VERSION
  }
}

/* 读取布尔值（非法时回落默认值） */
function boolOf(value: unknown, fallback: boolean): boolean {
  return typeof value === 'boolean' ? value : fallback
}

/* 读取字符串（非法时回落默认值） */
function stringOf(value: unknown, fallback: string): string {
  return typeof value === 'string' ? value : fallback
}

/* 解析目录展开态（非法时回落空对象） */
function expandedOf(value: unknown): Record<string, boolean> {
  return value && typeof value === 'object' ? (value as Record<string, boolean>) : {}
}

/*
 * 默认目录树宽度：按工具面板宽度自适应。
 *   - 目标宽度为工具面板的 45%；
 *   - 限制在 180px - 300px，面板放大时目录树保持稳定，不随面板无限变宽。
 */
export function defaultTreeWidth(toolPanelWidth: number): number {
  const target = Math.max(1, toolPanelWidth) * LAYOUT.fileTree.defaultRatio
  return Math.round(
    Math.min(
      LAYOUT.fileTree.defaultMaxWidth,
      Math.max(LAYOUT.fileTree.defaultMinWidth, target)
    )
  )
}

/* 解析目录树宽度：兼容旧版占比字段，非法时回落默认值 */
function treeWidthOf(value: unknown, toolPanelWidth: number): number {
  const fileExplorer = value as { treeWidth?: unknown; treeWidthRatio?: unknown } | undefined
  const width = Number(fileExplorer?.treeWidth)
  if (Number.isFinite(width) && width > 0) {
    return Math.round(width)
  }
  const ratio = Number(fileExplorer?.treeWidthRatio)
  if (Number.isFinite(ratio) && ratio > 0) {
    return Math.round(ratio * Math.max(1, toolPanelWidth))
  }
  return defaultTreeWidth(toolPanelWidth)
}

/* 归一化持久化数据：结构非法时逐字段回落默认值 */
export function normalizeState(raw: unknown): WorkspaceState {
  const parsed = raw as Partial<WorkspaceState>
  const base = createState()
  return {
    toolPanel: {
      visible: boolOf(parsed.toolPanel?.visible, base.toolPanel.visible),
      activeTool:
        parsed.toolPanel?.activeTool === 'browser' || parsed.toolPanel?.activeTool === 'terminal'
          ? parsed.toolPanel.activeTool
          : 'files',
      width: Math.max(
        LAYOUT.toolPanel.minWidth,
        Math.round(Number(parsed.toolPanel?.width) || base.toolPanel.width)
      ),
      mode: parsed.toolPanel?.mode === 'focus' ? 'focus' : 'dock'
    },
    terminalPanel: {
      /* 终端标签属于运行时资源：重启后不恢复，首次展示时按会话重新创建 */
      tabs: [],
      activeId: ''
    },
    fileExplorer: {
      selectedPath: stringOf(parsed.fileExplorer?.selectedPath, ''),
      preview: boolOf(parsed.fileExplorer?.preview, false),
      keyword: stringOf(parsed.fileExplorer?.keyword, ''),
      expanded: expandedOf(parsed.fileExplorer?.expanded),
      treeWidth: treeWidthOf(
        parsed.fileExplorer,
        Number(parsed.toolPanel?.width) || base.toolPanel.width
      )
    },
    updateTime: Number(parsed.updateTime) || Date.now(),
    version: STORAGE_VERSION
  }
}
