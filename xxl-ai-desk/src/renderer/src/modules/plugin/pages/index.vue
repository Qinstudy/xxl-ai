<script setup lang="ts">
/*
 * 插件模块正文：MCP 插件中心（管理 / 发现）与 SKILL 技能占位。
 * @author xuxueli 2026-10-18
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import McpCenter from '../components/McpCenter.vue'
import { t } from '@renderer/i18n'

const route = useRoute()

/* 是否 SKILL 技能路由（其余为 MCP 插件中心） */
const isSkill = computed(() => route.path.endsWith('/skill'))
</script>

<template>
  <McpCenter v-if="!isSkill" />
  <div v-else class="plugin-page">
    <div class="plugin-placeholder">
      <div class="plugin-name">{{ t('plugin.skill') }}</div>
      <div class="plugin-wip">{{ t('plugin.wip') }}</div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.plugin-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--desk-bg);
}

.plugin-placeholder {
  text-align: center;
}

.plugin-name {
  font-size: 16px;
  font-weight: 600;
  color: var(--desk-text);
}

.plugin-wip {
  margin-top: 8px;
  font-size: 14px;
  color: var(--desk-text-tertiary);
}
</style>
