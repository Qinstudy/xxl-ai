<script setup lang="ts">
/*
 * 底部终端面板：终端标签与 PTY 按会话隔离。
 *   - 每个标签一个本地 PTY（主进程 node-pty）+ xterm 实例；
 *   - 切换会话时仅卸载 xterm，PTY 保持存活；
 *   - 切回时通过主进程环形缓冲回放输出（attach），并续接实时事件。
 */
import { computed, nextTick, onBeforeUnmount, ref, watch, type ComponentPublicInstance } from 'vue'
import { ElMessage } from 'element-plus'
import { Terminal, type ITheme } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import { api } from '@renderer/api'
import { useWorkspaceStore } from '@renderer/stores/workspace'
import { usePanelResize } from '@renderer/composables/usePanelResize'
import { t } from '@renderer/i18n'
import type { TerminalEvent } from '@shared/ipc'

const props = defineProps<{
  sessionId: string
  cwd?: string
  title?: string
  visible?: boolean
}>()
const emit = defineEmits<{ close: [] }>()

/* 会话工作区 store：终端显隐/高度/标签均按会话隔离 */
const workspace = useWorkspaceStore()
/* 当前会话的终端状态 */
const terminalState = computed(() => workspace.sliceOf(props.sessionId).terminal)
/* 当前会话的终端标签与激活项 */
const tabs = computed(() => terminalState.value.tabs)
const activeId = computed(() => terminalState.value.activeId)

/* 拖拽上边缘调整终端高度（统一复用 usePanelResize，底部面板手柄在上缘） */
const { startResize } = usePanelResize({
  getSize: () => terminalState.value.height,
  setSize: (height) => workspace.setTerminalHeight(props.sessionId, height),
  edge: 'bottom'
})

/* 标签 id → xterm 容器 DOM */
const containers = new Map<string, HTMLDivElement>()

/* 标签 id → xterm 运行时（实例、fit、监听句柄、回放序号、待处理事件） */
interface TerminalRuntime {
  term: Terminal
  fit: FitAddon
  observer: ResizeObserver
  offData: { dispose(): void }
  lastSeq: number
  ready: boolean
  pending: TerminalEvent[]
}
const runtimes = new Map<string, TerminalRuntime>()
let offEvent: (() => void) | undefined

/* 当前已挂载标签的会话：会话切换时先卸载旧会话的 xterm（PTY 保留） */
let mountedSessionId = ''

/* 按当前主题选择 xterm 配色（深浅色与设计令牌一致） */
function xtermTheme(): ITheme {
  const dark = document.documentElement.classList.contains('dark')
  return dark
    ? { background: '#1e1e1e', foreground: '#ececec', cursor: '#ececec', selectionBackground: '#3a3a3a' }
    : { background: '#ffffff', foreground: '#0d0d0d', cursor: '#0d0d0d', selectionBackground: '#d8d8d8' }
}

/* 容器 ref 回调：记录/清理标签 DOM */
function setContainer(id: string, el: Element | ComponentPublicInstance | null): void {
  if (el instanceof HTMLDivElement) {
    containers.set(id, el)
  } else {
    containers.delete(id)
  }
}

/* 对指定终端执行自适应尺寸并同步回主进程 */
function fitTerminal(id: string): void {
  const runtime = runtimes.get(id)
  const el = containers.get(id)
  if (!runtime || !el || el.clientWidth === 0 || el.clientHeight === 0) {
    return
  }
  try {
    runtime.fit.fit()
    void api.terminal.resize(id, runtime.term.cols, runtime.term.rows)
  } catch {
    /* 容器尺寸异常时忽略 */
  }
}

/* 取目录名（跨平台分隔符），用于终端标签命名 */
function folderName(dir: string): string {
  const parts = dir.split(/[\\/]/).filter(Boolean)
  return parts[parts.length - 1] || dir
}

