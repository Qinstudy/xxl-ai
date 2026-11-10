/**
 * 交付物产物预览与一键下载共用 composable（C 端对话页与 B 端对话管理抽屉共用）
 *
 * 封装在线预览弹窗状态、Blob 下载与 JSON 错误载荷解包提示。
 *
 * @author xxl-ai 2026-11-08
 */
import { ref } from 'vue'
import { saveAs } from 'file-saver'
import { ElMessage } from 'element-plus'
import { t } from '@/i18n'
import type { Response } from '@/types'
import type { ArtifactItem, ArtifactPreviewDTO } from '@/modules/business/session/types'

export interface ArtifactActionFetcher {
  preview: (msgId: number, artifactId: string, sessionId?: number) => Promise<Response<ArtifactPreviewDTO>>
  download: (msgId: number, artifactId: string, sessionId?: number) => Promise<Blob>
}

export function useArtifactActions(fetcher: ArtifactActionFetcher) {
  const previewDialog = ref<{
    visible: boolean
    loading: boolean
    data?: ArtifactPreviewDTO
    msgId?: number
    sessionId?: number
    artifact?: ArtifactItem
  }>({
    visible: false,
    loading: false,
    data: undefined,
    msgId: undefined,
    sessionId: undefined,
    artifact: undefined
  })

  const downloadingId = ref<string>('')

  /**
   * 打开在线预览弹窗并拉取最新预览文本
   */
  async function openPreview(artifact: ArtifactItem, msgId?: number, sessionId?: number) {
    if (!msgId || !artifact?.artifactId) {
      ElMessage.error(t('business.chat.artifactPreviewFail'))
      return
    }
    previewDialog.value = {
      visible: true,
      loading: true,
      data: undefined,
      msgId,
      sessionId,
      artifact
    }
    try {
      const res = await fetcher.preview(msgId, artifact.artifactId, sessionId)
      previewDialog.value.data = res.data
    } catch (e) {
      ElMessage.error(t('business.chat.artifactPreviewFail'))
      previewDialog.value.visible = false
    } finally {
      previewDialog.value.loading = false
    }
  }

  /**
   * 一键下载交付物（识别后端返回的 JSON 错误 Blob 并提示具体原因）
   */
  async function downloadArtifact(artifact: ArtifactItem, msgId?: number, sessionId?: number) {
    if (!msgId || !artifact?.artifactId) {
      ElMessage.error(t('business.chat.artifactDownloadFail'))
      return
    }
    downloadingId.value = artifact.artifactId
    try {
      const blob = await fetcher.download(msgId, artifact.artifactId, sessionId)
      if (!(blob instanceof Blob)) {
        ElMessage.error(t('business.chat.artifactDownloadFail'))
        return
      }
      if (blob.type && blob.type.includes('application/json')) {
        try {
          const text = await blob.text()
          const parsed = JSON.parse(text) as { msg?: string }
          ElMessage.error(parsed.msg || t('business.chat.artifactDownloadFail'))
        } catch {
          ElMessage.error(t('business.chat.artifactDownloadFail'))
        }
        return
      }
      saveAs(blob, artifact.fileName || 'artifact')
    } catch (e) {
      ElMessage.error(t('business.chat.artifactDownloadFail'))
    } finally {
      downloadingId.value = ''
    }
  }

  return {
    previewDialog,
    downloadingId,
    openPreview,
    downloadArtifact
  }
}
