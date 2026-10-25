<script setup lang="ts">
/*
 * SKILL 详情抽屉：展示技能元信息、SKILL.md 正文与附属文件，并支持启停与定位目录。
 * @author xuxueli 2026-10-25
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useSkillStore } from '@renderer/stores/skill'
import { renderMarkdown } from '@renderer/composables/useMarkdown'
import { ipcErrorMessage } from '@renderer/utils/ipcError'
import { t } from '@renderer/i18n'
import type { SkillDetail } from '@shared/ipc'

const visible = defineModel<boolean>('visible', { default: false })
const props = defineProps<{ skillId: string }>()

const skill = useSkillStore()
const detail = ref<SkillDetail | null>(null)
const loading = ref(false)

/* 正文渲染（已消毒） */
const html = computed(() => renderMarkdown(detail.value?.content ?? ''))

/* 文件体积展示 */
function formatSize(size: number): string {
  if (size >= 1024 * 1024) {
    return `${(size / 1024 / 1024).toFixed(1)} MB`
  }
  if (size >= 1024) {
    return `${(size / 1024).toFixed(1)} KB`
  }
  return `${size} B`
}

/* 读取详情：抽屉打开或切换技能时刷新 */
async function loadDetail(): Promise<void> {
  if (!props.skillId) {
    detail.value = null
    return
  }
  loading.value = true
  try {
    detail.value = await skill.detail(props.skillId)
  } catch (error) {
    detail.value = null
    ElMessage.error(ipcErrorMessage(error))
  } finally {
    loading.value = false
  }
}

watch(
  () => [visible.value, props.skillId],
  ([opened]) => {
    if (opened) {
      void loadDetail()
    }
  }
)

/* 启停：同步更新抽屉与列表 */
async function onToggle(value: boolean): Promise<void> {
  if (!detail.value) {
    return
  }
  try {
    const saved = await skill.setEnabled(detail.value.id, value)
    detail.value = { ...detail.value, enabled: saved.enabled }
  } catch (error) {
    ElMessage.error(ipcErrorMessage(error))
  }
}

/* 在文件管理器中显示技能目录 */
async function onReveal(): Promise<void> {
  if (!detail.value) {
    return
  }
  try {
    await skill.reveal(detail.value.id)
  } catch (error) {
    ElMessage.error(ipcErrorMessage(error))
  }
}
</script>

<template>
  <el-drawer
    v-model="visible"
    :title="detail?.name || t('plugin.skillCenter.detailTitle')"
    size="600px"
    append-to-body
  >
    <div v-loading="loading" class="detail-body">
      <template v-if="detail">
        <div class="meta-card">
          <div class="meta-row">
            <span class="meta-label">{{ t('plugin.skillCenter.source') }}</span>
            <el-tag size="small" :type="detail.source === 'local' ? 'info' : 'success'">
              {{
                detail.source === 'local'
                  ? t('plugin.skillCenter.sourceLocal')
                  : t('plugin.skillCenter.sourceRemote')
              }}
            </el-tag>
            <span v-if="detail.version" class="meta-version">v{{ detail.version }}</span>
            <el-switch
              class="meta-switch"
              :model-value="detail.enabled"
              @update:model-value="(value: boolean) => onToggle(value)"
            />
          </div>
          <div class="meta-row">
            <span class="meta-label">{{ t('plugin.skillCenter.detailFolder') }}</span>
            <span class="meta-value mono">{{ detail.id }}</span>
            <el-button text type="primary" class="meta-action" @click="onReveal">
              {{ t('plugin.skillCenter.folder') }}
            </el-button>
          </div>
          <div v-if="detail.sourceId" class="meta-row">
            <span class="meta-label">{{ t('plugin.skillCenter.sourceId') }}</span>
            <span class="meta-value mono">{{ detail.sourceId }}</span>
          </div>
          <div class="meta-row">
            <span class="meta-label">{{ t('plugin.skillCenter.description') }}</span>
            <span class="meta-value">
              {{ detail.description || t('plugin.skillCenter.noDescription') }}
            </span>
          </div>
        </div>

        <div class="section-title">{{ t('plugin.skillCenter.detailContent') }}</div>
        <div class="markdown-body" v-html="html" />

        <div class="section-title">{{ t('plugin.skillCenter.detailFiles') }}</div>
        <div v-if="detail.files.length === 0" class="empty-files">
          {{ t('plugin.skillCenter.detailNoFiles') }}
        </div>
        <div v-else class="file-list">
          <div v-for="file in detail.files" :key="file.path" class="file-item">
            <span class="file-path mono">{{ file.path }}</span>
            <span class="file-size">{{ formatSize(file.size) }}</span>
          </div>
        </div>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped lang="scss">
.detail-body {
  min-height: 200px;
  font-size: 14px;
}

.meta-card {
  padding: 14px 16px;
  border-radius: 8px;
  background: var(--desk-panel-bg);
}

.meta-row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 26px;
}

.meta-label {
  flex-shrink: 0;
  width: 76px;
  font-size: 12px;
  color: var(--desk-text-tertiary);
}

.meta-value {
  min-width: 0;
  color: var(--desk-text);
  word-break: break-word;
}

.meta-version {
  font-size: 12px;
  color: var(--desk-text-tertiary);
}

.meta-switch {
  margin-left: auto;
}

.meta-action {
  margin-left: auto;
  padding: 0;
}

.mono {
  font-family: var(--el-font-family-mono, monospace);
  font-size: 12px;
}

.section-title {
  margin: 20px 0 10px;
  font-size: 13px;
  font-weight: 600;
  color: var(--desk-text);
}

.markdown-body {
  padding: 4px 2px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--desk-text);
  word-break: break-word;
}

.markdown-body :deep(pre) {
  padding: 10px 12px;
  border-radius: 6px;
  background: var(--desk-panel-bg);
  overflow-x: auto;
}

.markdown-body :deep(code) {
  font-family: var(--el-font-family-mono, monospace);
}

.empty-files {
  font-size: 12px;
  color: var(--desk-text-tertiary);
}

.file-list {
  border-top: 1px solid var(--desk-border);
}

.file-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 2px;
  border-bottom: 1px solid var(--desk-border);
}

.file-path {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--desk-text-secondary);
}

.file-size {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--desk-text-tertiary);
}
</style>
