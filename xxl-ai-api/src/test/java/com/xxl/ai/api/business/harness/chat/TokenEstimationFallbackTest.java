package com.xxl.ai.api.business.harness.chat;

import com.xxl.ai.api.business.session.constant.SessionConstant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [LLM-002] 流式中断/异常时 Token 本地估算兜底单元测试
 *
 * @author xxl-ai 2026-11-10
 */
public class TokenEstimationFallbackTest {

    private TraceCollector createCollector() {
        return new TraceCollector(888L, 99L, 10L, "worker-test-0");
    }

    @Test
    @DisplayName("1. 验证尾帧缺失（totalTokens=0）时触发本地估算兜底并打标 estimated=true")
    public void testEstimatedUsageRecordedWhenTotalZero() {
        TraceCollector collector = createCollector();

        // 初始状态 totalTokens 为 0，estimated 为 false
        assertEquals(0, collector.getTokenUsage().getTotalTokens());
        assertFalse(collector.getTokenUsage().isEstimated());

        // 模拟流中断或异常，调用估算兜底
        collector.recordEstimatedUsageIfZero(240, 60);

        assertEquals(240, collector.getTokenUsage().getPromptTokens());
        assertEquals(60, collector.getTokenUsage().getCompletionTokens());
        assertEquals(300, collector.getTokenUsage().getTotalTokens());
        assertTrue(collector.getTokenUsage().isEstimated());
        assertEquals(1, collector.getTokenUsage().getRounds());

        // 再次调用兜底估算，不应覆盖已有数据
        collector.recordEstimatedUsageIfZero(999, 999);
        assertEquals(300, collector.getTokenUsage().getTotalTokens());
        assertEquals(240, collector.getTokenUsage().getPromptTokens());
    }

    @Test
    @DisplayName("2. 验证模型已返回精确 Usage 时，估算兜底绝不覆盖精确统计且保持 estimated=false")
    public void testExactUsageNeverOverwrittenByEstimation() {
        TraceCollector collector = createCollector();

        // 模拟正常流式尾帧上报真实 Usage
        collector.addTokenUsage(180, 45, 225);

        assertEquals(225, collector.getTokenUsage().getTotalTokens());
        assertFalse(collector.getTokenUsage().isEstimated());
        assertEquals(1, collector.getTokenUsage().getRounds());

        // 模拟收尾阶段触发兜底预检：由于已有精确统计，不应发生任何变更
        collector.recordEstimatedUsageIfZero(500, 500);

        assertEquals(225, collector.getTokenUsage().getTotalTokens());
        assertEquals(180, collector.getTokenUsage().getPromptTokens());
        assertEquals(45, collector.getTokenUsage().getCompletionTokens());
        assertFalse(collector.getTokenUsage().isEstimated());
    }

    @Test
    @DisplayName("3. 验证 ChatContextWindowTool 在上下文裁剪时将估算 Token 沉淀至 TraceCollector")
    public void testContextWindowRecordsEstimatedPromptTokens() {
        TraceCollector collector = createCollector();
        ChatContextWindowTool windowTool = new ChatContextWindowTool();

        String systemPrompt = "你是一个代码审计专家";
        String contextualQuestion = "请分析这段 Java 程序的潜在并发缺陷并给出修复方案";

        List<String> roleList = new ArrayList<>(List.of(
                SessionConstant.ROLE_USER,
                SessionConstant.ROLE_ASSISTANT
        ));
        List<String> contentList = new ArrayList<>(List.of(
                "上一轮对话提问",
                "上一轮对话回答"
        ));

        windowTool.pruneHistory(roleList, contentList, systemPrompt, contextualQuestion, collector);

        int estimatedPrompt = collector.getEstimatedPromptTokens();
        assertTrue(estimatedPrompt > 0, "预估输入 Token 必须大于 0");

        // 验证空历史场景也能正确记录输入 Prompt Token
        TraceCollector emptyHistoryCollector = new TraceCollector(889L, 99L, 10L, "worker-test-0");
        windowTool.pruneHistory(new ArrayList<>(), new ArrayList<>(), systemPrompt, contextualQuestion, emptyHistoryCollector);
        assertTrue(emptyHistoryCollector.getEstimatedPromptTokens() > 0);
    }

    @Test
    @DisplayName("4. 验证 ext_json 序列化包含 estimated 字段与非零 Token 数据")
    public void testExtJsonSerializationIncludesEstimatedField() {
        TraceCollector collector = createCollector();
        collector.recordEstimatedUsageIfZero(150, 50);
        collector.completeRun("CANCELLED", "用户主动终止");

        String extJson = collector.toExtJson();
        assertTrue(extJson.contains("\"totalTokens\":200"));
        assertTrue(extJson.contains("\"estimated\":true"));
        assertTrue(extJson.contains("\"state\":\"CANCELLED\""));
    }
}
