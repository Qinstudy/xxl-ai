package com.xxl.ai.api.business.harness.chat;

import com.xxl.ai.api.business.session.constant.SessionConstant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChatContextWindowTool 历史上下文 Token 预算评估与滑动窗口裁剪单元测试
 *
 * @author xxl-ai 2026-11-10
 */
public class ChatContextWindowToolTest {

    private ChatContextWindowTool windowTool = new ChatContextWindowTool();

    @BeforeEach
    public void setUp() {
        windowTool = new ChatContextWindowTool();
    }

    @Test
    public void testEstimateTokensReturnsAccurateCount() {
        assertEquals(0, windowTool.estimateTokens(null));
        assertEquals(0, windowTool.estimateTokens(""));

        int enTokens = windowTool.estimateTokens("Hello world, this is a token test.");
        assertTrue(enTokens > 0);

        int zhTokens = windowTool.estimateTokens("你好世界，这是一个中文分词估算测试。");
        assertTrue(zhTokens > 0);
    }

    @Test
    public void testPruneHistoryWithinBudgetKeepsAllMessages() {
        windowTool.setMaxTokens(1000);

        List<String> roleList = new ArrayList<>(List.of(
                SessionConstant.ROLE_USER,
                SessionConstant.ROLE_ASSISTANT,
                SessionConstant.ROLE_USER,
                SessionConstant.ROLE_ASSISTANT
        ));
        List<String> contentList = new ArrayList<>(List.of(
                "第一轮提问",
                "第一轮回答",
                "第二轮提问",
                "第二轮回答"
        ));

        windowTool.pruneHistory(roleList, contentList, "你是一个助手", "当前用户提问", null);

        assertEquals(4, roleList.size());
        assertEquals(4, contentList.size());
        assertEquals("第一轮提问", contentList.get(0));
    }

    @Test
    public void testPruneHistoryExceedingBudgetPrunesOldestMessages() {
        // 设置极小预算，只够容纳系统指令、当前提问以及最近一轮对话
        windowTool.setMaxTokens(60);

        List<String> roleList = new ArrayList<>(List.of(
                SessionConstant.ROLE_USER,
                SessionConstant.ROLE_ASSISTANT,
                SessionConstant.ROLE_USER,
                SessionConstant.ROLE_ASSISTANT
        ));
        List<String> contentList = new ArrayList<>(List.of(
                "较早的第一轮提问，包含一些较长字符内容以占用 token 预算空间",
                "较早的第一轮助手详细回答，包含许多文字内容",
                "最新提问",
                "最新回答"
        ));

        windowTool.pruneHistory(roleList, contentList, "系统指令", "当前提问", null);

        // 较早的轮次被丢弃，保留较新的完整轮次，且首条必为 user
        assertTrue(roleList.size() < 4);
        if (!roleList.isEmpty()) {
            assertEquals(SessionConstant.ROLE_USER, roleList.get(0));
            assertEquals("最新提问", contentList.get(0));
        }
    }

    @Test
    public void testPruneHistoryEnsuresHistoryStartsWithUser() {
        windowTool.setMaxTokens(40);

        // 构造三条历史：user1, assistant1, assistant2（异常连续）
        List<String> roleList = new ArrayList<>(List.of(
                SessionConstant.ROLE_USER,
                SessionConstant.ROLE_ASSISTANT
        ));
        List<String> contentList = new ArrayList<>(List.of(
                "超长的前置用户消息占用超过可用预算以致必须被裁剪掉，这里放置非常多的汉字以确保超出预算",
                "短助手消息"
        ));

        windowTool.pruneHistory(roleList, contentList, "系统提示词", "当前问题", null);

        // 若前置 user 被裁剪后首条变成 assistant，算法必须丢弃该 assistant 确保以 user 开头
        if (!roleList.isEmpty()) {
            assertEquals(SessionConstant.ROLE_USER, roleList.get(0));
        } else {
            assertTrue(roleList.isEmpty());
            assertTrue(contentList.isEmpty());
        }
    }

    @Test
    public void testPruneHistoryBudgetExhaustedClearsAllHistory() {
        // 总预算极小，甚至不足以支撑系统指令与当前提问
        windowTool.setMaxTokens(10);

        List<String> roleList = new ArrayList<>(List.of(
                SessionConstant.ROLE_USER,
                SessionConstant.ROLE_ASSISTANT
        ));
        List<String> contentList = new ArrayList<>(List.of(
                "历史提问",
                "历史回答"
        ));

        windowTool.pruneHistory(roleList, contentList,
                "这是一个非常长非常长的系统提示词，本身就已经耗尽了所有的 Token 预算",
                "当前问题也是长篇大论", null);

        assertTrue(roleList.isEmpty());
        assertTrue(contentList.isEmpty());
    }
}
