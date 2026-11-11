package com.xxl.ai.api.business.harness.rag;

import com.xxl.ai.api.business.knowledge.mapper.KnowledgeDocMapper;
import com.xxl.ai.api.business.knowledge.model.entity.KnowledgeDoc;
import com.xxl.tool.cache.CacheTool;
import com.xxl.tool.cache.iface.Cache;
import com.xxl.tool.core.CollectionTool;
import com.xxl.tool.core.StringTool;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG 内存级 BM25 稀疏检索与 RRF 倒数排名融合引擎（[FEAT-004] / [FEAT-011]）
 *
 * 核心能力：
 *  1. 轻量分词器：零外部依赖支持英文字母/数字/连字符/错误码标识符 + 中文 Uni-gram &amp; Bi-gram 组合，过滤高频无意义停用词；
 *  2. 分片级 Okapi BM25 倒排索引：基于状态为已向量化（status=1）的文档切片构建，与 Milvus (docId, chunkIndex) 100% 对齐；
 *  3. 内存防护：CacheTool LRU 缓存（200 库，TTL 5分钟），单库最大索引分片 10,000 个，超额截断报警防 OOM；
 *  4. RRF 倒数排名融合（Reciprocal Rank Fusion）：支持 HYBRID / VECTOR / BM25 检索模式，QA 问答对享受 1.15x 权重加成；
 *  5. 阈值对齐与关键词救援：提供 [0, 1] 校准展示分，支持强关键词命中分片从向量低分中抢救召回。
 *
 * @author xxl-ai 2026-11-10
 */
@Component
public class RagBm25Tool {

    private static final Logger logger = LoggerFactory.getLogger(RagBm25Tool.class);

    /** 单库最大索引分片上限，防堆内存过载 */
    public static final int MAX_INDEXED_CHUNKS_PER_BASE = 10000;

    /** BM25 参数 */
    private static final double BM25_K1 = 1.5d;
    private static final double BM25_B = 0.75d;
    /** BM25 归一化平滑常数 */
    private static final double BM25_NORM_FACTOR = 5.0d;

    /** RRF 常数与权重 */
    private static final double RRF_K = 60.0d;
    private static final double WEIGHT_VECTOR = 0.6d;
    private static final double WEIGHT_BM25 = 0.4d;
    /** QA 问答对加权倍数 */
    private static final double QA_BOOST_MULTIPLIER = 1.15d;

