package com.xxl.ai.api.business.harness.chat;

import com.xxl.ai.api.business.agent.mapper.AgentMapper;
import com.xxl.ai.api.business.agent.model.adaptor.AgentAdaptor;
import com.xxl.ai.api.business.agent.model.dto.AgentDTO;
import com.xxl.ai.api.business.agent.model.entity.Agent;
import com.xxl.ai.api.business.harness.llm.LlmChatTool;
import com.xxl.ai.api.business.harness.mcp.McpToolFactory;
import com.xxl.ai.api.business.harness.rag.RagTool;
import com.xxl.ai.api.business.supplier.model.SupplierRuntime;
import com.xxl.ai.api.business.supplier.service.SupplierService;
import com.xxl.tool.response.Response;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SubAgents 多智能体协作体系演进单元测试（纯静态 / 纯动态 / 复合单门面协同 + 路由劫持防重造）
 *
 * @author xxl-ai 2026-11-10
 */
public class SubAgentToolTest {

    @Test
    void testAgentAdaptorSubAgentAndAutoDecompose() {
        AgentDTO dto = new AgentDTO();
        dto.setId(10L);
        dto.setName("主控编排智能体");
        dto.setSubAgentIds(Arrays.asList(101L, 102L, 103L));
        dto.setAutoDecomposeStatus(1);

        Agent agent = AgentAdaptor.adapt(dto);
        assertNotNull(agent);
        assertEquals("101,102,103", agent.getSubAgentIds());
        assertEquals(1, agent.getAutoDecomposeStatus());

        AgentDTO back = AgentAdaptor.adapt2dto(agent);
        assertNotNull(back);
        assertNotNull(back.getSubAgentIds());
        assertEquals(3, back.getSubAgentIds().size());
        assertEquals(101L, back.getSubAgentIds().get(0));
        assertEquals(102L, back.getSubAgentIds().get(1));
        assertEquals(103L, back.getSubAgentIds().get(2));
        assertEquals(1, back.getAutoDecomposeStatus());
    }

    @Test
    void testEmptySubAgentAndAutoDecomposeReturnEmptyTools() {
        SubAgentTool subAgentTool = new SubAgentTool();

        Agent parent = new Agent();
        parent.setId(1L);
        parent.setSpaceId(10L);
        parent.setSubAgentIds(null);
        parent.setAutoDecomposeStatus(0);

        List<Object> tools = subAgentTool.buildTools(parent, null);
        assertNotNull(tools);
        assertTrue(tools.isEmpty());

        parent.setSubAgentIds("   ");
        tools = subAgentTool.buildTools(parent, null);
        assertNotNull(tools);
        assertTrue(tools.isEmpty());
    }

