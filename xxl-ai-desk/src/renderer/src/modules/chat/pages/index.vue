<script setup lang="ts">
/* 对话页：装配会话工作区（对话区 + 右侧工具面板），各区域状态按会话隔离 */
import { computed, watch } from 'vue'
import ChatRegion from '../components/ChatRegion.vue'
import { WorkspaceLayout, useTerminalTarget } from '@renderer/modules/workspace'
import { useChatStore } from '@renderer/stores/chat'
import { DRAFT_SESSION_KEY, useWorkspaceStore } from '@renderer/stores/workspace'

const chat = useChatStore()
const workspace = useWorkspaceStore()
/* 右侧工具面板与终端共用当前项目定位 */
const { terminalProject } = useTerminalTarget()

/* 当前工作区 key：真实会话 id；新建对话草稿态使用固定 key */
const workspaceKey = computed(() => chat.currentId || DRAFT_SESSION_KEY)

/* 确保当前会话的工作区状态已创建（含本地持久化恢复） */
watch(workspaceKey, (key) => workspace.ensure(key), { immediate: true })
</script>

<template>
  <WorkspaceLayout
    :session-id="workspaceKey"
    :root="terminalProject?.path"
    :project-name="terminalProject?.name"
  >
    <ChatRegion />
  </WorkspaceLayout>
</template>
