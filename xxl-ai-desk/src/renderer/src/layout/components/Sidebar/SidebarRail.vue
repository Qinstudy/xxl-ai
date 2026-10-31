<script setup lang="ts">
/*
 * 左侧常驻导航图标栏：首页 / 插件 / 底部系统设置，切换一级板块
 * @author xuxueli 2026-10-18
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useLayoutStore } from '@renderer/stores/layout'
import { SECTION_ENTRY, SECTION_ICON, sectionOfPath, type SectionKey } from '@renderer/config/nav'
import { t } from '@renderer/i18n'

const route = useRoute()
const router = useRouter()
const layout = useLayoutStore()

/* 当前所属板块（高亮对应入口） */
const section = computed(() => sectionOfPath(route.path))

/* 切换板块：点击其它板块展开子菜单栏并跳转入口地址；点击当前板块则折叠/展开子菜单栏 */
function go(key: SectionKey): void {
  if (section.value === key) {
    layout.toggleSidebar()
    return
  }
  layout.expandSidebar()
  void router.push(SECTION_ENTRY[key])
}
</script>

<template>
  <nav class="sidebar-rail">
    <div class="rail-group">
      <button
        class="rail-btn"
        :class="{ active: section === 'home' }"
        :title="t('chat.title')"
        @click="go('home')"
      >
        <!-- 首页：会话气泡 -->
        <el-icon><component :is="SECTION_ICON.home" /></el-icon>
      </button>
      <button
        class="rail-btn"
        :class="{ active: section === 'plugin' }"
        :title="t('plugin.title')"
        @click="go('plugin')"
      >
        <!-- 插件：装配/扩展 -->
        <el-icon><component :is="SECTION_ICON.plugin" /></el-icon>
      </button>
    </div>
    <div class="rail-group">
      <button
        class="rail-btn"
        :class="{ active: section === 'settings' }"
        :title="t('settings.title')"
        @click="go('settings')"
      >
        <el-icon><component :is="SECTION_ICON.settings" /></el-icon>
      </button>
    </div>
  </nav>
</template>

<style scoped lang="scss">
/* 常驻图标栏：固定宽度，与顶部栏同色（最深） */
.sidebar-rail {
  flex-shrink: 0;
  width: var(--desk-rail-width);
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  background: var(--desk-chrome-bg);
}

.rail-group {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

/* 图标按钮：宽度收紧至图标栏内留出等距外边距 */
.rail-btn {
  width: 32px;
  height: 32px;
  padding: 0;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--desk-text-secondary);
  font-size: var(--desk-icon-lg);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  -webkit-app-region: no-drag;
  transition:
    background 0.15s ease,
    color 0.15s ease;
}

.rail-btn:hover {
  background: var(--desk-primary-soft);
  color: var(--desk-text-secondary);
}

.rail-btn.active {
  background: var(--desk-primary-soft);
  color: var(--desk-text-secondary);
}
</style>
