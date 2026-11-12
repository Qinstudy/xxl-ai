package com.xxl.ai.api.business.harness.workflow;

import com.xxl.ai.api.business.harness.workflow.model.WorkflowNode;

/**
 * 工作流节点执行处理器契约接口
 *
 * @author xuxueli 2026-11-12
 */
public interface WorkflowNodeHandler {

    /**
     * 节点类型标识（大写，如 START, LLM, RETRIEVAL, TOOL, CONDITION, END）
     */
    String getNodeType();

    /**
     * 执行具体节点逻辑并向上下文沉淀结果
     *
     * @param node 当前节点信息与专有配置
     * @param ctx  执行上下文
     * @throws Exception 节点执行异常
     */
    void execute(WorkflowNode node, WorkflowContext ctx) throws Exception;
}
