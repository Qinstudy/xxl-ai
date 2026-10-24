<script setup lang="ts">
/*
* 首页子菜单面板：新建对话 / 项目分组（项目下挂会话）/ 搜索命令面板
* @author xuxueli 2026-10-18
*/
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useChatStore } from '@renderer/stores/chat'
import { useSettingsStore } from '@renderer/stores/settings'
import { useProjectStore } from '@renderer/stores/project'
import { t } from '@renderer/i18n'
import CommandPalette from './CommandPalette.vue'
import ProjectGroup from './ProjectGroup.vue'
import type { SessionDTO } from '@shared/ipc'

const router = useRouter()
const chat = useChatStore()
const settings = useSettingsStore()
const project = useProjectStore()

/* 项目展开状态由 project store 统一维护（切换板块不会丢失） */

/* 搜索命令面板显隐 */
const searchVisible = ref(false)

/* 排序方式：名称 / 更新时间（默认按名称排序） */
const sortBy = ref<'time' | 'name'>('name')

/* 项目 → 其下会话 */
function sessionsOf(projectId: string): SessionDTO[] {
  return chat.sessions.filter((item) => item.projectId === projectId)
}

/* 按当前排序方式排序的会话 */
function sortedSessionsOf(projectId: string): SessionDTO[] {
  const list = sessionsOf(projectId)
  if (sortBy.value === 'name') {
    return [...list].sort((a, b) => (a.title || '').localeCompare(b.title || '', 'zh'))
  }
  return [...list].sort((a, b) => b.updateTime.localeCompare(a.updateTime))
}

/* 按当前排序方式排序的项目 */
const sortedProjects = computed(() => {
  const list = [...project.projects]
  if (sortBy.value === 'name') {
    return list.sort((a, b) => a.name.localeCompare(b.name, 'zh'))
  }
  return list.sort((a, b) => b.updateTime.localeCompare(a.updateTime))
})

/* 项目分组列表（一级项目 + 二级会话） */
const projectGroups = computed(() =>
  sortedProjects.value.map((item) => ({ project: item, sessions: sortedSessionsOf(item.id) }))
)

/* 切换排序方式 */
function onSortCommand(command: string): void {
  if (command === 'name' || command === 'time') {
    sortBy.value = command
  }
}

/* 展开状态（由 project store 维护，组件重挂载后仍保留） */
function isExpanded(projectId: string): boolean {
  return project.isExpanded(projectId)
}

/* 打开搜索面板（居中弹框） */
function openSearch(): void {
  searchVisible.value = true
}

/* 点击项目：选中并折叠/展开 */
function toggleProject(projectId: string): void {
  project.selectProject(projectId)
  project.toggleExpanded(projectId)
}

/* 当前项目变化时自动展开（点击会话/新建项目同步选中） */
watch(
  () => project.currentId,
  (id) => {
    if (id) {
      project.setExpanded(id, true)
    }
  }
)

/* 新建项目：选择本地目录并自动生成项目（store 内自动选中并展开） */
async function onCreateProject(): Promise<void> {
  try {
    await project.createProject()
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

/* 新建对话并回到对话页 */
function onNew(): void {
  chat.startNewChat()
  router.push('/')
}

/* 选中会话并回到对话页 */
async function onSelect(id: string): Promise<void> {
  await chat.selectSession(id)
  router.push('/')
}

</script>

<template>
  <div class="sidebar-head">
    <div class="brand">
      <div class="brand-text">
        <div class="brand-name">{{ settings.settings.appName || t('app.name') }}</div>
      </div>
      <!-- 搜索入口：位于标题右侧，打开命令面板 -->
      <el-icon class="brand-search" :title="t('common.search')" @click="openSearch">
        <Search />
      </el-icon>
    </div>
    <div class="new-btn" @click="onNew">
      <el-icon class="new-icon"><Edit /></el-icon>
      <span class="new-btn-label">{{ t('chat.newChatAction') }}</span>
    </div>
  </div>

  <!-- 「项目:」标题：右侧 排序（...）与 + 新建项目，鼠标悬浮展示 -->
  <div class="project-head">
    <span class="project-label">{{ t('project.label') }}:</span>
    <span class="project-head-actions">
      <el-dropdown trigger="click" popper-class="sort-dropdown" @command="onSortCommand">
        <el-icon class="project-icon-btn" :title="t('common.actions')"><MoreFilled /></el-icon>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item disabled class="sort-group">
              {{ t('project.sortBy') }}
            </el-dropdown-item>
            <el-dropdown-item command="name">
              {{ t('project.sortName') }}
              <el-icon v-if="sortBy === 'name'" class="sort-check"><Check /></el-icon>
            </el-dropdown-item>
            <el-dropdown-item command="time">
              {{ t('project.sortTime') }}
              <el-icon v-if="sortBy === 'time'" class="sort-check"><Check /></el-icon>
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <el-icon class="project-icon-btn" :title="t('project.new')" @click="onCreateProject">
        <Plus />
      </el-icon>
    </span>
  </div>

  <el-scrollbar class="sidebar-list">
    <ProjectGroup
      v-for="group in projectGroups"
      :key="group.project.id"
      :project="group.project"
      :sessions="group.sessions"
      :expanded="isExpanded(group.project.id)"
      :active="group.project.id === project.currentId"
      :active-session-id="chat.currentId"
      @toggle="toggleProject(group.project.id)"
      @select="onSelect"
    />
    <el-empty v-if="projectGroups.length === 0" :description="t('project.empty')" :image-size="60" />
  </el-scrollbar>

  <!-- 搜索命令面板：项目/会话关键字搜索 + 快捷操作入口 -->
  <CommandPalette v-model:visible="searchVisible" @select="onSelect" />
</template>

<style scoped lang="scss">
.sidebar-head {
  padding: 10px 16px 12px;
}

.brand {
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 10px;
  min-width: 0;
  margin-bottom: 16px;
}

.brand-text {
  flex: 1;
  min-width: 0;
}

.brand-name {
  font-size: 18px;
  font-weight: 600;
  line-height: 32px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 标题右侧搜索入口 */
.brand-search {
  flex-shrink: 0;
  font-size: 16px;
  color: var(--desk-text-secondary);
  cursor: pointer;
  -webkit-app-region: no-drag;
  transition: color 0.15s ease;
}

.brand-search:hover {
  color: var(--desk-primary);
}

/* 新建对话：与会话条目同款列表行，文字色/字重对齐选中项目名（近黑 + 加粗） */
.new-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px 6px 0;
  border-radius: 10px;
  cursor: pointer;
  color: var(--desk-text);
  font-size: 14px;
  font-weight: 600;
  transition: background 0.15s ease;
  -webkit-app-region: no-drag;
}

.new-btn:hover {
  background: var(--desk-primary-soft);
  color: var(--desk-text);
}

.new-icon {
  flex-shrink: 0;
}

/* 「项目:」分组标题：右侧排序/新建入口 */
.project-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px 6px;
}

.project-label {
  font-size: 14px;
  font-weight: 600;
  color: var(--desk-text-tertiary);
}

/* 排序/新建入口：默认隐藏，悬浮「项目:」标题行时展示 */
.project-head-actions {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  visibility: hidden;
}

.project-head:hover .project-head-actions {
  visibility: visible;
}

.project-icon-btn {
  font-size: 14px;
  color: var(--desk-text-secondary);
  cursor: pointer;
  transition: color 0.15s ease;
}

.project-icon-btn:hover {
  color: var(--desk-primary);
}

</style>
