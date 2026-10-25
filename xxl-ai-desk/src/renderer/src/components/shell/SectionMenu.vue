<script setup lang="ts">
/*
 * 通用二级子菜单列表：插件 / 系统设置 等板块的二级列表（数据来自 config/nav）
 * @author xuxueli 2026-10-18
 */
import SidebarPanelHeader from '@renderer/components/SidebarPanelHeader.vue'
import SidebarRow from '@renderer/components/SidebarRow.vue'
import { t } from '@renderer/i18n'
import type { NavItem } from '@renderer/config/nav'

/* 子菜单项列表：选中项加粗由 SidebarRow 统一处理 */
defineProps<{ titleKey: string; items: NavItem[]; activeKey: string }>()
const emit = defineEmits<{ select: [item: NavItem] }>()
</script>

<template>
  <SidebarPanelHeader :title-key="titleKey" />
  <el-scrollbar class="sidebar-list">
    <SidebarRow
      v-for="item in items"
      :key="item.key"
      :active="item.key === activeKey"
      @click="emit('select', item)"
    >
      <template #icon>
        <el-icon v-if="item.icon" class="menu-icon"><component :is="item.icon" /></el-icon>
      </template>
      {{ t(item.labelKey) }}
    </SidebarRow>
  </el-scrollbar>
</template>

<style scoped lang="scss">
.menu-icon {
  flex-shrink: 0;
  font-size: 15px;
}
</style>
