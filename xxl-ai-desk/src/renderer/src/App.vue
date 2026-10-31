<script setup lang="ts">
/* 应用外壳：三段式布局 + 壳级宿主（终端/浏览器/审批/更新提示），启动逻辑见 useAppBootstrap */
import { BrowserHost, ShellTerminalHost } from '@renderer/modules/workspace'
import { UpdateDialog } from '@renderer/modules/settings'
import ApprovalDialog from '@renderer/components/ApprovalDialog.vue'
import AppSidebar from '@renderer/components/shell/AppSidebar.vue'
import AppTopbar from '@renderer/components/shell/AppTopbar.vue'
import { useAppBootstrap } from '@renderer/composables/useAppBootstrap'

useAppBootstrap()
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
