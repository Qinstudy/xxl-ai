<script setup lang="ts">
/*
 * 底部终端面板：终端标签与 PTY 按会话隔离（运行时逻辑见 useSessionTerminals）。
 * @author xuxueli 2026-10-25
 */
import { computed } from 'vue'
import { useWorkspaceStore } from '@renderer/stores/workspace'
import PanelResizer from '@renderer/components/PanelResizer.vue'
import TerminalIcon from '@renderer/components/icons/TerminalIcon.vue'
import { t } from '@renderer/i18n'
import { useSessionTerminals } from '../../composables/useSessionTerminals'

const props = defineProps<{
  sessionId: string
  cwd?: string
  title?: string
  visible?: boolean
}>()
const emit = defineEmits<{ close: [] }>()

/* 会话工作区 store：终端高度按会话隔离 */
const workspace = useWorkspaceStore()
const terminalState = computed(() => workspace.sliceOf(props.sessionId).terminal)
const terminalHeight = computed({
  get: () => terminalState.value.height,
  set: (height: number) => workspace.setTerminalHeight(props.sessionId, height)
})

/* 终端运行时：标签、xterm 挂载与会话切换 */
const { tabs, activeId, setContainer, activate, openTerminal, disposeTab } = useSessionTerminals({
  sessionId: () => props.sessionId,
  cwd: () => props.cwd,
  title: () => props.title,
  visible: () => props.visible === true
})
</script>

<template>
  <div class="chat-terminal" :style="{ height: `${terminalState.height}px` }">
    <!-- 上边缘拖拽手柄：拖拽调整终端高度，双击恢复默认高度 -->
    <PanelResizer
      v-model:size="terminalHeight"
      edge="bottom"
      :title="t('layout.resetPanelHeight')"
      @reset="workspace.resetTerminalHeight(props.sessionId)"
    />
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
            <TerminalIcon class="panel-icon" />
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
