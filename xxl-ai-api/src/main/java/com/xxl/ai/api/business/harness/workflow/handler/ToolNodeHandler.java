package com.xxl.ai.api.business.harness.workflow.handler;

import com.xxl.ai.api.business.harness.workflow.WorkflowContext;
import com.xxl.ai.api.business.harness.workflow.WorkflowNodeHandler;
import com.xxl.ai.api.business.harness.workflow.WorkflowVariableResolver;
import com.xxl.ai.api.business.harness.workflow.model.WorkflowNode;
import com.xxl.tool.core.StringTool;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * TOOL 工具执行节点处理器：负责 MCP 外部工具真实调用与结果沉淀
 *
 * @author xuxueli 2026-11-12
 */
@Component
public class ToolNodeHandler implements WorkflowNodeHandler {

    @Override
    public String getNodeType() {
        return "TOOL";
    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(WorkflowNode node, WorkflowContext ctx) throws Exception {
        Map<String, Object> config = node.getConfig() != null ? node.getConfig() : Collections.emptyMap();

        String toolType = config.get("toolType") != null ? String.valueOf(config.get("toolType")) : "MCP";
        String toolName = config.get("toolName") != null ? String.valueOf(config.get("toolName")) : "";
        Long mcpId = parseLong(config.get("mcpId"));

        // 1、解析入参字典模板
        Object rawParamsObj = config.get("params");
        Map<String, Object> rawParams = rawParamsObj instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Collections.emptyMap();
        Map<String, Object> resolvedParams = (Map<String, Object>) WorkflowVariableResolver.resolveObject(rawParams, ctx);

        // 2、若显式绑定了真实 mcpId，强制执行真实工具；仅在未绑定服务ID且允许 Mock 时使用 mockResult
        boolean hasExplicitTarget = mcpId != null && mcpId > 0;
        if (!hasExplicitTarget && ctx.isAllowMock() && config.containsKey("mockResult") && config.get("mockResult") != null) {
            String mockResult = String.valueOf(config.get("mockResult"));
            mockResult = WorkflowVariableResolver.resolveString(mockResult, ctx);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("result", mockResult);
            out.put("success", true);
            ctx.setNodeOutput(node.getId(), out);
            return;
        }

        if (StringTool.isBlank(toolName)) {
            throw new IllegalArgumentException("TOOL 节点工具名称 (toolName) 不能为空, nodeId=" + node.getId());
        }
        if (ctx.getRuntimeProvider() == null) {
            throw new IllegalStateException("TOOL 节点未提供运行时工具执行器 (runtimeProvider), toolName=" + toolName);
        }

        // 3、通过运行时提供者执行真实工具
        String result = ctx.getRuntimeProvider().executeTool(toolType, mcpId, toolName.trim(), resolvedParams);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("result", result != null ? result : "");
        output.put("success", true);

        ctx.setNodeOutput(node.getId(), output);
    }

    private Long parseLong(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number n) return n.longValue();
        try {
            String s = String.valueOf(obj).trim();
            return s.isEmpty() ? null : Long.parseLong(s);
        } catch (Exception e) {
            return null;
        }
    }
}
