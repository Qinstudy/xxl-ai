/**
 * AI 评测体系（Eval）API 封装
 */
import { request } from '@/utils/request'
import type { Response, PageModel } from '@/types'
import type { EvalDataset, EvalDatasetListQuery, EvalExperiment, EvalExperimentListQuery } from '../types'

// ==================== 1. 评测数据集 API ====================

/** 分页查询评测集列表 */
export function listEvalDataset(query: EvalDatasetListQuery): Promise<Response<PageModel<EvalDataset>>> {
  return request({ url: '/eval/dataset/pageList', method: 'get', params: query })
}

/** 查询单个评测集详情 */
export function loadEvalDataset(id: number): Promise<Response<EvalDataset>> {
  return request({ url: '/eval/dataset/load', method: 'get', params: { id } })
}

/** 新增评测集 */
export function addEvalDataset(data: EvalDataset): Promise<Response<string>> {
  return request({ url: '/eval/dataset/insert', method: 'post', data })
}

/** 修改评测集 */
export function updateEvalDataset(data: EvalDataset): Promise<Response<string>> {
  return request({ url: '/eval/dataset/update', method: 'post', data })
}

/** 批量删除评测集 */
export function delEvalDataset(ids: number | number[]): Promise<Response<string>> {
  return request({ url: '/eval/dataset/delete', method: 'post', data: Array.isArray(ids) ? ids : [ids] })
}

/** 导入评测样本集 JSON */
export function importEvalDatasetJson(id: number, jsonContent: string): Promise<Response<string>> {
  return request({ url: '/eval/dataset/importJson', method: 'post', data: { id, jsonContent } })
}

// ==================== 2. 评测实验记录 API ====================

/** 分页查询评测实验列表 */
export function listEvalExperiment(query: EvalExperimentListQuery): Promise<Response<PageModel<EvalExperiment>>> {
  return request({ url: '/eval/experiment/pageList', method: 'get', params: query })
}

/** 查询实验详情（含汇总与逐用例结果） */
export function loadEvalExperiment(id: number): Promise<Response<EvalExperiment>> {
  return request({ url: '/eval/experiment/load', method: 'get', params: { id } })
}

/** 创建并启动评测实验 */
export function createAndRunExperiment(data: Partial<EvalExperiment>): Promise<Response<number>> {
  return request({ url: '/eval/experiment/createAndRun', method: 'post', data })
}

/** 取消正在运行的实验 */
export function cancelEvalExperiment(id: number): Promise<Response<string>> {
  return request({ url: '/eval/experiment/cancel', method: 'post', params: { id } })
}

/** 删除评测实验记录 */
export function delEvalExperiment(ids: number | number[]): Promise<Response<string>> {
  return request({ url: '/eval/experiment/delete', method: 'post', data: Array.isArray(ids) ? ids : [ids] })
}
