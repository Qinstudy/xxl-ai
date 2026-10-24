<script setup lang="ts">
/* 应用外壳：左侧会话栏（可折叠）+ 右侧内容区 + 全局快捷键 */
import { onBeforeUnmount, onMounted } from 'vue'
import Sidebar from '@renderer/modules/chat/components/Sidebar.vue'
import ApprovalDialog from '@renderer/components/ApprovalDialog.vue'
import AppTopbar from '@renderer/components/AppTopbar.vue'
import { useSettingsStore } from '@renderer/stores/settings'
import { useChatStore } from '@renderer/stores/chat'
import { useProjectStore } from '@renderer/stores/project'
import { useQuickAction } from '@renderer/composables/useQuickAction'
import { matchShortcut } from '@renderer/utils/shortcut'
import { t } from './i18n'
import type { QuickAction } from '@shared/ipc'

const settings = useSettingsStore()
const chat = useChatStore()
const project = useProjectStore()
const runQuickAction = useQuickAction()

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
      <Sidebar />
      <div class="desk-main">
        <RouterView />
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
