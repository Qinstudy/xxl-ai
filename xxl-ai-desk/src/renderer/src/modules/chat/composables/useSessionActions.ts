/*
* 会话当前项操作：重命名 / 删除（供顶栏等外壳复用，逻辑归属 chat 域）
* @author xuxueli 2026-10-18
*/
import { ElMessage, ElMessageBox } from 'element-plus'
import { useChatStore } from '@renderer/stores/chat'
import { t } from '@renderer/i18n'

export interface SessionActions {
  onChatCommand: (command: string) => void
  renameCurrent: () => Promise<void>
  deleteCurrent: () => Promise<void>
}

export function useSessionActions(): SessionActions {
  const chat = useChatStore()

  /* 重命名当前会话 */
  async function renameCurrent(): Promise<void> {
    const session = chat.currentSession
    if (!session) {
      return
    }
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

  /* 删除当前会话 */
  async function deleteCurrent(): Promise<void> {
    const session = chat.currentSession
    if (!session) {
      return
    }
    try {
      await ElMessageBox.confirm(t('chat.deleteConfirm'), t('chat.deleteSession'), {
        type: 'warning',
        confirmButtonText: t('common.delete'),
        cancelButtonText: t('common.cancel')
      })
      await chat.removeSession(session.id)
      ElMessage.success(t('common.deleted'))
    } catch {
      /* 取消 */
    }
  }

  /* 会话操作下拉命令分发 */
  function onChatCommand(command: string): void {
    if (command === 'rename') {
      void renameCurrent()
    } else if (command === 'delete') {
      void deleteCurrent()
    }
  }

  return { onChatCommand, renameCurrent, deleteCurrent }
}