    @Test
    void testDelegateSpecialistMountAndFiltering() throws Exception {
        SubAgentTool subAgentTool = new SubAgentTool();

        // 模拟 Agent 库数据
        Map<Long, Agent> mockDb = new HashMap<>();

        // 子 Agent 1：有效已发布、同空间
        Agent sub1 = new Agent();
        sub1.setId(101L);
        sub1.setName("SQL优化专家");
        sub1.setIntro("负责复杂慢SQL优化与索引建议");
        sub1.setSpaceId(10L);
        sub1.setPublishStatus(1);
        mockDb.put(101L, sub1);

        // 子 Agent 2：未发布（应该被过滤跳过）
        Agent sub2 = new Agent();
        sub2.setId(102L);
        sub2.setName("草稿智能体");
        sub2.setSpaceId(10L);
        sub2.setPublishStatus(0);
        mockDb.put(102L, sub2);

        // 子 Agent 3：跨空间（应该被过滤跳过）
        Agent sub3 = new Agent();
        sub3.setId(103L);
        sub3.setName("外部空间智能体");
        sub3.setSpaceId(999L);
        sub3.setPublishStatus(1);
        mockDb.put(103L, sub3);

        // Mock AgentMapper
        AgentMapper mockMapper = (AgentMapper) java.lang.reflect.Proxy.newProxyInstance(
                AgentMapper.class.getClassLoader(),
                new Class<?>[]{AgentMapper.class},
                (proxy, method, args) -> {
                    if ("load".equals(method.getName()) && args != null && args.length > 0) {
                        Long id = (Long) args[0];
                        return mockDb.get(id);
                    }
                    return null;
                }
        );

        Field mapperField = SubAgentTool.class.getDeclaredField("agentMapper");
        mapperField.setAccessible(true);
        mapperField.set(subAgentTool, mockMapper);

        // 主 Agent 纯静态挂载：自身(1L) + 有效(101L) + 未发布(102L) + 跨空间(103L) + 不存在(9999L)
        Agent parent = new Agent();
        parent.setId(1L);
        parent.setSpaceId(10L);
        parent.setSubAgentIds("1,101,102,103,9999");
        parent.setAutoDecomposeStatus(0);

        List<Object> tools = subAgentTool.buildTools(parent, null);
        assertNotNull(tools);
        // 纯静态模式仅挂载 delegate_specialist
        assertEquals(1, tools.size(), "纯静态模式应该挂载且仅挂载单一 delegate_specialist 批量委派工具");

        Object toolObj = tools.get(0);
        assertTrue(toolObj instanceof ToolCallback);
        ToolCallback callback = (ToolCallback) toolObj;
        assertNotNull(callback.getToolDefinition());
        assertEquals("delegate_specialist", callback.getToolDefinition().name());
        // 描述中应包含过滤后有效绑定的专家信息
        assertTrue(callback.getToolDefinition().description().contains("SQL优化专家"));
        assertTrue(callback.getToolDefinition().description().contains("负责复杂慢SQL优化与索引建议"));
        assertFalse(callback.getToolDefinition().description().contains("草稿智能体"));
        assertFalse(callback.getToolDefinition().description().contains("外部空间智能体"));
        // Schema 应包含 tasks 数组
        assertTrue(callback.getToolDefinition().inputSchema().contains("tasks"));
        assertTrue(callback.getToolDefinition().inputSchema().contains("agentId"));
    }

    @Test
    void testDecomposeAndSpawnMount() {
        SubAgentTool subAgentTool = new SubAgentTool();

        Agent parent = new Agent();
        parent.setId(2L);
        parent.setSpaceId(10L);
        parent.setSubAgentIds(null);
        parent.setAutoDecomposeStatus(1);

        List<Object> tools = subAgentTool.buildTools(parent, null);
        assertNotNull(tools);
        assertEquals(1, tools.size(), "纯动态开启 autoDecomposeStatus 时应挂载单一 decompose_and_spawn 工具");

        Object toolObj = tools.get(0);
        assertTrue(toolObj instanceof ToolCallback);
        ToolCallback callback = (ToolCallback) toolObj;
        assertEquals("decompose_and_spawn", callback.getToolDefinition().name());
        assertTrue(callback.getToolDefinition().inputSchema().contains("roleName"));
        assertTrue(callback.getToolDefinition().inputSchema().contains("systemInstruction"));
        assertTrue(callback.getToolDefinition().inputSchema().contains("taskPrompt"));
    }

