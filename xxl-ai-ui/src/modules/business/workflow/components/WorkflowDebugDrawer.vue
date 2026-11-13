<script setup lang="ts">
import { ref, computed } from 'vue'
import { runWorkflow, loadWorkflowExecution } from '../api'
import modal from '@/utils/modal'
import { t } from '@/i18n'
import type { Workflow, WorkflowExecution, WorkflowExecutionTrace } from '../types'

const visible = ref(false)
const running = ref(false)
const currentWorkflow = ref<Workflow | null>(null)
const executionResult = ref<WorkflowExecution | null>(null)

// 试跑入参 JSON
const inputJsonStr = ref('{\n  "query": "叶文洁在红岸基地做了什么？"\n}')

// 节点执行链路列表
const traceList = computed<WorkflowExecutionTrace[]>(() => {
  if (!executionResult.value?.executionJson) return []
  try {
    return JSON.parse(executionResult.value.executionJson)
  } catch {
    return []
  }
})

// 最终输出 JSON
const outputFormatted = computed(() => {
  if (!executionResult.value?.outputJson) return ''
  try {
    const obj = JSON.parse(executionResult.value.outputJson)
    return JSON.stringify(obj, null, 2)
  } catch {
    return executionResult.value.outputJson
  }
})

function open(workflow: Workflow, executionId?: number) {
  currentWorkflow.value = workflow
  executionResult.value = null
  visible.value = true

  if (executionId) {
    loadExecution(executionId)
  } else {
    // 提取开始节点声明的默认入参
    tryDefaultInputs(workflow)
  }
}

function tryDefaultInputs(workflow: Workflow) {
  try {
    if (!workflow.graphJson) return
    const graph = JSON.parse(workflow.graphJson)
    const startNode = graph.nodes?.find((n: any) => n.type === 'START')
    if (startNode?.config?.fields) {
      const sample: Record<string, any> = {}
      for (const f of startNode.config.fields) {
        sample[f.name] = f.default ?? (f.type === 'NUMBER' ? 1 : '测试内容')
      }
      inputJsonStr.value = JSON.stringify(sample, null, 2)
    }
  } catch {
    // 保留默认
  }
}

async function loadExecution(id: number) {
  try {
    const res = await loadWorkflowExecution(id)
    if (res.code === 200 && res.data) {
      executionResult.value = res.data
      if (res.data.inputJson) {
        try {
          inputJsonStr.value = JSON.stringify(JSON.parse(res.data.inputJson), null, 2)
        } catch {
          inputJsonStr.value = res.data.inputJson
        }
      }
    }
  } catch (err: any) {
    modal.msgError(err.message || '加载执行记录失败')
  }
}

async function handleRun() {
  if (!currentWorkflow.value?.id) return
  let inputs: Record<string, any> = {}
  if (inputJsonStr.value.trim()) {
    try {
      inputs = JSON.parse(inputJsonStr.value)
    } catch {
      modal.msgError(t('business.workflow.inputJsonInvalid'))
      return
    }
  }

  running.value = true
  executionResult.value = null

  try {
    const res = await runWorkflow(currentWorkflow.value.id, inputs)
    if (res.code === 200 && res.data) {
      executionResult.value = res.data
      modal.msgSuccess(t('business.workflow.runSuccess'))
    } else {
      modal.msgError(res.msg || t('business.workflow.runFailed'))
      if (res.data) {
        executionResult.value = res.data
      }
    }
  } catch (err: any) {
    modal.msgError(err.message || t('business.workflow.runFailed'))
  } finally {
    running.value = false
  }
}

function formatJson(obj: any) {
  if (!obj) return '-'
  try {
    return JSON.stringify(obj, null, 2)
  } catch {
    return String(obj)
  }
}

defineExpose({ open })
</script>

