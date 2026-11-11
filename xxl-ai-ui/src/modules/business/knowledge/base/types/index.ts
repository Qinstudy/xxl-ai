/**
 * 知识库 类型定义
 */
import type { ListQuery } from '@/types'

/** 知识库实体（对应后端 KnowledgeBase / KnowledgeBaseDTO） */
export interface KnowledgeBase {
  id?: number
  spaceId?: number
  /** 知识库名称 */
  name?: string
  /** 描述 */
  description?: string
  /** 向量化供应商ID */
  embedSupplierId?: number
  /** 向量化模型ID */
  embedModelId?: number
  /** 分片大小 */
  chunkSize?: number
  /** 分片重叠 */
  chunkOverlap?: number
  /** 检索数量 */
  topK?: number
  /** 相似度阈值 (0.00-1.00，默认 0.40) */
  similarityThreshold?: number
  /** 状态：0-正常、1-停用 */
  status?: number
  addTime?: string
  updateTime?: string
  [key: string]: unknown
}

/** 搜索栏表单查询参数 */
export interface KnowledgeBaseQuery {
  pageNum: number
  pageSize: number
  name?: string
  status: number
}

/** 列表接口请求参数 */
export type KnowledgeBaseListQuery = ListQuery<KnowledgeBaseQuery>

/** 检索命中项（多路召回 + RRF 融合打分透视） */
export interface KnowledgeHit {
  text: string
  docId: number
  docName?: string
  chunkIndex?: number
  score: number
  matchType?: 'HYBRID' | 'VECTOR' | 'BM25'
  vectorScore?: number
  bm25Score?: number
  bm25NormScore?: number
  rrfScore?: number
  qaChunk?: boolean
  threshold?: number
  passed?: boolean
  [key: string]: unknown
}