    @Test
    void testDualModeMountSingleHybridFacade() throws Exception {
        SubAgentTool subAgentTool = new SubAgentTool();

        Map<Long, Agent> mockDb = new HashMap<>();
        Agent sub1 = new Agent();
        sub1.setId(101L);
        sub1.setName("SQL优化专家");
        sub1.setIntro("负责慢SQL与索引调优");
        sub1.setSpaceId(10L);
        sub1.setPublishStatus(1);
        mockDb.put(101L, sub1);

        AgentMapper mockMapper = (AgentMapper) java.lang.reflect.Proxy.newProxyInstance(
                AgentMapper.class.getClassLoader(),
                new Class<?>[]{AgentMapper.class},
                (proxy, method, args) -> {
                    if ("load".equals(method.getName()) && args != null && args.length > 0) {
                        return mockDb.get((Long) args[0]);
                    }
                    return null;
                }
        );

        Field mapperField = SubAgentTool.class.getDeclaredField("agentMapper");
        mapperField.setAccessible(true);
        mapperField.set(subAgentTool, mockMapper);

        Agent parent = new Agent();
        parent.setId(3L);
        parent.setSpaceId(10L);
        parent.setSubAgentIds("101");
        parent.setAutoDecomposeStatus(1);

        List<Object> tools = subAgentTool.buildTools(parent, null);
        assertNotNull(tools);
        // 复合双开时，按单门面自适应原则，必须暴露且仅暴露单一混合协同工具 dispatch_subagents，根除工具竞争与串行等待
        assertEquals(1, tools.size(), "双模复合时必须暴露且仅暴露 1 个混合协同门面工具 dispatch_subagents");

        ToolCallback callback = (ToolCallback) tools.get(0);
        assertEquals("dispatch_subagents", callback.getToolDefinition().name());
        assertTrue(callback.getToolDefinition().description().contains("SQL优化专家"));
        assertTrue(callback.getToolDefinition().inputSchema().contains("agentId"));
        assertTrue(callback.getToolDefinition().inputSchema().contains("roleName"));
    }

    @Test
    void testTaskInputParsing() {
        SubAgentTool subAgentTool = new SubAgentTool();

        // 1、测试 specialist tasks 数组解析
        Map<String, Object> specialistInput = Map.of("tasks", List.of(
                Map.of("agentId", 101, "task", "优化慢SQL"),
                Map.of("agentId", 102L, "task", "检查死锁")
        ));
        List<SubAgentTool.SpecialistTaskInput> tasks = subAgentTool.parseSpecialistTasks(specialistInput);
        assertNotNull(tasks);
        assertEquals(2, tasks.size());
        assertEquals(101L, tasks.get(0).getAgentId());
        assertEquals("优化慢SQL", tasks.get(0).getTask());
        assertEquals(102L, tasks.get(1).getAgentId());

        // 2、测试 decompose tasks 数组解析
        Map<String, Object> decomposeInput = Map.of("tasks", List.of(
                Map.of("roleName", "架构审计员", "systemInstruction", "你是一名资深架构师", "taskPrompt", "审计事务边界", "enableRag", false)
        ));
        List<SubAgentTool.DecomposeTaskInput> decTasks = subAgentTool.parseDecomposeTasks(decomposeInput);
        assertNotNull(decTasks);
        assertEquals(1, decTasks.size());
        assertEquals("架构审计员", decTasks.get(0).getRoleName());
        assertEquals("你是一名资深架构师", decTasks.get(0).getSystemInstruction());
        assertEquals("审计事务边界", decTasks.get(0).getTaskPrompt());
        assertEquals(false, decTasks.get(0).getEnableRag());

        // 3、测试 hybrid tasks 混合混编解析
        Map<String, Object> hybridInput = Map.of("tasks", List.of(
                Map.of("agentId", 101L, "task", "调优慢查询"),
                Map.of("roleName", "Redis秒杀架构师", "systemInstruction", "你精通缓存", "task", "设计预扣减", "enableRag", true)
        ));
        List<SubAgentTool.HybridTaskInput> hybridTasks = subAgentTool.parseHybridTasks(hybridInput);
        assertNotNull(hybridTasks);
        assertEquals(2, hybridTasks.size());
        assertEquals(101L, hybridTasks.get(0).getAgentId());
        assertEquals("调优慢查询", hybridTasks.get(0).getTask());
        assertNull(hybridTasks.get(1).getAgentId());
        assertEquals("Redis秒杀架构师", hybridTasks.get(1).getRoleName());
        assertEquals("设计预扣减", hybridTasks.get(1).getTask());
    }

