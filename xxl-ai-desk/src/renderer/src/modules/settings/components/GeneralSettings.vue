<script setup lang="ts">
/*
 * 设置 - 常规：主题 / 语言 / 自动更新 + 快捷键 / 数据目录 / 运行环境。
 * @author xuxueli 2026-10-25
 */
import { computed } from 'vue'
import { useSettingsStore } from '@renderer/stores/settings'
import { t } from '@renderer/i18n'
import ShortcutSettings from './ShortcutSettings.vue'
import DataDirSettings from './DataDirSettings.vue'
import RuntimeSettings from './RuntimeSettings.vue'
import type { AppSettings } from '@shared/ipc'

const settings = useSettingsStore()

/* 主题 / 语言切换后立即生效（无需保存） */
const theme = computed<AppSettings['theme']>({
  get: () => settings.settings.theme,
  set: (value) => {
    void settings.saveSettings({ theme: value })
  }
})
const language = computed<AppSettings['language']>({
  get: () => settings.settings.language,
  set: (value) => {
    void settings.saveSettings({ language: value })
  }
})
</script>

<template>
  <div>
    <el-form label-position="top" class="settings-form">
      <el-form-item :label="t('settings.theme')">
        <el-radio-group v-model="theme" class="soft-radio">
          <el-radio-button value="light">{{ t('settings.themeLight') }}</el-radio-button>
          <el-radio-button value="dark">{{ t('settings.themeDark') }}</el-radio-button>
          <el-radio-button value="system">{{ t('settings.themeSystem') }}</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item :label="t('settings.language')">
        <el-radio-group v-model="language" class="soft-radio">
          <el-radio-button value="zh">{{ t('settings.languageZh') }}</el-radio-button>
          <el-radio-button value="en">{{ t('settings.languageEn') }}</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item :label="t('settings.autoCheckUpdate')">
        <el-switch
          :model-value="settings.settings.updateAutoCheck"
          @update:model-value="(value: boolean) => settings.saveSettings({ updateAutoCheck: value })"
        />
      </el-form-item>
    </el-form>

    <ShortcutSettings />
    <DataDirSettings />
    <RuntimeSettings />
  </div>
</template>

<style scoped lang="scss">
.settings-form {
  max-width: 640px;
}
</style>
