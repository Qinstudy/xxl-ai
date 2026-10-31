<script setup lang="ts">
/* 应用根组件：布局框架 + 全局宿主（浏览器/审批/更新提示），启动逻辑见 useAppBootstrap */
import { ChatApprovalDialog } from '@renderer/modules/chat'
import { BrowserHost } from '@renderer/modules/workspace'
import { UpdateDialog } from '@renderer/modules/settings'
import Layout from '@renderer/layout/index.vue'
import { useAppBootstrap } from '@renderer/composables/useAppBootstrap'

useAppBootstrap()
</script>

<template>
  <!-- 布局主框架：顶栏 + 左侧栏 + 主内容区 -->
  <Layout />

  <!-- 浏览器宿主层：跨会话保活 webview，按工具面板锚点定位 -->
  <BrowserHost />

  <!-- 越界访问审批：全局应用内对话框 -->
  <ChatApprovalDialog />

  <!-- 客户端更新提示：发现新版本时引导前往下载 -->
  <UpdateDialog />
</template>
