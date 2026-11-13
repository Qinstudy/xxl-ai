<!--
  工作流编排（Workflow）管理主界面
  双页签架构：工作流管理与定义 + 执行历史与链路追溯
-->
<template>
  <div class="app-container">
    <div class="content-inner">
      <el-tabs v-model="activeTab" type="border-card">
        <!-- ==================== TAB 1: 工作流列表 ==================== -->
        <el-tab-pane :label="t('business.workflow.tabWorkflows')" name="workflows">
          <!-- 搜索栏 -->
          <el-form :model="wfQuery" ref="wfQueryRef" :inline="true" class="mb8">
            <el-form-item :label="t('business.workflow.name')" prop="name">
              <el-input
                v-model="wfQuery.name"
                :placeholder="t('common.inputPlaceholder', [t('business.workflow.name')])"
                clearable
                style="width: 200px"
                @keyup.enter="handleWfQuery"
              />
            </el-form-item>
            <el-form-item :label="t('business.workflow.status')" prop="status">
              <el-select v-model="wfQuery.status" :placeholder="t('common.selectPlaceholder')" clearable style="width: 130px">
                <el-option :label="t('common.all')" :value="-1" />
                <el-option :label="t('business.workflow.statusDraft')" :value="0" />
                <el-option :label="t('business.workflow.statusPublished')" :value="1" />
                <el-option :label="t('business.workflow.statusDisabled')" :value="2" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" icon="Search" @click="handleWfQuery">{{ t('common.search') }}</el-button>
              <el-button icon="Refresh" @click="resetWfQuery">{{ t('common.reset') }}</el-button>
            </el-form-item>
          </el-form>

          <!-- 按钮栏 -->
          <el-row :gutter="10" class="mb8">
            <el-col :span="1.5">
              <el-button type="primary" plain icon="Plus" @click="handleAddWorkflow" v-hasPermi="['workflow:default']">
                {{ t('common.add') }}
              </el-button>
            </el-col>
          </el-row>

          <!-- 工作流列表表格 -->
          <el-table v-loading="wfLoading" :data="wfList">
            <el-table-column :label="t('common.serialNo')" align="center" prop="id" width="70" />
            <el-table-column :label="t('business.workflow.name')" align="center" prop="name" min-width="160" :show-overflow-tooltip="true" />
            <el-table-column :label="t('business.workflow.description')" align="center" prop="description" min-width="180" :show-overflow-tooltip="true" />
            <el-table-column :label="t('business.workflow.nodeCount')" align="center" width="100">
              <template #default="scope">
                <el-tag type="info">{{ t('business.workflow.nodesUnit', [scope.row.nodeCount ?? 0]) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('business.workflow.status')" align="center" width="100">
              <template #default="scope">
                <el-tag v-if="scope.row.status === 1" type="success">{{ t('business.workflow.statusPublished') }}</el-tag>
                <el-tag v-else-if="scope.row.status === 2" type="danger">{{ t('business.workflow.statusDisabled') }}</el-tag>
                <el-tag v-else type="info">{{ t('business.workflow.statusDraft') }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('common.updateTime')" align="center" prop="updateTime" width="160" />

            <el-table-column :label="t('common.operation')" align="center" width="280" class-name="small-padding fixed-width">
              <template #default="scope">
                <el-button
                  link
                  type="success"
                  icon="CaretRight"
                  @click="handleDebug(scope.row)"
                  v-hasPermi="['workflow:default']"
                >
                  {{ t('business.workflow.runDebug') }}
                </el-button>
                <el-button
                  link
                  type="primary"
                  icon="Edit"
                  @click="handleEditWorkflow(scope.row)"
                  v-hasPermi="['workflow:default']"
                >
                  {{ t('common.modify') }}
                </el-button>
                <el-button
                  link
                  type="info"
                  icon="Tickets"
                  @click="handleViewExecutions(scope.row)"
                  v-hasPermi="['workflow:default']"
                >
                  {{ t('business.workflow.tabExecutions') }}
                </el-button>
                <el-button
                  link
                  type="danger"
                  icon="Delete"
                  @click="handleDeleteWorkflow(scope.row)"
                  v-hasPermi="['workflow:default']"
                >
                  {{ t('common.delete') }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <Pagination
            v-show="wfTotal > 0"
            :total="wfTotal"
            v-model:page="wfQuery.pageNum"
            v-model:limit="wfQuery.pageSize"
            @pagination="getWfList"
          />
        </el-tab-pane>

        <!-- ==================== TAB 2: 执行记录 ==================== -->
        <el-tab-pane :label="t('business.workflow.tabExecutions')" name="executions">
          <!-- 搜索栏 -->
          <el-form :model="execQuery" ref="execQueryRef" :inline="true" class="mb8">
            <el-form-item :label="t('business.workflow.filterWfId')" prop="workflowId">
              <el-input-number
                v-model="execQuery.workflowId"
                :min="1"
                :controls="false"
                :value-on-clear="undefined"
                :placeholder="t('business.workflow.filterWfId')"
                style="width: 160px"
                @keyup.enter="handleExecQuery"
              />
            </el-form-item>
            <el-form-item :label="t('business.workflow.execStatus')" prop="status">
              <el-select v-model="execQuery.status" :placeholder="t('common.selectPlaceholder')" clearable style="width: 130px">
                <el-option :label="t('common.all')" :value="-1" />
                <el-option :label="t('business.workflow.statusRunning')" :value="0" />
                <el-option :label="t('business.workflow.statusSuccess')" :value="1" />
                <el-option :label="t('business.workflow.statusFailed')" :value="2" />
                <el-option :label="t('business.workflow.statusTimeout')" :value="3" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" icon="Search" @click="handleExecQuery">{{ t('common.search') }}</el-button>
              <el-button icon="Refresh" @click="resetExecQuery">{{ t('common.reset') }}</el-button>
            </el-form-item>
          </el-form>

          <!-- 执行历史表格 -->
          <el-table v-loading="execLoading" :data="execList">
            <el-table-column :label="t('common.serialNo')" align="center" prop="id" width="80" />
            <el-table-column :label="t('business.workflow.filterWfId')" align="center" prop="workflowId" width="110" />
            <el-table-column :label="t('business.workflow.triggerType')" align="center" prop="triggerType" width="110">
              <template #default="scope">
                <el-tag effect="plain">{{ scope.row.triggerType }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('business.workflow.execStatus')" align="center" width="100">
              <template #default="scope">
                <el-tag v-if="scope.row.status === 1" type="success">{{ t('business.workflow.statusSuccess') }}</el-tag>
                <el-tag v-else-if="scope.row.status === 0" type="primary">{{ t('business.workflow.statusRunning') }}</el-tag>
                <el-tag v-else-if="scope.row.status === 3" type="warning">{{ t('business.workflow.statusTimeout') }}</el-tag>
                <el-tag v-else type="danger">{{ t('business.workflow.statusFailed') }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('business.workflow.duration')" align="center" width="120">
              <template #default="scope">
                <span>{{ scope.row.durationMs ?? 0 }} ms</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('business.workflow.tokens')" align="center" prop="totalTokens" width="110" />
            <el-table-column :label="t('common.addTime')" align="center" prop="addTime" width="160" />

            <el-table-column :label="t('common.operation')" align="center" width="180">
              <template #default="scope">
                <el-button
                  link
                  type="primary"
                  icon="View"
                  @click="handleViewExecutionDetail(scope.row)"
                  v-hasPermi="['workflow:default']"
                >
                  {{ t('business.workflow.viewTraces') }}
                </el-button>
                <el-button
                  link
                  type="danger"
                  icon="Delete"
                  @click="handleDeleteExecution(scope.row)"
                  v-hasPermi="['workflow:default']"
                >
                  {{ t('common.delete') }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <Pagination
            v-show="execTotal > 0"
            :total="execTotal"
            v-model:page="execQuery.pageNum"
            v-model:limit="execQuery.pageSize"
            @pagination="getExecList"
          />
        </el-tab-pane>
      </el-tabs>
    </div>

    <!-- 工作流新增/编辑弹窗 -->
    <el-dialog
      v-model="wfDialogVisible"
      :title="wfDialogTitle"
      width="70%"
      destroy-on-close
    >
      <el-form ref="wfFormRef" :model="wfForm" :rules="wfRules" label-width="110px">
        <el-row :gutter="20">
          <el-col :span="14">
            <el-form-item :label="t('business.workflow.name')" prop="name">
              <el-input v-model="wfForm.name" :placeholder="t('common.inputPlaceholder', [t('business.workflow.name')])" />
            </el-form-item>
          </el-col>
          <el-col :span="10">
            <el-form-item :label="t('business.workflow.status')" prop="status">
              <el-radio-group v-model="wfForm.status">
                <el-radio :value="0">{{ t('business.workflow.statusDraft') }}</el-radio>
                <el-radio :value="1">{{ t('business.workflow.statusPublished') }}</el-radio>
                <el-radio :value="2">{{ t('business.workflow.statusDisabled') }}</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item :label="t('business.workflow.description')" prop="description">
          <el-input v-model="wfForm.description" type="textarea" :rows="2" :placeholder="t('business.workflow.descPlaceholder')" />
        </el-form-item>

        <el-form-item :label="t('business.workflow.quickTemplate')">
          <el-select
            v-model="selectedTemplate"
            :placeholder="t('business.workflow.selectTemplate')"
            style="width: 100%"
            @change="applyTemplate"
          >
            <el-option :label="t('business.workflow.tplLinear')" value="linear" />
            <el-option :label="t('business.workflow.tplRag')" value="rag" />
            <el-option :label="t('business.workflow.tplCondition')" value="condition" />
          </el-select>
        </el-form-item>

        <el-form-item :label="t('business.workflow.graphJson')" prop="graphJson">
          <el-input
            v-model="wfForm.graphJson"
            type="textarea"
            :rows="12"
            class="code-editor"
            placeholder="{ nodes: [...], edges: [...] }"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <span class="dialog-footer">
          <el-button @click="wfDialogVisible = false">{{ t('modal.cancelButton') }}</el-button>
          <el-button type="primary" :loading="wfSubmitting" @click="submitWfForm">{{ t('modal.confirmButton') }}</el-button>
        </span>
      </template>
    </el-dialog>

    <!-- 调试试跑与全链路 Trace 抽屉 -->
    <WorkflowDebugDrawer ref="debugDrawerRef" />
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'Workflow' })
import { ref, nextTick } from 'vue'
import {
  listWorkflow,
  loadWorkflow,
  addWorkflow,
  updateWorkflow,
  delWorkflow,
  listWorkflowExecution,
  delWorkflowExecution
} from '../api'
import WorkflowDebugDrawer from '../components/WorkflowDebugDrawer.vue'
import { Pagination } from '@/components'
import { usePageParams } from '@/composables/usePageParams'
import modal from '@/utils/modal'
import { t } from '@/i18n'
import type { Workflow, WorkflowQuery, WorkflowExecution, WorkflowExecutionQuery } from '../types'
import type { FormInstance } from 'element-plus'

// --------------------------------- ref data ---------------------------------
const activeTab = ref('workflows')

const wfQueryRef = ref<FormInstance>()
const wfLoading = ref(false)
const wfList = ref<Workflow[]>([])
const wfTotal = ref(0)
const wfQuery = ref<WorkflowQuery>({
  pageNum: 1,
  pageSize: 10,
  name: undefined,
  status: -1
})

const execQueryRef = ref<FormInstance>()
const execLoading = ref(false)
const execList = ref<WorkflowExecution[]>([])
const execTotal = ref(0)
const execQuery = ref<WorkflowExecutionQuery>({
  pageNum: 1,
  pageSize: 10,
  workflowId: undefined,
  status: -1
})

const wfDialogVisible = ref(false)
const wfDialogTitle = ref('')
const wfSubmitting = ref(false)
const wfFormRef = ref<FormInstance>()
const selectedTemplate = ref('')
const debugDrawerRef = ref()

const wfForm = ref<Workflow>({
  name: '',
  description: '',
  status: 0,
  graphJson: ''
})

const wfRules = {
  name: [{ required: true, message: t('business.workflow.nameRequired'), trigger: 'blur' }],
  graphJson: [{ required: true, message: t('business.workflow.graphRequired'), trigger: 'blur' }]
}

// --------------------------------- fun ---------------------------------
function buildPresetTemplate(key: string) {
  if (key === 'linear') {
    return {
      nodes: [
        { id: 'start_1', name: t('business.workflow.tplNodeStart'), type: 'START', config: { fields: [{ name: 'query', type: 'STRING', required: true }] } },
        { id: 'llm_1', name: t('business.workflow.tplNodeLlm'), type: 'LLM', config: { userPrompt: t('business.workflow.tplLinearPrompt'), mockResponse: t('business.workflow.tplLinearMock') } },
        { id: 'end_1', name: t('business.workflow.tplNodeEnd'), type: 'END', config: { outputs: { answer: '{{llm_1.text}}' } } }
      ],
      edges: [
        { id: 'e1', source: 'start_1', target: 'llm_1' },
        { id: 'e2', source: 'llm_1', target: 'end_1' }
      ]
    }
  }
  if (key === 'rag') {
    return {
      nodes: [
        { id: 'start_1', name: t('business.workflow.tplNodeQuestion'), type: 'START', config: { fields: [{ name: 'question', type: 'STRING', required: true }] } },
        { id: 'retrieval_1', name: t('business.workflow.tplNodeRetrieval'), type: 'RETRIEVAL', config: { kbIds: [], query: '{{start_1.question}}', topK: 3, mockHits: [{ text: t('business.workflow.tplRagHit') }] } },
        { id: 'llm_1', name: t('business.workflow.tplNodeSummary'), type: 'LLM', config: { systemPrompt: t('business.workflow.tplRagSysPrompt'), userPrompt: '{{start_1.question}}', mockResponse: t('business.workflow.tplRagMock') } },
        { id: 'end_1', name: t('business.workflow.tplNodeAnswer'), type: 'END', config: { outputs: { answer: '{{llm_1.text}}', refCount: '{{retrieval_1.count}}' } } }
      ],
      edges: [
        { id: 'e1', source: 'start_1', target: 'retrieval_1' },
        { id: 'e2', source: 'retrieval_1', target: 'llm_1' },
        { id: 'e3', source: 'llm_1', target: 'end_1' }
      ]
    }
  }
  if (key === 'condition') {
    return {
      nodes: [
        { id: 'start_1', name: t('business.workflow.tplNodeStartSimple'), type: 'START', config: { fields: [{ name: 'type', type: 'STRING', required: true }] } },
        { id: 'cond_1', name: t('business.workflow.tplNodeCondition'), type: 'CONDITION', config: { conditions: [{ left: '{{start_1.type}}', operator: 'EQUALS', right: 'VIP', targetHandle: 'vip_branch' }], defaultHandle: 'normal_branch' } },
        { id: 'llm_vip', name: t('business.workflow.tplNodeVip'), type: 'LLM', config: { userPrompt: t('business.workflow.tplVipPrompt'), mockResponse: t('business.workflow.tplVipMock') } },
        { id: 'llm_normal', name: t('business.workflow.tplNodeNormal'), type: 'LLM', config: { userPrompt: t('business.workflow.tplNormalPrompt'), mockResponse: t('business.workflow.tplNormalMock') } },
        { id: 'end_1', name: t('business.workflow.tplNodeEnd'), type: 'END', config: { outputs: { reply: '{{llm_vip.text}}{{llm_normal.text}}' } } }
      ],
      edges: [
        { id: 'e1', source: 'start_1', target: 'cond_1' },
        { id: 'e2', source: 'cond_1', target: 'llm_vip', sourceHandle: 'vip_branch' },
        { id: 'e3', source: 'cond_1', target: 'llm_normal', sourceHandle: 'normal_branch' },
        { id: 'e4', source: 'llm_vip', target: 'end_1' },
        { id: 'e5', source: 'llm_normal', target: 'end_1' }
      ]
    }
  }
  return null
}

function getWfList() {
  wfLoading.value = true
  const params = usePageParams(wfQuery)()
  listWorkflow(params)
    .then((res) => {
      wfList.value = res.data.data
      wfTotal.value = res.data.total
    })
    .finally(() => {
      wfLoading.value = false
    })
}

function handleWfQuery() {
  wfQuery.value.pageNum = 1
  getWfList()
}

function resetWfQuery() {
  wfQuery.value = { pageNum: 1, pageSize: 10, name: undefined, status: -1 }
  getWfList()
}

function handleAddWorkflow() {
  const tpl = buildPresetTemplate('linear')
  wfForm.value = {
    id: undefined,
    name: '',
    description: '',
    status: 0,
    graphJson: tpl ? JSON.stringify(tpl, null, 2) : ''
  }
  selectedTemplate.value = 'linear'
  wfDialogTitle.value = t('business.workflow.addTitle')
  wfDialogVisible.value = true
  nextTick(() => wfFormRef.value?.clearValidate())
}

function handleEditWorkflow(row: Workflow) {
  loadWorkflow(row.id!).then((res) => {
    wfForm.value = { ...res.data }
    selectedTemplate.value = ''
    try {
      wfForm.value.graphJson = JSON.stringify(JSON.parse(res.data.graphJson), null, 2)
    } catch {
      wfForm.value.graphJson = res.data.graphJson
    }
    wfDialogTitle.value = t('business.workflow.editTitle')
    wfDialogVisible.value = true
    nextTick(() => wfFormRef.value?.clearValidate())
  })
}

function applyTemplate(key: string) {
  const tpl = buildPresetTemplate(key)
  if (tpl) {
    wfForm.value.graphJson = JSON.stringify(tpl, null, 2)
  }
}

function submitWfForm() {
  wfFormRef.value?.validate((valid) => {
    if (!valid) return
    try {
      JSON.parse(wfForm.value.graphJson)
    } catch {
      modal.msgError(t('business.workflow.graphJsonInvalid'))
      return
    }

    wfSubmitting.value = true
    const action = wfForm.value.id ? updateWorkflow(wfForm.value) : addWorkflow(wfForm.value)
    action
      .then(() => {
        modal.msgSuccess(t('common.saveSuccess'))
        wfDialogVisible.value = false
        getWfList()
      })
      .finally(() => {
        wfSubmitting.value = false
      })
  })
}

function handleDeleteWorkflow(row: Workflow) {
  modal.confirm(t('business.workflow.confirmDeleteWorkflow', [row.name]))
    .then(() => delWorkflow(row.id!))
    .then(() => {
      modal.msgSuccess(t('common.deleteSuccess'))
      getWfList()
    })
    .catch(() => {})
}

function handleDebug(row: Workflow) {
  debugDrawerRef.value?.open(row)
}

function handleViewExecutions(row: Workflow) {
  execQuery.value.workflowId = row.id
  execQuery.value.pageNum = 1
  activeTab.value = 'executions'
  getExecList()
}

function getExecList() {
  execLoading.value = true
  const params = usePageParams(execQuery)()
  listWorkflowExecution(params)
    .then((res) => {
      execList.value = res.data.data
      execTotal.value = res.data.total
    })
    .finally(() => {
      execLoading.value = false
    })
}

function handleExecQuery() {
  execQuery.value.pageNum = 1
  getExecList()
}

function resetExecQuery() {
  execQuery.value = { pageNum: 1, pageSize: 10, workflowId: undefined, status: -1 }
  getExecList()
}

function handleViewExecutionDetail(row: WorkflowExecution) {
  debugDrawerRef.value?.open(
    { id: row.workflowId, name: t('business.workflow.workflowItemName', [row.workflowId]) } as Workflow,
    row.id
  )
}

function handleDeleteExecution(row: WorkflowExecution) {
  modal.confirm(t('business.workflow.confirmDeleteExecution', [row.id]))
    .then(() => delWorkflowExecution(row.id!))
    .then(() => {
      modal.msgSuccess(t('common.deleteSuccess'))
      getExecList()
    })
    .catch(() => {})
}

// --------------------------------- page init ---------------------------------
getWfList()
getExecList()
</script>

<style scoped>
.mb8 {
  margin-bottom: 8px;
}

.code-editor {
  font-family: 'JetBrains Mono', Consolas, Monaco, monospace;
  font-size: 13px;
}
</style>
