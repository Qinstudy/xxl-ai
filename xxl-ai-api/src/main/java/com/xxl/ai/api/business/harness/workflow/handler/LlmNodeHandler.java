package com.xxl.ai.api.business.harness.workflow.handler;

import com.xxl.ai.api.business.harness.llm.LlmChatTool;
import com.xxl.ai.api.business.harness.workflow.WorkflowContext;
import com.xxl.ai.api.business.harness.workflow.WorkflowNodeHandler;
import com.xxl.ai.api.business.harness.workflow.WorkflowVariableResolver;
import com.xxl.ai.api.business.harness.workflow.model.WorkflowNode;
import com.xxl.ai.api.business.supplier.model.SupplierRuntime;
import com.xxl.tool.core.StringTool;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * LLM 模型节点处理器：负责 Prompt 模板变量插值与大模型单轮推理
 *
 * @author xuxueli 2026-11-12
 */
@Component
public class LlmNodeHandler implements WorkflowNodeHandler {

    @Resource
    private LlmChatTool llmChatTool;

    @Override
    public String getNodeType() {
        return "LLM";
    }

    @Override
    public void execute(WorkflowNode node, WorkflowContext ctx) throws Exception {
        Map<String, Object> config = node.getConfig() != null ? node.getConfig() : Collections.emptyMap();

        Long supplierId = parseLong(config.get("supplierId"));
        Long modelId = parseLong(config.get("modelId"));
        String systemPromptTpl = (String) config.getOrDefault("systemPrompt", "");
        String userPromptTpl = (String) config.getOrDefault("userPrompt", "");

        // 1、解析 Prompt 模板变量插值
        String resolvedSysPrompt = WorkflowVariableResolver.resolveString(systemPromptTpl, ctx);
        String resolvedUserPrompt = WorkflowVariableResolver.resolveString(userPromptTpl, ctx);

        if (StringTool.isBlank(resolvedUserPrompt)) {
            throw new IllegalArgumentException("LLM 节点用户提示词解析为空, nodeId=" + node.getId());
        }

        // 2、支持单元测试或 Mock 模式
        if (config.containsKey("mockResponse")) {
            String mockText = String.valueOf(config.get("mockResponse"));
            mockText = WorkflowVariableResolver.resolveString(mockText, ctx);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("text", mockText);
            out.put("reasoning", "");
            out.put("tokens", 50);
            ctx.setNodeOutput(node.getId(), out);
            ctx.addTokens(50);
            return;
        }

        // 3、获取模型运行时
        SupplierRuntime runtime = null;
        if (ctx.getRuntimeProvider() != null && supplierId != null && modelId != null) {
            runtime = ctx.getRuntimeProvider().getSupplierRuntime(supplierId, modelId);
        }

        if (runtime == null) {
            throw new IllegalStateException("LLM 节点未解析到可用的模型运行时: supplierId=" + supplierId + ", modelId=" + modelId);
        }

        // 4、调用 LLM 生成
        String sessionId = "wf-" + ctx.getWorkflowId() + "-" + node.getId() + "-" + System.currentTimeMillis();
        LlmChatTool.ChatText chatText = llmChatTool.chat(
                runtime,
                resolvedSysPrompt,
                Collections.emptyList(),
                Collections.emptyList(),
                resolvedUserPrompt,
                new Object[0],
                Collections.emptyList(),
                sessionId,
                null,
                null
        );

        String text = chatText != null ? chatText.getContent() : "";
        String reasoning = chatText != null ? chatText.getThinking() : "";

        // 粗略估算 Token
        int tokens = Math.max(1, (resolvedSysPrompt.length() + resolvedUserPrompt.length() + text.length()) / 3);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("text", text);
        output.put("reasoning", reasoning);
        output.put("tokens", tokens);

        ctx.setNodeOutput(node.getId(), output);
        ctx.addTokens(tokens);
    }

    private Long parseLong(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(obj).trim());
        } catch (Exception e) {
            return null;
        }
    }
}
