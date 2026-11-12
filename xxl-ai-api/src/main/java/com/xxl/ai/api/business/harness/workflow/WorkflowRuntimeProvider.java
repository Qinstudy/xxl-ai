package com.xxl.ai.api.business.harness.workflow;

import com.xxl.ai.api.business.knowledge.model.KnowledgeBase;
import com.xxl.ai.api.business.supplier.model.SupplierRuntime;

import java.util.Map;

/**
 * 工作流运行时依赖提供者接口（解耦 harness 与 business，防止反向依赖 Spring 循环死锁）
 *
 * @author xuxueli 2026-11-12
 */
public interface WorkflowRuntimeProvider {

    /**
     * 获取指定供应商与模型的运行时配置
     */
    SupplierRuntime getSupplierRuntime(Long supplierId, Long modelId);

    /**
     * 获取指定知识库实体
     */
    KnowledgeBase getKnowledgeBase(Long kbId);

    /**
     * 执行指定工具（MCP / SKILL）
     */
    String executeTool(String toolType, String toolName, Map<String, Object> params);
}
