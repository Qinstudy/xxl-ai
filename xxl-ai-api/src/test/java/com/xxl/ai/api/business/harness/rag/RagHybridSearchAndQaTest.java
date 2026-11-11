package com.xxl.ai.api.business.harness.rag;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG BM25 稀疏检索、RRF 融合与 QA 问答切分专项单测（[FEAT-004] / [FEAT-011]）
 *
 * @author xxl-ai 2026-11-10
 */
public class RagHybridSearchAndQaTest {

    @Test
    public void testTokenizer() {
        String text = "XXL-AI 发生 ERR_SSO_401 鉴权失败，请检查 Redis 缓存与 Token 状态！";
        List<String> tokens = RagBm25Tool.tokenize(text);

        Assertions.assertFalse(tokens.isEmpty());
        // 验证代码标识符与英文连续保留
        Assertions.assertTrue(tokens.contains("xxl-ai"), "应包含 xxl-ai 词元");
        Assertions.assertTrue(tokens.contains("err_sso_401"), "应精准识别 ERR_SSO_401 标识符");
        Assertions.assertTrue(tokens.contains("redis"), "应识别 redis 词元");
        Assertions.assertTrue(tokens.contains("token"), "应识别 token 词元");
        // 验证中文 Uni-gram 与 Bi-gram
        Assertions.assertTrue(tokens.contains("鉴权"), "应包含中文 Bi-gram「鉴权」");
        Assertions.assertTrue(tokens.contains("缓存"), "应包含中文 Bi-gram「缓存」");
        // 验证停用词「与」被过滤
        Assertions.assertFalse(tokens.contains("与"), "停用词「与」应被过滤");
    }

    @Test
    public void testQaFormatDetectionAndAtomicChunking() {
        String singleQa = "【问题】如何配置 XXL-SSO 单点登录客户端？\n【答案】引入 xxl-sso-core 依赖并在 application.properties 中配置 xxl.sso.server 地址。";
        Assertions.assertTrue(RagTextSplitter.isQaFormatted(singleQa), "应识别标准 QA 格式");

        List<String> chunks = RagTextSplitter.split(singleQa, 300, 20);
        Assertions.assertEquals(1, chunks.size(), "单条 QA 对在允许长度内应保持原子分片，不被中途腰斩");
        Assertions.assertTrue(chunks.get(0).contains("【问题】") && chunks.get(0).contains("【答案】"));

        String multipleQa = "【问题】问题1\n【答案】答案1\n---\n【问题】问题2\n【答案】答案2";
        List<String> multiChunks = RagTextSplitter.split(multipleQa, 300, 20);
        Assertions.assertEquals(2, multiChunks.size(), "多组 QA 问答对拆分为独立的 2 个分片");
        Assertions.assertTrue(multiChunks.get(0).contains("问题1"));
        Assertions.assertTrue(multiChunks.get(1).contains("问题2"));
    }

    @Test
    public void testBm25IndexAndRanking() {
        RagBm25Tool.Bm25Index index = new RagBm25Tool.Bm25Index(1001L);
        index.addChunk(1L, 0, "XXL-AI 是一个现代化的 AI Agent 平台，支持多模型与工作流编排", false);
        index.addChunk(2L, 0, "发生 ERR_SSO_401 错误码时，通常表示 Token 无效或 Redis 登录态已过期", false);
        index.addChunk(3L, 0, "Milvus 向量数据库用于存储高维 Embeddings 并执行语义相似度匹配", false);
        index.finishBuild();

        // 检索专有名词错误码
        List<RagBm25Tool.Bm25Hit> hits = index.search("ERR_SSO_401", 5);
        Assertions.assertFalse(hits.isEmpty(), "应命中相关分片");
        Assertions.assertEquals(2L, hits.get(0).docId, "首位命中分片应为精准包含 ERR_SSO_401 的文档 2");
        Assertions.assertTrue(hits.get(0).score > 0.0d);
        Assertions.assertTrue(hits.get(0).normScore > 0.0d);
    }

