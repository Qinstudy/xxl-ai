<template>
  <el-drawer
    v-model="visible"
    :title="t('business.eval.experimentDetailTitle')"
    size="72%"
    :destroy-on-close="true"
  >
    <div v-loading="loading" class="experiment-detail-container">
      <!-- 实验概览卡片 -->
      <el-card shadow="never" class="overview-card mb16">
        <div class="header-info">
          <div class="title-row">
            <span class="exp-name">{{ experiment?.name }}</span>
            <el-tag :type="statusTagType(experiment?.status)">
              {{ statusLabel(experiment?.status) }}
            </el-tag>
            <el-tag effect="plain" type="info">{{ experiment?.evalType }}</el-tag>
          </div>
          <div class="meta-row">
            <span>{{ t('business.eval.target') }}: <b>{{ experiment?.targetName }}</b></span>
            <span>{{ t('business.eval.judgeModel') }}: <b>{{ experiment?.judgeModelName || t('business.eval.defaultModel') }}</b></span>
            <span>{{ t('business.eval.progress') }}: <b>{{ experiment?.completedCases }} / {{ experiment?.totalCases }}</b></span>
            <span>{{ t('business.eval.time') }}: {{ experiment?.addTime }}</span>
          </div>
        </div>

        <!-- 关键量化指标卡片阵列 -->
        <el-row :gutter="12" class="metrics-grid mt16">
          <el-col :span="4" v-if="experiment?.evalType === 'RAG'">
            <div class="metric-box">
              <div class="metric-label">{{ t('business.eval.metricHitRate3') }}</div>
              <div class="metric-value text-primary">{{ formatRate(summary?.metrics?.hitRateAt3 ?? experiment?.avgHitRate) }}</div>
              <div class="metric-sub">HitRate@1: {{ formatRate(summary?.metrics?.hitRateAt1) }}</div>
            </div>
          </el-col>
          <el-col :span="4" v-if="experiment?.evalType === 'RAG'">
            <div class="metric-box">
              <div class="metric-label">{{ t('business.eval.metricMrr') }}</div>
              <div class="metric-value text-success">{{ formatScore(summary?.metrics?.mrr ?? experiment?.avgMrr) }}</div>
              <div class="metric-sub">{{ t('business.eval.mrrDesc') }}</div>
            </div>
          </el-col>
          <el-col :span="4" v-if="experiment?.evalType === 'RAG'">
            <div class="metric-box">
              <div class="metric-label">{{ t('business.eval.metricFaithfulness') }}</div>
              <div class="metric-value text-warning">{{ formatScore(summary?.metrics?.faithfulness ?? experiment?.avgFaithfulness) }}</div>
              <div class="metric-sub">{{ t('business.eval.faithDesc') }}</div>
            </div>
          </el-col>
          <el-col :span="4">
            <div class="metric-box">
              <div class="metric-label">{{ t('business.eval.metricRelevance') }}</div>
              <div class="metric-value text-info">{{ formatScore(summary?.metrics?.relevance ?? experiment?.avgRelevance) }}</div>
              <div class="metric-sub">{{ t('business.eval.relevanceDesc') }}</div>
            </div>
          </el-col>
          <el-col :span="4" v-if="experiment?.evalType === 'AGENT'">
            <div class="metric-box">
              <div class="metric-label">{{ t('business.eval.metricGoalCompletion') }}</div>
              <div class="metric-value text-success">{{ formatScore(summary?.metrics?.goalCompletion ?? experiment?.avgGoalCompletion) }}</div>
              <div class="metric-sub">{{ t('business.eval.goalDesc') }}</div>
            </div>
          </el-col>
          <el-col :span="4">
            <div class="metric-box">
              <div class="metric-label">{{ t('business.eval.metricLatency') }}</div>
              <div class="metric-value">{{ (summary?.metrics?.avgLatencyMs ?? experiment?.avgLatencyMs) || 0 }}ms</div>
              <div class="metric-sub">Tokens: {{ (summary?.metrics?.totalTokens ?? experiment?.totalTokens) || 0 }}</div>
            </div>
          </el-col>
        </el-row>

        <!-- 失败/取消原因提示 -->
        <el-alert
          v-if="experiment?.errorMsg"
          type="error"
          :title="experiment.errorMsg"
          :closable="false"
          show-icon
          class="mt16"
        />

        <!-- 目标基线快照折叠提示 -->
        <div v-if="summary?.targetSnapshot" class="target-snapshot-alert mt16">
          <el-alert type="info" :closable="false" show-icon>
            <template #title>
              <span>{{ t('business.eval.snapshotTitle') }}: </span>
              <span v-if="summary.targetSnapshot.similarityThreshold !== undefined">
                similarityThreshold={{ summary.targetSnapshot.similarityThreshold }}, topK={{ summary.targetSnapshot.topK }}
              </span>
              <span v-if="summary.targetSnapshot.systemPrompt">
                systemPrompt: {{ summary.targetSnapshot.systemPrompt.substring(0, 50) }}...
              </span>
            </template>
          </el-alert>
        </div>
      </el-card>

      <!-- 用例列表明细 -->
      <el-card shadow="never" class="cases-card">
        <div class="cases-header">
          <span class="cases-title">{{ t('business.eval.casesTitle') }} ({{ casesList.length }})</span>
          <el-radio-group v-model="filterType" size="small">
            <el-radio-button value="ALL">{{ t('common.all') }}</el-radio-button>
            <el-radio-button value="HIT" v-if="experiment?.evalType === 'RAG'">{{ t('business.eval.hitOnly') }}</el-radio-button>
            <el-radio-button value="MISS" v-if="experiment?.evalType === 'RAG'">{{ t('business.eval.missOnly') }}</el-radio-button>
          </el-radio-group>
        </div>

        <el-collapse class="mt16" accordion>
          <el-collapse-item
            v-for="(c, idx) in filteredCases"
            :key="c.caseId || idx"
            :name="c.caseId || idx"
          >
            <template #title>
              <div class="case-item-title">
                <el-tag size="small" type="info" class="mr8">#{{ idx + 1 }}</el-tag>
                <el-tag
                  v-if="experiment?.evalType === 'RAG'"
                  size="small"
                  :type="c.hitRateAt3 && c.hitRateAt3 > 0 ? 'success' : 'danger'"
                  class="mr8"
                >
                  {{ c.hitRateAt3 && c.hitRateAt3 > 0 ? t('business.eval.hit') : t('business.eval.miss') }}
                </el-tag>
                <span class="query-preview">{{ c.query }}</span>
                <span class="case-meta-tags">
                  <span v-if="c.faithfulnessScore !== undefined" class="meta-tag">
                    {{ t('business.eval.faith') }}: {{ c.faithfulnessScore }}
                  </span>
                  <span v-if="c.relevanceScore !== undefined" class="meta-tag">
                    {{ t('business.eval.relevance') }}: {{ c.relevanceScore }}
                  </span>
                  <span class="meta-tag">{{ c.latencyMs }}ms</span>
                </span>
              </div>
            </template>

            <div class="case-detail-content">
              <!-- 问题与标准答案 -->
              <div class="detail-row">
                <div class="detail-label">{{ t('business.eval.question') }}:</div>
                <div class="detail-value font-bold">{{ c.query }}</div>
              </div>
              <div class="detail-row" v-if="c.groundTruth">
                <div class="detail-label">{{ t('business.eval.groundTruth') }}:</div>
                <div class="detail-value text-muted">{{ c.groundTruth }}</div>
              </div>
              <div class="detail-row mt12" v-if="c.error">
                <el-alert type="error" :title="c.error" :closable="false" show-icon />
              </div>

              <!-- 检索命中的切片 -->
              <div class="detail-row mt12" v-if="c.retrievedChunks && c.retrievedChunks.length">
                <div class="detail-label">{{ t('business.eval.retrievedChunks') }}:</div>
                <div class="detail-value chunks-list">
                  <div v-for="(chunk, cIdx) in c.retrievedChunks" :key="cIdx" class="chunk-card">
                    <div class="chunk-header">
                      <span class="chunk-doc">[{{ chunk.docName }}]</span>
                      <el-tag size="small" type="info">Score: {{ chunk.score }}</el-tag>
                    </div>
                    <div class="chunk-content">{{ chunk.content }}</div>
                  </div>
                </div>
              </div>

              <!-- 模型实际回答 -->
              <div class="detail-row mt12">
                <div class="detail-label">{{ t('business.eval.actualAnswer') }}:</div>
                <div class="detail-value actual-answer">{{ c.actualAnswer || t('business.eval.noAnswer') }}</div>
              </div>

              <!-- 裁判打分与理由 -->
              <div class="detail-row mt12 judge-box">
                <div class="judge-item" v-if="c.faithfulnessScore !== undefined">
                  <div class="judge-header">
                    <span>{{ t('business.eval.metricFaithfulness') }}: <b>{{ c.faithfulnessScore }}</b></span>
                  </div>
                  <div class="judge-reason">{{ c.faithfulnessReason }}</div>
                </div>
                <div class="judge-item" v-if="c.relevanceScore !== undefined">
                  <div class="judge-header">
                    <span>{{ t('business.eval.metricRelevance') }}: <b>{{ c.relevanceScore }}</b></span>
                  </div>
                  <div class="judge-reason">{{ c.relevanceReason }}</div>
                </div>
                <div class="judge-item" v-if="c.goalCompletionScore !== undefined">
                  <div class="judge-header">
                    <span>{{ t('business.eval.metricGoalCompletion') }}: <b>{{ c.goalCompletionScore }}</b></span>
                  </div>
                  <div class="judge-reason">{{ c.goalCompletionReason }}</div>
                </div>
              </div>
            </div>
          </el-collapse-item>
        </el-collapse>
      </el-card>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { t } from '@/i18n'
