package com.xxl.ai.api.business.harness.eval;

import com.xxl.ai.api.business.harness.chat.TraceCollector;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 评测引擎离散指标与算法单测
 */
public class EvalEngineToolTest {

    private EvalEngineTool evalEngineTool;

    @BeforeEach
    public void setUp() {
        evalEngineTool = new EvalEngineTool();
    }

    private TraceCollector.RagReference buildRef(String docName, String content) {
        TraceCollector.RagReference ref = new TraceCollector.RagReference();
        ref.setDocName(docName);
        ref.setContent(content);
        return ref;
    }

    @Test
    public void testCalculateHitRate() {
        List<TraceCollector.RagReference> refs = new ArrayList<>();
        refs.add(buildRef("开发规范.md", "这里介绍了微服务架构"));
        refs.add(buildRef("数据库设计.md", "MySQL 8.4 表结构"));
        refs.add(buildRef("部署运维.md", "Docker Compose 部署"));

        // 1、首位切片文档名命中 HitRate@1
        double hitAt1 = evalEngineTool.calculateHitRate(
                Collections.singletonList("开发规范.md"),
                Collections.emptyList(),
                refs,
                1
        );
        Assertions.assertEquals(1.0, hitAt1, 0.0001);

        // 2、第二位切片关键词命中，但查 K=1 时未命中
        double hitAt1Fail = evalEngineTool.calculateHitRate(
                Collections.emptyList(),
                Collections.singletonList("MySQL"),
                refs,
                1
        );
        Assertions.assertEquals(0.0, hitAt1Fail, 0.0001);

        // 3、第二位切片在 K=3 时命中
        double hitAt3 = evalEngineTool.calculateHitRate(
                Collections.emptyList(),
                Collections.singletonList("MySQL"),
                refs,
                3
        );
        Assertions.assertEquals(1.0, hitAt3, 0.0001);

        // 4、完全未命中
        double hitNone = evalEngineTool.calculateHitRate(
                Collections.singletonList("未命中文档.md"),
                Collections.singletonList("Redis哨兵"),
                refs,
                3
        );
        Assertions.assertEquals(0.0, hitNone, 0.0001);
    }

    @Test
    public void testCalculateMrr() {
        List<TraceCollector.RagReference> refs = new ArrayList<>();
        refs.add(buildRef("无关文档A.md", "普通介绍内容"));
        refs.add(buildRef("命中目标B.md", "核心业务逻辑"));
        refs.add(buildRef("命中目标C.md", "备份配置说明"));

        // 命中第二个切片，MRR 应当是 1.0 / 2 = 0.5
        double mrrRank2 = evalEngineTool.calculateMrr(
                Collections.singletonList("目标B"),
                Collections.emptyList(),
                refs
        );
        Assertions.assertEquals(0.5, mrrRank2, 0.0001);

        // 命中第一个切片，MRR 应当是 1.0 / 1 = 1.0
        double mrrRank1 = evalEngineTool.calculateMrr(
                Collections.singletonList("无关文档A"),
                Collections.emptyList(),
                refs
        );
        Assertions.assertEquals(1.0, mrrRank1, 0.0001);

        // 命中第三个切片关键词，MRR 应当是 1.0 / 3 = 0.3333
        double mrrRank3 = evalEngineTool.calculateMrr(
                Collections.emptyList(),
                Collections.singletonList("备份配置"),
                refs
        );
        Assertions.assertEquals(1.0 / 3.0, mrrRank3, 0.0001);

        // 均未命中，MRR 应当是 0.0
        double mrrZero = evalEngineTool.calculateMrr(
                Collections.singletonList("不存在"),
                Collections.emptyList(),
                refs
        );
        Assertions.assertEquals(0.0, mrrZero, 0.0001);
    }

    @Test
    public void testIsChunkMatched() {
        TraceCollector.RagReference ref = buildRef("CLOUD_HANDOVER_CONTEXT.md", "默认 streamTimeout 为 180000ms");

        // 模糊文档名
        Assertions.assertTrue(evalEngineTool.isChunkMatched(ref, Collections.singletonList("HANDOVER"), null));
        // 关键 Token
        Assertions.assertTrue(evalEngineTool.isChunkMatched(ref, null, Collections.singletonList("180000ms")));
        // 大小写不敏感
        Assertions.assertTrue(evalEngineTool.isChunkMatched(ref, null, Collections.singletonList("STREAMTIMEOUT")));
        // 不匹配
        Assertions.assertFalse(evalEngineTool.isChunkMatched(ref, Collections.singletonList("OTHER.md"), Collections.singletonList("60000ms")));
    }
}
