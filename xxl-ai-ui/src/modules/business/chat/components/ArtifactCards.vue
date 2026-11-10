<!--
  Agent 交付物产物高亮卡片列表（C端对话页与B端对话管理明细抽屉共用）
  展示本轮生成归档的 .sql / .html / .csv / .md 产物，提供一键实时预览与下载
-->
<template>
  <div v-if="artifacts && artifacts.length > 0" class="artifact-cards-wrap">
    <div class="artifact-cards-header">
      <el-icon class="header-icon"><Files /></el-icon>
      <span class="header-title">{{ t('business.chat.artifactsTitle', [artifacts.length]) }}</span>
    </div>
    <div class="artifact-cards-grid">
      <div
        v-for="item in artifacts"
        :key="item.artifactId"
        class="artifact-card"
        :class="'ext-' + normalizedExt(item.ext)"
        @click="$emit('preview', item)"
      >
        <div class="artifact-card-main">
          <span class="artifact-ext-badge" :class="'badge-' + normalizedExt(item.ext)">
            {{ normalizedExt(item.ext).toUpperCase() }}
          </span>
          <div class="artifact-meta">
            <div class="artifact-name" :title="item.relativePath || item.fileName">{{ item.fileName }}</div>
            <div class="artifact-size">{{ formatFileSize(item.sizeBytes) }}</div>
          </div>
        </div>
        <div class="artifact-actions">
          <el-button link type="primary" size="small" icon="View" @click.stop="$emit('preview', item)">
            {{ t('business.chat.artifactPreview') }}
          </el-button>
          <el-button
            link
            type="primary"
            size="small"
            icon="Download"
            :loading="downloadingId === item.artifactId"
            @click.stop="$emit('download', item)"
          >
            {{ t('business.chat.artifactDownload') }}
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { t } from '@/i18n'
import type { ArtifactItem } from '@/modules/business/session/types'

defineProps<{
  artifacts?: ArtifactItem[]
  downloadingId?: string
}>()

defineEmits<{
  preview: [artifact: ArtifactItem]
  download: [artifact: ArtifactItem]
}>()

function normalizedExt(ext?: string): string {
  return (ext || 'file').toLowerCase()
}

function formatFileSize(bytes?: number): string {
  if (bytes == null || bytes < 0) return '0 B'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`
}
</script>

<style scoped>
.artifact-cards-wrap {
  margin-top: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid #dbeafe;
  background: linear-gradient(135deg, #f8fbff 0%, #f0f7ff 100%);
  white-space: normal;
}

.artifact-cards-header {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  font-size: 12px;
  font-weight: 600;
  color: #1d4ed8;
}

.header-icon {
  font-size: 14px;
}

.artifact-cards-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
  gap: 8px;
}

.artifact-card {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: 6px;
  padding: 8px 10px;
  border-radius: 7px;
  background: #fff;
  border: 1px solid #e2e8f0;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
  cursor: pointer;
  transition: all 0.2s ease;
  min-width: 0;
}

.artifact-card:hover {
  border-color: #60a5fa;
  box-shadow: 0 3px 10px rgba(37, 99, 235, 0.12);
  transform: translateY(-1px);
}

.artifact-card.ext-sql {
  border-left: 3px solid #4f46e5;
}

.artifact-card.ext-html {
  border-left: 3px solid #ea580c;
}

.artifact-card.ext-csv {
  border-left: 3px solid #059669;
}

.artifact-card.ext-md {
  border-left: 3px solid #0284c7;
}

.artifact-card-main {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.artifact-ext-badge {
  flex-shrink: 0;
  min-width: 42px;
  padding: 3px 6px;
  border-radius: 5px;
  font-size: 11px;
  font-weight: 700;
  text-align: center;
  letter-spacing: 0.4px;
  color: #fff;
  background: #64748b;
}

.badge-sql {
  background: #4f46e5;
}

.badge-html {
  background: #ea580c;
}

.badge-csv {
  background: #059669;
}

.badge-md {
  background: #0284c7;
}

.artifact-meta {
  flex: 1;
  min-width: 0;
}

.artifact-name {
  font-size: 13px;
  font-weight: 600;
  color: #1e293b;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.artifact-size {
  font-size: 11px;
  color: #64748b;
  margin-top: 1px;
}

.artifact-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  padding-top: 4px;
  border-top: 1px dashed #e2e8f0;
}
</style>
