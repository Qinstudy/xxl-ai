package com.xxl.ai.api.business.harness.workflow.handler;

import com.xxl.ai.api.business.harness.workflow.WorkflowContext;
import com.xxl.ai.api.business.harness.workflow.WorkflowNodeHandler;
import com.xxl.ai.api.business.harness.workflow.WorkflowVariableResolver;
import com.xxl.ai.api.business.harness.workflow.model.WorkflowNode;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * CONDITION 条件分支节点处理器：负责声明式断言评估与下游激活分支选择
 *
 * @author xuxueli 2026-11-12
 */
@Component
public class ConditionNodeHandler implements WorkflowNodeHandler {

    @Override
    public String getNodeType() {
        return "CONDITION";
    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(WorkflowNode node, WorkflowContext ctx) throws Exception {
        Map<String, Object> config = node.getConfig() != null ? node.getConfig() : Collections.emptyMap();

        List<Map<String, Object>> conditionList = (List<Map<String, Object>>) config.get("conditions");
        String defaultHandle = (String) config.getOrDefault("defaultHandle", "default");

        String selectedBranch = defaultHandle;

        if (conditionList != null) {
            for (Map<String, Object> rule : conditionList) {
                Object rawLeft = rule.get("left");
                String operator = (String) rule.get("operator");
                Object rawRight = rule.get("right");
                String targetHandle = (String) rule.get("targetHandle");

                // 解析左右操作数变量
                Object resolvedLeft = WorkflowVariableResolver.resolveObject(rawLeft, ctx);
                Object resolvedRight = WorkflowVariableResolver.resolveObject(rawRight, ctx);

                boolean matched = WorkflowVariableResolver.evaluateCondition(resolvedLeft, operator, resolvedRight);
                if (matched) {
                    selectedBranch = targetHandle;
                    break;
                }
            }
        }

        // 记录选中的分支输出端口
        ctx.recordBranchSelection(node.getId(), selectedBranch);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("selectedBranch", selectedBranch);
        ctx.setNodeOutput(node.getId(), output);
    }
}
