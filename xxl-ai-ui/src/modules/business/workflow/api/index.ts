/**
 * 工作流编排（Workflow）API 封装
 */
import { request } from '@/utils/request'
import type { Response, PageModel } from '@/types'
import type {
  Workflow,
  WorkflowListQuery,
  WorkflowExecution,
  WorkflowExecutionListQuery
} from '../types'

// ==================== 1. 工作流定义 API ====================

/** 分页查询工作流定义列表 */
export function listWorkflow(query: WorkflowListQuery): Promise<Response<PageModel<Workflow>>> {
  return request({ url: '/workflow/pageList', method: 'get', params: query })
}

/** 查询单个工作流详情 */
export function loadWorkflow(id: number): Promise<Response<Workflow>> {
  return request({ url: '/workflow/load', method: 'get', params: { id } })
}

/** 新增工作流定义 */
export function addWorkflow(data: Workflow): Promise<Response<string>> {
  return request({ url: '/workflow/insert', method: 'post', data })
}

/** 修改工作流定义 */
export function updateWorkflow(data: Workflow): Promise<Response<string>> {
  return request({ url: '/workflow/update', method: 'post', data })
}

/** 批量删除工作流定义 */
export function delWorkflow(ids: number | number[]): Promise<Response<string>> {
  return request({ url: '/workflow/delete', method: 'post', data: Array.isArray(ids) ? ids : [ids] })
}

/** 更新工作流启用/发布状态 */
export function updateWorkflowStatus(id: number, status: number): Promise<Response<string>> {
  return request({ url: '/workflow/updateStatus', method: 'post', params: { id, status } })
}

/** 调试试跑单次工作流 */
export function runWorkflow(id: number, inputs?: Record<string, any>): Promise<Response<WorkflowExecution>> {
  return request({ url: '/workflow/run', method: 'post', params: { id }, data: inputs || {} })
}

// ==================== 2. 工作流执行记录 API ====================

/** 分页查询工作流执行记录 */
export function listWorkflowExecution(query: WorkflowExecutionListQuery): Promise<Response<PageModel<WorkflowExecution>>> {
  return request({ url: '/workflow/execution/pageList', method: 'get', params: query })
}

/** 查询单个执行记录详情（含逐节点链路快照） */
export function loadWorkflowExecution(id: number): Promise<Response<WorkflowExecution>> {
  return request({ url: '/workflow/execution/load', method: 'get', params: { id } })
}

/** 批量删除执行记录 */
export function delWorkflowExecution(ids: number | number[]): Promise<Response<string>> {
  return request({ url: '/workflow/execution/delete', method: 'post', data: Array.isArray(ids) ? ids : [ids] })
}
