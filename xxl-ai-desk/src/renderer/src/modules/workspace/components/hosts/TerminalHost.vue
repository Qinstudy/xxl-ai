<script setup lang="ts">
/*
 * 壳级终端宿主：底部终端跨路由常驻，按当前会话隔离显隐与 PTY。
 *   - 首次打开后保持挂载，仅切换 v-show，避免路由切换重建 PTY；
 *   - 工作区 key、终端目录解析等域逻辑留在 workspace，供外壳直接挂载。
 * @author xuxueli 2026-10-25
 */
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useChatStore } from '@renderer/stores/chat'
import { DRAFT_SESSION_KEY, useWorkspaceStore } from '@renderer/stores/workspace'
import { sectionOfPath } from '@renderer/config/nav'
import { useTerminalTarget } from '../../composables/useTerminalTarget'
import { WorkspaceTerminalPanel } from '../../terminal'

const route = useRoute()
const chat = useChatStore()
const workspace = useWorkspaceStore()
const { terminalCwd, terminalName } = useTerminalTarget()

/* 当前工作区 key：真实会话 id；新建对话草稿态使用固定 key */
const workspaceKey = computed(() => chat.currentId || DRAFT_SESSION_KEY)
/* 当前会话的工作区切片 */
const workspaceState = computed(() => workspace.sliceOf(workspaceKey.value))

watch(workspaceKey, (key) => workspace.ensure(key), { immediate: true })

/* 是否对话页（终端仅在对话页展示，但组件常驻以保留会话） */
const isChat = computed(() => sectionOfPath(route.path) === 'home')
/* 终端展示条件：对话页且当前会话已打开终端 */
const terminalVisible = computed(
  () => isChat.value && workspaceState.value.terminalPanel.visible
)

/* 首次打开后挂载终端组件；此后仅 v-show 切换，PTY 与滚动历史保持 */
const terminalMounted = ref(false)
watch(
  terminalVisible,
  (visible) => {
    if (visible) {
      terminalMounted.value = true
    }
  },
  { immediate: true }
)

/* 关闭当前会话终端 */
function closeTerminal(): void {
  workspace.setTerminalVisible(workspaceKey.value, false)
}
</script>

<template>
  <WorkspaceTerminalPanel
    v-if="terminalMounted"
    v-show="terminalVisible"
    :session-id="workspaceKey"
    :cwd="terminalCwd"
    :title="terminalName"
    :visible="terminalVisible"
    @close="closeTerminal"
  />
</template>