/* 在已挂载的容器上创建并绑定 xterm 实例，并回放主进程历史输出 */
async function mountTerminal(id: string): Promise<void> {
  if (runtimes.has(id)) {
    fitTerminal(id)
    return
  }
  const el = containers.get(id)
  if (!el) {
    return
  }
  const term = new Terminal({
    cursorBlink: true,
    fontSize: 13,
    fontFamily: "'SFMono-Regular', ui-monospace, Menlo, Consolas, monospace",
    scrollback: 3000,
    theme: xtermTheme()
  })
  const fit = new FitAddon()
  term.loadAddon(fit)
  term.open(el)
  /* 用户输入 → 主进程 PTY */
  const offData = term.onData((data) => {
    void api.terminal.write(id, data)
  })
  /* 容器尺寸变化 → 自适应并同步尺寸 */
  const observer = new ResizeObserver(() => fitTerminal(id))
  observer.observe(el)
  const runtime: TerminalRuntime = {
    term,
    fit,
    observer,
    offData,
    lastSeq: 0,
    ready: false,
    pending: []
  }
  runtimes.set(id, runtime)
  fitTerminal(id)

  /* 回放历史输出：attach 返回截止 seq 与历史片段，之后的事件按 seq 续接 */
  const attached = await api.terminal.attach(id)
  const current = runtimes.get(id)
  if (!current) {
    return
  }
  if (!attached) {
    /* PTY 已退出（应用重启或后台退出）：提示并停止后续绑定 */
    current.ready = true
    current.term.write(`\r\n${t('chat.terminalExited', ['-1'])}\r\n`)
    return
  }
  for (const chunk of attached.chunks) {
    current.term.write(chunk.data)
  }
  current.lastSeq = attached.seq
  current.ready = true
  /* 回放期间收到的实时事件：按序号补齐 */
  for (const event of current.pending) {
    if (event.type === 'data' && event.data != null && event.seq > current.lastSeq) {
      current.lastSeq = event.seq
      current.term.write(event.data)
    }
  }
  current.pending = []
  fitTerminal(id)
}

/* 递归：清理单个 xterm 运行时（不销毁 PTY） */
function detachRuntime(id: string): void {
  const runtime = runtimes.get(id)
  if (!runtime) {
    return
  }
  runtime.observer.disconnect()
  runtime.offData.dispose()
  runtime.term.dispose()
  runtimes.delete(id)
}

/* 卸载当前会话的全部 xterm 运行时（PTY 保留在主进程） */
function detachSession(): void {
  for (const id of [...runtimes.keys()]) {
    detachRuntime(id)
  }
  containers.clear()
  mountedSessionId = ''
}

/* 新建终端：创建 PTY → 以当前文件夹命名标签 → 挂载 xterm 并聚焦 */
async function createTerminalFor(sessionId: string): Promise<void> {
  try {
    const dto = await api.terminal.create({ sessionId, cwd: props.cwd })
    const title = folderName(dto.cwd) || props.title || t('chat.terminal')
    workspace.addTerminalTab(sessionId, { id: dto.id, title })
    if (sessionId !== props.sessionId) {
      return
    }
    await nextTick()
    await mountTerminal(dto.id)
    fitTerminal(dto.id)
    runtimes.get(dto.id)?.term.focus()
  } catch (error) {
    console.warn('创建终端失败：', error)
    ElMessage.warning(t('chat.terminalLimit'))
  }
}

/* 新建当前会话的终端（标签栏「+」） */
async function openTerminal(): Promise<void> {
  await createTerminalFor(props.sessionId)
}

