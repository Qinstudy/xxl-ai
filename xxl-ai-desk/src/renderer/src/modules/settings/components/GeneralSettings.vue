<script setup lang="ts">
/*
 * 设置 - 常规：外观 / 主题 / 语言 / 自动更新 + 快捷键 / 数据目录 / 运行环境。
 * @author xuxueli 2026-10-25
 */
import { computed } from 'vue'
import { useSettingsStore } from '@renderer/stores/settings'
import { t } from '@renderer/i18n'
import ShortcutSettings from './ShortcutSettings.vue'
import DataDirSettings from './DataDirSettings.vue'
import RuntimeSettings from './RuntimeSettings.vue'
import { THEME_PRESETS, type AppSettings } from '@shared/ipc'

const settings = useSettingsStore()

/* 外观 / 主题切换后立即生效（无需保存） */
const appearance = computed<AppSettings['appearance']>({
  get: () => settings.settings.appearance,
  set: (value) => {
    void settings.saveSettings({ appearance: value })
  }
})
const themePreset = computed<AppSettings['themePreset']>({
  get: () => settings.settings.themePreset,
  set: (value) => {
    void settings.saveSettings({ themePreset: value })
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
      <el-form-item :label="t('settings.appearance')">
        <el-radio-group v-model="appearance" class="soft-radio">
          <el-radio-button value="light">{{ t('settings.appearanceLight') }}</el-radio-button>
          <el-radio-button value="dark">{{ t('settings.appearanceDark') }}</el-radio-button>
          <el-radio-button value="system">{{ t('settings.appearanceSystem') }}</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item :label="t('settings.theme')">
        <div class="theme-field">
          <el-radio-group v-model="themePreset" class="soft-radio">
            <el-radio-button v-for="preset in THEME_PRESETS" :key="preset" :value="preset">
              {{ t(`settings.themePreset.${preset}`) }}
            </el-radio-button>
          </el-radio-group>
          <div class="field-hint">{{ t('settings.themeHint') }}</div>
        </div>
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

/* 主题选项：横向排列，窄窗口自动换行 */
.theme-field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.theme-field :deep(.soft-radio) {
  flex-wrap: wrap;
}

.field-hint {
  font-size: var(--desk-font-xs);
  color: var(--desk-text-tertiary);
  line-height: 1.5;
}
</style>
