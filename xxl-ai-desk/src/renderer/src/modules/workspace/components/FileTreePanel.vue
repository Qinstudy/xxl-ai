<script setup lang="ts">
/*
 * 项目文件树：目录过滤、懒加载展开与文件选中。
 * @author xuxueli 2026-10-25
 */
import { useProjectFileTree } from '../composables/useProjectFileTree'
import FileTreeNode from './FileTreeNode.vue'
import { t } from '@renderer/i18n'
import type { FsEntry } from '@shared/ipc'

const props = defineProps<{ root?: string; selectedPath: string }>()
const emit = defineEmits<{ select: [entry: FsEntry] }>()

const { childrenMap, expandedMap, loadingMap, keyword, rootChildren, visibleRoot, toggle } =
  useProjectFileTree({ root: () => props.root })
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
/* 右侧文件树 */
.file-tree {
  flex-shrink: 0;
  width: 240px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-left: 1px solid var(--desk-border);
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
