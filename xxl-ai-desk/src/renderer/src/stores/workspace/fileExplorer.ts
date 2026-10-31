/* 文件面板 UI 动作：选中文件、预览、过滤与目录展开态 */

import type { WorkspaceState } from './state'

/* 文件动作依赖的会话状态读取函数 */
type StateOf = (key: string) => WorkspaceState

export function createFileExplorerActions(stateOf: StateOf) {
  /* 设置选中文件路径 */
  function setSelectedPath(key: string, path: string): void {
    stateOf(key).fileExplorer.selectedPath = path
  }

  /* 设置预览态 */
  function setPreview(key: string, preview: boolean): void {
    stateOf(key).fileExplorer.preview = preview
  }

  /* 设置文件树过滤关键字 */
  function setKeyword(key: string, keyword: string): void {
    stateOf(key).fileExplorer.keyword = keyword
  }

  /* 设置目录展开态 */
  function setExpanded(key: string, expanded: Record<string, boolean>): void {
    stateOf(key).fileExplorer.expanded = expanded
  }

  return { setSelectedPath, setPreview, setKeyword, setExpanded }
}
