<script setup lang="ts">
/*
* 通用子菜单列表：插件 / 系统设置 等板块的二级列表（数据来自 config/nav）
* @author xuxueli 2026-10-18
*/
import { t } from '@renderer/i18n'
import type { NavItem } from '@renderer/config/nav'

/* 子菜单项列表：选中项加粗由组件内 active 样式统一处理 */
defineProps<{ titleKey: string; items: NavItem[]; activeKey: string }>()
const emit = defineEmits<{ select: [item: NavItem] }>()
</script>

<template>
  <div class="panel-head">
    <div class="panel-title">{{ t(titleKey) }}</div>
  </div>
  <el-scrollbar class="sidebar-list">
    <div
      v-for="item in items"
      :key="item.key"
      class="menu-item"
      :class="{ active: item.key === activeKey }"
      @click="emit('select', item)"
    >
      <el-icon v-if="item.icon" class="menu-icon"><component :is="item.icon" /></el-icon>
      <span class="menu-label">{{ t(item.labelKey) }}</span>
    </div>
  </el-scrollbar>
</template>

<style scoped lang="scss">
/* 子菜单标题：字体与首页标题保持一致 */
.panel-head {
  padding: 10px 16px 12px;
}

.panel-title {
  font-size: 18px;
  font-weight: 600;
  line-height: 32px;
  color: var(--desk-text);
}

/* 子菜单项：默认常规字重，选中项加粗并高亮底色 */
.menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  margin-bottom: 2px;
  border-radius: 10px;
  cursor: pointer;
  color: var(--desk-text);
  font-size: 14px;
  font-weight: 400;
  transition: background 0.15s ease;
}

.menu-item:hover {
  background: var(--desk-primary-soft);
}

.menu-item.active {
  background: var(--desk-primary-soft);
  color: var(--desk-text);
  font-weight: 600;
}

.menu-icon {
  flex-shrink: 0;
  font-size: 15px;
}

.menu-label {
  flex: 1;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