import type { EvalExperiment, ExperimentSummaryReport, CaseResult } from '../types'
import { loadEvalExperiment } from '../api'

const visible = ref(false)
const loading = ref(false)
const experiment = ref<EvalExperiment | null>(null)
const summary = ref<ExperimentSummaryReport | null>(null)
const filterType = ref<'ALL' | 'HIT' | 'MISS'>('ALL')

function open(id: number) {
  experiment.value = null
  summary.value = null
  filterType.value = 'ALL'
  visible.value = true
  loading.value = true
  loadEvalExperiment(id)
    .then((res) => {
      experiment.value = res.data
      if (res.data.summaryJson) {
        try {
          summary.value = JSON.parse(res.data.summaryJson)
        } catch {
          summary.value = null
        }
      } else {
        summary.value = null
      }
    })
    .finally(() => {
      loading.value = false
    })
}

const casesList = computed<CaseResult[]>(() => {
  return summary.value?.cases || []
})

const filteredCases = computed(() => {
  if (filterType.value === 'HIT') {
    return casesList.value.filter((c) => c.hitRateAt3 && c.hitRateAt3 > 0)
  }
  if (filterType.value === 'MISS') {
    return casesList.value.filter((c) => !c.hitRateAt3 || c.hitRateAt3 === 0)
  }
  return casesList.value
})

