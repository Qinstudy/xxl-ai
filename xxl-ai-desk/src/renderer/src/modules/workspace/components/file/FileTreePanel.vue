<script setup lang="ts">
/*
 * 项目文件树：目录过滤、懒加载展开与文件选中。
 * @author xuxueli 2026-10-25
 */
import { computed } from 'vue'
import { useProjectFileTree } from '../../composables/useProjectFileTree'
import { useWorkspaceStore } from '@renderer/stores/workspace'
import FileTreeNode from './FileTreeNode.vue'
import { t } from '@renderer/i18n'
import type { FsEntry } from '@shared/ipc'

const props = defineProps<{ sessionId: string; root?: string; selectedPath: string }>()
const emit = defineEmits<{
  select: [entry: FsEntry]
  /* 右键文件：位置交由文件面板定位菜单 */
  'context-menu': [entry: FsEntry, position: { x: number; y: number }]
}>()

/* 会话工作区 store：关键字与目录展开态按会话隔离 */
const workspace = useWorkspaceStore()
const fileState = computed(() => workspace.sliceOf(props.sessionId).tool.files)
const keyword = computed({
  get: () => fileState.value.keyword,
  set: (value: string) => {
    fileState.value.keyword = value
  }
})
const expanded = computed({
  get: () => fileState.value.expanded,
  set: (value: Record<string, boolean>) => {
    fileState.value.expanded = value
  }
})

const { childrenMap, expandedMap, loadingMap, rootChildren, visibleRoot, toggle } =
  useProjectFileTree({ root: () => props.root, keyword, expanded })

/* 右键事件上抛给文件面板（统一处理选中与菜单动作） */
function onContextMenu(entry: FsEntry, position: { x: number; y: number }): void {
  emit('context-menu', entry, position)
}
</script>

<template>
  <div class="file-tree">
    <div class="tree-filter">
      <el-input v-model="keyword" size="small" clearable :placeholder="t('panel.filterPlaceholder')">
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
    </div>
    <div class="tree-body">
      <div v-if="!root" class="tree-empty">{{ t('panel.needProject') }}</div>
      <template v-else>
        <FileTreeNode
          v-for="entry in visibleRoot"
          :key="entry.path"
          :entry="entry"
          :depth="0"
          :children-map="childrenMap"
          :expanded-map="expandedMap"
          :loading-map="loadingMap"
          :selected-path="selectedPath"
          :keyword="keyword"
          @select="emit('select', $event)"
          @toggle="toggle"
          @context-menu="onContextMenu"
        />
        <div v-if="childrenMap[root] && rootChildren.length === 0" class="tree-empty">
          {{ t('panel.emptyTree') }}
        </div>
        <div v-else-if="childrenMap[root] && visibleRoot.length === 0" class="tree-empty">
          {{ t('panel.emptyTree') }}
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped lang="scss">
/* 左侧文件树 */
.file-tree {
  flex-shrink: 0;
  width: 240px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-right: 1px solid var(--desk-border);
  background: var(--desk-sidebar);
}

.tree-filter {
  flex-shrink: 0;
  padding: 8px 10px;
  border-bottom: 1px solid var(--desk-border);
}

.tree-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 6px 6px 12px;
}

.tree-empty {
  padding: 16px 10px;
  font-size: 12px;
  color: var(--desk-text-tertiary);
  text-align: center;
}
</style>
