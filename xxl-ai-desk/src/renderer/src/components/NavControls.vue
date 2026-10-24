<script setup lang="ts">
/*
* 顶部导航控件：后退 / 前进 / 首页（进入对话模块的新建对话页），置于各页标题栏左侧固定位置
* @author xuxueli 2026-10-18
*/
import { useRouter } from 'vue-router'
import { useChatStore } from '@renderer/stores/chat'
import { useLayoutStore } from '@renderer/stores/layout'
import { useNavHistory } from '@renderer/composables/useNavHistory'
import { t } from '@renderer/i18n'

const router = useRouter()
const chat = useChatStore()
const layout = useLayoutStore()
/* 能否后退/前进（决定箭头可用态） */
const { canBack, canForward } = useNavHistory()

/* 后退到上一页 */
function goBack(): void {
  if (canBack.value) {
    router.back()
  }
}

/* 前进到下一页 */
function goForward(): void {
  if (canForward.value) {
    router.forward()
  }
}

/* 回到对话模块：展开子菜单栏并进入新建对话页面 */
function goHome(): void {
  layout.expandSidebar()
  chat.startNewChat()
  void router.push('/')
}
</script>

<template>
  <div class="nav-controls">
    <button class="sidebar-toggle" :disabled="!canBack" :title="t('layout.back')" @click="goBack">
      <el-icon><ArrowLeft /></el-icon>
    </button>
    <button
      class="sidebar-toggle"
      :disabled="!canForward"
      :title="t('layout.forward')"
      @click="goForward"
    >
      <el-icon><ArrowRight /></el-icon>
    </button>
    <button class="sidebar-toggle" :title="t('chat.newChatAction')" @click="goHome">
      <el-icon><HomeFilled /></el-icon>
    </button>
  </div>
</template>

<style scoped>
/* 导航控件组：与标题同排，固定于顶栏左侧 */
.nav-controls {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 2px;
  -webkit-app-region: no-drag;
}
</style>
