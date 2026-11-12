package com.xxl.ai.api.business.harness.workflow.handler;

import com.xxl.ai.api.business.harness.workflow.WorkflowContext;
import com.xxl.ai.api.business.harness.workflow.WorkflowNodeHandler;
import com.xxl.ai.api.business.harness.workflow.model.WorkflowNode;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * START 开始节点处理器：负责工作流外部入参校验与上下文装配
 *
 * @author xuxueli 2026-11-12
 */
@Component
public class StartNodeHandler implements WorkflowNodeHandler {

    @Override
    public String getNodeType() {
        return "START";
    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(WorkflowNode node, WorkflowContext ctx) throws Exception {
        Map<String, Object> config = node.getConfig() != null ? node.getConfig() : Collections.emptyMap();
        Map<String, Object> inputs = ctx.getInputs() != null ? ctx.getInputs() : Collections.emptyMap();

        // 校验入参 Schema（若配置了字段定义）
        Object fieldsObj = config.get("fields");
        if (fieldsObj instanceof List<?> fieldList) {
            for (Object field : fieldList) {
                if (field instanceof Map<?, ?> fMap) {
                    String name = String.valueOf(fMap.get("name"));
                    boolean required = Boolean.parseBoolean(String.valueOf(fMap.get("required")));
                    if (required && (!inputs.containsKey(name) || inputs.get(name) == null || String.valueOf(inputs.get(name)).trim().isEmpty())) {
                        throw new IllegalArgumentException("工作流缺少必填入参: " + name);
                    }
                }
            }
        }

        // 将 inputs 全部作为当前节点的输出沉淀
        Map<String, Object> output = new LinkedHashMap<>(inputs);
        ctx.setNodeOutput(node.getId(), output);
        // 兼容默认 alias
        ctx.setNodeOutput("start", output);
    }
}
