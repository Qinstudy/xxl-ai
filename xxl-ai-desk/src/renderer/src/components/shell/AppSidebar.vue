<script setup lang="ts">
/*
 * 应用侧栏外壳：常驻图标栏 + 子菜单栏（首页 / 插件 / 系统设置 按板块切换）
 * @author xuxueli 2026-10-18
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ActivityRail from '@renderer/components/shell/ActivityRail.vue'
import SectionMenu from '@renderer/components/shell/SectionMenu.vue'
import { ChatSidebarHomePanel } from '@renderer/modules/chat'
import { useShellStore } from '@renderer/stores/shell'
import { useSettingsStore } from '@renderer/stores/settings'
import PanelResizer from '@renderer/components/PanelResizer.vue'
import { NAV_SECTIONS, pluginItemKey, sectionOfPath, type NavItem } from '@renderer/config/nav'
import { t } from '@renderer/i18n'

const route = useRoute()
const router = useRouter()
const shell = useShellStore()
const settings = useSettingsStore()

/* 当前所属板块 */
const section = computed(() => sectionOfPath(route.path))

/* 非首页板块的子菜单定义（插件 / 系统设置）；首页为自定义面板 */
const submenu = computed(() => (section.value === 'home' ? null : NAV_SECTIONS[section.value]))

/* 当前子菜单选中项：插件按路由、系统设置按设置分类 */
const activeKey = computed(() =>
  section.value === 'plugin' ? pluginItemKey(route.path) : settings.activeTab
)

/* 子菜单栏宽度双向绑定：拖拽写入 store（含夹取与持久化） */
const panelWidth = computed({
  get: () => shell.panelWidth,
  set: (value: number) => shell.setPanelWidth(value)
})

/* 子菜单项点击：插件跳路由；系统设置切分类并进入设置页 */
function onSubmenuSelect(item: NavItem): void {
  if (item.tab) {
    settings.activeTab = item.tab
    if (route.path !== NAV_SECTIONS.settings.entryPath) {
      router.push(NAV_SECTIONS.settings.entryPath)
    }
    return
  }
  if (item.path && route.path !== item.path) {
    router.push(item.path)
  }
}
</script>

<template>
  <aside
    class="desk-sidebar"
    :class="{ collapsed: shell.sidebarCollapsed }"
    :style="{
      width: shell.sidebarCollapsed
        ? 'var(--desk-rail-width)'
        : `calc(var(--desk-rail-width) + ${shell.panelWidth}px)`
    }"
  >
    <ActivityRail />

    <!-- 子菜单栏：折叠时隐藏，仅保留左侧图标栏 -->
    <div v-show="!shell.sidebarCollapsed" class="sidebar-panel">
      <!-- 右边缘拖拽手柄：拖拽调整面板宽度，双击恢复默认宽度 -->
      <PanelResizer
        v-model:size="panelWidth"
        edge="left"
        :title="t('layout.resetPanelWidth')"
        @reset="shell.resetPanelWidth"
      />

      <ChatSidebarHomePanel v-if="!submenu" />
      <SectionMenu
        v-else
        :title-key="submenu.titleKey"
        :items="submenu.items"
        :active-key="activeKey"
        @select="onSubmenuSelect"
      />
    </div>
  </aside>
</template>

<style scoped lang="scss">
/* 左侧栏：图标栏 + 可折叠子菜单栏 */
.desk-sidebar {
  width: calc(var(--desk-rail-width) + 240px);
  flex-shrink: 0;
  position: relative;
  display: flex;
  flex-direction: row;
  background: var(--desk-panel-bg);
  overflow: hidden;
  transition: width 0.2s ease;
}

/* 拖拽调整宽度期间禁用过渡 */
:global(body.resizing) .desk-sidebar {
  transition: none;
}

/* 列表面板：图标栏右侧，折叠时隐藏；与右侧正文之间保留间隔线 */
.sidebar-panel {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-right: 1px solid var(--desk-border);
}
</style>
