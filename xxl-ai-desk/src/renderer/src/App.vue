<script setup lang="ts">
/* 应用外壳：左侧会话栏（可折叠）+ 右侧内容区 + 全局快捷键 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ChatSidebar, useTerminalTarget } from '@renderer/modules/chat'
import { WorkspaceTerminalPanel } from '@renderer/modules/workspace/terminal'
import ApprovalDialog from '@renderer/components/ApprovalDialog.vue'
import AppTopbar from '@renderer/components/AppTopbar.vue'
import { useSettingsStore } from '@renderer/stores/settings'
import { useChatStore } from '@renderer/stores/chat'
import { useProjectStore } from '@renderer/stores/project'
import { useLayoutStore } from '@renderer/stores/layout'
import { useQuickAction } from '@renderer/composables/useQuickAction'
import { sectionOfPath } from '@renderer/config/nav'
import { matchShortcut } from '@renderer/utils/shortcut'
import { t } from './i18n'
import type { QuickAction } from '@shared/ipc'

const route = useRoute()
const settings = useSettingsStore()
const chat = useChatStore()
const project = useProjectStore()
const layout = useLayoutStore()
const runQuickAction = useQuickAction()
const { terminalCwd, terminalName } = useTerminalTarget()

/* 终端面板是否已首次挂载：首次打开后常驻，避免路由切换导致 PTY 重建 */
const terminalMounted = ref(false)

/* 是否对话页（终端仅在对话页展示，但组件常驻以保留会话） */
const isChat = computed(() => sectionOfPath(route.path) === 'home')

/* 终端展示条件：对话页且用户已打开 */
const terminalVisible = computed(() => isChat.value && layout.terminalVisible)

/* 首次打开后挂载终端组件；此后仅 v-show 切换，PTY 与滚动历史保持 */
watch(
  terminalVisible,
  (visible) => {
    if (visible) {
      terminalMounted.value = true
    }
  },
  { immediate: true }
)

/* 启动后延迟检查更新的定时器（卸载时清理） */
let updateTimer: ReturnType<typeof setTimeout> | null = null

/* 全局快捷键：命中已配置的快捷操作则执行并阻止默认行为 */
function onGlobalKeydown(event: KeyboardEvent): void {
  if (event.defaultPrevented || event.isComposing) {
    return
  }
  const map = settings.settings.shortcuts
  if (!map) {
    return
  }
  for (const [action, binding] of Object.entries(map)) {
    if (matchShortcut(event, binding)) {
      event.preventDefault()
      runQuickAction(action as QuickAction)
      return
    }
  }
}

/* 挂载后：注册全局快捷键，绑定对话事件并初始化设置/项目/会话，默认进入最近对话 */
onMounted(async () => {
  window.addEventListener('keydown', onGlobalKeydown)
  chat.bind()
  await settings.load()
  await project.loadProjects()
  await chat.loadSessions()
  /* 默认定位会话：有历史会话则打开最后打开的会话，否则进入新建对话页面 */
  await chat.restoreLastSession()
  /* 启动后延迟静默检查更新（仅发现新版本时弹提示，失败静默） */
  if (settings.settings.updateAutoCheck) {
    updateTimer = setTimeout(() => {
      void settings.checkUpdate(false)
    }, 5000)
  }
})

/* 卸载前：移除全局快捷键监听、清理更新定时器，避免泄漏 */
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onGlobalKeydown)
  if (updateTimer) {
    clearTimeout(updateTimer)
    updateTimer = null
  }
})
</script>

<template>
  <div class="desk-shell">
    <!-- 全局顶栏：红黄绿（系统）+ 后退/前进 + 折叠按钮；页面标题/操作随路由展示 -->
    <AppTopbar />

    <div class="desk-body">
      <ChatSidebar />
      <div class="desk-main">
        <div class="desk-view">
          <RouterView />
        </div>

        <!-- 底部终端：首次打开后常驻挂载，仅切换显隐，避免 PTY 随路由切换重建 -->
        <WorkspaceTerminalPanel
          v-if="terminalMounted"
          v-show="terminalVisible"
          :cwd="terminalCwd"
          :title="terminalName"
          :visible="terminalVisible"
          @close="layout.toggleTerminal"
        />
      </div>
    </div>

    <!-- 越界访问审批：全局应用内对话框 -->
    <ApprovalDialog />

    <!-- 客户端更新提示：发现新版本时引导前往下载 -->
    <el-dialog
      :model-value="!!settings.updateInfo?.hasUpdate"
      class="update-dialog"
      :title="t('update.title')"
      width="520px"
      align-center
      @close="settings.dismissUpdate()"
    >
      <div class="update-body">
        <div class="update-version">
          {{ t('update.version', [settings.updateInfo?.latestVersion ?? '']) }}
        </div>
        <div class="update-current">
          {{ t('update.current', [settings.updateInfo?.currentVersion ?? '']) }}
        </div>
        <pre v-if="settings.updateInfo?.notes" class="update-notes">{{ settings.updateInfo.notes }}</pre>
      </div>
      <template #footer>
        <el-button @click="settings.dismissUpdate()">{{ t('update.later') }}</el-button>
        <el-button type="primary" @click="settings.openUpdate()">{{ t('update.download') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 页面容器：占满终端以外的剩余高度，供各页面 height:100% 使用 */
.desk-view {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.update-version {
  font-size: 15px;
  font-weight: 600;
}

.update-current {
  margin-top: 4px;
  font-size: 13px;
  color: var(--desk-text-tertiary);
}

.update-notes {
  margin: 12px 0 0;
  padding: 10px 12px;
  max-height: 260px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: 13px;
  color: var(--desk-text-secondary);
  background: var(--desk-bg-elevated);
  border: 1px solid var(--desk-border);
  border-radius: var(--desk-radius-sm);
}
</style>