    @Test
    public void testRrfFusionAndMatchType() {
        RagBm25Tool tool = new RagBm25Tool();

        List<Map<String, Object>> vectorHits = new ArrayList<>();
        Map<String, Object> v1 = new HashMap<>();
        v1.put("docId", 101L);
        v1.put("chunkIndex", 0);
        v1.put("score", 0.85d);
        v1.put("text", "向量高分分片1");
        vectorHits.add(v1);

        Map<String, Object> v2 = new HashMap<>();
        v2.put("docId", 102L);
        v2.put("chunkIndex", 0);
        v2.put("score", 0.70d);
        v2.put("text", "双路命中分片2");
        vectorHits.add(v2);

        List<RagBm25Tool.Bm25Hit> bm25Hits = new ArrayList<>();
        // 102 号分片在 BM25 中排名第一，103 号分片排名第二
        bm25Hits.add(new RagBm25Tool.Bm25Hit(102L, 0, "双路命中分片2", false, 15.0d, 0.75d, 1));
        bm25Hits.add(new RagBm25Tool.Bm25Hit(103L, 0, "BM25纯关键词分片3", false, 8.0d, 0.61d, 2));

        List<Map<String, Object>> fused = tool.fuseWithRrf(vectorHits, bm25Hits, "HYBRID", 0.40d, 10, true);
        Assertions.assertEquals(3, fused.size());

        // 双路共同命中的 102#0 在 RRF 融合下应拔得头筹
        Map<String, Object> topHit = fused.get(0);
        Assertions.assertEquals(102L, topHit.get("docId"), "双路命中文档应在 RRF 重排后排名第一");
        Assertions.assertEquals("HYBRID", topHit.get("matchType"), "双路命中的 matchType 应为 HYBRID");
        Assertions.assertTrue((Double) topHit.get("score") >= 0.70d, "综合得分应有关键词叠加加成");

        // 仅向量命中的 101#0
        Map<String, Object> secondHit = fused.get(1);
        Assertions.assertEquals(101L, secondHit.get("docId"));
        Assertions.assertEquals("VECTOR", secondHit.get("matchType"));

        // 仅 BM25 命中的 103#0
        Map<String, Object> thirdHit = fused.get(2);
        Assertions.assertEquals(103L, thirdHit.get("docId"));
        Assertions.assertEquals("BM25", thirdHit.get("matchType"));
    }

    @Test
    public void testQaChunkBoost() {
        RagBm25Tool tool = new RagBm25Tool();

        List<Map<String, Object>> vectorHits = new ArrayList<>();
        Map<String, Object> vNorm = new HashMap<>();
        vNorm.put("docId", 201L);
        vNorm.put("chunkIndex", 0);
        vNorm.put("score", 0.80d);
        vNorm.put("text", "普通文档分片");
        vNorm.put("qaChunk", false);
        vectorHits.add(vNorm);

        Map<String, Object> vQa = new HashMap<>();
        vQa.put("docId", 202L);
        vQa.put("chunkIndex", 0);
        vQa.put("score", 0.80d);
        vQa.put("text", "【问题】QA问答【答案】内容");
        vQa.put("qaChunk", true);
        vectorHits.add(vQa);

        // 向量分数一致时，QA 享有 1.15x 加成
        List<Map<String, Object>> fused = tool.fuseWithRrf(vectorHits, null, "HYBRID", 0.40d, 10, true);
        Assertions.assertEquals(2, fused.size());
        Assertions.assertEquals(202L, fused.get(0).get("docId"), "享有 1.15x QA Boost 的问答分片应优先排在第一位");
    }

    @Test
    public void testModeIsolationAndKeywordRescue() {
        RagBm25Tool tool = new RagBm25Tool();

        List<Map<String, Object>> vectorHits = new ArrayList<>();
        Map<String, Object> vLow = new HashMap<>();
        vLow.put("docId", 301L);
        vLow.put("chunkIndex", 0);
        vLow.put("score", 0.32d); // 低于 0.40 阈值
        vLow.put("text", "包含专有名词 ERR_401 的说明");
        vectorHits.add(vLow);

        List<RagBm25Tool.Bm25Hit> bm25Hits = new ArrayList<>();
        bm25Hits.add(new RagBm25Tool.Bm25Hit(301L, 0, "包含专有名词 ERR_401 的说明", false, 12.0d, 0.70d, 1));

        // 1. 验证在生产模式下，强 BM25 命中可实现救援召回（即便向量只有 0.32）
        List<Map<String, Object>> rescued = tool.fuseWithRrf(vectorHits, bm25Hits, "HYBRID", 0.40d, 5, false);
        Assertions.assertEquals(1, rescued.size(), "BM25 强命中（0.70）应成功救援向量低分（0.32）的分片");
        Assertions.assertTrue((Boolean) rescued.get(0).get("passed"));

        // 2. 验证纯 VECTOR 模式：被严格过滤抛弃
        List<Map<String, Object>> vectorOnly = tool.fuseWithRrf(vectorHits, bm25Hits, "VECTOR", 0.40d, 5, false);
        Assertions.assertEquals(0, vectorOnly.size(), "纯向量模式下低于 0.40 阈值的分片应被过滤");

        // 3. 验证纯 BM25 模式：顺利召回
        List<Map<String, Object>> bm25Only = tool.fuseWithRrf(vectorHits, bm25Hits, "BM25", 0.40d, 5, false);
        Assertions.assertEquals(1, bm25Only.size(), "纯 BM25 模式下应顺利召回");
        Assertions.assertEquals("BM25", bm25Only.get(0).get("matchType"));
    }
}
