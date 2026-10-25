<script setup lang="ts">
/*
 * SKILL 技能中心：顶部「SKILL 管理 / 发现 SKILL」TAB，编排管理面板、发现面板与详情抽屉。
 * @author xuxueli 2026-10-25
 */
import { ref } from 'vue'
import SkillManagePanel from '../components/SkillManagePanel.vue'
import SkillDiscoverPanel from '../components/SkillDiscoverPanel.vue'
import SkillDetailDrawer from '../components/SkillDetailDrawer.vue'
import { t } from '@renderer/i18n'

/* 当前 TAB：manage 管理 / discover 发现 */
const activeTab = ref<'manage' | 'discover'>('manage')

/* 详情抽屉状态 */
const detailVisible = ref(false)
const detailId = ref('')

/* 打开技能详情 */
function openDetail(id: string): void {
  detailId.value = id
  detailVisible.value = true
}
</script>

<template>
  <div class="skill-center">
    <div class="center-header">
      <div class="tab-bar">
        <button
          class="tab-item"
          :class="{ active: activeTab === 'manage' }"
          @click="activeTab = 'manage'"
        >
          {{ t('plugin.skillCenter.tabManage') }}
        </button>
        <button
          class="tab-item"
          :class="{ active: activeTab === 'discover' }"
          @click="activeTab = 'discover'"
        >
          {{ t('plugin.skillCenter.tabDiscover') }}
        </button>
      </div>
    </div>

    <div class="center-body">
      <SkillManagePanel
        v-show="activeTab === 'manage'"
        @detail="openDetail"
        @discover="activeTab = 'discover'"
      />
      <SkillDiscoverPanel v-show="activeTab === 'discover'" @installed="openDetail" />
    </div>

    <SkillDetailDrawer v-model:visible="detailVisible" :skill-id="detailId" />
  </div>
</template>

<style scoped lang="scss">
.skill-center {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
  background: var(--desk-bg);
}

.center-header {
  flex-shrink: 0;
  padding: 18px 28px 0;
  border-bottom: 1px solid var(--desk-border);
}

.tab-bar {
  display: flex;
  align-items: center;
  gap: 22px;
}

.tab-item {
  position: relative;
  padding: 0 0 12px;
  border: none;
  background: transparent;
  font-size: 14px;
  color: var(--desk-text-secondary);
  cursor: pointer;
  transition: color 0.15s ease;
}

.tab-item:hover {
  color: var(--desk-text);
}

.tab-item.active {
  color: var(--desk-text);
  font-weight: 600;
}

.tab-item.active::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 2px;
  border-radius: 2px;
  background: var(--desk-primary);
}

.center-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding-top: 16px;
}
</style>
