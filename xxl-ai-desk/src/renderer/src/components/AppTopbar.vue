<script setup lang="ts">
/*
* 全局顶栏：贯通整窗，固定承载「后退/前进 + 首页（新建对话）」并展示当前页面标题与操作
* @author xuxueli 2026-10-18
*/
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import NavControls from '@renderer/components/NavControls.vue'
import {
  ChatCommandPalette,
  ChatTopbarActions,
  useCommandPalette,
  useSessionActions
} from '@renderer/modules/chat'
import { useChatStore } from '@renderer/stores/chat'
import { useSettingsStore } from '@renderer/stores/settings'
import { NAV_SECTIONS, sectionOfPath } from '@renderer/config/nav'
import { t } from '@renderer/i18n'

const route = useRoute()
const router = useRouter()
const chat = useChatStore()
const settings = useSettingsStore()
/* 会话操作（重命名 / 删除） */
const { onChatCommand } = useSessionActions()
/* 搜索命令面板显隐（顶栏入口与面板共享） */
const { visible: commandPaletteVisible } = useCommandPalette()

/* 搜索面板选中会话：定位会话并回到对话页 */
async function onPickSession(sessionId: string): Promise<void> {
  await chat.selectSession(sessionId)
  void router.push('/')
}

/* 当前所属板块 */
const section = computed(() => sectionOfPath(route.path))

/* 是否对话页（对话页展示会话标题与终端/侧栏开关） */
const isChat = computed(() => section.value === 'home')

/* 顶栏标题：对话页取当前会话标题（无会话回落「新对话」）；其余取板块标题 */
const pageTitle = computed(() =>
  section.value === 'home'
    ? chat.currentSession?.title || t('chat.newChat')
    : t(NAV_SECTIONS[section.value].titleKey)
)

/* 是否有标题：仅在有标题时才展示标题前的分隔线 */
const hasTitle = computed(() => pageTitle.value.trim().length > 0)
</script>

<template>
  <header class="desk-topbar" :class="{ mac: settings.platform === 'darwin' }">
    <div class="desk-topbar-left">
      <NavControls />
      <!-- 有标题时展示分隔线（｜），分隔导航控件与标题 -->
      <span v-if="hasTitle" class="topbar-sep" aria-hidden="true"></span>
      <div v-if="hasTitle" class="desk-topbar-context">
        <template v-if="isChat">
          <div class="chat-title-wrap">
            <div class="chat-title">{{ pageTitle }}</div>
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
        <div v-else class="topbar-title">{{ pageTitle }}</div>
      </div>
    </div>

    <!-- 对话页操作：搜索 / 底部终端 / 右侧侧栏（侧边任务）显隐开关 -->
    <div v-if="isChat" class="desk-topbar-actions">
      <ChatTopbarActions />
    </div>

    <!-- 会话搜索命令面板：由顶栏搜索入口唤起 -->
    <ChatCommandPalette v-model:visible="commandPaletteVisible" @select="onPickSession" />
  </header>
</template>

<style scoped lang="scss">
/* 标题前分隔线（｜）：区分导航控件与标题，仅在存在标题时渲染 */
.topbar-sep {
  flex-shrink: 0;
  width: 1px;
  height: 15px;
  background: var(--desk-text-tertiary);
  opacity: 0.45;
}

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
  font-size: 16px;
  font-weight: 600;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 会话操作用「...」：默认隐藏，悬浮标题文案时展示 */
.chat-title-more {
  flex-shrink: 0;
  font-size: 16px;
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

/* 非对话页标题（插件 / 系统设置） */
.topbar-title {
  font-size: 16px;
  font-weight: 600;
}
</style>
