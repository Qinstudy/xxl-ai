<!--
  KnowledgeDoc（知识文档）
  知识库文档管理：粘贴文本 / 上传 txt·md / 向量化 / 向量检索 / 删除
-->
<template>
  <div class="app-container">
    <div class="content-inner">
      <!-- 页头：知识库信息 + 返回 -->
      <el-row class="mb8" align="middle">
        <el-col :span="12">
          <el-space>
            <el-button icon="Back" @click="goBack">{{ t('business.knowledge.docBack') }}</el-button>
            <span class="doc-header-title">{{ t('business.knowledge.docManage') }}：{{ baseName }}</span>
          </el-space>
        </el-col>
      </el-row>

      <!-- 搜索栏 -->
      <el-form :model="queryParams" ref="queryRef" :inline="true" v-show="table.showSearch">
        <el-form-item :label="t('business.knowledge.docName')" prop="name">
          <el-input
            v-model="queryParams.name"
            :placeholder="t('common.inputPlaceholder', [t('business.knowledge.docName')])"
            clearable
            style="width: 220px"
            @keyup.enter="handleQuery"
          />
        </el-form-item>
        <el-form-item :label="t('common.status')" prop="status">
          <el-select v-model="queryParams.status" :placeholder="t('common.selectPlaceholder')" clearable style="width: 140px">
            <el-option :label="t('common.all')" :value="-1" />
            <el-option v-for="item in docStatusOptions" :key="item.code" :label="item.title" :value="item.code" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="Search" @click="handleQuery">{{ t('common.search') }}</el-button>
          <el-button icon="Refresh" @click="resetQuery">{{ t('common.reset') }}</el-button>
        </el-form-item>
      </el-form>

      <!-- 操作按钮 -->
      <el-row :gutter="10" class="mb8">
        <el-col :span="1.5">
          <el-button type="primary" plain icon="Plus" @click="handleAdd" v-hasPermi="['knowledge:doc']">{{ t('common.add') }}</el-button>
        </el-col>
        <el-col :span="1.5">
          <el-button type="warning" plain icon="ChatDotSquare" @click="handleAddQa" v-hasPermi="['knowledge:doc']">
            {{ t('business.knowledge.addQa') }}
          </el-button>
        </el-col>
        <el-col :span="1.5">
          <el-upload
            :show-file-list="false"
            accept=".txt,.md"
            :before-upload="handleBeforeUpload"
            :http-request="handleUpload"
            :disabled="uploading"
          >
            <el-button type="primary" plain icon="Upload" :loading="uploading" v-hasPermi="['knowledge:doc']">{{
              t('business.knowledge.docUpload')
            }}</el-button>
          </el-upload>
        </el-col>
        <el-col :span="1.5">
          <el-button type="success" plain icon="Edit" :disabled="table.single" @click="handleUpdate" v-hasPermi="['knowledge:doc']">
            {{ t('common.modify') }}
          </el-button>
        </el-col>
        <el-col :span="1.5">
          <el-button type="danger" plain icon="Delete" :disabled="table.multiple" @click="handleDelete" v-hasPermi="['knowledge:doc']">
            {{ t('common.delete') }}
          </el-button>
        </el-col>
        <el-col :span="1.5">
          <el-button type="warning" plain icon="MagicStick" :disabled="table.multiple" @click="handleVectorize" v-hasPermi="['knowledge:doc']">
            {{ t('business.knowledge.docVectorize') }}
          </el-button>
        </el-col>
        <el-col :span="1.5">
          <el-button type="warning" plain icon="MagicStick" @click="handleVectorizeAll" v-hasPermi="['knowledge:doc']">
            {{ t('business.knowledge.docVectorizeAll') }}
          </el-button>
        </el-col>
        <el-col :span="1.5">
          <el-button type="info" plain icon="Search" @click="openSearch" v-hasPermi="['knowledge:doc']">{{ t('business.knowledge.search') }}</el-button>
        </el-col>
        <RightToolbar v-model:showSearch="table.showSearch" @queryTable="getList" />
      </el-row>

      <!-- 文档列表 -->
      <el-table v-loading="table.loading" :data="table.list" @selection-change="handleSelectionChange">
        <el-table-column type="selection" width="45" align="center" />
        <el-table-column :label="t('common.serialNo')" align="center" prop="id" width="70" />
        <el-table-column :label="t('business.knowledge.docName')" align="left" prop="name" min-width="2.5" :show-overflow-tooltip="true">
          <template #default="scope">
            <el-tag v-if="isQaDoc(scope.row.name)" type="warning" size="small" effect="plain" class="mr4">{{
              t('business.knowledge.qaTag')
            }}</el-tag>
            <span>{{ scope.row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('business.knowledge.docStatus')" align="center" min-width="1">
          <template #default="scope">
            <el-tag :type="docStatusType(scope.row.status)">
              {{ docStatusTitle(scope.row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('business.knowledge.docChunks')" align="center" prop="chunkCount" min-width="1" />
        <el-table-column :label="t('common.createTime')" align="center" min-width="2">
          <template #default="scope">
            <span>{{ scope.row.addTime }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.operation')" align="center" width="260" class-name="small-padding fixed-width">
          <template #default="scope">
            <el-button link type="primary" icon="MagicStick" @click="handleVectorize(scope.row)" v-hasPermi="['knowledge:doc']">{{
              t('business.knowledge.docVectorize')
            }}</el-button>
            <el-button link type="primary" icon="Edit" @click="handleUpdate(scope.row)" v-hasPermi="['knowledge:doc']">{{
              t('common.modify')
            }}</el-button>
            <el-button link type="primary" icon="Delete" @click="handleDelete(scope.row)" v-hasPermi="['knowledge:doc']">{{
              t('common.delete')
            }}</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <Pagination
        v-show="table.total > 0"
        :total="table.total"
        v-model:page="queryParams.pageNum"
        v-model:limit="queryParams.pageSize"
        @pagination="getList"
      />
    </div>

    <!-- 添加或修改文档对话框 -->
    <el-dialog :title="formState.title" v-model="formState.visible" width="1000px" append-to-body>
      <el-form ref="formRef" :model="formState.form" :rules="formState.rules" label-width="90px">
        <el-form-item :label="t('business.knowledge.docName')" prop="name">
          <el-input v-model="formState.form.name" maxlength="200" />
        </el-form-item>
        <el-form-item :label="t('business.knowledge.docContent')" prop="content">
          <el-input
            v-model="formState.form.content"
            type="textarea"
            :rows="20"
            class="doc-content-input"
            :placeholder="t('business.knowledge.docContentPlaceholder')"
            maxlength="16777215"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button type="primary" @click="submitForm">{{ t('modal.confirmButton') }}</el-button>
          <el-button @click="cancel">{{ t('modal.cancelButton') }}</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 添加或修改问答对 (QA) 对话框（[FEAT-011]） -->
    <el-dialog :title="qaState.title" v-model="qaState.visible" width="750px" append-to-body>
      <el-form ref="qaFormRef" :model="qaState.form" :rules="qaState.rules" label-width="90px">
        <el-form-item :label="t('business.knowledge.qaQuestion')" prop="question">
          <el-input
            v-model="qaState.form.question"
            maxlength="200"
            :placeholder="t('business.knowledge.qaQuestionPlaceholder')"
            clearable
          />
        </el-form-item>
        <el-form-item :label="t('business.knowledge.qaAnswer')" prop="answer">
          <el-input
            v-model="qaState.form.answer"
            type="textarea"
            :rows="11"
            class="doc-content-input"
            maxlength="16777215"
            :placeholder="t('business.knowledge.qaAnswerPlaceholder')"
          />
        </el-form-item>
        <el-form-item>
          <el-switch v-model="qaState.autoVectorize" :active-text="t('business.knowledge.autoVectorize')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button type="primary" :loading="qaState.submitting" @click="submitQaForm">{{ t('modal.confirmButton') }}</el-button>
          <el-button @click="qaState.visible = false">{{ t('modal.cancelButton') }}</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 向量与多路召回检索测试台对话框（[FEAT-011]） -->
    <el-dialog :title="t('business.knowledge.searchTitle', [baseName])" v-model="search.visible" width="1050px" append-to-body>
      <el-alert
        :title="t('business.knowledge.searchTestAlert', [search.kbThreshold ? search.kbThreshold.toFixed(2) : '0.40'])"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 16px"
      />
      <el-form :inline="true" @submit.prevent="handleSearchSubmit" class="search-playground-form">
        <el-form-item :label="t('business.knowledge.searchQuery')">
          <el-input
            v-model="search.query"
            :placeholder="t('business.knowledge.searchQueryPlaceholder')"
            clearable
            style="width: 250px"
            @keyup.enter="handleSearchSubmit"
          />
        </el-form-item>
        <el-form-item :label="t('business.knowledge.searchMode')">
          <el-select v-model="search.mode" style="width: 175px">
            <el-option value="HYBRID" :label="t('business.knowledge.modeHybrid')" />
            <el-option value="VECTOR" :label="t('business.knowledge.modeVector')" />
            <el-option value="BM25" :label="t('business.knowledge.modeBm25')" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('business.knowledge.tempTopK')">
          <el-input-number v-model="search.topK" :min="1" :max="20" style="width: 95px" />
        </el-form-item>
        <el-form-item :label="t('business.knowledge.tempThreshold')">
          <el-input-number
            v-model="search.threshold"
            :min="0"
            :max="1"
            :step="0.05"
            :precision="2"
            style="width: 120px"
            :placeholder="t('business.knowledge.tempThresholdPlaceholder')"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="Search" :loading="search.loading" @click="handleSearchSubmit">{{
            t('business.knowledge.search')
          }}</el-button>
        </el-form-item>
      </el-form>
      <el-table v-loading="search.loading" :data="search.list" max-height="420">
        <el-table-column :label="t('common.serialNo')" align="center" type="index" width="55" />
        <el-table-column :label="t('business.knowledge.docName')" align="left" width="160" :show-overflow-tooltip="true">
          <template #default="scope">
            <el-tag v-if="scope.row.qaChunk || isQaDoc(scope.row.docName)" size="small" type="warning" effect="plain" class="mr4">{{
              t('business.knowledge.qaTag')
            }}</el-tag>
            <span>{{ scope.row.docName || scope.row.docId }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('business.knowledge.matchType')" align="center" width="105">
          <template #default="scope">
            <el-tag v-if="scope.row.matchType === 'HYBRID'" type="success" size="small" effect="dark">
              {{ t('business.knowledge.modeHybrid').split(' ')[0] }}
            </el-tag>
            <el-tag v-else-if="scope.row.matchType === 'BM25'" type="warning" size="small" effect="dark">
              {{ t('business.knowledge.modeBm25').split(' ')[0] }}
            </el-tag>
            <el-tag v-else type="primary" size="small" effect="dark">
              {{ t('business.knowledge.modeVector').split(' ')[0] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('business.knowledge.score')" align="center" width="150">
          <template #default="scope">
            <div style="display: flex; align-items: center; justify-content: center; gap: 6px">
              <span style="font-weight: 600">{{ Number(scope.row.score).toFixed(4) }}</span>
              <el-tag v-if="scope.row.passed" type="success" size="small">{{ t('business.knowledge.scorePassed') }}</el-tag>
              <el-tag v-else type="info" size="small">{{ t('business.knowledge.scoreFiltered') }}</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('business.knowledge.scoreBreakdown')" align="center" width="195">
          <template #default="scope">
            <div class="score-breakdown">
              <span>Vec: <b>{{ scope.row.vectorScore != null ? Number(scope.row.vectorScore).toFixed(4) : '-' }}</b></span>
              <span>BM25: <b>{{ scope.row.bm25NormScore != null ? Number(scope.row.bm25NormScore).toFixed(4) : (scope.row.bm25Score != null ? Number(scope.row.bm25Score).toFixed(2) : '-') }}</b></span>
              <span>RRF: <b>{{ scope.row.rrfScore != null ? Number(scope.row.rrfScore).toFixed(6) : '-' }}</b></span>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('business.knowledge.hitContent')" align="left" :show-overflow-tooltip="true">
          <template #default="scope">{{ scope.row.text }}</template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'KnowledgeDoc' })
import { t } from '@/i18n'
import { listKnowledgeDoc, addKnowledgeDoc, updateKnowledgeDoc, delKnowledgeDoc, uploadKnowledgeDoc, vectorizeKnowledgeDoc, vectorizeKnowledgeBaseAll } from '@/modules/business/knowledge/doc/api'
import { searchKnowledgeDoc, loadKnowledgeBase } from '@/modules/business/knowledge/base/api'
import { useEnumOption } from '@/composables/useEnumOption'
import { useFormReset } from '@/composables/useFormReset'
import { usePageParams } from '@/composables/usePageParams'
import modal from '@/utils/modal'
import { useRoute, useRouter } from 'vue-router'
import { RightToolbar, Pagination } from '@/components'
import type { FormState, TableState } from '@/types'
import type { KnowledgeDoc, KnowledgeDocQuery } from '@/modules/business/knowledge/doc/types'
import type { KnowledgeHit } from '@/modules/business/knowledge/base/types'
import type { FormInstance, UploadRequestOptions } from 'element-plus'
import { ref } from 'vue'

const resetForm = useFormReset()
const route = useRoute()
const router = useRouter()

interface KnowledgeDocForm extends KnowledgeDoc {}

// --------------------------------- ref data ---------------------------------
const formRef = ref<FormInstance>() /* 编辑表单 ref */
const qaFormRef = ref<FormInstance>() /* QA 表单 ref */
const baseId = ref<number>(Number((route.query.baseId as string) ?? 0))
const baseName = ref<string>((route.query.baseName as string) ?? '')
const uploading = ref(false)
const { DocStatusEnum: docStatusOptions } = useEnumOption('DocStatusEnum')

const queryParams = ref<KnowledgeDocQuery>({ pageNum: 1, pageSize: 10, baseId: baseId.value, name: undefined, status: -1 })

const table = ref<TableState<KnowledgeDoc>>({ list: [], total: 0, loading: true, showSearch: true, ids: [], single: true, multiple: true })

const search = ref({
  visible: false,
  query: '',
  mode: 'HYBRID',
  topK: 5,
  threshold: undefined as number | undefined,
  list: [] as KnowledgeHit[],
  loading: false,
  kbThreshold: 0
})

const qaState = ref({
  visible: false,
  title: '',
  docId: undefined as number | undefined,
  form: {
    question: '',
    answer: ''
  },
  autoVectorize: true,
  submitting: false,
  rules: {
    question: [{ required: true, message: t('common.requiredMsg', [t('business.knowledge.qaQuestion')]), trigger: 'blur' }],
    answer: [{ required: true, message: t('common.requiredMsg', [t('business.knowledge.qaAnswer')]), trigger: 'blur' }]
  }
})

const formState = ref<FormState<KnowledgeDocForm>>({
  visible: false,
  title: '',
  form: {},
  rules: {
    name: [{ required: true, message: t('common.requiredMsg', [t('business.knowledge.docName')]), trigger: 'blur' }],
    content: [{ required: true, message: t('common.requiredMsg', [t('business.knowledge.docContent')]), trigger: 'blur' }]
  }
})

// --------------------------------- fun ---------------------------------
function isQaDoc(name?: string) {
  return typeof name === 'string' && name.startsWith('[QA]')
}

function parseQaContent(content?: string) {
  if (!content) return { question: '', answer: '' }
  let q = ''
  let a = ''
  const qIdx = content.indexOf('【问题】')
  const aIdx = content.indexOf('【答案】')
  if (qIdx !== -1 && aIdx !== -1) {
    q = content.substring(qIdx + 4, aIdx).trim()
    a = content.substring(aIdx + 4).trim()
  } else {
    q = content
  }
  return { question: q, answer: a }
}

function docStatusTitle(status: number) {
  return docStatusOptions.value.find((i) => i.code === status)?.title ?? String(status)
}
function docStatusType(status: number) {
  return status === 1 ? 'success' : status === 2 ? 'danger' : 'info'
}

function getList() {
  table.value.loading = true
  const params = usePageParams(queryParams)()
  listKnowledgeDoc(params).then((response) => {
    table.value.list = response.data.data
    table.value.total = response.data.total
    table.value.loading = false
  })
}
function reset() {
  formState.value.form = { id: undefined, baseId: baseId.value, name: undefined, content: undefined, chunkCount: 0, status: 0 }
  resetForm('formRef')
}
function handleQuery() {
  queryParams.value.pageNum = 1
  getList()
}
function resetQuery() {
  resetForm('queryRef')
  handleQuery()
}
function handleSelectionChange(selection: KnowledgeDoc[]) {
  table.value.ids = selection.map((i) => i.id as number)
  table.value.single = selection.length !== 1
  table.value.multiple = !selection.length
}
function handleAdd() {
  reset()
  formState.value.visible = true
  formState.value.title = t('common.titleAdd', [t('business.knowledge.doc')])
}
function handleAddQa() {
  qaState.value.docId = undefined
  qaState.value.form = { question: '', answer: '' }
  qaState.value.autoVectorize = true
  qaState.value.visible = true
  qaState.value.title = t('business.knowledge.addQa')
  resetForm('qaFormRef')
}
function handleUpdate(row: any) {
  reset()
  const id = row?.id ?? table.value.ids[0]
  if (id == null) return
  const current = table.value.list.find((item) => item.id === id)
  if (!current) return
  if (isQaDoc(current.name)) {
    qaState.value.docId = current.id
    const parsed = parseQaContent(current.content)
    qaState.value.form = { question: parsed.question, answer: parsed.answer }
    qaState.value.autoVectorize = true
    qaState.value.visible = true
    qaState.value.title = t('business.knowledge.editQa')
    return
  }
  formState.value.form = { ...current }
  formState.value.visible = true
  formState.value.title = t('common.titleEdit', [t('business.knowledge.doc')])
}
function handleDelete(row: any) {
  const ids = row?.id ?? table.value.ids
  if (ids == null || (Array.isArray(ids) && ids.length === 0)) return
  modal
    .confirm(t('business.knowledge.confirmDeleteDoc', [ids]))
    .then(() => delKnowledgeDoc(ids))
    .then(() => {
      getList()
      modal.msgSuccess(t('common.deleteSuccess'))
    })
    .catch(() => {})
}
function submitForm() {
  formRef.value!.validate((valid) => {
    if (!valid) return
    const submitData = { ...formState.value.form }
    delete submitData.addTime
    delete submitData.updateTime
    const req = submitData.id != null ? updateKnowledgeDoc(submitData) : addKnowledgeDoc(submitData)
    req.then(() => {
      modal.msgSuccess(submitData.id != null ? t('common.updateSuccess') : t('common.addSuccess'))
      formState.value.visible = false
      getList()
    })
  })
}
function submitQaForm() {
  qaFormRef.value!.validate(async (valid) => {
    if (!valid) return
    qaState.value.submitting = true
    const question = qaState.value.form.question.trim()
    const answer = qaState.value.form.answer.trim()
    const docName = `[QA] ${question}`
    const docContent = `【问题】\n${question}\n\n【答案】\n${answer}`

    try {
      if (qaState.value.docId != null) {
        await updateKnowledgeDoc({
          id: qaState.value.docId,
          baseId: baseId.value,
          name: docName,
          content: docContent
        })
        modal.msgSuccess(t('common.updateSuccess'))
        if (qaState.value.autoVectorize) {
          await vectorizeKnowledgeDoc(qaState.value.docId)
        }
      } else {
        await addKnowledgeDoc({
          baseId: baseId.value,
          name: docName,
          content: docContent,
          status: 0
        })
        modal.msgSuccess(t('common.addSuccess'))
        if (qaState.value.autoVectorize) {
          const listRes = await listKnowledgeDoc({ baseId: baseId.value, pageNum: 1, pageSize: 1, name: docName, status: -1 } as any)
          const newDoc = listRes.data?.data?.[0]
          if (newDoc?.id) {
            await vectorizeKnowledgeDoc(newDoc.id)
          }
        }
      }
      qaState.value.visible = false
      getList()
    } catch (e) {
      // handled by request interceptor
    } finally {
      qaState.value.submitting = false
    }
  })
}
function cancel() {
  formState.value.visible = false
  reset()
}

// --------------------------------- 上传 / 向量化 ---------------------------------
/** 上传前校验：仅允许 .txt/.md 且不超 10MB */
function handleBeforeUpload(file: File): boolean {
  const ext = (file.name.toLowerCase().match(/\.(\w+)$/) || [])[1]
  if (ext !== 'txt' && ext !== 'md') {
    modal.msgError(t('business.knowledge.docUploadTypeTip'))
    return false
  }
  if (file.size > 10 * 1024 * 1024) {
    modal.msgError(t('business.knowledge.docUploadSizeTip'))
    return false
  }
  return true
}

/** 文件上传（自定义 http-request，走业务接口） */
async function handleUpload(options: UploadRequestOptions) {
  if (!baseId.value) {
    modal.msgError(t('business.knowledge.searchRequired'))
    return
  }
  uploading.value = true
  await uploadKnowledgeDoc(baseId.value, options.file as File)
    .then(() => {
      modal.msgSuccess(t('common.addSuccess'))
      getList()
    })
    .catch(() => {})
    .finally(() => {
      uploading.value = false
    })
}

/** 向量化：分片 → 嵌入 → 写 Milvus（支持勾选多条批量向量化） */
function handleVectorize(row: any) {
  const raw = row?.id ?? table.value.ids
  const ids = Array.isArray(raw) ? raw : [raw]
  if (ids.length === 0) return
  modal
    .confirm(t('business.knowledge.vectorizeConfirm'))
    .then(async () => {
      let success = 0
      let fail = 0
      for (const id of ids) {
        try {
          await vectorizeKnowledgeDoc(id)
          success++
        } catch (e) {
          fail++
        }
      }
      return { success, fail }
    })
    .then(({ success, fail }) => {
      modal.msgSuccess(t('business.knowledge.vectorizeDone', [success, fail]))
      getList()
    })
    .catch(() => {})
}

/** 整个知识库批量向量化 */
function handleVectorizeAll() {
  if (!baseId.value) return
  modal
    .confirm(t('business.knowledge.vectorizeAllConfirm'))
    .then(() => vectorizeKnowledgeBaseAll(baseId.value))
    .then((res) => {
      modal.msgSuccess(res.msg)
      getList()
    })
    .catch(() => {})
}

/** 返回知识库列表 */
function goBack() {
  router.push({ path: '/knowledge/base' })
}

// --------------------------------- 向量检索 ---------------------------------
/** 打开向量与多路召回检索测试台弹窗（检索当前知识库全量文档） */
function openSearch() {
  search.value.visible = true
  search.value.query = ''
  search.value.mode = 'HYBRID'
  search.value.topK = 5
  search.value.threshold = undefined
  search.value.list = []
  if (baseId.value) {
    loadKnowledgeBase(baseId.value)
      .then((res) => {
        if (res.data?.similarityThreshold != null) {
          search.value.kbThreshold = res.data.similarityThreshold
        }
      })
      .catch(() => {})
  }
}
function handleSearchSubmit() {
  if (!search.value.query) {
    modal.msgWarning(t('business.knowledge.searchRequired'))
    return
  }
  search.value.loading = true
  searchKnowledgeDoc(baseId.value, search.value.query, search.value.topK, search.value.mode, search.value.threshold)
    .then((response) => {
      search.value.list = response.data || []
      if (response.data && response.data.length > 0 && response.data[0].threshold != null) {
        search.value.kbThreshold = response.data[0].threshold
      }
      search.value.loading = false
    })
    .catch(() => {
      search.value.loading = false
    })
}

// --------------------------------- page init ---------------------------------
getList()
</script>

<style scoped>
.doc-header-title {
  font-size: 15px;
  font-weight: 600;
}
.search-query-input {
  width: 460px;
}
.doc-content-input :deep(.el-textarea__inner) {
  max-height: 52vh;
  overflow-y: auto;
}
.search-playground-form :deep(.el-form-item) {
  margin-bottom: 12px;
  margin-right: 12px;
}
.score-breakdown {
  display: flex;
  flex-direction: column;
  font-size: 11px;
  line-height: 1.4;
  color: var(--el-text-color-secondary);
}
.score-breakdown b {
  color: var(--el-text-color-primary);
}
.mr4 {
  margin-right: 4px;
}
</style>