<template>
  <el-drawer
    v-model="visible"
    :title="t('business.workflow.debugTitle') + (currentWorkflow ? ' - ' + currentWorkflow.name : '')"
    size="65%"
    destroy-on-close
  >
    <div class="workflow-debug-container">
      <!-- 顶部操作栏与入参配置 -->
      <el-card shadow="never" class="section-card">
        <template #header>
          <div class="card-header">
            <span class="header-title">{{ t('business.workflow.inputParams') }}</span>
            <el-button
              type="primary"
              :loading="running"
              @click="handleRun"
            >
              {{ t('business.workflow.runDebug') }}
            </el-button>
          </div>
        </template>
        <el-input
          v-model="inputJsonStr"
          type="textarea"
          :rows="4"
          placeholder="{ ... }"
          class="code-editor"
        />
      </el-card>

      <!-- 执行指标汇总 -->
      <div v-if="executionResult" class="metrics-row">
        <el-card shadow="never" class="metric-card">
          <div class="metric-label">{{ t('business.workflow.execStatus') }}</div>
          <div class="metric-value">
            <el-tag v-if="executionResult.status === 1" type="success" size="large">{{ t('business.workflow.statusSuccess') }}</el-tag>
            <el-tag v-else-if="executionResult.status === 0" type="primary" size="large">{{ t('business.workflow.statusRunning') }}</el-tag>
            <el-tag v-else-if="executionResult.status === 3" type="warning" size="large">{{ t('business.workflow.statusTimeout') }}</el-tag>
            <el-tag v-else type="danger" size="large">{{ t('business.workflow.statusFailed') }}</el-tag>
          </div>
        </el-card>

        <el-card shadow="never" class="metric-card">
          <div class="metric-label">{{ t('business.workflow.duration') }}</div>
          <div class="metric-value text-blue">{{ executionResult.durationMs ?? 0 }} ms</div>
        </el-card>

        <el-card shadow="never" class="metric-card">
          <div class="metric-label">{{ t('business.workflow.tokens') }}</div>
          <div class="metric-value text-green">{{ executionResult.totalTokens ?? 0 }}</div>
        </el-card>

        <el-card shadow="never" class="metric-card">
          <div class="metric-label">{{ t('business.workflow.nodeCount') }}</div>
          <div class="metric-value text-purple">{{ traceList.length }}</div>
        </el-card>
      </div>

      <!-- 异常提示 -->
      <el-alert
        v-if="executionResult && (executionResult.status === 2 || executionResult.status === 3) && executionResult.errorMsg"
        type="error"
        :title="t('business.workflow.errorAlert')"
        :description="executionResult.errorMsg"
        show-icon
        :closable="false"
        style="margin-bottom: 16px;"
      />

      <!-- 最终输出结果 -->
      <el-card v-if="outputFormatted" shadow="never" class="section-card">
        <template #header>
          <span class="header-title">{{ t('business.workflow.finalOutput') }}</span>
        </template>
        <pre class="json-code-block">{{ outputFormatted }}</pre>
      </el-card>

      <!-- 节点逐步执行瀑布流与追踪快照 -->
      <el-card v-if="traceList.length > 0" shadow="never" class="section-card">
        <template #header>
          <span class="header-title">{{ t('business.workflow.nodeTraces') }}</span>
        </template>

        <el-timeline class="node-timeline">
          <el-timeline-item
            v-for="(trace, index) in traceList"
            :key="index"
            :type="trace.status === 'SUCCESS' ? 'success' : (trace.status === 'SKIPPED' ? 'info' : 'danger')"
            :timestamp="(trace.durationMs || 0) + ' ms' + (trace.tokens ? ' · ' + trace.tokens + ' tokens' : '')"
            placement="top"
          >
            <el-card shadow="hover" class="trace-item-card">
              <div class="trace-header">
                <div class="trace-title">
                  <span class="node-name">{{ trace.nodeName || trace.nodeId }}</span>
                  <el-tag size="small" effect="plain" class="node-tag">{{ trace.nodeType }}</el-tag>
                  <el-tag
                    size="small"
                    :type="trace.status === 'SUCCESS' ? 'success' : (trace.status === 'SKIPPED' ? 'info' : 'danger')"
                  >
                    {{ trace.status }}
                  </el-tag>
                </div>
              </div>

              <!-- 错误信息 -->
              <div v-if="trace.errorMsg" class="trace-error">
                <el-icon class="el-icon--left"><Warning /></el-icon>
                {{ trace.errorMsg }}
              </div>

              <!-- 折叠入参出参详情 -->
              <el-collapse class="trace-collapse">
                <el-collapse-item :title="t('business.workflow.viewNodeIO')" :name="index">
                  <div class="io-grid">
                    <div class="io-col">
                      <div class="io-label">{{ t('business.workflow.nodeInput') }}</div>
                      <pre class="json-mini-block">{{ formatJson(trace.inputSnapshot) }}</pre>
                    </div>
                    <div class="io-col">
                      <div class="io-label">{{ t('business.workflow.nodeOutput') }}</div>
                      <pre class="json-mini-block">{{ formatJson(trace.outputSnapshot) }}</pre>
                    </div>
                  </div>
                </el-collapse-item>
              </el-collapse>
            </el-card>
          </el-timeline-item>
        </el-timeline>
      </el-card>
    </div>
  </el-drawer>
</template>

<style scoped>
.workflow-debug-container {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.section-card {
  border-radius: 8px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-title {
  font-weight: 600;
  font-size: 15px;
}

.code-editor {
  font-family: 'JetBrains Mono', Consolas, Monaco, monospace;
  font-size: 13px;
}

.metrics-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}

.metric-card {
  border-radius: 8px;
  text-align: center;
}

.metric-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}

.metric-value {
  font-size: 18px;
  font-weight: 600;
}

.text-blue { color: #409eff; }
.text-green { color: #67c23a; }
.text-purple { color: #8e44ad; }

.json-code-block {
  background: #f8f9fa;
  padding: 12px;
  border-radius: 6px;
  font-family: monospace;
  font-size: 13px;
  max-height: 250px;
  overflow-y: auto;
  margin: 0;
}

.node-timeline {
  padding-left: 8px;
  margin-top: 12px;
}

.trace-item-card {
  border-radius: 6px;
}

.trace-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.trace-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.node-name {
  font-weight: 600;
  font-size: 14px;
}

.node-tag {
  font-weight: normal;
}

.trace-error {
  color: #f56c6c;
  font-size: 13px;
  background: #fef0f0;
  padding: 6px 10px;
  border-radius: 4px;
  margin-bottom: 8px;
}

.trace-collapse {
  border: none;
}

.io-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.io-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
}

.json-mini-block {
  background: #f5f7fa;
  padding: 8px;
  border-radius: 4px;
  font-family: monospace;
  font-size: 12px;
  max-height: 160px;
  overflow-y: auto;
  margin: 0;
}
</style>
