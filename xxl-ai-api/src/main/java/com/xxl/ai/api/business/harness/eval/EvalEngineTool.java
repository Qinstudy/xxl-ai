package com.xxl.ai.api.business.harness.eval;

import com.xxl.ai.api.business.agent.model.entity.Agent;
import com.xxl.ai.api.business.harness.chat.TraceCollector;
import com.xxl.ai.api.business.harness.llm.LlmChatTool;
import com.xxl.ai.api.business.harness.llm.LlmModelFactory;
import com.xxl.ai.api.business.harness.rag.RagTool;
import com.xxl.ai.api.business.supplier.model.SupplierRuntime;
import com.xxl.tool.core.CollectionTool;
import com.xxl.tool.core.StringTool;
import com.xxl.tool.json.GsonTool;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 评测运算引擎工具（harness，无 Controller，纯运行时能力）
 *
 * 职责：
 *   1. 离散检索指标计算：HitRate@K、MRR；
 *   2. 基于大模型的裁判判定（LLM-as-a-Judge）：忠实度（Faithfulness/防幻觉）、答案相关性（Answer Relevance）、任务达成率（Goal Completion）；
 *   3. 单个测试用例端到端驱动：前置检索捕获切片 → 大模型生成回答 → 裁判打分归因。
 *
 * @author xxl-ai 2026-11-12
 */
@Component
public class EvalEngineTool {

    private static final Logger logger = LoggerFactory.getLogger(EvalEngineTool.class);

    private static final Pattern JSON_SCORE_PATTERN = Pattern.compile("\"score\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern JSON_REASON_PATTERN = Pattern.compile("\"reason\"\\s*:\\s*\"([^\"]*)\"");

    @Resource
    private RagTool ragTool;

    @Resource
    private LlmChatTool llmChatTool;

    @Resource
    private LlmModelFactory llmModelFactory;

    // ==================== 1. 离散检索指标计算 (0 Token 成本) ====================

    /**
     * 计算 Top-K 命中率（HitRate@K）
     * 规则：在召回的前 K 个切片中，若有至少一个切片的文档名命中 expectedDocNames 或内容包含 expectedKeywords，即判定命中 (1.0)，否则 (0.0)
     */
    public double calculateHitRate(List<String> expectedDocNames,
                                   List<String> expectedKeywords,
                                   List<TraceCollector.RagReference> references,
                                   int k) {
        if (CollectionTool.isEmpty(references) || k <= 0) {
            return 0.0;
        }
        int limit = Math.min(k, references.size());
        for (int i = 0; i < limit; i++) {
            if (isChunkMatched(references.get(i), expectedDocNames, expectedKeywords)) {
                return 1.0;
            }
        }
        return 0.0;
    }

