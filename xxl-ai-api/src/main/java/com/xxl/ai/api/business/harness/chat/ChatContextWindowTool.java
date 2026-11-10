package com.xxl.ai.api.business.harness.chat;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.xxl.ai.api.business.session.constant.SessionConstant;
import com.xxl.tool.core.CollectionTool;
import com.xxl.tool.core.StringTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 对话上下文 Token 预算评估与滑动窗口裁剪工具（[CTX-001]）
 *
 * @author xxl-ai 2026-11-10
 */
@Component
public class ChatContextWindowTool {

    private static final Logger logger = LoggerFactory.getLogger(ChatContextWindowTool.class);

    /* 单条消息的结构格式开销（角色、换行与标记符等预估 4 tokens） */
    private static final int MSG_OVERHEAD_TOKENS = 4;

    /* 全局输入 Token 预算上限（系统指令 + RAG + 历史 + 当前提问） */
    @Value("${xxl-ai.chat.history.max-tokens:24000}")
    private int maxTokens = 24000;

    /* JTokkit 分词器实例（cl100k_base 编码） */
    private final Encoding encoding;

    public ChatContextWindowTool() {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    /**
     * 估算文本所占用的 Token 数量（基于 cl100k_base）
     *
     * @param text 输入文本
     * @return 预估 Token 数量（>= 0）
     */
    public int estimateTokens(String text) {
        if (StringTool.isBlank(text)) {
            return 0;
        }
        try {
            return encoding.countTokens(text);
        } catch (Exception e) {
            // 兜底估算：中文约 1.5 字符/token，英文约 4 字符/token，取保守的 2 字符/token
            return Math.max(1, text.length() / 2);
        }
    }

    /**
     * 根据全局 Token 预算裁剪历史消息（从新到旧累加，超额整条丢弃，保证首条为 user 角色）
     *
     * @param roleList           历史消息角色平行列表（原地修改）
     * @param contentList        历史消息内容平行列表（原地修改）
     * @param systemPrompt       系统提示词（含长期记忆）
     * @param contextualQuestion 当前用户提问（含 RAG 上下文）
     * @param traceCollector     全链路追踪收集器（可空）
     */
    public void pruneHistory(List<String> roleList,
                             List<String> contentList,
                             String systemPrompt,
                             String contextualQuestion,
                             TraceCollector traceCollector) {
        if (CollectionTool.isEmpty(roleList) || CollectionTool.isEmpty(contentList)) {
            return;
        }
        if (roleList.size() != contentList.size()) {
            logger.warn("历史消息角色列表与内容列表大小不匹配, roleSize={}, contentSize={}",
                    roleList.size(), contentList.size());
            return;
        }

        int originalCount = roleList.size();
        int systemTokens = estimateTokens(systemPrompt);
        int questionTokens = estimateTokens(contextualQuestion);
        int availableHistoryTokens = maxTokens - systemTokens - questionTokens;

        // 若系统指令与当前提问已占满或超出总预算，历史全量清空
        if (availableHistoryTokens <= 0) {
            roleList.clear();
            contentList.clear();
            logger.warn("可用历史 Token 预算不足 (总预算={}, 系统={}, 当前提问={})，历史消息全量丢弃",
                    maxTokens, systemTokens, questionTokens);
            if (traceCollector != null) {
                traceCollector.addLog("WARN", "context",
                        "历史 " + originalCount + " 条 → 保留 0 条 (预算已耗尽，可用 " + availableHistoryTokens + " tokens)");
            }
            return;
        }

        // 从新到旧累加历史消息的 Token 开销
        int accumulatedTokens = 0;
        int keepStartIndex = originalCount;

        for (int i = originalCount - 1; i >= 0; i--) {
            int msgTokens = estimateTokens(contentList.get(i)) + MSG_OVERHEAD_TOKENS;
            if (accumulatedTokens + msgTokens <= availableHistoryTokens) {
                accumulatedTokens += msgTokens;
                keepStartIndex = i;
            } else {
                break;
            }
        }

        // 确保裁剪后的首条消息为 user 角色（避免残留无上下文呼应的孤立 assistant 总结）
        while (keepStartIndex < originalCount
                && SessionConstant.ROLE_ASSISTANT.equals(roleList.get(keepStartIndex))) {
            accumulatedTokens -= (estimateTokens(contentList.get(keepStartIndex)) + MSG_OVERHEAD_TOKENS);
            keepStartIndex++;
        }

        if (keepStartIndex >= originalCount) {
            roleList.clear();
            contentList.clear();
            logger.warn("历史消息裁剪后无完整 user-assistant 轮次，历史已清空");
            if (traceCollector != null) {
                traceCollector.addLog("WARN", "context",
                        "历史 " + originalCount + " 条 → 保留 0 条 (截断后无完整对话轮次)");
            }
            return;
        }

        // 发生了消息裁剪，移除超出预算的较早历史
        if (keepStartIndex > 0) {
            roleList.subList(0, keepStartIndex).clear();
            contentList.subList(0, keepStartIndex).clear();
            logger.info("历史上下文根据 Token 预算已裁剪: {} 条 → {} 条 (估算 {} / 预算 {} tokens)",
                    originalCount, roleList.size(), accumulatedTokens, availableHistoryTokens);
            if (traceCollector != null) {
                traceCollector.addLog("INFO", "context",
                        "历史 " + originalCount + " 条 → 保留 " + roleList.size()
                                + " 条 (估算 " + accumulatedTokens + " / 预算 " + availableHistoryTokens + " tokens)");
            }
        }
    }
}
