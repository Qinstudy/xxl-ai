package com.xxl.ai.api.business.harness.workflow.handler;

import com.xxl.ai.api.business.harness.workflow.WorkflowContext;
import com.xxl.ai.api.business.harness.workflow.WorkflowNodeHandler;
import com.xxl.ai.api.business.harness.workflow.WorkflowVariableResolver;
import com.xxl.ai.api.business.harness.workflow.model.WorkflowNode;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * TOOL 工具执行节点处理器：负责 MCP 或 Skill 外部工具调用与结果沉淀
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

        String toolType = (String) config.getOrDefault("toolType", "MCP");
        String toolName = (String) config.getOrDefault("toolName", "");

        // 1、解析入参字典模板
        Map<String, Object> rawParams = (Map<String, Object>) config.getOrDefault("params", Collections.emptyMap());
        Map<String, Object> resolvedParams = (Map<String, Object>) WorkflowVariableResolver.resolveObject(rawParams, ctx);

        // 2、测试或 Mock 模式支持
        if (config.containsKey("mockResult")) {
            String mockResult = String.valueOf(config.get("mockResult"));
            mockResult = WorkflowVariableResolver.resolveString(mockResult, ctx);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("result", mockResult);
            out.put("success", true);
            ctx.setNodeOutput(node.getId(), out);
            return;
        }

        // 3、通过运行时提供者执行工具
        String result = "";
        if (ctx.getRuntimeProvider() != null) {
            result = ctx.getRuntimeProvider().executeTool(toolType, toolName, resolvedParams);
        } else {
            result = "未提供工具执行器，模拟完成: " + toolName;
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("result", result);
        output.put("success", true);

        ctx.setNodeOutput(node.getId(), output);
    }
}
