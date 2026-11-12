<!--
  AI 质量评测（Eval）主面板
  包含：评测数据集管理 + 评测实验中心（双页签）
-->
<template>
  <div class="app-container">
    <div class="content-inner">
      <el-tabs v-model="activeTab" type="border-card">
        <!-- ==================== TAB 1: 评测数据集 ==================== -->
        <el-tab-pane :label="t('business.eval.tabDatasets')" name="datasets">
          <!-- 搜索栏 -->
          <el-form :model="datasetQuery" ref="datasetQueryRef" :inline="true" class="mb8">
            <el-form-item :label="t('business.eval.datasetName')" prop="name">
              <el-input
                v-model="datasetQuery.name"
                :placeholder="t('common.inputPlaceholder', [t('business.eval.datasetName')])"
                clearable
                style="width: 200px"
                @keyup.enter="getDatasetList"
              />
            </el-form-item>
            <el-form-item :label="t('business.eval.evalType')" prop="evalType">
              <el-select v-model="datasetQuery.evalType" :placeholder="t('common.selectPlaceholder')" clearable style="width: 130px">
                <el-option :label="t('common.all')" value="" />
                <el-option label="RAG" value="RAG" />
                <el-option label="AGENT" value="AGENT" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" icon="Search" @click="getDatasetList">{{ t('common.search') }}</el-button>
              <el-button icon="Refresh" @click="resetDatasetQuery">{{ t('common.reset') }}</el-button>
            </el-form-item>
          </el-form>

          <!-- 按钮栏 -->
          <el-row :gutter="10" class="mb8">
            <el-col :span="1.5">
              <el-button type="primary" plain icon="Plus" @click="handleAddDataset" v-hasPermi="['eval:default']">
                {{ t('common.add') }}
              </el-button>
            </el-col>
          </el-row>

          <!-- 数据集表格 -->
          <el-table v-loading="datasetLoading" :data="datasetList">
            <el-table-column :label="t('common.serialNo')" align="center" prop="id" width="70" />
            <el-table-column :label="t('business.eval.datasetName')" align="center" prop="name" min-width="140" :show-overflow-tooltip="true" />
            <el-table-column :label="t('business.eval.evalType')" align="center" width="100">
              <template #default="scope">
                <el-tag :type="scope.row.evalType === 'RAG' ? 'primary' : 'success'">{{ scope.row.evalType }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('business.eval.target')" align="center" prop="targetName" min-width="130" :show-overflow-tooltip="true" />
            <el-table-column :label="t('business.eval.sampleCount')" align="center" prop="sampleCount" width="100">
              <template #default="scope">
                <el-badge :value="scope.row.sampleCount" class="item" :type="scope.row.sampleCount > 0 ? 'primary' : 'warning'" />
              </template>
            </el-table-column>
            <el-table-column :label="t('business.eval.description')" align="center" prop="description" min-width="160" :show-overflow-tooltip="true" />
            <el-table-column :label="t('common.updateTime')" align="center" prop="updateTime" width="160" />
            <el-table-column :label="t('common.operate')" align="center" width="260" class-name="small-padding fixed-width">
              <template #default="scope">
                <el-button link type="primary" icon="VideoPlay" @click="handleRunExperiment(scope.row)" v-hasPermi="['eval:default']">
                  {{ t('business.eval.runExperiment') }}
                </el-button>
                <el-button link type="primary" icon="Upload" @click="handleOpenImport(scope.row)" v-hasPermi="['eval:default']">
                  {{ t('business.eval.importSamples') }}
                </el-button>
                <el-button link type="primary" icon="Edit" @click="handleEditDataset(scope.row)" v-hasPermi="['eval:default']">
                  {{ t('common.modify') }}
                </el-button>
                <el-button link type="danger" icon="Delete" @click="handleDeleteDataset(scope.row)" v-hasPermi="['eval:default']">
                  {{ t('common.delete') }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <!-- 分页 -->
          <pagination
            v-show="datasetTotal > 0"
            :total="datasetTotal"
            v-model:page="datasetQuery.pageNum"
            v-model:limit="datasetQuery.pageSize"
            @pagination="getDatasetList"
          />
        </el-tab-pane>

        <!-- ==================== TAB 2: 评测实验中心 ==================== -->
        <el-tab-pane :label="t('business.eval.tabExperiments')" name="experiments">
          <!-- 搜索栏 -->
          <el-form :model="expQuery" ref="expQueryRef" :inline="true" class="mb8">
            <el-form-item :label="t('business.eval.experimentName')" prop="name">
              <el-input
                v-model="expQuery.name"
                :placeholder="t('common.inputPlaceholder', [t('business.eval.experimentName')])"
                clearable
                style="width: 200px"
                @keyup.enter="getExpList"
              />
            </el-form-item>
            <el-form-item :label="t('common.status')" prop="status">
              <el-select v-model="expQuery.status" :placeholder="t('common.selectPlaceholder')" clearable style="width: 130px">
                <el-option :label="t('common.all')" :value="-1" />
                <el-option :label="t('business.eval.statusRunning')" :value="1" />
                <el-option :label="t('business.eval.statusCompleted')" :value="2" />
                <el-option :label="t('business.eval.statusFailed')" :value="3" />
                <el-option :label="t('business.eval.statusCancelled')" :value="4" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" icon="Search" @click="getExpList">{{ t('common.search') }}</el-button>
              <el-button icon="Refresh" @click="resetExpQuery">{{ t('common.reset') }}</el-button>
            </el-form-item>
          </el-form>

          <!-- 实验表格 -->
          <el-table v-loading="expLoading" :data="expList">
            <el-table-column :label="t('common.serialNo')" align="center" prop="id" width="70" />
            <el-table-column :label="t('business.eval.experimentName')" align="center" prop="name" min-width="150" :show-overflow-tooltip="true" />
            <el-table-column :label="t('business.eval.target')" align="center" prop="targetName" min-width="130" :show-overflow-tooltip="true" />
            <el-table-column :label="t('common.status')" align="center" width="110">
              <template #default="scope">
                <el-tag :type="statusTagType(scope.row.status)">{{ statusLabel(scope.row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('business.eval.progress')" align="center" width="140">
              <template #default="scope">
                <el-progress
                  :percentage="calcPercentage(scope.row.completedCases, scope.row.totalCases)"
                  :status="scope.row.status === 2 ? 'success' : (scope.row.status === 3 ? 'exception' : undefined)"
                />
              </template>
            </el-table-column>
            <el-table-column :label="t('business.eval.metricHitRate3')" align="center" width="110">
              <template #default="scope">
                <span>{{ scope.row.evalType === 'RAG' ? formatRate(scope.row.avgHitRate) : '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('business.eval.metricMrr')" align="center" width="90">
              <template #default="scope">
                <span>{{ scope.row.evalType === 'RAG' ? formatScore(scope.row.avgMrr) : '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('business.eval.metricFaithfulness')" align="center" width="100">
              <template #default="scope">
                <span>{{ scope.row.evalType === 'RAG' ? formatScore(scope.row.avgFaithfulness) : '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('business.eval.metricRelevance')" align="center" width="90">
              <template #default="scope">
                <span>{{ formatScore(scope.row.avgRelevance) }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('business.eval.metricGoalCompletion')" align="center" width="110">
              <template #default="scope">
                <span>{{ scope.row.evalType === 'AGENT' ? formatScore(scope.row.avgGoalCompletion) : '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('common.createTime')" align="center" prop="addTime" width="160" />
            <el-table-column :label="t('common.operate')" align="center" width="180" class-name="small-padding fixed-width">
              <template #default="scope">
                <el-button link type="primary" icon="View" @click="handleOpenDetail(scope.row.id)" v-hasPermi="['eval:default']">
                  {{ t('business.eval.viewReport') }}
                </el-button>
                <el-button link type="warning" icon="CircleClose" v-if="scope.row.status === 1" @click="handleCancelExp(scope.row)" v-hasPermi="['eval:default']">
                  {{ t('common.cancel') }}
                </el-button>
                <el-button link type="danger" icon="Delete" @click="handleDeleteExp(scope.row)" v-hasPermi="['eval:default']">
                  {{ t('common.delete') }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <!-- 分页 -->
          <pagination
            v-show="expTotal > 0"
            :total="expTotal"
            v-model:page="expQuery.pageNum"
            v-model:limit="expQuery.pageSize"
            @pagination="getExpList"
          />
        </el-tab-pane>
      </el-tabs>

      <!-- ==================== 对话框：新增/编辑数据集 ==================== -->
      <el-dialog v-model="datasetDialog.visible" :title="datasetDialog.title" width="560px" append-to-body>
        <el-form ref="datasetFormRef" :model="datasetForm" :rules="datasetRules" label-width="110px">
          <el-form-item :label="t('business.eval.datasetName')" prop="name">
            <el-input v-model="datasetForm.name" :placeholder="t('common.inputPlaceholder', [t('business.eval.datasetName')])" />
          </el-form-item>
          <el-form-item :label="t('business.eval.evalType')" prop="evalType">
            <el-radio-group v-model="datasetForm.evalType" @change="handleEvalTypeChange">
              <el-radio label="RAG">RAG 知识库检索生成</el-radio>
              <el-radio label="AGENT">Agent 目标与工具链</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item :label="t('business.eval.target')" prop="targetId">
            <el-select v-model="datasetForm.targetId" :placeholder="t('common.selectPlaceholder')" style="width: 100%">
              <el-option
                v-for="item in targetOptions"
                :key="item.id"
                :label="item.name"
                :value="item.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('business.eval.description')" prop="description">
            <el-input v-model="datasetForm.description" type="textarea" :rows="3" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="datasetDialog.visible = false">{{ t('common.cancel') }}</el-button>
          <el-button type="primary" @click="submitDatasetForm">{{ t('common.confirm') }}</el-button>
        </template>
      </el-dialog>

      <!-- ==================== 对话框：导入样本集 JSON ==================== -->
      <el-dialog v-model="importDialog.visible" :title="t('business.eval.importDialogTitle')" width="680px" append-to-body>
        <div class="mb8 flex justify-between">
          <span class="text-muted text-sm">{{ t('business.eval.importTip') }}</span>
          <el-button type="primary" link size="small" @click="fillSampleTemplate">{{ t('business.eval.fillTemplate') }}</el-button>
        </div>
        <el-input
          v-model="importDialog.jsonContent"
          type="textarea"
          :rows="12"
          placeholder='[{"id":"case-1","query":"...","groundTruth":"...","expectedDocNames":["..."],"expectedKeywords":["..."]}]'
        />
        <template #footer>
          <el-button @click="importDialog.visible = false">{{ t('common.cancel') }}</el-button>
          <el-button type="primary" @click="submitImportJson">{{ t('common.confirm') }}</el-button>
        </template>
      </el-dialog>

      <!-- ==================== 对话框：创建并启动评测实验 ==================== -->
      <el-dialog v-model="runDialog.visible" :title="t('business.eval.runExperimentTitle')" width="520px" append-to-body>
        <el-form :model="runForm" label-width="110px">
          <el-form-item :label="t('business.eval.datasetName')">
            <el-input :value="runForm.datasetName" disabled />
          </el-form-item>
          <el-form-item :label="t('business.eval.experimentName')" required>
            <el-input v-model="runForm.name" />
          </el-form-item>
          <el-form-item :label="t('business.eval.judgeModel')">
            <el-select v-model="runForm.judgeModelId" :placeholder="t('business.eval.defaultModelTip')" clearable style="width: 100%">
              <el-option
                v-for="m in modelOptions"
                :key="m.id"
                :label="m.name"
                :value="m.id"
              />
            </el-select>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="runDialog.visible = false">{{ t('common.cancel') }}</el-button>
          <el-button type="primary" @click="submitRunExperiment">{{ t('business.eval.startRun') }}</el-button>
        </template>
      </el-dialog>

      <!-- 实验报告抽屉 -->
      <ExperimentDetailDrawer ref="detailDrawerRef" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { t } from '@/i18n'
import type { EvalDataset, EvalDatasetListQuery, EvalExperiment, EvalExperimentListQuery } from '../types'
import {
  listEvalDataset,
  loadEvalDataset,
  addEvalDataset,
  updateEvalDataset,
  delEvalDataset,
  importEvalDatasetJson,
  listEvalExperiment,
  createAndRunExperiment,
  cancelEvalExperiment,
  delEvalExperiment
} from '../api'
import { listKnowledgeBaseBySpace } from '@/modules/business/knowledge/base/api'
import { listAgentBySpace } from '@/modules/business/agent/api'
import { listSupplierBySpace, listModelBySupplier } from '@/modules/business/supplier/api'
import ExperimentDetailDrawer from '../components/ExperimentDetailDrawer.vue'

const activeTab = ref('datasets')

// ------------------- Tab 1: 评测数据集 -------------------
const datasetLoading = ref(false)
const datasetList = ref<EvalDataset[]>([])
const datasetTotal = ref(0)
const datasetQuery = ref<EvalDatasetListQuery>({
  pageNum: 1,
  pageSize: 10,
  name: undefined,
  evalType: undefined
})

const datasetDialog = ref({
  visible: false,
  title: ''
})
const datasetForm = ref<EvalDataset>({
  id: undefined,
  name: '',
  evalType: 'RAG',
  targetId: 0,
  description: ''
})
const datasetRules = {
  name: [{ required: true, message: t('common.requiredMsg', [t('business.eval.datasetName')]), trigger: 'blur' }],
  targetId: [{ required: true, message: t('common.requiredMsg', [t('business.eval.target')]), trigger: 'change' }]
}

const targetOptions = ref<Array<{ id: number; name: string }>>([])

function getDatasetList() {
  datasetLoading.value = true
  listEvalDataset(datasetQuery.value)
    .then((res) => {
      datasetList.value = res.data.data
      datasetTotal.value = res.data.total
    })
    .finally(() => {
      datasetLoading.value = false
    })
}

function resetDatasetQuery() {
  datasetQuery.value = { pageNum: 1, pageSize: 10, name: undefined, evalType: undefined }
  getDatasetList()
}

function handleEvalTypeChange(val: 'RAG' | 'AGENT') {
  datasetForm.value.targetId = 0
  loadTargetOptions(val)
}

function loadTargetOptions(type: 'RAG' | 'AGENT') {
  targetOptions.value = []
  if (type === 'RAG') {
    listKnowledgeBaseBySpace().then((res) => {
      targetOptions.value = res.data.map((item) => ({ id: item.id as number, name: item.name }))
    })
  } else {
    listAgentBySpace().then((res) => {
      targetOptions.value = res.data.map((item) => ({ id: item.id as number, name: item.name }))
    })
  }
}

function handleAddDataset() {
  datasetForm.value = { id: undefined, name: '', evalType: 'RAG', targetId: 0, description: '' }
  datasetDialog.value = { visible: true, title: t('business.eval.addDataset') }
  loadTargetOptions('RAG')
}

function handleEditDataset(row: EvalDataset) {
  loadEvalDataset(row.id as number).then((res) => {
    datasetForm.value = { ...res.data }
    datasetDialog.value = { visible: true, title: t('business.eval.editDataset') }
    loadTargetOptions(res.data.evalType)
  })
}

function submitDatasetForm() {
  if (!datasetForm.value.name) {
    ElMessage.warning(t('common.requiredMsg', [t('business.eval.datasetName')]))
    return
  }
  if (!datasetForm.value.targetId) {
    ElMessage.warning(t('common.requiredMsg', [t('business.eval.target')]))
    return
  }
  if (datasetForm.value.id) {
    updateEvalDataset(datasetForm.value).then(() => {
      ElMessage.success(t('common.operateSuccess'))
      datasetDialog.value.visible = false
      getDatasetList()
    })
  } else {
    addEvalDataset(datasetForm.value).then(() => {
      ElMessage.success(t('common.operateSuccess'))
      datasetDialog.value.visible = false
      getDatasetList()
    })
  }
}

function handleDeleteDataset(row: EvalDataset) {
  ElMessageBox.confirm(t('common.confirmDelete'), t('common.tip'), { type: 'warning' }).then(() => {
    delEvalDataset(row.id as number).then(() => {
      ElMessage.success(t('common.deleteSuccess'))
      getDatasetList()
    })
  })
}

// 样本集导入
const importDialog = ref({
  visible: false,
  datasetId: 0,
  jsonContent: ''
})

function handleOpenImport(row: EvalDataset) {
  importDialog.value.datasetId = row.id as number
  importDialog.value.jsonContent = row.contentJson && row.contentJson !== '[]' ? row.contentJson : ''
  importDialog.value.visible = true
}

function fillSampleTemplate() {
  importDialog.value.jsonContent = JSON.stringify(
    [
      {
        id: 'sample-001',
        query: 'XXL-AI 的流式超时时间配置项是什么？',
        groundTruth: '配置项为 xxl-ai.chat.stream.timeout，默认 180000ms',
        expectedDocNames: ['CLOUD_HANDOVER_CONTEXT.md'],
        expectedKeywords: ['streamTimeout', '180000']
      },
      {
        id: 'sample-002',
        query: '系统多租户隔离基于什么字段？',
        groundTruth: '核心基于 space_id 空间强隔离',
        expectedDocNames: ['CLOUD_HANDOVER_CONTEXT.md'],
        expectedKeywords: ['space_id', 'SpaceContext']
      }
    ],
    null,
    2
  )
}

function submitImportJson() {
  if (!importDialog.value.jsonContent) {
    ElMessage.warning(t('business.eval.jsonRequired'))
    return
  }
  importEvalDatasetJson(importDialog.value.datasetId, importDialog.value.jsonContent).then(() => {
    ElMessage.success(t('common.operateSuccess'))
    importDialog.value.visible = false
    getDatasetList()
  })
}

// ------------------- Tab 2: 评测实验中心 -------------------
const expLoading = ref(false)
const expList = ref<EvalExperiment[]>([])
const expTotal = ref(0)
const expQuery = ref<EvalExperimentListQuery>({
  pageNum: 1,
  pageSize: 10,
  name: undefined,
  status: -1
})

function getExpList() {
  expLoading.value = true
  listEvalExperiment(expQuery.value)
    .then((res) => {
      expList.value = res.data.data
      expTotal.value = res.data.total
    })
    .finally(() => {
      expLoading.value = false
    })
}

function resetExpQuery() {
  expQuery.value = { pageNum: 1, pageSize: 10, name: undefined, status: -1 }
  getExpList()
}

// 触发评测实验
const runDialog = ref({
  visible: false
})
const runForm = ref({
  datasetId: 0,
  datasetName: '',
  name: '',
  judgeModelId: undefined as number | undefined
})
const modelOptions = ref<Array<{ id: number; name: string }>>([])

function handleRunExperiment(row: EvalDataset) {
  runForm.value = {
    datasetId: row.id as number,
    datasetName: row.name,
    name: row.name + '-' + t('business.eval.experimentSuffix') + '-' + new Date().toISOString().substring(11, 19).replace(/:/g, ''),
    judgeModelId: undefined
  }
  loadModelOptions()
  runDialog.value.visible = true
}

function loadModelOptions() {
  modelOptions.value = []
  listSupplierBySpace().then((res) => {
    if (res.data && res.data.length > 0) {
      res.data.forEach((supplier) => {
        listModelBySupplier(supplier.id as number).then((mRes) => {
          if (mRes.data) {
            mRes.data
              .filter((m) => m.type === 0 && m.status === 0)
              .forEach((m) => {
                modelOptions.value.push({ id: m.id as number, name: `${supplier.name} - ${m.name}` })
              })
          }
        })
      })
    }
  })
}

function submitRunExperiment() {
  if (!runForm.value.name) {
    ElMessage.warning(t('common.requiredMsg', [t('business.eval.experimentName')]))
    return
  }
  createAndRunExperiment({
    datasetId: runForm.value.datasetId,
    name: runForm.value.name,
    judgeModelId: runForm.value.judgeModelId
  }).then(() => {
    ElMessage.success(t('business.eval.experimentStarted'))
    runDialog.value.visible = false
    activeTab.value = 'experiments'
    getExpList()
  })
}

function handleCancelExp(row: EvalExperiment) {
  ElMessageBox.confirm(t('business.eval.confirmCancel'), t('common.tip'), { type: 'warning' }).then(() => {
    cancelEvalExperiment(row.id as number).then(() => {
      ElMessage.success(t('common.operateSuccess'))
      getExpList()
    })
  })
}

function handleDeleteExp(row: EvalExperiment) {
  ElMessageBox.confirm(t('common.confirmDelete'), t('common.tip'), { type: 'warning' }).then(() => {
    delEvalExperiment(row.id as number).then(() => {
      ElMessage.success(t('common.deleteSuccess'))
      getExpList()
    })
  })
}

// 详情抽屉
const detailDrawerRef = ref()
function handleOpenDetail(id: number) {
  detailDrawerRef.value?.open(id)
}

function calcPercentage(completed?: number, total?: number) {
  if (!total || total <= 0) return 0
  return Math.min(100, Math.round(((completed || 0) / total) * 100))
}

function formatRate(val?: number) {
  if (val === undefined || val === null) return '0.0%'
  return (val * 100).toFixed(1) + '%'
}

function formatScore(val?: number) {
  if (val === undefined || val === null) return '0.00'
  return Number(val).toFixed(2)
}

function statusTagType(status?: number): '' | 'success' | 'warning' | 'danger' | 'info' {
  switch (status) {
    case 1: return 'warning'
    case 2: return 'success'
    case 3: return 'danger'
    case 4: return 'info'
    default: return 'info'
  }
}

function statusLabel(status?: number): string {
  switch (status) {
    case 0: return t('business.eval.statusPending')
    case 1: return t('business.eval.statusRunning')
    case 2: return t('business.eval.statusCompleted')
    case 3: return t('business.eval.statusFailed')
    case 4: return t('business.eval.statusCancelled')
    default: return t('common.unknown')
  }
}

onMounted(() => {
  getDatasetList()
  getExpList()
})
</script>

<style scoped>
.mb8 { margin-bottom: 8px; }
.justify-between { justify-content: space-between; }
.text-sm { font-size: 13px; }
.text-muted { color: #909399; }
</style>