    /**
     * 计算平均倒数排名（MRR，Reciprocal Rank）
     * 规则：找到第一个命中切片在召回结果列表中的 1-based 序号 rank，返回 1.0 / rank；若全未命中返回 0.0
     */
    public double calculateMrr(List<String> expectedDocNames,
                               List<String> expectedKeywords,
                               List<TraceCollector.RagReference> references) {
        if (CollectionTool.isEmpty(references)) {
            return 0.0;
        }
        for (int i = 0; i < references.size(); i++) {
            if (isChunkMatched(references.get(i), expectedDocNames, expectedKeywords)) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /**
     * 判断单个检索分片是否命中期望目标（文档名匹配 或 包含关键词）
     */
    public boolean isChunkMatched(TraceCollector.RagReference reference,
                                  List<String> expectedDocNames,
                                  List<String> expectedKeywords) {
        if (reference == null) {
            return false;
        }
        // 1、文档名称匹配
        if (CollectionTool.isNotEmpty(expectedDocNames) && StringTool.isNotBlank(reference.getDocName())) {
            String docNameLower = reference.getDocName().trim().toLowerCase();
            for (String expectedDoc : expectedDocNames) {
                if (StringTool.isNotBlank(expectedDoc)) {
                    String expLower = expectedDoc.trim().toLowerCase();
                    if (docNameLower.contains(expLower) || expLower.contains(docNameLower)) {
                        return true;
                    }
                }
            }
        }
        // 2、关键内容匹配
        if (CollectionTool.isNotEmpty(expectedKeywords) && StringTool.isNotBlank(reference.getContent())) {
            String contentLower = reference.getContent().toLowerCase();
            for (String kw : expectedKeywords) {
                if (StringTool.isNotBlank(kw) && contentLower.contains(kw.trim().toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    // ==================== 2. LLM-as-a-Judge 评估器 ====================

    /**
     * 判定忠实度（Faithfulness）：模型回答是否完全基于参考内容，是否存在无中生有或捏造事实
     */
    public JudgeResult judgeFaithfulness(String query, String context, String answer, SupplierRuntime judgeRuntime) {
        if (judgeRuntime == null || StringTool.isBlank(answer)) {
            return new JudgeResult(1.0, "无裁判模型或回答为空，默认跳过忠实度校验");
        }
        String prompt = "你是严谨客观的大模型事实核验专家。请根据以下提供的用户问题、参考上下文（Context）以及实际回答（Answer），" +
                "评估回答的忠实度（Faithfulness）：回答内容是否完全有参考上下文支持？是否存在脱离参考的捏造或幻觉？\n\n" +
                "【用户问题】: " + (query != null ? query : "") + "\n" +
                "【参考上下文】: " + (context != null ? context : "（无参考切片）") + "\n" +
                "【实际回答】: " + answer + "\n\n" +
                "评分标准：1.0 表示完全忠实无幻觉；0.0 表示完全胡编乱造；0.5 表示部分属实但含未验证假定。\n" +
                "请严格输出如下 JSON 格式，严禁包含任何前缀或额外文字：\n" +
                "{\"score\": 1.0, \"reason\": \"理由说明\"}";

        return invokeJudgeModel(prompt, judgeRuntime);
    }

    /**
     * 判定答案相关性（Answer Relevance）：模型回答是否切题，是否准确响应了用户意图
     */
    public JudgeResult judgeAnswerRelevance(String query, String answer, SupplierRuntime judgeRuntime) {
        if (judgeRuntime == null || StringTool.isBlank(answer)) {
            return new JudgeResult(1.0, "无裁判模型或回答为空，默认跳过相关性校验");
        }
        String prompt = "你是严谨的大模型质量评审专家。请根据用户提问（Question）和实际回答（Answer），评估答案相关性（Relevance）：" +
                "回答是否直接且充分回应了用户核心疑问？是否存在答非所问、冗余废话或拒答？\n\n" +
                "【用户提问】: " + (query != null ? query : "") + "\n" +
                "【实际回答】: " + answer + "\n\n" +
                "评分标准：1.0 表示精准切题；0.0 表示完全答非所问；0.5 表示含糊切题但未实质回答核心点。\n" +
                "请严格输出如下 JSON 格式，严禁包含任何前缀或额外文字：\n" +
                "{\"score\": 1.0, \"reason\": \"理由说明\"}";

        return invokeJudgeModel(prompt, judgeRuntime);
    }

    /**
     * 判定任务达成率（Goal Completion）：Agent 最终回答及工具调用是否达成了用户目标
     */
    public JudgeResult judgeGoalCompletion(String query, String answer, SupplierRuntime judgeRuntime) {
        if (judgeRuntime == null || StringTool.isBlank(answer)) {
            return new JudgeResult(1.0, "无裁判模型或回答为空，默认跳过目标达成率校验");
        }
        String prompt = "你是严谨的智能体（Agent）任务完成度评估专家。请根据用户的任务指令（Instruction）和智能体最终给出的方案/交付结果（Output），" +
                "评估任务达成率（Goal Completion）：是否完整解决了用户指令所要求的全部任务？\n\n" +
                "【任务指令】: " + (query != null ? query : "") + "\n" +
                "【智能体交付输出】: " + answer + "\n\n" +
                "评分标准：1.0 表示完全达成且质量优异；0.0 表示任务完全失败；0.5 表示仅部分完成。\n" +
                "请严格输出如下 JSON 格式，严禁包含任何前缀或额外文字：\n" +
                "{\"score\": 1.0, \"reason\": \"理由说明\"}";

        return invokeJudgeModel(prompt, judgeRuntime);
    }

    /**
     * 统一调用 Judge 模型并解析 JSON 结果（具备正则提取兜底，防止 LLM 输出包裹 markdown 代码块）
     */
    private JudgeResult invokeJudgeModel(String prompt, SupplierRuntime judgeRuntime) {
        try {
            LlmChatTool.ChatText chatText = llmChatTool.chat(
                    judgeRuntime,
                    "你是一个只输出 JSON 的高精度评测专家系统。",
                    Collections.emptyList(),
                    Collections.emptyList(),
                    prompt,
                    new Object[0],
                    Collections.emptyList(),
                    "eval-judge-" + System.currentTimeMillis(),
                    null,
                    null
            );

            String raw = chatText != null ? chatText.getContent() : null;
            if (StringTool.isBlank(raw)) {
                return new JudgeResult(0.5, "裁判模型未返回内容");
            }

            // 清理 markdown 代码块
            String cleaned = raw.replaceAll("```json", "").replaceAll("```", "").trim();

            // 1、尝试直接 JSON 反序列化
            try {
                JudgeResult parsed = GsonTool.fromJson(cleaned, JudgeResult.class);
                if (parsed != null && parsed.getScore() >= 0.0) {
                    parsed.setScore(Math.min(1.0, Math.max(0.0, parsed.getScore())));
                    return parsed;
                }
            } catch (Exception ignore) {
                // 回退到正则提取
            }

            // 2、正则提取 score 与 reason 兜底
            double score = 0.5;
            String reason = "模型返回未格式化文本，回退默认";
            Matcher scoreMatcher = JSON_SCORE_PATTERN.matcher(cleaned);
            if (scoreMatcher.find()) {
                try {
                    score = Double.parseDouble(scoreMatcher.group(1));
                    score = Math.min(1.0, Math.max(0.0, score));
                } catch (Exception ignore) {}
            }
            Matcher reasonMatcher = JSON_REASON_PATTERN.matcher(cleaned);
            if (reasonMatcher.find()) {
                reason = reasonMatcher.group(1);
            } else if (cleaned.length() < 200) {
                reason = cleaned;
            }
            return new JudgeResult(score, reason);
        } catch (Exception e) {
            logger.warn("Judge model execution failed: {}", e.getMessage());
            return new JudgeResult(0.0, "裁判模型调用失败: " + e.getMessage());
        }
    }

    // ==================== 3. 用例端到端驱动执行 ====================

    /**
     * 驱动执行单个 RAG 测试用例
     */
    public CaseResult runRagCase(long spaceId,
                                 List<Long> kbIds,
                                 EvalCase evalCase,
                                 SupplierRuntime generatorRuntime,
                                 SupplierRuntime judgeRuntime) {
        CaseResult result = new CaseResult();
        result.setCaseId(evalCase.getId());
        result.setQuery(evalCase.getQuery());
        result.setGroundTruth(evalCase.getGroundTruth());

        long startTime = System.currentTimeMillis();
        try {
            // 1、执行前置真实检索
            List<String> degradeNotices = new ArrayList<>();
            TraceCollector traceCollector = new TraceCollector(0L, 0L, 0L, "eval-" + evalCase.getId());
            List<TraceCollector.RagReference> references = ragTool.retrieveForChat(
                    spaceId, kbIds, evalCase.getQuery(), degradeNotices, traceCollector);
            result.setRetrievedChunks(references);

            // 2、计算检索离散指标
            double hitRateAt1 = calculateHitRate(evalCase.getExpectedDocNames(), evalCase.getExpectedKeywords(), references, 1);
            double hitRateAt3 = calculateHitRate(evalCase.getExpectedDocNames(), evalCase.getExpectedKeywords(), references, 3);
            double mrr = calculateMrr(evalCase.getExpectedDocNames(), evalCase.getExpectedKeywords(), references);

            result.setHitRateAt1(hitRateAt1);
            result.setHitRateAt3(hitRateAt3);
            result.setReciprocalRank(mrr);

            // 3、生成模型推理回答（如果未配置生成模型，则仅评测检索）
            StringBuilder contextBuilder = new StringBuilder();
            if (CollectionTool.isNotEmpty(references)) {
                for (int i = 0; i < references.size(); i++) {
                    TraceCollector.RagReference ref = references.get(i);
                    contextBuilder.append(String.format("[%d] 《%s》: %s\n", i + 1, ref.getDocName(), ref.getContent()));
                }
            }
            String context = contextBuilder.toString();

            String actualAnswer = "";
            if (generatorRuntime != null) {
                String systemPrompt = "请根据以下参考资料回答用户问题，不要编造未提供的内容：\n\n" + context;
                LlmChatTool.ChatText chatText = llmChatTool.chat(
                        generatorRuntime,
                        systemPrompt,
                        Collections.emptyList(),
                        Collections.emptyList(),
                        evalCase.getQuery(),
                        new Object[0],
                        Collections.emptyList(),
                        "eval-rag-" + evalCase.getId(),
                        null,
                        null
                );
                actualAnswer = chatText != null ? chatText.getContent() : "";
            }
            result.setActualAnswer(actualAnswer);

            // 4、裁判模型打分
            if (judgeRuntime != null && StringTool.isNotBlank(actualAnswer)) {
                JudgeResult faith = judgeFaithfulness(evalCase.getQuery(), context, actualAnswer, judgeRuntime);
                result.setFaithfulnessScore(faith.getScore());
                result.setFaithfulnessReason(faith.getReason());

                JudgeResult relev = judgeAnswerRelevance(evalCase.getQuery(), actualAnswer, judgeRuntime);
                result.setRelevanceScore(relev.getScore());
                result.setRelevanceReason(relev.getReason());
            } else {
                result.setFaithfulnessScore(1.0);
                result.setFaithfulnessReason("未启用裁判模型评估生成内容");
                result.setRelevanceScore(1.0);
                result.setRelevanceReason("未启用裁判模型评估生成内容");
            }

            result.setTokens(Math.max(1, actualAnswer.length() / 2));
        } catch (Exception e) {
            logger.error("Run RAG case failed: {}", evalCase.getId(), e);
            result.setError(e.getMessage());
        } finally {
            result.setLatencyMs(System.currentTimeMillis() - startTime);
        }
        return result;
    }

    /**
     * 驱动执行单个 Agent 测试用例
     */
    public CaseResult runAgentCase(long spaceId,
                                   Agent agent,
                                   EvalCase evalCase,
                                   SupplierRuntime agentRuntime,
                                   SupplierRuntime judgeRuntime) {
        CaseResult result = new CaseResult();
        result.setCaseId(evalCase.getId());
        result.setQuery(evalCase.getQuery());
        result.setGroundTruth(evalCase.getGroundTruth());

        long startTime = System.currentTimeMillis();
        try {
            String actualAnswer = "";
            if (agentRuntime != null && agent != null) {
                String systemPrompt = StringTool.isNotBlank(agent.getSystemPrompt()) ? agent.getSystemPrompt() : "你是一个有用的助手。";
                LlmChatTool.ChatText chatText = llmChatTool.chat(
                        agentRuntime,
                        systemPrompt,
                        Collections.emptyList(),
                        Collections.emptyList(),
                        evalCase.getQuery(),
                        new Object[0],
                        Collections.emptyList(),
                        "eval-agent-" + evalCase.getId(),
                        null,
                        null
                );
                actualAnswer = chatText != null ? chatText.getContent() : "";
            }
            result.setActualAnswer(actualAnswer);

            // 裁判模型打分：目标达成率与相关性
            if (judgeRuntime != null && StringTool.isNotBlank(actualAnswer)) {
                JudgeResult goal = judgeGoalCompletion(evalCase.getQuery(), actualAnswer, judgeRuntime);
                result.setGoalCompletionScore(goal.getScore());
                result.setGoalCompletionReason(goal.getReason());

                JudgeResult relev = judgeAnswerRelevance(evalCase.getQuery(), actualAnswer, judgeRuntime);
                result.setRelevanceScore(relev.getScore());
                result.setRelevanceReason(relev.getReason());
            } else {
                result.setGoalCompletionScore(1.0);
                result.setGoalCompletionReason("未启用裁判模型");
                result.setRelevanceScore(1.0);
                result.setRelevanceReason("未启用裁判模型");
            }
            result.setTokens(Math.max(1, actualAnswer.length() / 2));
        } catch (Exception e) {
            logger.error("Run Agent case failed: {}", evalCase.getId(), e);
            result.setError(e.getMessage());
        } finally {
            result.setLatencyMs(System.currentTimeMillis() - startTime);
        }
        return result;
    }

    // ==================== 4. 数据结构模型 (POJOs) ====================

    /**
     * 评测用例输入
     */
    public static class EvalCase {
        private String id;
        private String query;
        private String groundTruth;
        private List<String> expectedDocNames;
        private List<String> expectedKeywords;
        private List<String> expectedTools;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }
        public String getGroundTruth() { return groundTruth; }
        public void setGroundTruth(String groundTruth) { this.groundTruth = groundTruth; }
        public List<String> getExpectedDocNames() { return expectedDocNames; }
        public void setExpectedDocNames(List<String> expectedDocNames) { this.expectedDocNames = expectedDocNames; }
        public List<String> getExpectedKeywords() { return expectedKeywords; }
        public void setExpectedKeywords(List<String> expectedKeywords) { this.expectedKeywords = expectedKeywords; }
        public List<String> getExpectedTools() { return expectedTools; }
        public void setExpectedTools(List<String> expectedTools) { this.expectedTools = expectedTools; }
    }

    /**
     * 裁判判定结果
     */
    public static class JudgeResult {
        private double score;
        private String reason;

        public JudgeResult() {}
        public JudgeResult(double score, String reason) {
            this.score = score;
            this.reason = reason;
        }
        public double getScore() { return score; }
        public void setScore(double score) { this.score = score; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    /**
     * 单用例执行报告
     */
    public static class CaseResult {
        private String caseId;
        private String query;
        private String groundTruth;
        private List<TraceCollector.RagReference> retrievedChunks;
        private String actualAnswer;
        private double hitRateAt1;
        private double hitRateAt3;
        private double reciprocalRank;
        private double faithfulnessScore;
        private String faithfulnessReason;
        private double relevanceScore;
        private String relevanceReason;
        private double goalCompletionScore;
        private String goalCompletionReason;
        private long latencyMs;
        private int tokens;
        private String error;

        public String getCaseId() { return caseId; }
        public void setCaseId(String caseId) { this.caseId = caseId; }
        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }
        public String getGroundTruth() { return groundTruth; }
        public void setGroundTruth(String groundTruth) { this.groundTruth = groundTruth; }
        public List<TraceCollector.RagReference> getRetrievedChunks() { return retrievedChunks; }
        public void setRetrievedChunks(List<TraceCollector.RagReference> retrievedChunks) { this.retrievedChunks = retrievedChunks; }
        public String getActualAnswer() { return actualAnswer; }
        public void setActualAnswer(String actualAnswer) { this.actualAnswer = actualAnswer; }
        public double getHitRateAt1() { return hitRateAt1; }
        public void setHitRateAt1(double hitRateAt1) { this.hitRateAt1 = hitRateAt1; }
        public double getHitRateAt3() { return hitRateAt3; }
        public void setHitRateAt3(double hitRateAt3) { this.hitRateAt3 = hitRateAt3; }
        public double getReciprocalRank() { return reciprocalRank; }
        public void setReciprocalRank(double reciprocalRank) { this.reciprocalRank = reciprocalRank; }
        public double getFaithfulnessScore() { return faithfulnessScore; }
        public void setFaithfulnessScore(double faithfulnessScore) { this.faithfulnessScore = faithfulnessScore; }
        public String getFaithfulnessReason() { return faithfulnessReason; }
        public void setFaithfulnessReason(String faithfulnessReason) { this.faithfulnessReason = faithfulnessReason; }
        public double getRelevanceScore() { return relevanceScore; }
        public void setRelevanceScore(double relevanceScore) { this.relevanceScore = relevanceScore; }
        public String getRelevanceReason() { return relevanceReason; }
        public void setRelevanceReason(String relevanceReason) { this.relevanceReason = relevanceReason; }
        public double getGoalCompletionScore() { return goalCompletionScore; }
        public void setGoalCompletionScore(double goalCompletionScore) { this.goalCompletionScore = goalCompletionScore; }
        public String getGoalCompletionReason() { return goalCompletionReason; }
        public void setGoalCompletionReason(String goalCompletionReason) { this.goalCompletionReason = goalCompletionReason; }
        public long getLatencyMs() { return latencyMs; }
        public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }
        public int getTokens() { return tokens; }
        public void setTokens(int tokens) { this.tokens = tokens; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
    }
}