    @Test
    void testSpecialistFirstRoutingAndHijack() {
        SubAgentTool subAgentTool = new SubAgentTool();

        Agent staticDba = new Agent();
        staticDba.setId(101L);
        staticDba.setName("SQL优化专家");
        staticDba.setIntro("精通MySQL慢查询与索引优化");

        Map<Long, Agent> specialistMap = Map.of(101L, staticDba);

        // Case 1: 显式填写有效 agentId -> 正常命中静态专家，无劫持
        SubAgentTool.HybridTaskInput directInput = new SubAgentTool.HybridTaskInput(
                101L, null, null, "分析慢SQL", true
        );
        SubAgentTool.SpecialistMatch match1 = subAgentTool.matchExistingSpecialist(directInput, specialistMap);
        assertNotNull(match1);
        assertEquals(101L, match1.getAgent().getId());
        assertFalse(match1.isHijacked(), "显式指定 agentId 不应标记为劫持纠偏");

        // Case 2: 未填 agentId，但捏造角色与静态专家核心词重叠（如 "SQL优化顾问"） -> 触发劫持纠偏至 staticDba
        SubAgentTool.HybridTaskInput redundantInput = new SubAgentTool.HybridTaskInput(
                null, "SQL优化顾问", "你是一名SQL大师", "帮我看看慢SQL", true
        );
        SubAgentTool.SpecialistMatch match2 = subAgentTool.matchExistingSpecialist(redundantInput, specialistMap);
        assertNotNull(match2, "重造相近角色必须被仲裁器拦截纠偏");
        assertEquals(101L, match2.getAgent().getId());
        assertTrue(match2.isHijacked(), "应标记为劫持重定向");

        // Case 3: 确属全新领域角色（如 "Kafka削峰架构师"） -> 不触发劫持，放行交由动态瞬态专家
        SubAgentTool.HybridTaskInput novelInput = new SubAgentTool.HybridTaskInput(
                null, "Kafka削峰架构师", "你精通消息队列", "设计高并发削峰", true
        );
        SubAgentTool.SpecialistMatch match3 = subAgentTool.matchExistingSpecialist(novelInput, specialistMap);
        assertNull(match3, "全新空白领域角色不应被误劫持");
    }

    @Test
    void testStopwordsAndBigramOverlap() {
        // 验证停用词过滤：剔除 "专家"、"顾问" 后均收敛为 "sql优化"
        assertEquals("sql优化", SubAgentTool.stripStopwords("SQL优化专家"));
        assertEquals("sql优化", SubAgentTool.stripStopwords("SQL优化顾问"));

        // 验证重叠度检测
        assertTrue(SubAgentTool.isRoleOverlapped("sql优化", "sql优化", "任务", "介绍"));
        assertFalse(SubAgentTool.isRoleOverlapped("kafka削峰", "sql优化", "任务", "介绍"));
    }

    @Test
    void testSubtaskTimeoutConfigFallbackAndOverride() {
        SubAgentTool tool = new SubAgentTool();
        assertEquals(180, tool.getSubtaskTimeoutSeconds(), "默认子任务超时应放宽至 180 秒");

        ReflectionTestUtils.setField(tool, "subtaskTimeoutSeconds", 0);
        assertEquals(180, tool.getSubtaskTimeoutSeconds(), "非正数配置应回退默认 180 秒");

        ReflectionTestUtils.setField(tool, "subtaskTimeoutSeconds", 240);
        assertEquals(240, tool.getSubtaskTimeoutSeconds());
    }