/* 确保会话的终端标签可用：清理已退出标签，全部失效时补开一个 */
async function ensureTabs(sessionId: string): Promise<void> {
  const state = workspace.sliceOf(sessionId).terminal
  if (state.tabs.length === 0) {
    const alive = await api.terminal.list(sessionId)
    if (alive.length > 0) {
      workspace.setTerminalTabs(
        sessionId,
        alive.map((item) => ({
          id: item.id,
          title: folderName(item.cwd) || props.title || t('chat.terminal')
        }))
      )
      return
    }
    await createTerminalFor(sessionId)
    return
  }

  /* 已有标签：用轻量 list 校验 PTY 存活（避免每次切换会话都回放 256KB 缓冲） */
  const alive = await api.terminal.list(sessionId)
  const aliveIds = new Set(alive.map((item) => item.id))
  const missing = state.tabs.filter((tab) => !aliveIds.has(tab.id))
  const kept = state.tabs.filter((tab) => aliveIds.has(tab.id))
  if (missing.length > 0) {
    /* 兜底校验：草稿终端迁移到新会话的短暂窗口内，list 可能尚未包含该实例 */
    const checks = await Promise.all(missing.map((tab) => api.terminal.attach(tab.id)))
    missing.forEach((tab, index) => {
      if (checks[index]) {
        kept.push(tab)
      }
    })
  }
  if (kept.length !== state.tabs.length) {
    workspace.setTerminalTabs(sessionId, kept)
  }
  if (kept.length === 0) {
    await createTerminalFor(sessionId)
  }
}

/* 挂载当前会话的激活标签 */
async function mountActive(sessionId: string): Promise<void> {
  let active = workspace.sliceOf(sessionId).terminal.activeId
  if (!active) {
    const last = workspace.sliceOf(sessionId).terminal.tabs.at(-1)
    if (last) {
      workspace.setActiveTerminal(sessionId, last.id)
      active = last.id
    }
  }
  if (!active) {
    return
  }
  await nextTick()
  await mountTerminal(active)
  fitTerminal(active)
  runtimes.get(active)?.term.focus()
}

/* 激活会话：切换会话时仅重建 xterm，不触碰 PTY */
async function activateSession(sessionId: string): Promise<void> {
  if (!sessionId) {
    return
  }
  if (mountedSessionId !== sessionId) {
    detachSession()
    mountedSessionId = sessionId
  }
  await ensureTabs(sessionId)
  await mountActive(sessionId)
}

/* 切换标签：展示并聚焦，重新自适应 */
async function activate(id: string): Promise<void> {
  workspace.setActiveTerminal(props.sessionId, id)
  await nextTick()
  await mountTerminal(id)
  fitTerminal(id)
  runtimes.get(id)?.term.focus()
}

/* 关闭并回收单个终端（显式关闭才销毁 PTY） */
function disposeTab(id: string): void {
  detachRuntime(id)
  containers.delete(id)
  void api.terminal.dispose(id)
  workspace.removeTerminalTab(props.sessionId, id)
}

/* 统一订阅终端事件：按 id 路由到对应 xterm；回放未完成时先入队 */
offEvent = api.terminal.onEvent((event) => {
  const runtime = runtimes.get(event.id)
  if (!runtime) {
    return
  }
  if (!runtime.ready) {
    runtime.pending.push(event)
    return
  }
  if (event.seq <= runtime.lastSeq) {
    return
  }
  runtime.lastSeq = event.seq
  if (event.type === 'data' && event.data != null) {
    runtime.term.write(event.data)
  } else if (event.type === 'exit') {
    runtime.term.write(`\r\n${t('chat.terminalExited', [String(event.exitCode ?? 0)])}\r\n`)
  }
})

/* 面板显示时确保终端已就绪；会话切换时重建当前会话的 xterm */
watch(
  () => props.visible,
  (visible) => {
    if (visible) {
      void activateSession(props.sessionId)
    }
  },
  { immediate: true }
)

watch(
  () => props.sessionId,
  (sessionId) => {
    if (props.visible) {
      void activateSession(sessionId)
    }
  }
)

onBeforeUnmount(() => {
  offEvent?.()
  /* 仅销毁 xterm；PTY 由主进程在应用退出 / 会话删除时统一回收 */
  detachSession()
})
</script>

