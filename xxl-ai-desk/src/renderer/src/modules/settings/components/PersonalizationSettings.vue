<script setup lang="ts">
/*
 * 设置 - 个性化：名称 / Slogan / 自定义指令，支持恢复默认。
 * @author xuxueli 2026-10-25
 */
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useSettingsStore } from '@renderer/stores/settings'
import { DEFAULT_SYSTEM_PROMPT } from '@shared/ipc'
import { t } from '@renderer/i18n'

const settings = useSettingsStore()

/* 自定义指令默认值（与主进程共用 shared/defaults） */

/* 表单：名称 / Slogan / 自定义指令 */
const appName = ref('')
const slogan = ref('')
const systemPrompt = ref('')

/* 从设置同步个性化表单（空值回退内置文案） */
function syncPersonalization(): void {
  appName.value = settings.settings.appName || t('app.name')
  slogan.value = settings.settings.slogan || t('chat.emptyTitle')
  systemPrompt.value = settings.settings.systemPrompt
}

watch(() => settings.loaded, syncPersonalization)
onMounted(syncPersonalization)

/* 保存个性化设置 */
async function savePersonalization(): Promise<void> {
  await settings.saveSettings({
    appName: appName.value,
    slogan: slogan.value,
    systemPrompt: systemPrompt.value
  })
  ElMessage.success(t('common.saved'))
}

/* 恢复默认：名称/Slogan 回内置文案，自定义指令回内置默认 */
function restorePersonalization(): void {
  appName.value = t('app.name')
  slogan.value = t('chat.emptyTitle')
  systemPrompt.value = DEFAULT_SYSTEM_PROMPT
}
</script>

<template>
  <el-form label-position="top" class="settings-form">
    <el-form-item :label="t('settings.name')">
      <el-input v-model="appName" clearable :placeholder="t('settings.namePlaceholder')" />
    </el-form-item>
    <el-form-item :label="t('settings.slogan')">
      <el-input v-model="slogan" clearable :placeholder="t('settings.sloganPlaceholder')" />
    </el-form-item>
    <el-form-item :label="t('settings.customInstruction')">
      <el-input
        v-model="systemPrompt"
        type="textarea"
        :rows="5"
        :placeholder="t('settings.customInstructionPlaceholder')"
      />
    </el-form-item>
    <div class="form-actions">
      <el-button type="primary" @click="savePersonalization">{{ t('common.save') }}</el-button>
      <el-button @click="restorePersonalization">{{ t('settings.restoreDefault') }}</el-button>
    </div>
  </el-form>
</template>

<style scoped lang="scss">
.settings-form {
  max-width: 640px;
}

/* 表单底部操作按钮（个性化：保存 / 恢复默认） */
.form-actions {
  display: flex;
  gap: 8px;
}
</style>
