package com.xxl.ai.api.business.harness.workflow;

import com.xxl.ai.api.business.harness.workflow.handler.*;
import com.xxl.ai.api.business.harness.workflow.model.WorkflowEdge;
import com.xxl.ai.api.business.harness.workflow.model.WorkflowExecutionTrace;
import com.xxl.ai.api.business.harness.workflow.model.WorkflowGraph;
import com.xxl.ai.api.business.harness.workflow.model.WorkflowNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工作流轻量 DAG 执行引擎测试用例
 *
 * @author xuxueli 2026-11-12
 */
public class WorkflowDagEngineTest {

    private WorkflowDagEngine engine;

    @BeforeEach
    public void setup() {
        engine = new WorkflowDagEngine();
        engine.registerHandler(new StartNodeHandler());
        engine.registerHandler(new LlmNodeHandler());
        engine.registerHandler(new RetrievalNodeHandler());
        engine.registerHandler(new ToolNodeHandler());
        engine.registerHandler(new ConditionNodeHandler());
        engine.registerHandler(new EndNodeHandler());
    }

    @Test
    @DisplayName("测试 1：线性工作流 (Start -> LLM -> End) 变量注入与终态产出")
    public void testLinearExecution() throws Exception {
        // 构建节点
        WorkflowNode start = new WorkflowNode("start_1", "开始", "START", Map.of(
                "fields", List.of(Map.of("name", "query", "required", true))
        ));

        WorkflowNode llm = new WorkflowNode("llm_1", "模型总结", "LLM", Map.of(
                "userPrompt", "用户提问是：{{start_1.query}}",
                "mockResponse", "关于[{{start_1.query}}]的回答：一切正常！"
        ));

        WorkflowNode end = new WorkflowNode("end_1", "结束输出", "END", Map.of(
                "outputs", Map.of("finalAnswer", "{{llm_1.text}}")
        ));

        // 连线
        WorkflowGraph graph = new WorkflowGraph(
                List.of(start, llm, end),
                List.of(
                        new WorkflowEdge("e1", "start_1", "llm_1"),
                        new WorkflowEdge("e2", "llm_1", "end_1")
                )
        );

        WorkflowContext ctx = new WorkflowContext();
        Map<String, Object> inputs = Map.of("query", "三体舰队什么时候到达？");

        WorkflowContext result = engine.execute(graph, inputs, ctx);

        assertNotNull(result);
        assertEquals(3, result.getTraces().size());
        assertEquals("SUCCESS", result.getTraces().get(0).getStatus());
        assertEquals("SUCCESS", result.getTraces().get(1).getStatus());
        assertEquals("SUCCESS", result.getTraces().get(2).getStatus());

        Map<String, Object> finalOutput = result.getFinalOutput();
        assertNotNull(finalOutput);
        assertEquals("关于[三体舰队什么时候到达？]的回答：一切正常！", finalOutput.get("finalAnswer"));
        assertTrue(result.getTotalTokens() > 0);
    }

    @Test
    @DisplayName("测试 2：RAG + LLM 组合工作流 (Start -> Retrieval -> LLM -> End)")
    public void testRetrievalAndLlm() throws Exception {
        WorkflowNode start = new WorkflowNode("start_1", "开始", "START", Collections.emptyMap());

        WorkflowNode retrieval = new WorkflowNode("retrieval_1", "知识检索", "RETRIEVAL", Map.of(
                "query", "{{start_1.question}}",
                "mockHits", List.of(
                        Map.of("text", "叶文洁在红岸基地向太阳发射了电波信号。"),
                        Map.of("text", "三体人接收到信号并派出第一舰队。")
                )
        ));

        WorkflowNode llm = new WorkflowNode("llm_1", "回答生成", "LLM", Map.of(
                "systemPrompt", "背景知识：\n{{retrieval_1.context}}",
                "userPrompt", "{{start_1.question}}",
                "mockResponse", "依据背景，红岸基地叶文洁向太阳发送了信号。"
        ));

        WorkflowNode end = new WorkflowNode("end_1", "结束输出", "END", Map.of(
                "outputs", Map.of(
                        "answer", "{{llm_1.text}}",
                        "refCount", "{{retrieval_1.count}}"
                )
        ));

        WorkflowGraph graph = new WorkflowGraph(
                List.of(start, retrieval, llm, end),
                List.of(
                        new WorkflowEdge("e1", "start_1", "retrieval_1"),
                        new WorkflowEdge("e2", "retrieval_1", "llm_1"),
                        new WorkflowEdge("e3", "llm_1", "end_1")
                )
        );

        WorkflowContext result = engine.execute(graph, Map.of("question", "叶文洁做了什么？"), new WorkflowContext());

        assertEquals(4, result.getTraces().size());
        assertEquals("依据背景，红岸基地叶文洁向太阳发送了信号。", result.getFinalOutput().get("answer"));
        assertEquals(2, result.getFinalOutput().get("refCount"));
    }

