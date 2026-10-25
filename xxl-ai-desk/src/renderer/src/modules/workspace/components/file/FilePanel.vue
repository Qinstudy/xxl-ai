<script setup lang="ts">
/*
 * 右侧「文件」面板：左为项目目录文件树，右为选中文件内容（可编辑代码 / 预览）。
 * 顶部提供保存、预览切换、打开（左半默认应用打开 / 右半打开方式下拉）与「打开所在文件夹」。
 * 文件树状态由 useProjectFileTree 维护，查看器由 FileViewer 渲染。
 * @author xuxueli 2026-10-18
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@renderer/api'
import { t } from '@renderer/i18n'
import { useWorkspaceStore } from '@renderer/stores/workspace'
import { normalizePath, parentDir } from '../../utils/path'
import FileToolbar from './FileToolbar.vue'
import FileViewer from './FileViewer.vue'
import FileTreePanel from './FileTreePanel.vue'
import type { FileContent, FsEntry, OpenWithApp } from '@shared/ipc'

const props = defineProps<{ sessionId: string; root?: string; projectName?: string }>()

/* 会话工作区 store：选中文件与预览态按会话隔离 */
const workspace = useWorkspaceStore()
const fileState = computed(() => workspace.sliceOf(props.sessionId).tool.files)

/* 当前选中节点、文件内容与加载/错误态、是否预览 */
const selected = ref<FsEntry | null>(null)
const file = ref<FileContent | null>(null)
const fileLoading = ref(false)
const fileError = ref('')
const preview = ref(fileState.value.preview)

/* 预览态回写会话工作区（切换会话后保留） */
watch(preview, (value) => {
  fileState.value.preview = value
})

/* 编辑态：original 为磁盘基线，draft 为编辑内容；二者不一致即未保存 */
const original = ref('')
const draft = ref('')
const saving = ref(false)

/* 本机可用「打开方式」应用（macOS 探测常见编辑器） */
const openApps = ref<OpenWithApp[]>([])

/* 文件变更监听解绑函数 */
let offFsChange: (() => void) | null = null

/* 挂载：监听当前文件所在目录变更，并探测本机「打开方式」应用 */
onMounted(async () => {
  offFsChange = api.fs.onChange((dir) => {
    if (selected.value && parentDir(selected.value.path) === normalizePath(dir)) {
      void refreshSelected()
    }
  })
  try {
    openApps.value = await api.shell.openWithApps()
  } catch {
    openApps.value = []
  }
  /* 恢复会话上次选中的文件（切换会话后保留文件视图） */
  const path = fileState.value.selectedPath
  if (path && props.root) {
    const entry: FsEntry = { name: path.split(/[\\/]/).pop() || path, path, isDir: false }
    await select(entry)
  }
})

/* 卸载：取消文件变更监听 */
onBeforeUnmount(() => {
  offFsChange?.()
  offFsChange = null
})

/* 扩展名小写（含点） */
function extOf(name: string): string {
  const index = name.lastIndexOf('.')
  return index >= 0 ? name.slice(index).toLowerCase() : ''
}

/* 可预览图片扩展名 */
const IMAGE_EXTS = ['.png', '.jpg', '.jpeg', '.gif', '.webp', '.bmp', '.svg']

/* 是否为可预览的图片文件 */
function isImageName(name: string): boolean {
  return IMAGE_EXTS.includes(extOf(name))
}

/* 当前文件的图片 / Markdown 判定、是否可预览、预览是否生效 */
const isImage = computed(() => Boolean(file.value) && isImageName(file.value?.name ?? ''))
const isMarkdown = computed(
  () => Boolean(file.value) && ['.md', '.markdown'].includes(extOf(file.value?.name ?? ''))
)
const canPreview = computed(() => isImage.value || isMarkdown.value)
const showPreview = computed(() => preview.value && canPreview.value)

/* 可编辑：文本文件且未被截断 */
const editable = computed(() => Boolean(file.value) && !file.value?.binary && !file.value?.truncated)
/* 有未保存修改 */
const dirty = computed(() => editable.value && draft.value !== original.value)

/* 选中的文件在磁盘上变化（或被删除）时回读；编辑中（dirty）不覆盖 */
async function refreshSelected(): Promise<void> {
  const root = props.root
  const entry = selected.value
  if (!root || !entry || dirty.value) {
    return
  }
  try {
    const loaded = await api.fs.read(root, entry.path)
    file.value = loaded
    original.value = loaded.content
    draft.value = loaded.content
  } catch {
    /* 文件已被删除：清空内容 */
    selected.value = null
    fileState.value.selectedPath = ''
    file.value = null
    fileError.value = ''
    original.value = ''
    draft.value = ''
  }
}

