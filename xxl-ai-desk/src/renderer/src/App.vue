<script setup lang="ts">
/* 应用外壳：三段式布局 + 全局快捷键 + 壳级宿主（终端/浏览器/审批/更新提示） */
import { onBeforeUnmount, onMounted } from 'vue'
import { BrowserHost, ShellTerminalHost } from '@renderer/modules/workspace'
import UpdateDialog from '@renderer/modules/settings/components/UpdateDialog.vue'
import ApprovalDialog from '@renderer/components/ApprovalDialog.vue'
import AppSidebar from '@renderer/components/shell/AppSidebar.vue'
import AppTopbar from '@renderer/components/shell/AppTopbar.vue'
import { useSettingsStore } from '@renderer/stores/settings'
import { useChatStore } from '@renderer/stores/chat'
import { useProjectStore } from '@renderer/stores/project'
import { useQuickAction } from '@renderer/composables/useQuickAction'
import { useSessionLifecycle } from '@renderer/composables/useSessionLifecycle'
import { matchShortcut } from '@renderer/utils/shortcut'
import type { QuickAction } from '@shared/ipc'

const settings = useSettingsStore()
const chat = useChatStore()
const project = useProjectStore()
const runQuickAction = useQuickAction()
const sessionLifecycle = useSessionLifecycle()

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
  /* 校准并开始监听会话生命周期：清理已删除会话遗留的工作区/浏览器/对话 UI 持久化 */
  sessionLifecycle.start(chat.sessions.map((session) => session.id))
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
    <!-- 全局顶栏：软件名称 + 页面标题/操作随路由展示 -->
    <AppTopbar />

    <div class="desk-body">
      <AppSidebar />
      <div class="desk-main">
        <div class="desk-view">
          <RouterView />
        </div>

        <!-- 底部终端：首次打开后常驻挂载（域逻辑在 workspace 内） -->
        <ShellTerminalHost />
      </div>
    </div>

    <!-- 浏览器宿主层：跨会话保活 webview，按工具面板锚点定位 -->
    <BrowserHost />

    <!-- 越界访问审批：全局应用内对话框 -->
    <ApprovalDialog />

    <!-- 客户端更新提示：发现新版本时引导前往下载 -->
    <UpdateDialog />
  </div>
</template>

<style scoped lang="scss">
/* 外壳纵向布局：顶栏 + 正文 */
.desk-shell {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
  background: var(--desk-bg);
}

/* 顶栏之下：左侧栏（图标栏 + 列表面板） + 内容区 */
.desk-body {
  flex: 1;
  min-height: 0;
  display: flex;
}

.desk-main {
  position: relative;
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: var(--desk-bg);
}

/* 页面容器：占满终端以外的剩余高度，供各页面 height:100% 使用 */
.desk-view {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
</style>