<template>
  <div class="chat-terminal" :style="{ height: `${terminalState.height}px` }">
    <!-- 上边缘拖拽手柄：拖拽调整终端高度，双击恢复默认高度 -->
    <div
      class="terminal-resizer"
      :title="t('layout.resetPanelHeight')"
      @mousedown="startResize"
      @dblclick="workspace.resetTerminalHeight(props.sessionId)"
    ></div>
    <!-- 标签栏：切换/关闭终端标签，标签右侧为新建，最右侧为隐藏面板 -->
    <div class="terminal-header">
      <div class="terminal-tabs-bar">
        <div class="terminal-tabs">
          <button
            v-for="tab in tabs"
            :key="tab.id"
            class="terminal-tab"
            :class="{ active: tab.id === activeId }"
            :title="tab.title"
            @click="activate(tab.id)"
          >
            <svg class="panel-icon" viewBox="0 0 24 24" aria-hidden="true">
              <rect x="3" y="4.5" width="18" height="15" rx="2.5" />
              <path d="M7.5 10l2.5 2.5-2.5 2.5" />
              <path d="M12.5 15H17" />
            </svg>
            <span class="terminal-tab-name">{{ tab.title }}</span>
            <el-icon class="terminal-tab-close" @click.stop="disposeTab(tab.id)"><Close /></el-icon>
          </button>
        </div>
        <button
          class="sidebar-toggle terminal-new"
          :title="t('chat.newTerminal')"
          @click="openTerminal"
        >
          <el-icon><Plus /></el-icon>
        </button>
      </div>
      <div class="terminal-actions">
        <button class="sidebar-toggle" :title="t('chat.hideTerminal')" @click="emit('close')">
          <el-icon><Close /></el-icon>
        </button>
      </div>
    </div>
    <!-- 终端画布：每个标签一个 xterm 容器，仅展示当前激活标签 -->
    <div class="terminal-body">
      <div
        v-for="tab in tabs"
        v-show="tab.id === activeId"
        :key="tab.id"
        class="terminal-xterm"
        :ref="(el) => setContainer(tab.id, el)"
      ></div>
    </div>
  </div>
</template>

<style scoped lang="scss">
/* 底部终端面板：横跨主区底部（高度由会话工作区状态提供，可拖拽调整） */
.chat-terminal {
  position: relative;
  height: 240px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  border-top: 1px solid var(--desk-border);
  background: var(--desk-bg);
}

/* 上边缘拖拽手柄：调整终端高度；透明命中区略宽于视觉条 */
.terminal-resizer {
  position: absolute;
  top: 0;
  left: 0;
  z-index: 10;
  width: 100%;
  height: 5px;
  cursor: row-resize;
  -webkit-app-region: no-drag;
}

.terminal-resizer::after {
  content: '';
  position: absolute;
  top: -2px;
  left: 0;
  width: 100%;
  height: 12px;
}

.terminal-resizer:hover,
body.resizing-vertical .terminal-resizer {
  background: var(--desk-primary-soft);
}

.terminal-header {
  height: 40px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 0 10px 0 14px;
  border-bottom: 1px solid var(--desk-border);
}

/* 左侧分组：标签区 + 新建按钮，新建紧随标签右侧且不随标签滚动 */
.terminal-tabs-bar {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 1;
  min-width: 0;
}

.terminal-tabs {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  overflow-x: auto;
}

/* 标签：终端图标 + 名称 + 关闭 */
.terminal-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  padding: 4px 6px 4px 10px;
  border: none;
  border-radius: 8px;
  background: transparent;
  font-size: 13px;
  color: var(--desk-text-secondary);
  cursor: pointer;
  transition:
    background 0.15s ease,
    color 0.15s ease;
}

.terminal-tab:hover {
  background: var(--desk-primary-soft);
}

.terminal-tab.active {
  background: var(--desk-primary-soft);
  color: var(--desk-text);
}

.terminal-tab-name {
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 终端图标：随文字色着色 */
.panel-icon {
  width: 15px;
  height: 15px;
  flex-shrink: 0;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-linejoin: round;
}

/* 关闭图标：默认隐藏，悬浮标签时展示 */
.terminal-tab-close {
  font-size: 13px;
  visibility: hidden;
}

.terminal-tab:hover .terminal-tab-close {
  visibility: visible;
}

.terminal-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

/* 终端画布容器：绝对铺满，交由 xterm 绘制 */
.terminal-body {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow: hidden;
  background: var(--desk-bg);
}

.terminal-xterm {
  position: absolute;
  inset: 6px 8px;
}
</style>