function formatRate(val?: number) {
  if (val === undefined || val === null) return '0.0%'
  return (val * 100).toFixed(1) + '%'
}

function formatScore(val?: number) {
  if (val === undefined || val === null) return '0.00'
  return Number(val).toFixed(2)
}

function statusTagType(status?: number): 'success' | 'warning' | 'danger' | 'info' {
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

defineExpose({ open })
</script>

<style scoped>
.experiment-detail-container {
  padding: 8px 16px;
}
.header-info .title-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.header-info .exp-name {
  font-size: 18px;
  font-weight: 600;
}
.meta-row {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
  margin-top: 8px;
  color: #606266;
  font-size: 13px;
}
.metric-box {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 12px;
  text-align: center;
}
.metric-label {
  font-size: 12px;
  color: #64748b;
  margin-bottom: 4px;
}
.metric-value {
  font-size: 22px;
  font-weight: 700;
  line-height: 1.2;
}
.metric-sub {
  font-size: 11px;
  color: #94a3b8;
  margin-top: 4px;
}
.text-primary { color: #3b82f6; }
.text-success { color: #10b981; }
.text-warning { color: #f59e0b; }
.text-info { color: #6366f1; }
.cases-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.cases-title {
  font-size: 15px;
  font-weight: 600;
}
.case-item-title {
  display: flex;
  align-items: center;
  width: 100%;
}
.query-preview {
  flex: 1;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.case-meta-tags {
  display: flex;
  gap: 12px;
  font-size: 12px;
  color: #909399;
  margin-right: 16px;
}
.case-detail-content {
  padding: 8px 12px;
  background: #fdfdfd;
  font-size: 13px;
}
.detail-row {
  margin-bottom: 8px;
}
.detail-label {
  font-weight: 600;
  color: #475569;
  margin-bottom: 2px;
}
.chunk-card {
  background: #f1f5f9;
  border-radius: 6px;
  padding: 8px 10px;
  margin-top: 6px;
}
.chunk-header {
  display: flex;
  justify-content: space-between;
  font-weight: 600;
  font-size: 12px;
  margin-bottom: 4px;
}
.chunk-content {
  font-size: 12px;
  color: #334155;
  white-space: pre-wrap;
}
.actual-answer {
  background: #eff6ff;
  border-left: 3px solid #3b82f6;
  padding: 8px 12px;
  border-radius: 4px;
  color: #1e3a8a;
}
.judge-box {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 12px;
  background: #fefce8;
  border: 1px solid #fef08a;
  border-radius: 6px;
  padding: 10px;
}
.judge-header {
  font-size: 12px;
  color: #854d0e;
  margin-bottom: 4px;
}
.judge-reason {
  font-size: 12px;
  color: #713f12;
}
.mr8 { margin-right: 8px; }
.mb16 { margin-bottom: 16px; }
.mt16 { margin-top: 16px; }
.mt12 { margin-top: 12px; }
</style>