    @Test
    void testDelegateSpecialistTimeoutInterruptsWorkersAndPreservesNames() throws Exception {
        SubAgentTool subAgentTool = new SubAgentTool();
        ReflectionTestUtils.setField(subAgentTool, "subtaskTimeoutSeconds", 1);

        Agent sqlExpert = new Agent();
        sqlExpert.setId(6L);
        sqlExpert.setSpaceId(10L);
        sqlExpert.setName("SQL性能专家");
        sqlExpert.setPublishStatus(1);
        sqlExpert.setModelSupplierId(4L);
        sqlExpert.setModelId(9L);

        Agent secExpert = new Agent();
        secExpert.setId(7L);
        secExpert.setSpaceId(10L);
        secExpert.setName("代码安全审计专家");
        secExpert.setPublishStatus(1);
        secExpert.setModelSupplierId(4L);
        secExpert.setModelId(9L);

        Map<Long, Agent> agentDb = Map.of(6L, sqlExpert, 7L, secExpert);
        AgentMapper mockAgentMapper = (AgentMapper) java.lang.reflect.Proxy.newProxyInstance(
                AgentMapper.class.getClassLoader(),
                new Class<?>[]{AgentMapper.class},
                (proxy, method, args) -> "load".equals(method.getName()) && args != null && args.length > 0
                        ? agentDb.get((Long) args[0]) : null
        );

        SupplierRuntime runtime = new SupplierRuntime();
        runtime.setSupplierId(4L);
        runtime.setModelId(9L);
        runtime.setModelName("glm-5.3-flash");
        SupplierService mockSupplierService = (SupplierService) java.lang.reflect.Proxy.newProxyInstance(
                SupplierService.class.getClassLoader(),
                new Class<?>[]{SupplierService.class},
                (proxy, method, args) -> "loadRuntime".equals(method.getName()) ? Response.ofSuccess(runtime) : null
        );

        McpToolFactory stubMcpFactory = new McpToolFactory() {
            @Override
            public List<Object> buildTools(Agent agent) {
                return Collections.emptyList();
            }
        };

        CountDownLatch workerInterruptedLatch = new CountDownLatch(2);
        AtomicInteger sawCancelledSignal = new AtomicInteger(0);
        LlmChatTool slowCancelledLlm = new LlmChatTool() {
            @Override
            public ChatText chat(SupplierRuntime rt,
                                 String systemPrompt,
                                 List<String> roleList,
                                 List<String> contentList,
                                 String content,
                                 Object[] tools,
                                 List<Object> advisorObjects,
                                 String sessionId,
                                 ChatOptions chatOptions,
                                 Consumer<String> onThinking,
                                 Consumer<String> onContent,
                                 TraceCollector traceCollector,
                                 Consumer<String> onUsageEvent,
                                 BooleanSupplier cancelled) {
                try {
                    long deadline = System.currentTimeMillis() + 5000L;
                    while (System.currentTimeMillis() < deadline) {
                        if (cancelled != null && cancelled.getAsBoolean()) {
                            sawCancelledSignal.incrementAndGet();
                            break;
                        }
                        Thread.sleep(50L);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    if (cancelled != null && cancelled.getAsBoolean()) {
                        sawCancelledSignal.incrementAndGet();
                        workerInterruptedLatch.countDown();
                    }
                }
                throw new IllegalStateException("任务已超时中断");
            }
        };

        ReflectionTestUtils.setField(subAgentTool, "agentMapper", mockAgentMapper);
        ReflectionTestUtils.setField(subAgentTool, "supplierService", mockSupplierService);
        ReflectionTestUtils.setField(subAgentTool, "mcpToolFactory", stubMcpFactory);
        ReflectionTestUtils.setField(subAgentTool, "ragTool", new RagTool());
        ReflectionTestUtils.setField(subAgentTool, "llmChatTool", slowCancelledLlm);

        Agent parent = new Agent();
        parent.setId(8L);
        parent.setSpaceId(10L);
        parent.setName("架构总控调度员");
        parent.setSubAgentIds("6,7");
        parent.setAutoDecomposeStatus(0);

        TraceCollector collector = new TraceCollector(2001L, 74L, 8L, "worker-node-0");
        List<String> emittedEvents = Collections.synchronizedList(new ArrayList<>());
        List<Object> rawTools = subAgentTool.buildTools(
                parent,
                collector,
                new AtomicBoolean(false),
                (ev, data) -> {
                    if (ChatStreamTool.EVENT_TOOL.equals(ev)) {
                        emittedEvents.add(data);
                    }
                }
        );
        ToolCallback[] wrapped = ObservableToolCallback.wrapAll(
                List.of(new ObservableToolCallback.TaggedTool("SUBAGENT", rawTools.get(0))),
                collector,
                emittedEvents::add
        );

        String report = wrapped[0].call(
                "{\"tasks\":[{\"agentId\":6,\"task\":\"分析慢SQL\"},{\"agentId\":7,\"task\":\"审计SQL注入\"}]}"
        );

        // 1、确认后台子任务线程均收到超时取消/中断信号并退出，而非继续空跑
        assertTrue(workerInterruptedLatch.await(3, TimeUnit.SECONDS), "超时后子智能体工作线程必须被及时中断");
        assertTrue(sawCancelledSignal.get() >= 1);

        // 2、确认报告保留中文专家名（非缺失名字的“子智能体 (ID=6)”）且无漏写
        assertTrue(report.contains("【SQL性能专家 (ID=6)】"), "超时报告应保留专家名 SQL性能专家");
        assertTrue(report.contains("【代码安全审计专家 (ID=7)】"), "超时报告应保留专家名 代码安全审计专家");
        assertTrue(report.contains("超时"), "超时报告应包含超时提示");

        // 3、确认外层 ToolTrace 标为 ERROR 且携带 subTasks 明细，同时没有任何 RUNNING 残留 Span
        assertEquals(1, collector.getToolTraces().size());
        TraceCollector.ToolTrace trace = collector.getToolTraces().get(0);
        assertEquals("ERROR", trace.getStatus());
        assertNotNull(trace.getSubTasks());
        assertEquals(2, trace.getSubTasks().size());
        assertEquals("SQL性能专家", trace.getSubTasks().get(0).getRoleName());
        assertEquals("ERROR", trace.getSubTasks().get(0).getStatus());
        assertEquals("代码安全审计专家", trace.getSubTasks().get(1).getRoleName());
        assertEquals("ERROR", trace.getSubTasks().get(1).getStatus());

        assertTrue(collector.getSpans().stream().noneMatch(s -> "RUNNING".equals(s.getStatus())),
                "超时结束后不应存在仍处于 RUNNING 状态的 Span");
        assertFalse(emittedEvents.isEmpty());
        assertTrue(emittedEvents.get(emittedEvents.size() - 1).contains("ERROR"));
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Running SubAgentToolTest...");
        SubAgentToolTest test = new SubAgentToolTest();
        test.testAgentAdaptorSubAgentAndAutoDecompose();
        System.out.println("✓ testAgentAdaptorSubAgentAndAutoDecompose PASSED");
        test.testEmptySubAgentAndAutoDecomposeReturnEmptyTools();
        System.out.println("✓ testEmptySubAgentAndAutoDecomposeReturnEmptyTools PASSED");
        test.testDelegateSpecialistMountAndFiltering();
        System.out.println("✓ testDelegateSpecialistMountAndFiltering PASSED");
        test.testDecomposeAndSpawnMount();
        System.out.println("✓ testDecomposeAndSpawnMount PASSED");
        test.testDualModeMountSingleHybridFacade();
        System.out.println("✓ testDualModeMountSingleHybridFacade PASSED");
        test.testTaskInputParsing();
        System.out.println("✓ testTaskInputParsing PASSED");
        test.testSpecialistFirstRoutingAndHijack();
        System.out.println("✓ testSpecialistFirstRoutingAndHijack PASSED");
        test.testStopwordsAndBigramOverlap();
        System.out.println("✓ testStopwordsAndBigramOverlap PASSED");
        System.out.println("ALL SUBAGENT TESTS PASSED SUCCESSFULLY!");
    }

}