    /** 英文与标识符正则（识别如 ERR_SSO_401、xxl-ai-api、v1.0.2、502） */
    private static final Pattern PATTERN_CODE_TOKEN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_\\-\\.]*[a-zA-Z0-9]|[a-zA-Z0-9]");
    /** 中文字符正则 */
    private static final Pattern PATTERN_CHINESE = Pattern.compile("[\\u4e00-\\u9fa5]");

    /** 常见通用中英文停用词表 */
    private static final Set<String> STOP_WORDS = new HashSet<>();

    static {
        String[] stops = {
                "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一", "一个",
                "上", "也", "很", "到", "说", "要", "去", "你", "会", "着", "没有", "看", "好",
                "自己", "这", "那", "与", "或", "及", "等", "为", "之", "以", "所", "其", "它",
                "the", "a", "an", "is", "are", "was", "were", "of", "and", "in", "to",
                "for", "with", "on", "at", "by", "from", "as", "it", "this", "that"
        };
        Collections.addAll(STOP_WORDS, stops);
    }

    @Resource
    private KnowledgeDocMapper knowledgeDocMapper;

    /** BM25 倒排索引缓存（baseId → Bm25Index），LRU 200 库，5min 过期 */
    private final Cache<Long, Bm25Index> bm25IndexCache = CacheTool.<Long, Bm25Index>newLRUCache(200)
            .expireAfterWrite(5 * 60 * 1000L)
            .build();

    /**
     * 失效指定知识库的 BM25 倒排索引缓存（在文档增删、向量化或库删除时调用）
     *
     * @param baseId 知识库ID
     */
    public void evict(long baseId) {
        bm25IndexCache.remove(baseId);
        logger.debug("BM25 倒排索引缓存已失效, baseId={}", baseId);
    }

    /**
     * 执行分片级 BM25 检索
     *
     * @param baseId 知识库ID
     * @param query  检索内容
     * @param topK   返回数量
     * @return 命中的 BM25 结果列表
     */
    public List<Bm25Hit> searchBm25(long baseId, int chunkSize, int chunkOverlap, String query, int topK) {
        if (StringTool.isBlank(query)) {
            return Collections.emptyList();
        }
        Bm25Index index = getOrBuildIndex(baseId, chunkSize, chunkOverlap);
        if (index == null || index.chunks.isEmpty()) {
            return Collections.emptyList();
        }
        return index.search(query, topK);
    }

    /**
     * 获取或构建指定知识库的 BM25 倒排索引
     */
    public Bm25Index getOrBuildIndex(long baseId, int chunkSize, int chunkOverlap) {
        Bm25Index cached = bm25IndexCache.getIfPresent(baseId);
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = bm25IndexCache.getIfPresent(baseId);
            if (cached != null) {
                return cached;
            }
            List<KnowledgeDoc> docs = knowledgeDocMapper.listByBase(baseId);
            Bm25Index built = buildIndex(baseId, docs, chunkSize, chunkOverlap);
            bm25IndexCache.put(baseId, built);
            return built;
        }
    }

    /**
     * 构建 BM25 倒排索引
     */
    public Bm25Index buildIndex(long baseId, List<KnowledgeDoc> docs, int chunkSize, int chunkOverlap) {
        Bm25Index index = new Bm25Index(baseId);
        if (CollectionTool.isEmpty(docs)) {
            return index;
        }

        int targetChunkSize = Math.max(chunkSize, 100);
        int targetChunkOverlap = Math.min(Math.max(chunkOverlap, 0), targetChunkSize - 1);

        int totalChunksIndexed = 0;
        for (KnowledgeDoc doc : docs) {
            // 仅对状态为已向量化（status=1）或有内容的文档建立索引，与向量库保持数据视图一致
            if (doc.getStatus() != 1 || StringTool.isBlank(doc.getContent())) {
                continue;
            }
            boolean isQaDoc = isQaDoc(doc.getName(), doc.getContent());
            List<String> chunks = RagTextSplitter.split(doc.getContent(), targetChunkSize, targetChunkOverlap);
            for (int i = 0; i < chunks.size(); i++) {
                if (totalChunksIndexed >= MAX_INDEXED_CHUNKS_PER_BASE) {
                    logger.warn("知识库 BM25 索引分片达到上限截断, baseId={}, max={}", baseId, MAX_INDEXED_CHUNKS_PER_BASE);
                    break;
                }
                String chunkText = chunks.get(i);
                boolean isQaChunk = isQaDoc || RagTextSplitter.isQaFormatted(chunkText);
                index.addChunk(doc.getId(), i, chunkText, isQaChunk);
                totalChunksIndexed++;
            }
            if (totalChunksIndexed >= MAX_INDEXED_CHUNKS_PER_BASE) {
                break;
            }
        }

        index.finishBuild();
        logger.debug("BM25 倒排索引构建完成, baseId={}, chunks={}, terms={}",
                baseId, index.chunks.size(), index.invertedIndex.size());
        return index;
    }

    /**
     * 判断文档是否为 QA 问答对文档
     */
    private boolean isQaDoc(String docName, String content) {
        if (docName != null && docName.startsWith("[QA]")) {
            return true;
        }
        return RagTextSplitter.isQaFormatted(content);
    }

    /**
     * 多路召回 RRF 融合与重排（核心算法）
     *
     * @param vectorHits       向量检索命中列表 [{text, docId, chunkIndex, score, ...}]
     * @param bm25Hits         BM25 检索命中列表
     * @param mode             检索模式：HYBRID（默认）、VECTOR、BM25
     * @param similarityThresh 知识库配置的向量相似度阈值
     * @param topK             目标返回条数
     * @param isTestMode       是否为测试台探针模式（探针模式不过滤，仅打 passed 标签）
     * @return 最终融合排序后的命中列表（每项包含 matchType / vectorScore / bm25Score / rrfScore / qaChunk 等）
     */
    public List<Map<String, Object>> fuseWithRrf(List<Map<String, Object>> vectorHits,
                                                 List<Bm25Hit> bm25Hits,
                                                 String mode,
                                                 double similarityThresh,
                                                 int topK,
                                                 boolean isTestMode) {
        String activeMode = StringTool.isNotBlank(mode) ? mode.trim().toUpperCase(Locale.ROOT) : "HYBRID";
        int limit = topK > 0 ? topK : 5;

        // 1. 纯向量模式
        if ("VECTOR".equals(activeMode)) {
            List<Map<String, Object>> result = new ArrayList<>();
            if (CollectionTool.isNotEmpty(vectorHits)) {
                for (Map<String, Object> hit : vectorHits) {
                    double vScore = parseDouble(hit.get("score"));
                    boolean passed = similarityThresh <= 0.0 || vScore >= similarityThresh;
                    if (!isTestMode && !passed) {
                        continue;
                    }
                    Map<String, Object> item = new HashMap<>(hit);
                    item.put("matchType", "VECTOR");
                    item.put("vectorScore", round4(vScore));
                    item.put("bm25Score", 0.0d);
                    item.put("rrfScore", round6(1.0d / (RRF_K + result.size() + 1)));
                    item.put("passed", passed);
                    result.add(item);
                    if (result.size() >= limit && !isTestMode) {
                        break;
                    }
                }
            }
            return result;
        }

        // 2. 纯 BM25 模式
        if ("BM25".equals(activeMode)) {
            List<Map<String, Object>> result = new ArrayList<>();
            if (CollectionTool.isNotEmpty(bm25Hits)) {
                for (Bm25Hit bHit : bm25Hits) {
                    boolean passed = similarityThresh <= 0.0 || bHit.normScore >= Math.min(similarityThresh, 0.40d);
                    if (!isTestMode && !passed) {
                        continue;
                    }
                    Map<String, Object> item = new HashMap<>();
                    item.put("text", bHit.text);
                    item.put("docId", bHit.docId);
                    item.put("chunkIndex", bHit.chunkIndex);
                    item.put("score", round4(bHit.normScore));
                    item.put("matchType", "BM25");
                    item.put("vectorScore", 0.0d);
                    item.put("bm25Score", round4(bHit.score));
                    item.put("bm25NormScore", round4(bHit.normScore));
                    item.put("rrfScore", round6(1.0d / (RRF_K + bHit.rank)));
                    item.put("qaChunk", bHit.qaChunk);
                    item.put("passed", passed);
                    result.add(item);
                    if (result.size() >= limit && !isTestMode) {
                        break;
                    }
                }
            }
            return result;
        }

        // 3. 混合检索（HYBRID）双路融合
        Map<String, FusionCandidate> candidateMap = new LinkedHashMap<>();

        // 注入向量路候选
        if (CollectionTool.isNotEmpty(vectorHits)) {
            for (int i = 0; i < vectorHits.size(); i++) {
                Map<String, Object> vHit = vectorHits.get(i);
                long docId = parseLong(vHit.get("docId"));
                int chunkIndex = (int) parseLong(vHit.get("chunkIndex"));
                String key = docId + "#" + chunkIndex;

                FusionCandidate cand = candidateMap.computeIfAbsent(key, k -> new FusionCandidate(docId, chunkIndex));
                cand.text = vHit.get("text") != null ? String.valueOf(vHit.get("text")) : "";
                cand.vectorRank = i + 1;
                cand.vectorScore = parseDouble(vHit.get("score"));
                cand.hasVector = true;
                if (vHit.get("qaChunk") != null) {
                    cand.qaChunk = Boolean.TRUE.equals(vHit.get("qaChunk"));
                }
            }
        }

        // 注入 BM25 路候选
        if (CollectionTool.isNotEmpty(bm25Hits)) {
            for (int j = 0; j < bm25Hits.size(); j++) {
                Bm25Hit bHit = bm25Hits.get(j);
                String key = bHit.docId + "#" + bHit.chunkIndex;

                FusionCandidate cand = candidateMap.computeIfAbsent(key, k -> new FusionCandidate(bHit.docId, bHit.chunkIndex));
                if (StringTool.isBlank(cand.text)) {
                    cand.text = bHit.text;
                }
                cand.bm25Rank = j + 1;
                cand.bm25Score = bHit.score;
                cand.bm25NormScore = bHit.normScore;
                cand.hasBm25 = true;
                if (bHit.qaChunk) {
                    cand.qaChunk = true;
                }
            }
        }

        // 计算加权 RRF 与校准得分
        List<FusionCandidate> candidates = new ArrayList<>(candidateMap.values());
        for (FusionCandidate cand : candidates) {
            double vRrf = cand.hasVector ? (WEIGHT_VECTOR / (RRF_K + cand.vectorRank)) : 0.0d;
            double bRrf = cand.hasBm25 ? (WEIGHT_BM25 / (RRF_K + cand.bm25Rank)) : 0.0d;
            double rrfTotal = vRrf + bRrf;
            if (cand.qaChunk) {
                rrfTotal *= QA_BOOST_MULTIPLIER;
            }
            cand.rrfScore = rrfTotal;

            // 准入判定（满足向量阈值 或 满足 BM25 强关键词救援阈值）
            boolean vectorPassed = cand.hasVector && (similarityThresh <= 0.0 || cand.vectorScore >= similarityThresh);
            boolean bm25Passed = cand.hasBm25 && cand.bm25NormScore >= Math.max(0.40d, similarityThresh);
            cand.passed = similarityThresh <= 0.0 || vectorPassed || bm25Passed;

            // matchType 与综合展示分校准
            if (cand.hasVector && cand.hasBm25) {
                cand.matchType = "HYBRID";
                // 双路命中：在向量分基础上叠加关键词增益，上限 1.0
                cand.finalScore = Math.min(1.0d, cand.vectorScore + Math.min(0.15d, cand.bm25NormScore * 0.15d));
            } else if (cand.hasVector) {
                cand.matchType = "VECTOR";
                cand.finalScore = cand.vectorScore;
            } else {
                cand.matchType = "BM25";
                // 纯关键词命中：按 BM25 归一化分映射
                cand.finalScore = Math.min(1.0d, cand.bm25NormScore);
            }
        }

        // 按加权 RRF 得分降序排序
        candidates.sort((o1, o2) -> Double.compare(o2.rrfScore, o1.rrfScore));

        List<Map<String, Object>> result = new ArrayList<>();
        for (FusionCandidate cand : candidates) {
            if (!isTestMode && !cand.passed) {
                continue;
            }
            Map<String, Object> item = new HashMap<>();
            item.put("text", cand.text);
            item.put("docId", cand.docId);
            item.put("chunkIndex", cand.chunkIndex);
            item.put("score", round4(cand.finalScore));
            item.put("matchType", cand.matchType);
            item.put("vectorScore", round4(cand.vectorScore));
            item.put("bm25Score", round4(cand.bm25Score));
            item.put("bm25NormScore", round4(cand.bm25NormScore));
            item.put("rrfScore", round6(cand.rrfScore));
            item.put("qaChunk", cand.qaChunk);
            item.put("passed", cand.passed);
            result.add(item);
            if (result.size() >= limit && !isTestMode) {
                break;
            }
        }

        return result;
    }

    /**
     * 中英文混合分词（连续英文/数字/代码标识符 + 中文 Uni-gram &amp; Bi-gram + 停用词过滤）
     */
    public static List<String> tokenize(String text) {
        if (StringTool.isBlank(text)) {
            return Collections.emptyList();
        }
        List<String> tokens = new ArrayList<>();
        String normalized = text.toLowerCase(Locale.ROOT);

        // 1. 抽取连续的英文、数字、标识符代码词元
        Matcher codeMatcher = PATTERN_CODE_TOKEN.matcher(normalized);
        while (codeMatcher.find()) {
            String token = codeMatcher.group();
            if (token.length() >= 2 && !STOP_WORDS.contains(token)) {
                tokens.add(token);
            }
        }

        // 2. 抽取中文 Uni-gram 与 Bi-gram
        List<String> chineseChars = new ArrayList<>();
        Matcher zhMatcher = PATTERN_CHINESE.matcher(normalized);
        while (zhMatcher.find()) {
            chineseChars.add(zhMatcher.group());
        }

        for (int i = 0; i < chineseChars.size(); i++) {
            String single = chineseChars.get(i);
            if (!STOP_WORDS.contains(single)) {
                tokens.add(single);
            }
            if (i < chineseChars.size() - 1) {
                String bigram = single + chineseChars.get(i + 1);
                tokens.add(bigram);
            }
        }

        return tokens;
    }

    // ==================== 内部模型与结构 ====================

    /**
     * 单知识库 BM25 内存倒排索引
     */
    public static class Bm25Index {
        private final long baseId;
        private final List<IndexedChunk> chunks = new ArrayList<>();
        private final Map<String, List<Posting>> invertedIndex = new HashMap<>();
        private double avgdl = 0.0d;

        public Bm25Index(long baseId) {
            this.baseId = baseId;
        }

        public synchronized void addChunk(long docId, int chunkIndex, String text, boolean qaChunk) {
            List<String> tokens = tokenize(text);
            int internalId = chunks.size();
            IndexedChunk chunk = new IndexedChunk(internalId, docId, chunkIndex, text, qaChunk, tokens.size());
            chunks.add(chunk);

            // 统计词频
            Map<String, Integer> tfMap = new HashMap<>();
            for (String token : tokens) {
                tfMap.merge(token, 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> entry : tfMap.entrySet()) {
                invertedIndex.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                        .add(new Posting(internalId, entry.getValue()));
            }
        }

        public synchronized void finishBuild() {
            if (chunks.isEmpty()) {
                avgdl = 0.0d;
                return;
            }
            long totalTokens = 0;
            for (IndexedChunk chunk : chunks) {
                totalTokens += chunk.tokenCount;
            }
            avgdl = (double) totalTokens / chunks.size();
        }

        public List<Bm25Hit> search(String query, int topK) {
            List<String> queryTokens = tokenize(query);
            if (queryTokens.isEmpty() || chunks.isEmpty()) {
                return Collections.emptyList();
            }

            int N = chunks.size();
            Map<Integer, Double> scoreMap = new HashMap<>();

            for (String term : queryTokens) {
                List<Posting> postings = invertedIndex.get(term);
                if (CollectionTool.isEmpty(postings)) {
                    continue;
                }
                int n_q = postings.size();
                // 经典 Okapi BM25 IDF: ln(1 + (N - n(q) + 0.5) / (n(q) + 0.5))
                double idf = Math.log(1.0d + (N - n_q + 0.5d) / (n_q + 0.5d));
                if (idf <= 0.0d) {
                    idf = 0.01d;
                }

                for (Posting p : postings) {
                    IndexedChunk chunk = chunks.get(p.chunkInternalId);
                    double tf = p.termFreq;
                    double denom = tf + BM25_K1 * (1.0d - BM25_B + BM25_B * (chunk.tokenCount / (avgdl > 0 ? avgdl : 1.0d)));
                    double termScore = idf * (tf * (BM25_K1 + 1.0d)) / (denom > 0 ? denom : 1.0d);
                    scoreMap.merge(p.chunkInternalId, termScore, Double::sum);
                }
            }

            if (scoreMap.isEmpty()) {
                return Collections.emptyList();
            }

            List<Map.Entry<Integer, Double>> entryList = new ArrayList<>(scoreMap.entrySet());
            entryList.sort((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()));

            int limit = topK > 0 ? Math.min(topK, entryList.size()) : entryList.size();
            List<Bm25Hit> hits = new ArrayList<>(limit);

            for (int i = 0; i < limit; i++) {
                Map.Entry<Integer, Double> entry = entryList.get(i);
                IndexedChunk chunk = chunks.get(entry.getKey());
                double rawScore = entry.getValue();
                // 归一化到 [0, 1) 区间: raw / (raw + FACTOR)
                double normScore = rawScore / (rawScore + BM25_NORM_FACTOR);
                hits.add(new Bm25Hit(chunk.docId, chunk.chunkIndex, chunk.text, chunk.qaChunk, rawScore, normScore, i + 1));
            }

            return hits;
        }

        public long getBaseId() {
            return baseId;
        }
    }

    public static class IndexedChunk {
        public final int internalId;
        public final long docId;
        public final int chunkIndex;
        public final String text;
        public final boolean qaChunk;
        public final int tokenCount;

        public IndexedChunk(int internalId, long docId, int chunkIndex, String text, boolean qaChunk, int tokenCount) {
            this.internalId = internalId;
            this.docId = docId;
            this.chunkIndex = chunkIndex;
            this.text = text;
            this.qaChunk = qaChunk;
            this.tokenCount = tokenCount;
        }
    }

    public static class Posting {
        public final int chunkInternalId;
        public final int termFreq;

        public Posting(int chunkInternalId, int termFreq) {
            this.chunkInternalId = chunkInternalId;
            this.termFreq = termFreq;
        }
    }

    public static class Bm25Hit {
        public final long docId;
        public final int chunkIndex;
        public final String text;
        public final boolean qaChunk;
        public final double score;
        public final double normScore;
        public final int rank;

        public Bm25Hit(long docId, int chunkIndex, String text, boolean qaChunk, double score, double normScore, int rank) {
            this.docId = docId;
            this.chunkIndex = chunkIndex;
            this.text = text;
            this.qaChunk = qaChunk;
            this.score = score;
            this.normScore = normScore;
            this.rank = rank;
        }
    }

    private static class FusionCandidate {
        final long docId;
        final int chunkIndex;
        String text = "";
        boolean qaChunk = false;
        boolean hasVector = false;
        int vectorRank = 9999;
        double vectorScore = 0.0d;
        boolean hasBm25 = false;
        int bm25Rank = 9999;
        double bm25Score = 0.0d;
        double bm25NormScore = 0.0d;
        double rrfScore = 0.0d;
        double finalScore = 0.0d;
        String matchType = "HYBRID";
        boolean passed = false;

        FusionCandidate(long docId, int chunkIndex) {
            this.docId = docId;
            this.chunkIndex = chunkIndex;
        }
    }

    private static long parseLong(Object obj) {
        if (obj == null) return 0L;
        if (obj instanceof Number num) return num.longValue();
        try {
            return Long.parseLong(String.valueOf(obj).trim());
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static double parseDouble(Object obj) {
        if (obj == null) return 0.0d;
        if (obj instanceof Number num) return num.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(obj).trim());
        } catch (Exception ignored) {
            return 0.0d;
        }
    }

    private static double round4(double val) {
        return Math.round(val * 10000.0d) / 10000.0d;
    }

    private static double round6(double val) {
        return Math.round(val * 1000000.0d) / 1000000.0d;
    }
}
