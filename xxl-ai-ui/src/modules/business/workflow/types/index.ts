import type { PageQuery } from '@/types'

/** 工作流定义 */
export interface Workflow {
  id?: number
  spaceId?: number
  name: string
  description?: string
  status?: number // 0-草稿/正常, 1-已发布, 2-已停用
  graphJson: string
  extJson?: string
  nodeCount?: number
  addTime?: string
  updateTime?: string
}

/** 工作流查询参数 */
export interface WorkflowListQuery extends PageQuery {
  name?: string
  status?: number
}

/** 工作流节点定义 */
export interface WorkflowNode {
  id: string
  name: string
  type: 'START' | 'LLM' | 'RETRIEVAL' | 'TOOL' | 'CONDITION' | 'END'
  config?: Record<string, any>
  position?: { x: number; y: number }
}

/** 工作流连线定义 */
export interface WorkflowEdge {
  id: string
  source: string
  target: string
  sourceHandle?: string
  targetHandle?: string
}

/** 工作流 DAG 图结构 */
export interface WorkflowGraph {
  nodes: WorkflowNode[]
  edges: WorkflowEdge[]
}

/** 工作流单节点执行追踪快照 */
export interface WorkflowExecutionTrace {
  nodeId: string
  nodeName: string
  nodeType: string
  status: 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED'
  durationMs: number
  tokens: number
  inputSnapshot?: Record<string, any>
  outputSnapshot?: Record<string, any>
  errorMsg?: string
}

/** 工作流执行记录 */
export interface WorkflowExecution {
  id?: number
  spaceId?: number
  workflowId: number
  triggerType?: string
  status?: number // 0-运行中, 1-成功, 2-失败, 3-超时中断, 4-已取消
  totalTokens?: number
  durationMs?: number
  inputJson?: string
  outputJson?: string
  executionJson?: string
  errorMsg?: string
  addTime?: string
  updateTime?: string
}

/** 执行记录查询参数 */
export interface WorkflowExecutionListQuery extends PageQuery {
  workflowId?: number
  status?: number
}
