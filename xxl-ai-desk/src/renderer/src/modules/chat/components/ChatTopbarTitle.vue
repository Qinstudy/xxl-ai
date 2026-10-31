<script setup lang="ts">
/*
 * 顶栏会话标题：展示当前会话标题与重命名 / 删除操作
 * @author xuxueli 2026-10-31
 */
import { useChatStore } from '@renderer/stores/chat'
import { useSessionActions } from '../composables/useSessionActions'
import { t } from '@renderer/i18n'

defineProps<{ title: string }>()

const chat = useChatStore()
/* 会话操作（重命名 / 删除） */
const { onChatCommand } = useSessionActions()
</script>

<template>
  <div class="chat-title-wrap">
    <div class="chat-title">{{ title }}</div>
    <!-- 会话操作：重命名 / 删除（悬浮标题文案展示） -->
    <el-dropdown
      v-if="chat.currentId"
      trigger="click"
      popper-class="chat-title-dropdown"
      @command="onChatCommand"
    >
      <el-icon class="chat-title-more" :title="t('common.actions')"><MoreFilled /></el-icon>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item command="rename">
            <el-icon><EditPen /></el-icon>{{ t('chat.renameSession') }}
          </el-dropdown-item>
          <el-dropdown-item command="delete" divided>
            <el-icon><Delete /></el-icon>{{ t('chat.deleteSession') }}
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<style scoped lang="scss">
/* 标题 + 操作：仅悬浮标题文案区域时显示「...」 */
.chat-title-wrap {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  /* 顶栏为窗口拖拽区，此处排除拖拽，保证 :hover 正常触发与图标可点击 */
  -webkit-app-region: no-drag;
}

.chat-title {
  font-size: var(--desk-font-base);
  font-weight: 500;
  color: var(--desk-text-secondary);
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 会话操作用「...」：默认隐藏，悬浮标题文案时展示 */
.chat-title-more {
  flex-shrink: 0;
  font-size: var(--desk-icon-md);
  color: var(--desk-text-tertiary);
  cursor: pointer;
  outline: none;
  visibility: hidden;
  transition: color 0.15s ease;
  -webkit-app-region: no-drag;
}

.chat-title-wrap:hover .chat-title-more {
  visibility: visible;
}

.chat-title-more:hover {
  color: var(--desk-text);
}
</style>
