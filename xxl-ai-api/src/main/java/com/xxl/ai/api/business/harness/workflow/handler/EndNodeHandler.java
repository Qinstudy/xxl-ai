package com.xxl.ai.api.business.harness.workflow.handler;

import com.xxl.ai.api.business.harness.workflow.WorkflowContext;
import com.xxl.ai.api.business.harness.workflow.WorkflowNodeHandler;
import com.xxl.ai.api.business.harness.workflow.WorkflowVariableResolver;
import com.xxl.ai.api.business.harness.workflow.model.WorkflowNode;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * END 结束节点处理器：负责工作流最终输出聚合与执行终结
 *
 * @author xuxueli 2026-11-12
 */
@Component
public class EndNodeHandler implements WorkflowNodeHandler {

    @Override
    public String getNodeType() {
        return "END";
    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(WorkflowNode node, WorkflowContext ctx) throws Exception {
        Map<String, Object> config = node.getConfig() != null ? node.getConfig() : Collections.emptyMap();

        Map<String, Object> rawOutputs = (Map<String, Object>) config.getOrDefault("outputs", Collections.emptyMap());
        if (rawOutputs.isEmpty() && config.containsKey("outputMapping")) {
            rawOutputs = (Map<String, Object>) config.get("outputMapping");
        }

        Map<String, Object> finalOutputs = new LinkedHashMap<>();
        if (!rawOutputs.isEmpty()) {
            Map<String, Object> resolved = (Map<String, Object>) WorkflowVariableResolver.resolveObject(rawOutputs, ctx);
            if (resolved != null) {
                finalOutputs.putAll(resolved);
            }
        } else {
            // 若未配置映射，默认提取上下文中最后一个节点的输出
            for (Map.Entry<String, Map<String, Object>> entry : ctx.getNodeOutputs().entrySet()) {
                if (!entry.getKey().equalsIgnoreCase("start")) {
                    finalOutputs.putAll(entry.getValue());
                }
            }
        }

        ctx.setFinalOutput(finalOutputs);
        ctx.setTerminated(true);
        ctx.setNodeOutput(node.getId(), finalOutputs);
    }
}
