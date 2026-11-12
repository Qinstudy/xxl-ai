package com.xxl.ai.api.business.harness.workflow.handler;

import com.xxl.ai.api.business.harness.rag.RagTool;
import com.xxl.ai.api.business.harness.workflow.WorkflowContext;
import com.xxl.ai.api.business.harness.workflow.WorkflowNodeHandler;
import com.xxl.ai.api.business.harness.workflow.WorkflowVariableResolver;
import com.xxl.ai.api.business.harness.workflow.model.WorkflowNode;
import com.xxl.ai.api.business.knowledge.model.KnowledgeBase;
import com.xxl.tool.core.StringTool;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * RETRIEVAL 知识检索节点处理器：负责知识库多路召回与相关切片提取
 *
 * @author xuxueli 2026-11-12
 */
@Component
public class RetrievalNodeHandler implements WorkflowNodeHandler {

    @Resource
    private RagTool ragTool;

    @Override
    public String getNodeType() {
        return "RETRIEVAL";
    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(WorkflowNode node, WorkflowContext ctx) throws Exception {
        Map<String, Object> config = node.getConfig() != null ? node.getConfig() : Collections.emptyMap();

        Long kbId = parseLong(config.get("kbId"));
        if (kbId == null && config.containsKey("kbIds")) {
            Object kbIdsObj = config.get("kbIds");
            if (kbIdsObj instanceof List<?> list && !list.isEmpty()) {
                kbId = parseLong(list.get(0));
            }
        }

        String queryTpl = (String) config.getOrDefault("query", "");
        String resolvedQuery = WorkflowVariableResolver.resolveString(queryTpl, ctx);

        if (StringTool.isBlank(resolvedQuery)) {
            throw new IllegalArgumentException("RETRIEVAL 节点检索词解析为空, nodeId=" + node.getId());
        }

        int topK = config.containsKey("topK") ? ((Number) config.get("topK")).intValue() : 3;

        // 1、支持测试或 Mock 模式
        if (config.containsKey("mockHits")) {
            List<Map<String, Object>> mockHits = (List<Map<String, Object>>) config.get("mockHits");
            populateOutput(node.getId(), mockHits, ctx);
            return;
        }

        // 2、读取知识库实体
        KnowledgeBase kb = null;
        if (ctx.getRuntimeProvider() != null && kbId != null) {
            kb = ctx.getRuntimeProvider().getKnowledgeBase(kbId);
        }

        if (kb == null) {
            throw new IllegalStateException("RETRIEVAL 节点未找到有效的知识库配置: kbId=" + kbId);
        }

        // 3、执行检索
        List<Map<String, Object>> hits = ragTool.search(kb, resolvedQuery, topK);
        populateOutput(node.getId(), hits, ctx);
    }

    private void populateOutput(String nodeId, List<Map<String, Object>> hits, WorkflowContext ctx) {
        StringBuilder sb = new StringBuilder();
        List<Map<String, Object>> references = new ArrayList<>();

        if (hits != null) {
            for (int i = 0; i < hits.size(); i++) {
                Map<String, Object> hit = hits.get(i);
                String text = String.valueOf(hit.getOrDefault("text", ""));
                if (StringTool.isNotBlank(text)) {
                    sb.append("[").append(i + 1).append("] ").append(text.trim()).append("\n\n");
                }
                references.add(hit);
            }
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("context", sb.toString().trim());
        output.put("references", references);
        output.put("count", references.size());

        ctx.setNodeOutput(nodeId, output);
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
