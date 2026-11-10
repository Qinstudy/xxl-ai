package com.xxl.ai.api.business.harness.chat;

import com.xxl.ai.api.business.agent.model.adaptor.AgentAdaptor;
import com.xxl.ai.api.business.agent.model.dto.AgentDTO;
import com.xxl.ai.api.business.agent.model.entity.Agent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;

import static org.junit.jupiter.api.Assertions.*;

/**
 * [FEAT-006] 大模型推理超参数动态配置 (Options) 单元测试
 *
 * @author xxl-ai 2026-11-10
 */
public class ChatAgentOptionsTest {

    @Test
    void testAgentAdaptorOptionsMapping() {
        AgentDTO dto = new AgentDTO();
        dto.setId(100L);
        dto.setName("测试助手");
        dto.setTemperature(0.7);
        dto.setTopP(0.9);
        dto.setMaxTokens(2048);

        Agent agent = AgentAdaptor.adapt(dto);
        assertNotNull(agent);
        assertEquals(0.7, agent.getTemperature());
        assertEquals(0.9, agent.getTopP());
        assertEquals(2048, agent.getMaxTokens());

        AgentDTO converted = AgentAdaptor.adapt2dto(agent);
        assertNotNull(converted);
        assertEquals(0.7, converted.getTemperature());
        assertEquals(0.9, converted.getTopP());
        assertEquals(2048, converted.getMaxTokens());
    }

    @Test
    void testAgentAdaptorNullOptionsFallback() {
        AgentDTO dto = new AgentDTO();
        dto.setId(101L);
        dto.setName("默认助手");

        Agent agent = AgentAdaptor.adapt(dto);
        assertNotNull(agent);
        assertNull(agent.getTemperature());
        assertNull(agent.getTopP());
        assertNull(agent.getMaxTokens());

        AgentDTO converted = AgentAdaptor.adapt2dto(agent);
        assertNotNull(converted);
        assertNull(converted.getTemperature());
        assertNull(converted.getTopP());
        assertNull(converted.getMaxTokens());
    }

    @Test
    void testBuildOpenAiChatOptions() {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
        builder.temperature(0.5);
        builder.topP(0.85);
        builder.maxTokens(4096);

        OpenAiChatOptions options = builder.build();
        assertNotNull(options);
        assertEquals(0.5, options.getTemperature());
        assertEquals(0.85, options.getTopP());
        assertEquals(4096, options.getMaxTokens());

        // 验证 mutate 支持
        assertNotNull(options.mutate());
    }

    public static void main(String[] args) {
        ChatAgentOptionsTest test = new ChatAgentOptionsTest();
        test.testAgentAdaptorOptionsMapping();
        test.testAgentAdaptorNullOptionsFallback();
        test.testBuildOpenAiChatOptions();
        System.out.println("ALL AGENT OPTIONS TESTS PASSED!");
    }
}