    @Test
    @DisplayName("测试 3：条件分支与路径剪枝 (Condition 分流到不同分支)")
    public void testConditionBranching() throws Exception {
        WorkflowNode start = new WorkflowNode("start_1", "开始", "START", Collections.emptyMap());

        WorkflowNode condition = new WorkflowNode("cond_1", "分支判断", "CONDITION", Map.of(
                "conditions", List.of(
                        Map.of(
                                "left", "{{start_1.type}}",
                                "operator", "EQUALS",
                                "right", "VIP",
                                "targetHandle", "branch_vip"
                        )
                ),
                "defaultHandle", "branch_normal"
        ));

        WorkflowNode llmVip = new WorkflowNode("llm_vip", "VIP响应", "LLM", Map.of(
                "userPrompt", "VIP请求",
                "mockResponse", "尊敬的VIP客户，为您优先处理！"
        ));

        WorkflowNode llmNormal = new WorkflowNode("llm_normal", "普通响应", "LLM", Map.of(
                "userPrompt", "普通请求",
                "mockResponse", "普通排队中..."
        ));

        WorkflowNode end = new WorkflowNode("end_1", "结束输出", "END", Map.of(
                "outputs", Map.of("reply", "{{llm_vip.text}}{{llm_normal.text}}")
        ));

        WorkflowGraph graph = new WorkflowGraph(
                List.of(start, condition, llmVip, llmNormal, end),
                List.of(
                        new WorkflowEdge("e1", "start_1", "cond_1"),
                        new WorkflowEdge("e2", "cond_1", "llm_vip", "branch_vip"),
                        new WorkflowEdge("e3", "cond_1", "llm_normal", "branch_normal"),
                        new WorkflowEdge("e4", "llm_vip", "end_1"),
                        new WorkflowEdge("e5", "llm_normal", "end_1")
                )
        );

        // 场景 A：命中 VIP 分支
        WorkflowContext resultVip = engine.execute(graph, Map.of("type", "VIP"), new WorkflowContext());
        assertEquals("尊敬的VIP客户，为您优先处理！", resultVip.getFinalOutput().get("reply"));

        // 验证 llm_normal 状态被标记为 SKIPPED
        boolean normalSkipped = resultVip.getTraces().stream()
                .anyMatch(t -> "llm_normal".equals(t.getNodeId()) && "SKIPPED".equals(t.getStatus()));
        assertTrue(normalSkipped, "普通分支应该被 SKIPPED 剪枝");

        // 场景 B：命中 普通分支
        WorkflowContext resultNormal = engine.execute(graph, Map.of("type", "NORMAL"), new WorkflowContext());
        assertEquals("普通排队中...", resultNormal.getFinalOutput().get("reply"));
        boolean vipSkipped = resultNormal.getTraces().stream()
                .anyMatch(t -> "llm_vip".equals(t.getNodeId()) && "SKIPPED".equals(t.getStatus()));
        assertTrue(vipSkipped, "VIP分支应该被 SKIPPED 剪枝");
    }

    @Test
    @DisplayName("测试 4：Kahn 算法有向环路检测与拒收拦截")
    public void testCycleDetection() {
        WorkflowNode start = new WorkflowNode("start_1", "开始", "START", Collections.emptyMap());
        WorkflowNode nodeA = new WorkflowNode("node_a", "节点A", "LLM", Collections.emptyMap());
        WorkflowNode nodeB = new WorkflowNode("node_b", "节点B", "LLM", Collections.emptyMap());
        WorkflowNode end = new WorkflowNode("end_1", "结束", "END", Collections.emptyMap());

        // A -> B -> A 形成死循环
        WorkflowGraph cycleGraph = new WorkflowGraph(
                List.of(start, nodeA, nodeB, end),
                List.of(
                        new WorkflowEdge("e1", "start_1", "node_a"),
                        new WorkflowEdge("e2", "node_a", "node_b"),
                        new WorkflowEdge("e3", "node_b", "node_a"),
                        new WorkflowEdge("e4", "node_b", "end_1")
                )
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            engine.validateGraph(cycleGraph);
        });

        assertTrue(ex.getMessage().contains("有向环路 (Cycle Detected)"));
    }
}
