/*
 * 项目文件树状态：目录懒加载缓存、展开态、过滤与目录监听生命周期。
 * @author xuxueli 2026-10-25
 */

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@renderer/api'
import { normalizePath, parentDir } from '../utils/path'
import type { FsEntry } from '@shared/ipc'

export interface UseProjectFileTreeOptions {
  /* 项目根目录（响应式取值） */
  root: () => string | undefined
}

export function useProjectFileTree(options: UseProjectFileTreeOptions) {
  /* 目录 → 子项（懒加载缓存）、展开态、加载态 */
  const childrenMap = ref<Record<string, FsEntry[]>>({})
  const expandedMap = ref<Record<string, boolean>>({})
  const loadingMap = ref<Record<string, boolean>>({})
  /* 过滤关键字 */
  const keyword = ref('')

  /* 已注册监听的目录集合（避免重复 IPC）与正在重载的目录集合 */
  const watchedDirs = new Set<string>()
  const reloading = new Set<string>()
  let offFsChange: (() => void) | null = null

  /* 根目录直接子项 */
  const rootChildren = computed(() => {
    const root = options.root()
    return root ? (childrenMap.value[root] ?? []) : []
  })

  /* 过滤时递归判断节点（或已加载子孙）是否命中 */
  function matchNode(entry: FsEntry, word: string): boolean {
    if (entry.name.toLowerCase().includes(word)) {
      return true
    }
    const kids = childrenMap.value[entry.path]
    return Boolean(kids && kids.some((child) => matchNode(child, word)))
  }

  /* 过滤后的根目录可见子项（无关键字时取全部） */
  const visibleRoot = computed(() => {
    const word = keyword.value.trim().toLowerCase()
    return word ? rootChildren.value.filter((entry) => matchNode(entry, word)) : rootChildren.value
  })

  /* 注册目录监听（幂等） */
  function registerWatch(dir: string): void {
    if (watchedDirs.has(dir)) {
      return
    }
    watchedDirs.add(dir)
    void api.fs.watch(dir)
  }

  /* 取消目录监听 */
  function unwatch(dir: string): void {
    if (!watchedDirs.delete(dir)) {
      return
    }
    void api.fs.unwatch(dir)
  }

  /*
   * 清理已消失的子目录：父目录重载后，若某已监听子目录不再存在，
   * 取消其监听并清空其缓存/展开态（避免残留幽灵节点）。
   */
  function cleanupChildren(dir: string, entries: FsEntry[]): void {
    const present = new Set(entries.map((entry) => normalizePath(entry.path)))
    const next = { ...childrenMap.value }
    let changed = false
    for (const watched of [...watchedDirs]) {
      if (normalizePath(parentDir(watched)) === normalizePath(dir) && !present.has(normalizePath(watched))) {
        unwatch(watched)
        if (next[watched] !== undefined) {
          delete next[watched]
          changed = true
        }
        expandedMap.value = { ...expandedMap.value, [watched]: false }
      }
    }
    if (changed) {
      childrenMap.value = next
    }
  }

  /* 加载目录子项（force=true 时强制重载）；成功后注册监听并清理消失的子目录 */
  async function loadDir(path: string, force = false): Promise<void> {
    const root = options.root()
    if (!root || (!force && (childrenMap.value[path] || loadingMap.value[path]))) {
      return
    }
    loadingMap.value = { ...loadingMap.value, [path]: true }
    try {
      const entries = await api.fs.list(root, path)
      childrenMap.value = { ...childrenMap.value, [path]: entries }
      registerWatch(path)
      cleanupChildren(path, entries)
    } catch (error) {
      /* 目录可能已被删除：清空并停止监听；否则提示错误 */
      childrenMap.value = { ...childrenMap.value, [path]: [] }
      unwatch(path)
      if (!force) {
        ElMessage.error((error as Error).message)
      }
    } finally {
      loadingMap.value = { ...loadingMap.value, [path]: false }
    }
  }

  /* 重载单个已加载目录 */
  async function reloadDir(dir: string): Promise<void> {
    if (reloading.has(dir)) {
      return
    }
    reloading.add(dir)
    try {
      await loadDir(dir, true)
    } finally {
      reloading.delete(dir)
    }
  }

  /* 目录变更回调：匹配已加载目录并刷新 */
  function onFsChange(dir: string): void {
    const target = Object.keys(childrenMap.value).find(
      (key) => normalizePath(key) === normalizePath(dir)
    )
    if (target !== undefined) {
      void reloadDir(target)
    }
  }

  /* 展开/收起目录 */
  function toggle(entry: FsEntry): void {
    const open = !expandedMap.value[entry.path]
    expandedMap.value = { ...expandedMap.value, [entry.path]: open }
    if (open) {
      void loadDir(entry.path)
    }
  }

  /* 重置面板状态并在根目录变化时重新加载 */
  function reset(): void {
    for (const dir of [...watchedDirs]) {
      unwatch(dir)
    }
    watchedDirs.clear()
    childrenMap.value = {}
    expandedMap.value = {}
    loadingMap.value = {}
    keyword.value = ''
    const root = options.root()
    if (root) {
      void loadDir(root)
    }
  }

  watch(options.root, reset, { immediate: true })

  /* 开始过滤时预加载根目录下各子目录一层，提升筛选命中率 */
  watch(keyword, (word) => {
    if (!word.trim()) {
      return
    }
    for (const entry of rootChildren.value) {
      if (entry.isDir) {
        void loadDir(entry.path)
      }
    }
  })

  /* 挂载：监听目录变更 */
  onMounted(() => {
    offFsChange = api.fs.onChange(onFsChange)
  })

  /* 卸载：取消文件变更监听并注销全部目录监听 */
  onBeforeUnmount(() => {
    offFsChange?.()
    for (const dir of [...watchedDirs]) {
      unwatch(dir)
    }
    watchedDirs.clear()
  })

  return {
    childrenMap,
    expandedMap,
    loadingMap,
    keyword,
    rootChildren,
    visibleRoot,
    toggle
  }
}
