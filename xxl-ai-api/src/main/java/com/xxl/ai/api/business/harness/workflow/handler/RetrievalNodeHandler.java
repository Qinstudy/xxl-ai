package com.xxl.ai.api.business.harness.workflow.handler;

import com.xxl.ai.api.business.harness.rag.RagTool;
import com.xxl.ai.api.business.harness.workflow.WorkflowContext;
import com.xxl.ai.api.business.harness.workflow.WorkflowNodeHandler;
import com.xxl.ai.api.business.harness.workflow.WorkflowVariableResolver;
import com.xxl.ai.api.business.harness.workflow.model.WorkflowNode;
import com.xxl.ai.api.business.knowledge.model.entity.KnowledgeBase;
import com.xxl.tool.core.StringTool;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * RETRIEVAL 知识检索节点处理器：负责知识库多路召回与多库聚合排序
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

        Set<Long> kbIdSet = parseKbIds(config);

        String queryTpl = config.get("query") != null ? String.valueOf(config.get("query")) : "";
        String resolvedQuery = WorkflowVariableResolver.resolveString(queryTpl, ctx);

        if (StringTool.isBlank(resolvedQuery)) {
            throw new IllegalArgumentException("RETRIEVAL 节点检索词解析为空, nodeId=" + node.getId());
        }

        int topK = parseInt(config.get("topK"), 3);
        if (topK <= 0) {
            topK = 3;
        }
        Double customThreshold = parseDouble(config.get("similarityThreshold"));

        // 1、Mock 仅在未配置任何真实知识库 ID 且允许调试 Mock 时生效
        if (kbIdSet.isEmpty()) {
            if (ctx.isAllowMock() && config.containsKey("mockHits") && config.get("mockHits") != null) {
                List<Map<String, Object>> mockHits = (List<Map<String, Object>>) config.get("mockHits");
                populateOutput(node.getId(), mockHits, ctx);
                return;
            }
            throw new IllegalStateException("RETRIEVAL 节点未配置有效的知识库 ID (kbId/kbIds), nodeId=" + node.getId());
        }

        if (ctx.getRuntimeProvider() == null) {
            throw new IllegalStateException("RETRIEVAL 节点缺少运行时提供者 (runtimeProvider), nodeId=" + node.getId());
        }
        if (ragTool == null) {
            throw new IllegalStateException("RagTool 未初始化，无法执行知识库检索");
        }

        // 2、逐库校验空间归属并聚合检索结果
        List<Map<String, Object>> mergedHits = new ArrayList<>();
        for (Long kbId : kbIdSet) {
            KnowledgeBase kb = ctx.getRuntimeProvider().getKnowledgeBase(kbId);
            if (kb == null) {
                throw new IllegalStateException("RETRIEVAL 节点关联的知识库不存在、已停用或无权访问: kbId=" + kbId);
            }

            KnowledgeBase searchTarget = kb;
            if (customThreshold != null && customThreshold >= 0.0d) {
                searchTarget = copyForThreshold(kb, customThreshold);
            }

            List<Map<String, Object>> hits = ragTool.search(searchTarget, resolvedQuery, topK);
            if (hits != null) {
                for (Map<String, Object> hit : hits) {
                    Map<String, Object> enriched = new LinkedHashMap<>(hit);
                    enriched.putIfAbsent("kbId", kb.getId());
                    enriched.putIfAbsent("kbName", kb.getName());
                    mergedHits.add(enriched);
                }
            }
        }

        // 3、多知识库场景按分数降序归并并截断至 Top-K
        if (kbIdSet.size() > 1 && mergedHits.size() > 1) {
            mergedHits.sort((a, b) -> Double.compare(parseScore(b.get("score")), parseScore(a.get("score"))));
            if (mergedHits.size() > topK) {
                mergedHits = new ArrayList<>(mergedHits.subList(0, topK));
            }
        }

        populateOutput(node.getId(), mergedHits, ctx);
    }

    private Set<Long> parseKbIds(Map<String, Object> config) {
        Set<Long> ids = new LinkedHashSet<>();
        Long singleId = parseLong(config.get("kbId"));
        if (singleId != null && singleId > 0) {
            ids.add(singleId);
        }
        Object kbIdsObj = config.get("kbIds");
        if (kbIdsObj instanceof List<?> list) {
            for (Object item : list) {
                Long id = parseLong(item);
                if (id != null && id > 0) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private KnowledgeBase copyForThreshold(KnowledgeBase src, double threshold) {
        KnowledgeBase copy = new KnowledgeBase();
        copy.setId(src.getId());
        copy.setSpaceId(src.getSpaceId());
        copy.setName(src.getName());
        copy.setEmbedSupplierId(src.getEmbedSupplierId());
        copy.setEmbedModelId(src.getEmbedModelId());
        copy.setChunkSize(src.getChunkSize());
        copy.setChunkOverlap(src.getChunkOverlap());
        copy.setTopK(src.getTopK());
        copy.setSimilarityThreshold(threshold);
        copy.setStatus(src.getStatus());
        return copy;
    }

    private void populateOutput(String nodeId, List<Map<String, Object>> hits, WorkflowContext ctx) {
        StringBuilder sb = new StringBuilder();
        List<Map<String, Object>> references = new ArrayList<>();

        if (hits != null) {
            for (int i = 0; i < hits.size(); i++) {
                Map<String, Object> hit = hits.get(i);
                Object textObj = hit.get("text");
                String text = textObj != null ? String.valueOf(textObj) : "";
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
            String s = String.valueOf(obj).trim();
            return s.isEmpty() ? null : Long.parseLong(s);
        } catch (Exception e) {
            return null;
        }
    }

    private int parseInt(Object obj, int defaultValue) {
        if (obj == null) return defaultValue;
        if (obj instanceof Number n) return n.intValue();
        try {
            String s = String.valueOf(obj).trim();
            return s.isEmpty() ? defaultValue : Integer.parseInt(s);
        } catch (Exception e) {
            return defaultValue;
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

    private double parseScore(Object obj) {
        Double d = parseDouble(obj);
        return d != null ? d : 0.0d;
    }
}
