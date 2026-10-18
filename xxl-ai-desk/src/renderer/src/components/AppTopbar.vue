<script setup lang="ts">
/*
* 全局顶栏：贯通整窗，固定承载「后退/前进 + 折叠」并展示当前页面标题与操作
* @author xuxueli 2026-10-18
*/
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import NavControls from '@renderer/components/NavControls.vue'
import { useChatStore } from '@renderer/stores/chat'
import { useLayoutStore } from '@renderer/stores/layout'
import { useSettingsStore } from '@renderer/stores/settings'
import { t } from '@renderer/i18n'

const route = useRoute()
const chat = useChatStore()
const layout = useLayoutStore()
const settings = useSettingsStore()

/* 是否为对话页（对话页展示会话标题与终端/侧栏开关） */
const isChat = computed(() => route.path === '/')

/* 是否为插件页（顶栏展示「插件」标题） */
const isPlugin = computed(() => route.path.startsWith('/plugin'))

/* 对话页标题：当前会话标题，无会话时展示「新对话」 */
const title = computed(() => chat.currentSession?.title || t('chat.newChat'))

/* 非对话页标题（插件 / 系统设置） */
const pageTitle = computed(() => (isPlugin.value ? t('plugin.title') : t('settings.title')))

/* 是否有标题：仅在有标题时才展示标题前的分隔线 */
const hasTitle = computed(() =>
  (isChat.value ? title.value : pageTitle.value).trim().length > 0
)

/* 会话操作下拉命令分发 */
function onChatCommand(command: string): void {
  if (command === 'rename') {
    void onRenameCurrent()
  } else if (command === 'delete') {
    void onDeleteCurrent()
  }
}

/* 重命名当前会话 */
async function onRenameCurrent(): Promise<void> {
  const session = chat.currentSession
  if (!session) {
    return
  }
  try {
    const { value } = await ElMessageBox.prompt('', t('chat.renameSession'), {
      inputValue: session.title,
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel')
    })
    await chat.renameSession(session.id, value || session.title)
  } catch {
    /* 取消 */
  }
}

/* 删除当前会话 */
async function onDeleteCurrent(): Promise<void> {
  const session = chat.currentSession
  if (!session) {
    return
  }
  try {
    await ElMessageBox.confirm(t('chat.deleteConfirm'), t('chat.deleteSession'), {
      type: 'warning',
      confirmButtonText: t('common.delete'),
      cancelButtonText: t('common.cancel')
    })
    await chat.removeSession(session.id)
    ElMessage.success(t('common.deleted'))
  } catch {
    /* 取消 */
  }
}
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
        <div v-else class="topbar-title">{{ pageTitle }}</div>
      </div>
    </div>

    <!-- 对话页操作：底部终端 / 右侧侧栏（侧边任务）显隐开关 -->
    <div v-if="isChat" class="desk-topbar-actions">
      <button
        class="sidebar-toggle"
        :class="{ active: layout.terminalVisible }"
        :title="layout.terminalVisible ? t('chat.hideTerminal') : t('chat.showTerminal')"
        @click="layout.toggleTerminal"
      >
        <svg class="panel-icon" viewBox="0 0 24 24" aria-hidden="true">
          <rect x="3" y="4.5" width="18" height="15" rx="2.5" />
          <path d="M7.5 10l2.5 2.5-2.5 2.5" />
          <path d="M12.5 15H17" />
        </svg>
      </button>
      <button
        class="sidebar-toggle"
        :class="{ active: layout.rightPanelVisible }"
        :title="layout.rightPanelVisible ? t('chat.hideSidePanel') : t('chat.showSidePanel')"
        @click="layout.toggleRightPanel"
      >
        <svg class="panel-icon" viewBox="0 0 24 24" aria-hidden="true">
          <rect x="3" y="4.5" width="18" height="15" rx="2.5" />
          <path d="M15 4.5v15" />
        </svg>
      </button>
    </div>
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
  font-size: 14px;
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

/* 设置页标题 */
.topbar-title {
  font-size: 16px;
  font-weight: 600;
}

/* 面板开关激活态：高亮当前已展开的面板 */
.sidebar-toggle.active {
  background: var(--desk-primary-soft);
  color: var(--desk-text);
}

/* 面板图标：随文字色着色（悬浮/激活态生效） */
.panel-icon {
  width: 16px;
  height: 16px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-linejoin: round;
}
</style>