/* 选中文件并加载内容（有未保存修改时先确认） */
async function select(entry: FsEntry): Promise<void> {
  const root = props.root
  if (!root) {
    return
  }
  if (dirty.value) {
    try {
      await ElMessageBox.confirm(t('panel.unsavedConfirm'), t('panel.unsavedTitle'), {
        type: 'warning',
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel')
      })
    } catch {
      /* 取消切换 */
      return
    }
  }
  selected.value = entry
  fileState.value.selectedPath = entry.path
  fileLoading.value = true
  fileError.value = ''
  preview.value = false
  original.value = ''
  draft.value = ''
  try {
    const loaded = await api.fs.read(root, entry.path)
    file.value = loaded
    original.value = loaded.content
    draft.value = loaded.content
    /* 图片直接进入预览，避免展示无意义的二进制代码 */
    preview.value = isImageName(loaded.name) && Boolean(loaded.dataUrl)
  } catch (error) {
    file.value = null
    fileError.value = (error as Error).message || t('panel.loadFail')
  } finally {
    fileLoading.value = false
  }
}

/* 保存编辑内容到磁盘 */
async function save(): Promise<void> {
  const root = props.root
  if (!root || !file.value || !editable.value || saving.value || !dirty.value) {
    return
  }
  saving.value = true
  try {
    await api.fs.write(root, file.value.path, draft.value)
    file.value = {
      ...file.value,
      content: draft.value,
      size: new Blob([draft.value]).size
    }
    original.value = draft.value
    ElMessage.success(t('common.saved'))
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    saving.value = false
  }
}

/* 根目录变化时清空当前文件选择（文件树由 composable 自行重置） */
watch(
  () => props.root,
  () => {
    selected.value = null
    fileState.value.selectedPath = ''
    file.value = null
    fileError.value = ''
    preview.value = false
    original.value = ''
    draft.value = ''
  }
)

/* 面包屑：项目名 > 相对根目录的各级路径 */
const breadcrumb = computed(() => {
  const parts: string[] = []
  if (props.projectName) {
    parts.push(props.projectName)
  }
  if (props.root && file.value) {
    const root = props.root.replace(/\\/g, '/').replace(/\/$/, '')
    const full = normalizePath(file.value.path)
    const rel = full.startsWith(root) ? full.slice(root.length + 1) : full
    parts.push(...rel.split('/').filter(Boolean))
  }
  return parts
})

/* 打开所在的文件夹：优先文件所在目录，未选中文件时打开项目根目录 */
function revealFolder(): void {
  const target = file.value?.path ?? props.root
  if (target) {
    void api.shell.reveal(target)
  }
}

/* 复制文件绝对路径 */
async function copyPath(): Promise<void> {
  if (!file.value) {
    return
  }
  await navigator.clipboard.writeText(file.value.path)
  ElMessage.success(t('panel.copied'))
}

/* 直接以系统默认应用打开当前文件（「打开」按钮左半区） */
function openDefault(): void {
  if (!file.value) {
    return
  }
  void api.shell.openPath(file.value.path)
}

/* 打开方式下拉分发 */
function onOpenCommand(command: string): void {
  if (!file.value) {
    return
  }
  if (command === 'default') {
    void api.shell.openPath(file.value.path)
  } else if (command === 'folder') {
    revealFolder()
  } else if (command === 'copy') {
    void copyPath()
  } else if (command.startsWith('app:')) {
    void api.shell.openWith(command.slice(4), file.value.path)
  }
}
</script>

<template>
  <div class="file-panel">
    <FileToolbar
      :breadcrumb="breadcrumb"
      :editable="editable"
      :dirty="dirty"
      :saving="saving"
      :can-preview="canPreview"
      :show-preview="showPreview"
      :has-file="Boolean(file)"
      :has-root="Boolean(root)"
      :open-apps="openApps"
      @save="save"
      @toggle-preview="preview = !preview"
      @reveal="revealFolder"
      @open-default="openDefault"
      @open-command="onOpenCommand"
    />

    <div class="file-main">
      <!-- 左：项目目录树 -->
      <FileTreePanel
        :session-id="props.sessionId"
        :root="root"
        :selected-path="selected?.path ?? ''"
        @select="select"
      />

      <!-- 右：文件内容 -->
      <FileViewer
        v-model:draft="draft"
        :root="root"
        :file="file"
        :loading="fileLoading"
        :error="fileError"
        :editable="editable"
        :show-preview="showPreview"
        :is-image="isImage"
        :is-markdown="isMarkdown"
        :saving="saving"
        @save="save"
      />
    </div>
  </div>
</template>

<style scoped lang="scss">
.file-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

/* 主体：左文件树 + 右内容 */
.file-main {
  flex: 1;
  min-height: 0;
  display: flex;
}
</style>
