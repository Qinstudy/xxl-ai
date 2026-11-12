import type { PageQuery } from '@/types'

/** 评测数据集 */
export interface EvalDataset {
  id?: number
  spaceId?: number
  name: string
  evalType: 'RAG' | 'AGENT'
  targetId: number
  targetName?: string
  sampleCount?: number
  contentJson?: string
  description?: string
  addTime?: string
  updateTime?: string
}

/** 评测数据集列表查询参数 */
export interface EvalDatasetListQuery extends PageQuery {
  name?: string
  evalType?: string
}

/** 评测实验记录 */
export interface EvalExperiment {
  id?: number
  spaceId?: number
  datasetId: number
  name: string
  evalType?: 'RAG' | 'AGENT'
  targetId?: number
  targetName?: string
  judgeModelId?: number
  judgeModelName?: string
  status?: number // 0-待运行, 1-运行中, 2-已完成, 3-失败, 4-已取消
  totalCases?: number
  completedCases?: number
  avgHitRate?: number
  avgMrr?: number
  avgFaithfulness?: number
  avgRelevance?: number
  avgGoalCompletion?: number
  avgLatencyMs?: number
  totalTokens?: number
  summaryJson?: string
  errorMsg?: string
  addTime?: string
  updateTime?: string
}

/** 评测实验列表查询参数 */
export interface EvalExperimentListQuery extends PageQuery {
  name?: string
  datasetId?: number
  status?: number
}

/** 单个测试用例定义 */
export interface EvalCase {
  id?: string
  query: string
  groundTruth?: string
  expectedDocNames?: string[]
  expectedKeywords?: string[]
  expectedTools?: string[]
}

/** 单个用例评测执行明细 */
export interface CaseResult {
  caseId: string
  query: string
  groundTruth?: string
  retrievedChunks?: Array<{
    docName: string
    content: string
    score: number
    chunkIndex?: number
  }>
  actualAnswer?: string
  hitRateAt1?: number
  hitRateAt3?: number
  reciprocalRank?: number
  faithfulnessScore?: number
  faithfulnessReason?: string
  relevanceScore?: number
  relevanceReason?: string
  goalCompletionScore?: number
  goalCompletionReason?: string
  latencyMs?: number
  tokens?: number
  error?: string
}

/** 实验汇总快照与明细结构 */
export interface ExperimentSummaryReport {
  targetSnapshot?: {
    targetId?: number
    targetName?: string
    evalType?: string
    similarityThreshold?: number
    topK?: number
    systemPrompt?: string
    temperature?: number
  }
  metrics?: {
    hitRateAt1: number
    hitRateAt3: number
    mrr: number
    faithfulness: number
    relevance: number
    goalCompletion: number
    avgLatencyMs: number
    totalTokens: number
  }
  cases?: CaseResult[]
}
