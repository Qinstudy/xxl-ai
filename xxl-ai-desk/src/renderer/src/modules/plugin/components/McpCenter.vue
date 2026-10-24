<script setup lang="ts">
/*
 * MCP 插件中心：顶部两个 TAB（MCP 管理 / 发现 MCP）+ 各面板与抽屉编排。
 * @author xuxueli 2026-10-24
 */
import { ref } from 'vue'
import McpManagePanel from './McpManagePanel.vue'
import McpDiscoverPanel from './McpDiscoverPanel.vue'
import McpEditDrawer from './McpEditDrawer.vue'
import McpToolsDrawer from './McpToolsDrawer.vue'
import type { McpDiscoverDetail, McpDTO } from '@shared/ipc'
import { useSettingsStore } from '@renderer/stores/settings'
import { pickSnippet } from '@renderer/utils/mcpSnippet'
import { t } from '@renderer/i18n'

/* 平台（片段选择时区分 cmd / npx） */
const settings = useSettingsStore()

/* 当前 TAB：mange 管理 / discover 发现 */
const activeTab = ref<'manage' | 'discover'>('manage')

/* 编辑抽屉状态与草稿 */
const editorVisible = ref(false)
const editorDraft = ref<Partial<McpDTO> | null>(null)
const editorJson = ref('')

/* 工具抽屉状态 */
const toolsVisible = ref(false)
const toolsServer = ref<McpDTO | null>(null)

/* 新建 MCP */
function onCreate(): void {
  editorDraft.value = null
  editorJson.value = ''
  editorVisible.value = true
}

/* 编辑 MCP */
function onEdit(row: McpDTO): void {
  editorDraft.value = { ...row }
  editorJson.value = ''
  editorVisible.value = true
}

/* 查看工具 */
function onTools(row: McpDTO): void {
  toolsServer.value = row
  toolsVisible.value = true
}

/* 社区条目导入：预填名称/备注，并带入选中的配置片段（不可解析时自动挑最可用的一条） */
function onImport(detail: McpDiscoverDetail, snippet: string): void {
  const usable = parseUsable(snippet, detail)
  editorDraft.value = {
    name: detail.name,
    remark: detail.description,
    enabled: false
  }
  editorJson.value = usable
  editorVisible.value = true
}

/* 片段可用性：优先传入片段，其次按平台挑选最佳片段 */
function parseUsable(snippet: string, detail: McpDiscoverDetail): string {
  if (snippet) {
    return snippet
  }
  return pickSnippet(detail.snippets, settings.platform)
}

/* 保存成功：切回管理 TAB 查看结果 */
function onSaved(): void {
  activeTab.value = 'manage'
}
</script>

<template>
  <div class="mcp-center">
    <div class="center-header">
      <div class="tab-bar">
        <button
          class="tab-item"
          :class="{ active: activeTab === 'manage' }"
          @click="activeTab = 'manage'"
        >
          {{ t('plugin.mcpCenter.tabManage') }}
        </button>
        <button
          class="tab-item"
          :class="{ active: activeTab === 'discover' }"
          @click="activeTab = 'discover'"
        >
          {{ t('plugin.mcpCenter.tabDiscover') }}
        </button>
      </div>
    </div>

    <div class="center-body">
      <McpManagePanel
        v-show="activeTab === 'manage'"
        @create="onCreate"
        @edit="onEdit"
        @tools="onTools"
        @discover="activeTab = 'discover'"
      />
      <McpDiscoverPanel v-show="activeTab === 'discover'" @add="onImport" />
    </div>

    <McpEditDrawer
      v-model:visible="editorVisible"
      :draft="editorDraft"
      :initial-json="editorJson"
      @saved="onSaved"
    />
    <McpToolsDrawer v-model:visible="toolsVisible" :server="toolsServer" />
  </div>
</template>

<style scoped lang="scss">
.mcp-center {
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
