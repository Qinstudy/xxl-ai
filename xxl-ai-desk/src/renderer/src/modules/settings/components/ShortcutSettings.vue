<script setup lang="ts">
/*
 * 设置 - 快捷操作快捷键：点击录制，修改后立即保存，支持恢复默认。
 * @author xuxueli 2026-10-25
 */
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useSettingsStore } from '@renderer/stores/settings'
import { t } from '@renderer/i18n'
import ShortcutInput from '@renderer/components/ShortcutInput.vue'
import { DEFAULT_SHORTCUTS } from '@shared/ipc'
import type { QuickAction, ShortcutMap } from '@shared/ipc'

const settings = useSettingsStore()

/* 快捷操作清单：id 对应全局快捷键动作，label 为 i18n key */
const quickActions: { id: QuickAction; label: string }[] = [
  { id: 'newChat', label: 'chat.newChatAction' },
  { id: 'settings', label: 'settings.title' },
  { id: 'terminal', label: 'chat.terminal' },
  { id: 'files', label: 'panel.files' },
  { id: 'browser', label: 'panel.browser' }
]

/* 当前快捷键（含默认值兜底） */
const shortcuts = ref<ShortcutMap>({})

/* 从设置同步快捷键 */
function syncShortcuts(): void {
  shortcuts.value = { ...DEFAULT_SHORTCUTS, ...settings.settings.shortcuts }
}

watch(() => settings.loaded, syncShortcuts)
onMounted(syncShortcuts)

/* 修改单个快捷键：合并后立即保存（传普通对象，避免 IPC 无法克隆 Vue 响应式代理） */
function onShortcutChange(id: QuickAction, binding: string): void {
  const next: ShortcutMap = { ...shortcuts.value, [id]: binding }
  shortcuts.value = next
  void settings.saveSettings({ shortcuts: { ...next } })
}

/* 恢复默认快捷键并保存 */
function resetShortcuts(): void {
  const next: ShortcutMap = { ...DEFAULT_SHORTCUTS }
  shortcuts.value = next
  void settings.saveSettings({ shortcuts: { ...next } })
  ElMessage.success(t('common.saved'))
}
</script>

<template>
  <div class="shortcuts">
    <div class="shortcuts-title">{{ t('settings.shortcuts') }}</div>
    <div class="shortcuts-tip">{{ t('settings.shortcutsTip') }}</div>
    <div v-for="action in quickActions" :key="action.id" class="shortcut-row">
      <span class="shortcut-label">{{ t(action.label) }}</span>
      <ShortcutInput
        :model-value="shortcuts[action.id]"
        @update:model-value="(value: string) => onShortcutChange(action.id, value)"
      />
    </div>
    <div class="shortcut-actions">
      <el-button @click="resetShortcuts">{{ t('settings.shortcutReset') }}</el-button>
    </div>
  </div>
</template>

<style scoped lang="scss">
/* 快捷键：快捷操作快捷键配置 */
.shortcuts {
  margin-top: 28px;
  padding-top: 20px;
  border-top: 1px solid var(--desk-border);
}

.shortcuts-title {
  font-size: 14px;
  color: var(--el-text-color-regular);
  margin-bottom: 6px;
}

.shortcuts-tip {
  font-size: 12px;
  color: var(--desk-text-tertiary);
  margin-bottom: 12px;
}

.shortcut-row {
  display: flex;
  align-items: center;
  gap: 16px;
  max-width: 640px;
  height: 40px;
}

.shortcut-label {
  flex: 1;
  min-width: 0;
  color: var(--desk-text);
}

.shortcut-actions {
  margin-top: 12px;
}
</style>
