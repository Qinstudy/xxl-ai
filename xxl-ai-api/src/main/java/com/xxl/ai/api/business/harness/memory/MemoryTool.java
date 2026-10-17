package com.xxl.ai.api.business.harness.memory;

import com.xxl.ai.api.business.agent.model.entity.Agent;
import com.xxl.ai.api.business.harness.llm.LlmChatTool;
import com.xxl.ai.api.business.memory.mapper.MemoryMapper;
import com.xxl.ai.api.business.memory.model.entity.Memory;
import com.xxl.ai.api.business.session.constant.SessionConstant;
import com.xxl.ai.api.business.session.model.entity.SessionMsg;
import com.xxl.ai.api.business.supplier.model.SupplierRuntime;
import com.xxl.ai.api.business.supplier.service.SupplierService;
import com.xxl.tool.core.CollectionTool;
import com.xxl.tool.core.StringTool;
import com.xxl.tool.response.Response;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 长期记忆工具（harness 运行时）
 *
 * 承担长期记忆的「读取注入」与「模型生成」两项底层能力：
 *  - {@link #loadMemory}：按 Agent×访客 读取已归档记忆；
 *  - {@link #mergePrompt}：把记忆以 Markdown 追加到系统指令，供对话注入；
 *  - {@link #generate}：以「历史记忆 + 最新对话」为输入，按固定维度抽取生成最新记忆（定长截断）。
 *
 * 记忆的持久化由调用方（对话编排 / 归档定时器）经 {@link MemoryMapper} 完成，本工具不含会话归属等业务编排。
 *
 * @author xxl-ai 2026-10-17
 */
@Component
public class MemoryTool {

    /** 记忆维度小节标题（规范化时用于拆分被合并到同一行的「标题 + 内容」） */
    private static final List<String> MEMORY_SECTIONS = List.of("用户画像", "偏好习惯", "关键事实", "目标与意图", "近期进展");

    /** 记忆维度与生成约束（系统指令，{maxLen} 为内容长度上限占位） */
    private static final String MEMORY_SYSTEM_PROMPT = """
            你是长期记忆归档助手。请基于【历史记忆】与【最新对话】，按下列维度抽取并更新用户长期记忆。

            输出格式（务必严格遵守）：
            1. 只输出 Markdown 记忆正文，不要任何解释、前言或代码块围栏；
            2. 每个维度标题独占一行，形如“## 维度名”（井号后有且仅有一个空格）；
            3. 每个要点独占一行，以“- ”开头；
            4. 标题与要点之间、要点与要点之间都必须换行，禁止把标题与内容写在同一行；
            5. 不同维度之间空一行。

            维度（无内容的维度整节省略）：
            ## 用户画像
            - 用户的身份、称呼、职业、所在地等基本画像
            ## 偏好习惯
            - 用户的沟通偏好、回答风格、常用语言与工具等
            ## 关键事实
            - 用户明确告知、需要长期记住的事实性信息
            ## 目标与意图
            - 用户的核心诉求、长期目标与待办事项
            ## 近期进展
            - 最近对话中已推进的事项、结论与下一步

            要求：合并历史记忆与新对话信息，删除重复与已过时内容，保持精炼；总长度不超过 {maxLen} 字。""";

    private static final String PLACEHOLDER_MAX_LEN = "{maxLen}";

    @Resource
    private MemoryMapper memoryMapper;
    @Resource
    private SupplierService supplierService;
    @Resource
    private LlmChatTool llmChatTool;

    /**
     * 读取 Agent×访客 的长期记忆内容（无则返回 null）
     */
    public String loadMemory(long agentId, String visitorId) {
        if (agentId <= 0 || StringTool.isBlank(visitorId)) {
            return null;
        }
        Memory memory = memoryMapper.loadByAgentVisitor(agentId, visitorId);
        return memory == null ? null : memory.getContent();
    }

    /**
     * 把长期记忆合并进系统指令：记忆为空时原样返回基础指令
     */
    public String mergePrompt(String basePrompt, String memory) {
        String prompt = basePrompt == null ? "" : basePrompt;
        if (StringTool.isBlank(memory)) {
            return prompt;
        }
        return prompt
                + "\n\n以下是该用户的历史长期记忆（Markdown），请在回答时结合这些信息；"
                + "若与用户当前表述冲突，以当前表述为准：\n" + memory.trim();
    }

    /**
     * 经模型生成最新记忆：输入「历史记忆 + 最新对话」，按固定维度抽取并定长截断
     *
     * @param agent     记忆所属 Agent（提供对话模型）
     * @param oldMemory 历史记忆（可空）
     * @param newMsgs   自上次归档以来的新消息（按时间正序）
     * @param maxLen    记忆内容长度上限（字符数）
     * @return 生成的最新记忆内容；生成失败抛异常由调用方降级
     */
    public String generate(Agent agent, String oldMemory, List<SessionMsg> newMsgs, int maxLen) {

        Response<SupplierRuntime> runtimeResp = supplierService.loadRuntime(agent.getSpaceId(),
                agent.getModelSupplierId(), agent.getModelId());
        if (!runtimeResp.isSuccess() || runtimeResp.getData() == null) {
            throw new IllegalStateException(runtimeResp.getMsg());
        }
        SupplierRuntime runtime = runtimeResp.getData();
        if (runtime.getModelType() != 0) {
            throw new IllegalStateException("所选模型不是对话模型");
        }

        int limit = maxLen > 0 ? maxLen : 2000;
        String systemPrompt = MEMORY_SYSTEM_PROMPT.replace(PLACEHOLDER_MAX_LEN, String.valueOf(limit));
        String userPrompt = buildUserPrompt(oldMemory, newMsgs);

        // 单轮流式对话聚合为完整文本（不注入工具/Advisor，不对外回调）
        LlmChatTool.ChatText chatText = llmChatTool.chat(runtime, systemPrompt, new ArrayList<>(), new ArrayList<>(),
                userPrompt, null, null, "xxl-ai-memory-" + agent.getUuid(), null, null);
        String content = chatText == null ? null : chatText.getContent();
        return limitLength(normalizeMarkdown(content), limit);
    }

    /**
     * 规范化模型输出的 Markdown：修正标题/列表标记格式，并确保「标题」与「内容」不在同一行
     *
     * 模型偶发把结构压成一行（如 {@code ##偏好习惯-沟通风格…}），这里统一纠正为可读的 Markdown。
     */
    private String normalizeMarkdown(String content) {
        if (StringTool.isBlank(content)) {
            return content;
        }
        String text = content.replace("\r\n", "\n").replace("\r", "\n").trim();
        // 1) 行内出现的标题标记另起一行：…文字## 标题 → …文字\n## 标题
        text = text.replaceAll("([^#\\n])(#{1,6})", "$1\n$2");
        // 2) 标题标记后补空格：#标题 → # 标题（要求 # 后为非 #、非空白，避免把 ## 拆成 # #）
        text = text.replaceAll("(?m)^(#{1,6})(?=[^#\\s])", "$1 ");
        // 3) 固定维度标题与紧随其后的内容拆行：## 偏好习惯-xxx → ## 偏好习惯\n- xxx
        for (String section : MEMORY_SECTIONS) {
            text = text.replaceAll("(?m)(#{1,6} " + section + ")[ \\t]*(?=\\S)", "$1\n");
        }
        // 4) 行内列表项另起一行：…口吻-开场… → …口吻\n- 开场…（仅当前一字符非 ASCII，避免误拆英文连字符 / URL）
        text = text.replaceAll("([^\\x00-\\x7F])[-*+\\u2022\\u2013\\u2014][ \\t]*(?=\\S)", "$1\n- ");
        // 5) 行首列表标记后补空格：-xxx → - xxx
        text = text.replaceAll("(?m)^([-*+\\u2022\\u2013\\u2014])(?=\\S)", "$1 ");
        // 6) 维度标题前空一行：不同维度之间留空行
        text = text.replaceAll("(?m)^(#{1,6} )", "\n$1");
        // 7) 合并多余空行
        text = text.replaceAll("\\n{3,}", "\n\n");
        return text.trim();
    }

    /**
     * 组装归档用户提示：历史记忆 + 最新对话
     */
    private String buildUserPrompt(String oldMemory, List<SessionMsg> newMsgs) {
        StringBuilder sb = new StringBuilder();
        sb.append("【历史记忆】\n");
        sb.append(StringTool.isBlank(oldMemory) ? "（暂无）" : oldMemory.trim()).append("\n\n");
        sb.append("【最新对话】\n");
        if (CollectionTool.isNotEmpty(newMsgs)) {
            for (SessionMsg msg : newMsgs) {
                String role = SessionConstant.ROLE_ASSISTANT.equals(msg.getRole()) ? "助手" : "用户";
                sb.append(role).append("：").append(msg.getContent() == null ? "" : msg.getContent()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 记忆内容定长截断：超过上限则截断（null/空返回原值）
     */
    private String limitLength(String content, int maxLen) {
        if (StringTool.isBlank(content)) {
            return content;
        }
        String trimmed = content.trim();
        if (maxLen > 0 && trimmed.length() > maxLen) {
            return trimmed.substring(0, maxLen);
        }
        return trimmed;
    }

}
