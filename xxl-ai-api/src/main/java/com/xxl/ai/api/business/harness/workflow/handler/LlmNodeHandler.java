package com.xxl.ai.api.business.harness.workflow.handler;

import com.xxl.ai.api.business.harness.chat.TraceCollector;
import com.xxl.ai.api.business.harness.llm.LlmChatTool;
import com.xxl.ai.api.business.harness.workflow.WorkflowContext;
import com.xxl.ai.api.business.harness.workflow.WorkflowNodeHandler;
import com.xxl.ai.api.business.harness.workflow.WorkflowVariableResolver;
import com.xxl.ai.api.business.harness.workflow.model.WorkflowNode;
import com.xxl.ai.api.business.supplier.model.SupplierRuntime;
import com.xxl.tool.core.StringTool;
import jakarta.annotation.Resource;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * LLM 模型节点处理器：负责 Prompt 模板变量插值、推理超参数注入与大模型单轮推理
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
        String systemPromptTpl = config.get("systemPrompt") != null ? String.valueOf(config.get("systemPrompt")) : "";
        String userPromptTpl = config.get("userPrompt") != null ? String.valueOf(config.get("userPrompt")) : "";

        // 1、解析 Prompt 模板变量插值
        String resolvedSysPrompt = WorkflowVariableResolver.resolveString(systemPromptTpl, ctx);
        String resolvedUserPrompt = WorkflowVariableResolver.resolveString(userPromptTpl, ctx);

        if (StringTool.isBlank(resolvedUserPrompt)) {
            throw new IllegalArgumentException("LLM 节点用户提示词解析为空, nodeId=" + node.getId());
        }

        // 2、Mock 仅在未绑定真实模型且允许调试 Mock 时生效，杜绝真实模型被模板残留 mockResponse 劫持
        boolean hasRealModel = supplierId != null && supplierId > 0 && modelId != null && modelId > 0;
        if (!hasRealModel) {
            if (ctx.isAllowMock() && config.containsKey("mockResponse") && config.get("mockResponse") != null) {
                String mockText = String.valueOf(config.get("mockResponse"));
                mockText = WorkflowVariableResolver.resolveString(mockText, ctx);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("text", mockText);
                out.put("reasoning", "");
                out.put("tokens", 50);
                out.put("estimated", true);
                ctx.setNodeOutput(node.getId(), out);
                ctx.addTokens(50);
                return;
            }
            throw new IllegalStateException("LLM 节点未配置有效的模型 (supplierId/modelId), nodeId=" + node.getId());
        }

        // 3、获取模型运行时
        SupplierRuntime runtime = ctx.getRuntimeProvider() != null
                ? ctx.getRuntimeProvider().getSupplierRuntime(supplierId, modelId)
                : null;

        if (runtime == null) {
            throw new IllegalStateException("LLM 节点未解析到可用的模型运行时: supplierId=" + supplierId + ", modelId=" + modelId);
        }
        if (llmChatTool == null) {
            throw new IllegalStateException("LlmChatTool 未初始化，无法执行真实模型调用");
        }

        // 4、装配动态推理超参数（temperature / topP / maxTokens）
        OpenAiChatOptions chatOptions = buildChatOptions(config, runtime.getModelName());

        // 5、注入轻量 TraceCollector 采集真实模型 Token Usage
        long executionId = ctx.getExecutionId() != null ? ctx.getExecutionId() : 0L;
        long workflowId = ctx.getWorkflowId() != null ? ctx.getWorkflowId() : 0L;
        TraceCollector traceCollector = new TraceCollector(executionId, workflowId, 0L, "workflow-" + node.getId());
        traceCollector.bindModel(supplierId, runtime.getSupplierName() != null ? runtime.getSupplierName() : "",
                modelId, runtime.getModelName() != null ? runtime.getModelName() : "");

        String sessionId = "wf-" + workflowId + "-" + node.getId() + "-" + System.currentTimeMillis();
        LlmChatTool.ChatText chatText = llmChatTool.chat(
                runtime,
                resolvedSysPrompt,
                Collections.emptyList(),
                Collections.emptyList(),
                resolvedUserPrompt,
                new Object[0],
                Collections.emptyList(),
                sessionId,
                chatOptions,
                null,
                null,
                traceCollector,
                null
        );

        String text = chatText != null && chatText.getContent() != null ? chatText.getContent() : "";
        String reasoning = chatText != null && chatText.getThinking() != null ? chatText.getThinking() : "";

        // 优先取底层模型返回的真实 Usage，缺失时再回退字符估算
        int promptTokens = traceCollector.getTokenUsage().getPromptTokens();
        int completionTokens = traceCollector.getTokenUsage().getCompletionTokens();
        int totalTokens = traceCollector.getTokenUsage().getTotalTokens();
        boolean estimated = false;
        if (totalTokens <= 0) {
            estimated = true;
            totalTokens = Math.max(1, (resolvedSysPrompt.length() + resolvedUserPrompt.length() + text.length()) / 3);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("text", text);
        output.put("reasoning", reasoning);
        output.put("tokens", totalTokens);
        output.put("promptTokens", promptTokens);
        output.put("completionTokens", completionTokens);
        output.put("estimated", estimated);

        ctx.setNodeOutput(node.getId(), output);
        ctx.addTokens(totalTokens);
    }

    private OpenAiChatOptions buildChatOptions(Map<String, Object> config, String modelName) {
        Double temperature = parseDouble(config.get("temperature"));
        Double topP = parseDouble(config.get("topP"));
        Integer maxTokens = parseInteger(config.get("maxTokens"));
        if (temperature == null && topP == null && (maxTokens == null || maxTokens <= 0)) {
            return null;
        }
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
        if (StringTool.isNotBlank(modelName)) {
            builder.model(modelName.trim());
        }
        if (temperature != null) {
            builder.temperature(temperature);
        }
        if (topP != null) {
            builder.topP(topP);
        }
        if (maxTokens != null && maxTokens > 0) {
            builder.maxTokens(maxTokens);
        }
        return builder.build();
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

    private Double parseDouble(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number n) return n.doubleValue();
        try {
            String s = String.valueOf(obj).trim();
            return s.isEmpty() ? null : Double.parseDouble(s);
        } catch (Exception e) {
            return null;
        }
    }

    private Integer parseInteger(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number n) return n.intValue();
        try {
            String s = String.valueOf(obj).trim();
            return s.isEmpty() ? null : Integer.parseInt(s);
        } catch (Exception e) {
            return null;
        }
    }
}
