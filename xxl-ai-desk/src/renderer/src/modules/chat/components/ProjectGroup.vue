<script setup lang="ts">
/*
 * 会话侧栏 - 单个项目分组：项目行（重命名 / 显示 / 删除）与其下会话列表。
 * @author xuxueli 2026-10-25
 */
import { computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useChatStore } from '@renderer/stores/chat'
import { useSettingsStore } from '@renderer/stores/settings'
import { useProjectStore } from '@renderer/stores/project'
import { t } from '@renderer/i18n'
import type { ProjectDTO, SessionDTO } from '@shared/ipc'

const props = defineProps<{
  /* 项目及其下会话（已按当前排序方式排好） */
  project: ProjectDTO
  sessions: SessionDTO[]
  /* 是否展开 */
  expanded: boolean
  /* 是否为当前选中项目 / 会话 */
  active: boolean
  activeSessionId: string
}>()
const emit = defineEmits<{ toggle: []; select: [sessionId: string] }>()

const chat = useChatStore()
const settings = useSettingsStore()
const projectStore = useProjectStore()

/* 「在文件管理器中显示」文案按运行平台区分 */
const revealLabel = computed(() => {
  if (settings.platform === 'darwin') {
    return t('project.revealMac')
  }
  if (settings.platform === 'win32') {
    return t('project.revealWin')
  }
  return t('project.revealLinux')
})

/* 项目操作下拉：重命名 / 在文件管理器中显示 / 删除 */
function onProjectCommand(command: string): void {
  if (command === 'rename') {
    void onRenameProject()
  } else if (command === 'reveal') {
    void projectStore.revealProject(props.project.id)
  } else if (command === 'delete') {
    void onDeleteProject()
  }
}

/* 重命名项目 */
async function onRenameProject(): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt('', t('project.rename'), {
      inputValue: props.project.name,
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel')
    })
    const name = value?.trim()
    if (name && name !== props.project.name) {
      await projectStore.renameProject(props.project.id, name)
      ElMessage.success(t('project.renamed'))
    }
  } catch {
    /* 取消 */
  }
}

/* 删除项目：级联删除其下会话与消息 */
async function onDeleteProject(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      t('project.deleteConfirm', [props.project.name]),
      t('project.delete'),
      {
        type: 'warning',
        confirmButtonText: t('common.delete'),
        cancelButtonText: t('common.cancel')
      }
    )
    await projectStore.removeProject(props.project.id)
    await chat.reloadSessions()
    ElMessage.success(t('project.deleted'))
  } catch {
    /* 取消 */
  }
}

/* 重命名会话：弹框输入，取消或未改动则保持原值 */
async function onRenameSession(session: SessionDTO): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt('', t('chat.renameSession'), {
      inputValue: session.title,
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel')
    })
    await chat.renameSession(session.id, value || session.title)
  } catch {
    /* 取消 */
  }
}

/* 删除会话：二次确认后移除 */
async function onDeleteSession(id: string): Promise<void> {
  try {
    await ElMessageBox.confirm(t('chat.deleteConfirm'), t('chat.deleteSession'), {
      type: 'warning',
      confirmButtonText: t('common.delete'),
      cancelButtonText: t('common.cancel')
    })
    await chat.removeSession(id)
    ElMessage.success(t('common.deleted'))
  } catch {
    /* 取消 */
  }
}
</script>

<template>
  <div class="project-group">
    <div class="project-item" :class="{ active }" @click="emit('toggle')">
      <el-icon class="project-icon">
        <FolderOpened v-if="expanded" />
        <Folder v-else />
      </el-icon>
      <span class="project-name" :title="project.path">{{ project.name }}</span>
      <span class="project-actions" @click.stop>
        <el-dropdown trigger="click" @command="onProjectCommand">
          <el-icon class="op" :title="t('common.actions')"><MoreFilled /></el-icon>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="rename">
                <el-icon><EditPen /></el-icon>{{ t('project.rename') }}
              </el-dropdown-item>
              <el-dropdown-item command="reveal">
                <el-icon><FolderOpened /></el-icon>{{ revealLabel }}
              </el-dropdown-item>
              <el-dropdown-item command="delete" divided>
                <el-icon><Delete /></el-icon>{{ t('project.delete') }}
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-icon class="op" :title="t('project.rename')" @click="onRenameProject">
          <EditPen />
        </el-icon>
      </span>
    </div>

    <div v-if="expanded" class="project-sessions">
      <div
        v-for="session in sessions"
        :key="session.id"
        class="session-item"
        :class="{ active: session.id === activeSessionId }"
        @click="emit('select', session.id)"
      >
        <span class="session-title">{{ session.title || t('chat.newChat') }}</span>
        <span class="session-actions" @click.stop>
          <el-icon class="op" @click="onRenameSession(session)"><EditPen /></el-icon>
          <el-icon class="op" @click="onDeleteSession(session.id)"><Delete /></el-icon>
        </span>
      </div>
      <div v-if="sessions.length === 0" class="session-empty">
        {{ t('project.noSession') }}
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.project-group {
  margin-bottom: 2px;
}

/* 项目行：图标/文字位置与「新建对话」行对齐（同缩进、同间距） */
.project-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 10px;
  cursor: pointer;
  color: var(--desk-text-secondary);
  font-size: 14px;
  transition: background 0.15s ease;
}

.project-item:hover {
  background: var(--desk-primary-soft);
}

.project-item.active {
  /* 选中态仅用底色与字重区分，文字保持次级灰，避免与未选中形成过强的黑白反差 */
  color: var(--desk-text-secondary);
  font-weight: 600;
}

.project-icon {
  flex-shrink: 0;
  font-size: 15px;
}

.project-name {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.project-actions {
  display: none;
  align-items: center;
  gap: 6px;
}

.project-item:hover .project-actions {
  display: inline-flex;
}

/* 项目下的会话缩进展示 */
.project-sessions {
  padding-left: 16px;
}

.session-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  margin-bottom: 2px;
  border-radius: 10px;
  cursor: pointer;
  color: var(--desk-text-secondary);
  transition: background 0.15s ease;
  font-size: 14px;
}

.session-item:hover {
  background: var(--desk-primary-soft);
}

.session-item.active {
  background: var(--desk-primary-soft);
  /* 选中态文字保持次级灰，仅用底色与轻字重区分，避免过黑 */
  color: var(--desk-text-secondary);
  font-weight: 500;
}

.session-title {
  flex: 1;
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.session-actions {
  display: none;
  gap: 6px;
}

.session-item:hover .session-actions {
  display: inline-flex;
}

.session-empty {
  padding: 6px 12px;
  font-size: 12px;
  color: var(--desk-text-tertiary);
}

.op {
  font-size: 13px;
  color: var(--desk-text-tertiary);
}

.op:hover {
  color: var(--desk-primary);
}
</style>
