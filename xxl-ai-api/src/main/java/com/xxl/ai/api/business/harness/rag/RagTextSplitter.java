package com.xxl.ai.api.business.harness.rag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * RAG 递归文本分块器（按语义边界逐级切分，防断词断句，保护代理对）
 *
 * @author xxl-ai 2026-11-10
 */
public final class RagTextSplitter {

    /* 段落分隔符（保留末尾换行） */
    private static final Pattern PATTERN_PARAGRAPH = Pattern.compile("(?<=\\n\\n)");

    /* 单行换行分隔符（保留末尾换行） */
    private static final Pattern PATTERN_LINE = Pattern.compile("(?<=\\n)");

    /* 句末标点分隔符（保留标点） */
    private static final Pattern PATTERN_SENTENCE = Pattern.compile("(?<=[。！？；.!?;])");

    /* 空格分隔符（保留空格） */
    private static final Pattern PATTERN_SPACE = Pattern.compile("(?<= )");

    private RagTextSplitter() {
    }

    /**
     * 递归切分文本为分块
     *
     * @param content      待分片原始文本
     * @param chunkSize    目标分片大小（字符数，>=1）
     * @param chunkOverlap 重叠大小（字符数，0 <= overlap < chunkSize）
     * @return 分片文本列表
     */
    public static List<String> split(String content, int chunkSize, int chunkOverlap) {
        if (content == null || content.isEmpty()) {
            return Collections.emptyList();
        }
        int size = Math.max(chunkSize, 1);
        int overlap = Math.min(Math.max(chunkOverlap, 0), size - 1);

        if (content.length() <= size) {
            return Collections.singletonList(content);
        }

        // 1. 递归细化切分为原子语义片段
        List<String> pieces = breakDown(content, 0, size);
        if (pieces.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 贪心合并为不超过 chunkSize 的分块，并以完整片段为单位向前重叠
        List<String> chunks = new ArrayList<>();
        int startIndex = 0;
        int totalPieces = pieces.size();

        while (startIndex < totalPieces) {
            StringBuilder chunkBuilder = new StringBuilder();
            int endIndex = startIndex;
            while (endIndex < totalPieces) {
                String nextPiece = pieces.get(endIndex);
                if (chunkBuilder.length() > 0 && chunkBuilder.length() + nextPiece.length() > size) {
                    break;
                }
                chunkBuilder.append(nextPiece);
                endIndex++;
            }

            int lastIncludedIndex = endIndex - 1;
            chunks.add(chunkBuilder.toString());

            if (endIndex >= totalPieces) {
                break;
            }

            // 3. 计算下一个分块的起始索引（以完整语义片段为单位回溯 overlap）
            int nextStart = endIndex;
            if (overlap > 0) {
                int overlapLength = 0;
                int candidateStart = endIndex;
                for (int j = lastIncludedIndex; j > startIndex; j--) {
                    int pieceLength = pieces.get(j).length();
                    if (overlapLength + pieceLength <= overlap) {
                        overlapLength += pieceLength;
                        candidateStart = j;
                    } else {
                        break;
                    }
                }
                nextStart = candidateStart;
            }

            // 确保严格向前推进，防止死循环
            if (nextStart <= startIndex) {
                nextStart = startIndex + 1;
            }
            startIndex = nextStart;
        }

        return chunks;
    }

    /**
     * 逐级按语义边界递归拆分超长文本
     * 优先级：段落(\n\n) → 换行(\n) → 标点(。！？；.!?;) → 空格( ) → Unicode代码点
     */
    private static List<String> breakDown(String text, int level, int maxLen) {
        if (text.length() <= maxLen) {
            return Collections.singletonList(text);
        }

        Pattern pattern = null;
        switch (level) {
            case 0:
                pattern = PATTERN_PARAGRAPH;
                break;
            case 1:
                pattern = PATTERN_LINE;
                break;
            case 2:
                pattern = PATTERN_SENTENCE;
                break;
            case 3:
                pattern = PATTERN_SPACE;
                break;
            default:
                break;
        }

        List<String> result = new ArrayList<>();
        if (pattern != null) {
            String[] parts = pattern.split(text, -1);
            List<String> nonEmptyParts = new ArrayList<>(parts.length);
            for (String part : parts) {
                if (!part.isEmpty()) {
                    nonEmptyParts.add(part);
                }
            }
            if (nonEmptyParts.size() > 1) {
                for (String part : nonEmptyParts) {
                    result.addAll(breakDown(part, level + 1, maxLen));
                }
                return result;
            } else {
                return breakDown(text, level + 1, maxLen);
            }
        }

        // 第 4 级兜底：按 Unicode CodePoint 拆分，保护 4 字节 Emoji 代理对不被劈开
        int[] codePoints = text.codePoints().toArray();
        for (int cp : codePoints) {
            result.add(new String(Character.toChars(cp)));
        }
        return result;
    }
}
