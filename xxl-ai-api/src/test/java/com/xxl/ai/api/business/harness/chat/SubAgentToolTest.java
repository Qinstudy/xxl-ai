package com.xxl.ai.api.business.harness.chat;

import com.xxl.ai.api.business.agent.mapper.AgentMapper;
import com.xxl.ai.api.business.agent.model.adaptor.AgentAdaptor;
import com.xxl.ai.api.business.agent.model.dto.AgentDTO;
import com.xxl.ai.api.business.agent.model.entity.Agent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * [FEAT-013] SubAgents 多智能体协作工具与适配测试
 *
 * @author xxl-ai 2026-11-10
 */
public class SubAgentToolTest {

    @Test
    void testAgentAdaptorSubAgentIds() {
        AgentDTO dto = new AgentDTO();
        dto.setId(10L);
        dto.setName("主Agent");
        dto.setSubAgentIds(Arrays.asList(101L, 102L, 103L));

        Agent agent = AgentAdaptor.adapt(dto);
        assertNotNull(agent);
        assertEquals("101,102,103", agent.getSubAgentIds());

        AgentDTO back = AgentAdaptor.adapt2dto(agent);
        assertNotNull(back);
        assertNotNull(back.getSubAgentIds());
        assertEquals(3, back.getSubAgentIds().size());
        assertEquals(101L, back.getSubAgentIds().get(0));
        assertEquals(102L, back.getSubAgentIds().get(1));
        assertEquals(103L, back.getSubAgentIds().get(2));
    }

    @Test
    void testEmptySubAgentIdsReturnEmptyTools() {
        SubAgentTool subAgentTool = new SubAgentTool();

        Agent parent = new Agent();
        parent.setId(1L);
        parent.setSpaceId(10L);
        parent.setSubAgentIds(null);

        List<Object> tools = subAgentTool.buildTools(parent, null);
        assertNotNull(tools);
        assertTrue(tools.isEmpty());

        parent.setSubAgentIds("   ");
        tools = subAgentTool.buildTools(parent, null);
        assertNotNull(tools);
        assertTrue(tools.isEmpty());
    }

    @Test
    void testSubAgentBuildFilteringRules() throws Exception {
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

        // 主 Agent 尝试挂载：自身(1L) + 有效(101L) + 未发布(102L) + 跨空间(103L) + 不存在(9999L)
        Agent parent = new Agent();
        parent.setId(1L);
        parent.setSpaceId(10L);
        parent.setSubAgentIds("1,101,102,103,9999");

        List<Object> tools = subAgentTool.buildTools(parent, null);
        assertNotNull(tools);
        // 仅 sub1(101L) 应该被成功挂载！
        assertEquals(1, tools.size(), "只有同空间、已发布、非自身的子 Agent 可以被成功挂载为工具");

        Object toolObj = tools.get(0);
        assertTrue(toolObj instanceof ToolCallback);
        ToolCallback callback = (ToolCallback) toolObj;
        assertNotNull(callback.getToolDefinition());
        assertEquals("subagent_101", callback.getToolDefinition().name());
        assertTrue(callback.getToolDefinition().description().contains("SQL优化专家"));
        assertTrue(callback.getToolDefinition().description().contains("负责复杂慢SQL优化与索引建议"));
        assertTrue(callback.getToolDefinition().inputSchema().contains("task"));
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Running SubAgentToolTest...");
        SubAgentToolTest test = new SubAgentToolTest();
        test.testAgentAdaptorSubAgentIds();
        System.out.println("✓ testAgentAdaptorSubAgentIds PASSED");
        test.testEmptySubAgentIdsReturnEmptyTools();
        System.out.println("✓ testEmptySubAgentIdsReturnEmptyTools PASSED");
        test.testSubAgentBuildFilteringRules();
        System.out.println("✓ testSubAgentBuildFilteringRules PASSED");
        System.out.println("ALL SUBAGENT TESTS PASSED SUCCESSFULLY!");
    }

}
