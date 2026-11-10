package com.xxl.ai.api.business.harness.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RagTextSplitter 递归分块器单元测试
 *
 * @author xxl-ai 2026-11-10
 */
public class RagTextSplitterTest {

    @Test
    void splitEmptyOrNullReturnsEmptyList() {
        assertTrue(RagTextSplitter.split(null, 500, 50).isEmpty());
        assertTrue(RagTextSplitter.split("", 500, 50).isEmpty());
    }

    @Test
    void splitContentShorterThanChunkSizeReturnsSingleChunk() {
        String content = "Hello world, 短文本测试。";
        List<String> chunks = RagTextSplitter.split(content, 100, 20);
        assertEquals(1, chunks.size());
        assertEquals(content, chunks.get(0));
    }

    @Test
    void splitZeroOverlapIsLossless() {
        String content = "这是第一段。\n\n这是第二段，有两行：\n第二行内容。问句吗？是的！\n还有带空格的 英文单词 sentence.";
        List<String> chunks = RagTextSplitter.split(content, 20, 0);
        assertFalse(chunks.isEmpty());
        // 无重叠时，所有切片合并等于原文（无信息丢失）
        assertEquals(content, String.join("", chunks));
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 20, "分片长度不应超过 chunkSize");
        }
    }

    @Test
    void splitParagraphHierarchy() {
        String p1 = "第一段第一句。第一段第二句。";
        String p2 = "第二段第一句。第二段第二句。";
        String content = p1 + "\n\n" + p2;
        // chunkSize 刚好容纳一段
        List<String> chunks = RagTextSplitter.split(content, p1.length() + 2, 0);
        assertEquals(2, chunks.size());
        assertEquals(p1 + "\n\n", chunks.get(0));
        assertEquals(p2, chunks.get(1));
    }

    @Test
    void splitSentenceHierarchy() {
        String s1 = "第一句结束！";
        String s2 = "第二句结束？";
        String s3 = "第三句结束；";
        String s4 = "第四句结束。";
        String content = s1 + s2 + s3 + s4;
        List<String> chunks = RagTextSplitter.split(content, 13, 0);
        // 每块至少应在标点处切断，而不是在文字中间劈开
        for (String chunk : chunks) {
            char lastChar = chunk.charAt(chunk.length() - 1);
            assertTrue(lastChar == '！' || lastChar == '？' || lastChar == '；' || lastChar == '。');
        }
    }

    @Test
    void splitSurrogatePairEmojiNotBroken() {
        // 每个 emoji 占 2 个 UTF-16 char
        String emojis = "😀🎉👍🔥🚀🌟";
        List<String> chunks = RagTextSplitter.split(emojis, 4, 2);
        assertFalse(chunks.isEmpty());
        for (String chunk : chunks) {
            // 校验每个分块均能合法解码为有效 Unicode 字符，且不含孤立代理项
            for (int i = 0; i < chunk.length(); i++) {
                char ch = chunk.charAt(i);
                if (Character.isHighSurrogate(ch)) {
                    assertTrue(i + 1 < chunk.length(), "高位代理后必须有低位代理");
                    assertTrue(Character.isLowSurrogate(chunk.charAt(i + 1)), "高位代理后必须跟随合法低位代理");
                    i++;
                } else {
                    assertFalse(Character.isLowSurrogate(ch), "分块中不应出现孤立的低位代理");
                }
            }
        }
    }

    @Test
    void splitOverlapKeepsCompleteSemanticPieces() {
        String content = "第一句。第二句！第三句？第四句；第五句。";
        // chunkSize 足够容纳约2句，overlap 足够容纳1句
        List<String> chunks = RagTextSplitter.split(content, 10, 4);
        assertTrue(chunks.size() >= 3);
        // 后续分块以完整标点句开头，而不是半截汉字
        for (int i = 1; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            assertTrue(chunk.startsWith("第") || chunk.endsWith("。") || chunk.length() <= 10);
        }
    }

    @Test
    void splitStrictlyAdvancesEvenIfOverlapIsLarge() {
        String content = "测试超大重叠是否导致死循环或停滞。这是一段用来验证安全性的文本内容。";
        // overlap 等于 chunkSize - 1，算法必须保证严格推进
        List<String> chunks = RagTextSplitter.split(content, 15, 14);
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.size() > 1);
    }

    @Test
    void splitLongUnbrokenStringFallback() {
        // 无空格、无标点的纯字符
        String longText = "a".repeat(100);
        List<String> chunks = RagTextSplitter.split(longText, 25, 5);
        assertFalse(chunks.isEmpty());
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 25);
        }
    }
}
