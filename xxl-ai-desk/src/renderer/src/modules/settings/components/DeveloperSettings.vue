<script setup lang="ts">
/*
 * 设置 - 开发者模式：开启后记录模型通讯 trace，并在右侧工具面板展示「会话日志」。
 * @author xuxueli 2026-11-01
 */
import { computed } from 'vue'
import { useSettingsStore } from '@renderer/stores/settings'
import { t } from '@renderer/i18n'

const settings = useSettingsStore()

/* 开关：保存后立即生效（trace 采集在下一轮对话开始） */
const developerMode = computed({
  get: () => settings.settings.developerMode,
  set: (value: boolean) => {
    void settings.saveSettings({ developerMode: value })
  }
})
</script>

<template>
  <div class="developer">
    <div class="developer-title">{{ t('settings.developerTitle') }}</div>
    <div class="developer-tip">{{ t('settings.developerTip') }}</div>
    <div class="developer-field">
      <span class="developer-field-label">{{ t('settings.developerMode') }}</span>
      <el-switch v-model="developerMode" />
    </div>
  </div>
</template>

<style scoped lang="scss">
.developer {
  margin-top: 28px;
  padding-top: 20px;
  border-top: 1px solid var(--desk-border);
}

.developer-title {
  font-size: var(--desk-font-base);
  color: var(--el-text-color-regular);
  margin-bottom: 6px;
}

.developer-tip {
  font-size: var(--desk-font-xs);
  color: var(--desk-text-tertiary);
  line-height: 1.5;
  margin-bottom: 16px;
}

.developer-field {
  display: flex;
  align-items: center;
  gap: 12px;
}

.developer-field-label {
  font-size: var(--desk-font-sm);
  color: var(--desk-text-secondary);
}
</style>
