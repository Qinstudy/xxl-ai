package com.xxl.ai.api.business.harness.skill;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xxl.ai.api.business.harness.chat.TraceCollector;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Agent 交付物产物沙箱（[FEAT-005]）单元测试
 *
 * @author xxl-ai 2026-11-08
 */
public class ArtifactSandboxTest {

    private static final Gson GSON = new Gson();

    @Test
    @DisplayName("1. [FEAT-005] 验证基线采集、差异识别（含同长度同秒覆写）与扩展名/目录过滤")
    public void testBaselineAndDiffArchiving(@TempDir Path tempDir) throws IOException {
        Path skillRoot = tempDir.resolve("skills");
        SkillArtifactTool tool = createTool(skillRoot, null);

        // 预先物化 Agent Skill 与已有交付物
        Path agentDir = skillRoot.resolve("agent_7");
        Path skillSubDir = agentDir.resolve("db-audit");
        Files.createDirectories(skillSubDir);
        Files.writeString(skillSubDir.resolve("SKILL.md"), "# DB Audit Skill", StandardCharsets.UTF_8);
        Files.writeString(skillSubDir.resolve("template.sql"), "SELECT 1;", StandardCharsets.UTF_8);
        Path modifiedSql = agentDir.resolve("existing.sql");
        Files.writeString(modifiedSql, "SELECT 'A';", StandardCharsets.UTF_8);
        FileTime fixedTime = FileTime.fromMillis(System.currentTimeMillis() - 5000);
        Files.setLastModifiedTime(modifiedSql, fixedTime);

        Map<String, SkillArtifactTool.FileFingerprint> baseline = tool.captureBaseline(7L);
        Assertions.assertEquals(2, baseline.size(), "仅基线内 .sql 纳入快照（SKILL.md 被排除）");

        // 模拟 Run 执行期间产物变更：
        // (1) 同长度 + 同时间戳覆写 existing.sql（校验 CRC32 差意识别）
        Files.writeString(modifiedSql, "SELECT 'B';", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(modifiedSql, fixedTime);

        // (2) 新增四类受支持产物（.sql / .html / .csv / .md）
        Path outDir = agentDir.resolve("output");
        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve("user_report.sql"), "SHOW CREATE TABLE xxl_ai_user;", StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("analysis.html"), "<html><body><h1>Report</h1></body></html>", StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("stats.csv"), "id,name\n1,admin\n", StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("summary.md"), "# Summary Report\nAll checks passed.\n", StandardCharsets.UTF_8);

        // 模拟 Run 期间哪怕有人更新了 SKILL.md，也严禁作为产物归档
        Files.writeString(skillSubDir.resolve("SKILL.md"), "# DB Audit Skill (Modified)", StandardCharsets.UTF_8);

        // (3) 新增应被忽略的文件（非白名单后缀、点文件、node_modules）
        Files.writeString(outDir.resolve("helper.py"), "print('hi')", StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve(".hidden.sql"), "SELECT 0;", StandardCharsets.UTF_8);
        Path ignoredNodeModules = agentDir.resolve("node_modules").resolve("demo");
        Files.createDirectories(ignoredNodeModules);
        Files.writeString(ignoredNodeModules.resolve("ignored.html"), "<html/>", StandardCharsets.UTF_8);

        List<TraceCollector.ArtifactItem> archived = tool.archiveRunArtifacts(7L, 101L, 501L, baseline);
        Assertions.assertEquals(5, archived.size(), "仅归档发生修改或新生成的 5 个 .sql/.html/.csv/.md 交付物");

        // template.sql 与 SKILL.md 未变化或被过滤，不应重复归档；源文件应在 workdir 保留（拷贝而非移动）
        Assertions.assertTrue(Files.exists(outDir.resolve("user_report.sql")));
        Assertions.assertTrue(Files.exists(outDir.resolve("summary.md")));
        Path runDir = skillRoot.resolve("_artifacts").resolve("session_101").resolve("run_501");
        for (TraceCollector.ArtifactItem item : archived) {
            Assertions.assertTrue(Files.exists(runDir.resolve(item.getStoredName())));
            Assertions.assertNotEquals("template.sql", item.getFileName());
            Assertions.assertNotEquals("SKILL.md", item.getFileName());
        }
        TraceCollector.ArtifactItem mdItem = archived.stream()
                .filter(i -> "summary.md".equals(i.getFileName()))
                .findFirst()
                .orElse(null);
        Assertions.assertNotNull(mdItem);
        Assertions.assertEquals("md", mdItem.getExt());
        Assertions.assertEquals("text/markdown; charset=UTF-8", mdItem.getContentType());
    }

    @Test
    @DisplayName("2. [FEAT-005] 验证跨子目录同名（含大小写差异）交付物去重归档不互相覆盖")
    public void testSameNameDisambiguation(@TempDir Path tempDir) throws IOException {
        Path skillRoot = tempDir.resolve("skills");
        Path customArtifactRoot = tempDir.resolve("custom-artifacts");
        SkillArtifactTool tool = createTool(skillRoot, customArtifactRoot);

        Map<String, SkillArtifactTool.FileFingerprint> baseline = tool.captureBaseline(8L);
        Assertions.assertTrue(baseline.isEmpty());

        Path agentDir = skillRoot.resolve("agent_8");
        Files.createDirectories(agentDir.resolve("a"));
        Files.createDirectories(agentDir.resolve("b"));
        Files.createDirectories(agentDir.resolve("c"));
        Files.writeString(agentDir.resolve("a").resolve("Report.csv"), "col\na\n", StandardCharsets.UTF_8);
        Files.writeString(agentDir.resolve("b").resolve("report.csv"), "col\nb\n", StandardCharsets.UTF_8);
        Files.writeString(agentDir.resolve("c").resolve("REPORT.CSV"), "col\nc\n", StandardCharsets.UTF_8);

        List<TraceCollector.ArtifactItem> archived = tool.archiveRunArtifacts(8L, 102L, 502L, baseline);
        Assertions.assertEquals(3, archived.size());

        Set<String> lowerStoredNames = new HashSet<>();
        Path runDir = customArtifactRoot.resolve("session_102").resolve("run_502");
        for (TraceCollector.ArtifactItem item : archived) {
            lowerStoredNames.add(item.getStoredName().toLowerCase(Locale.ROOT));
            Assertions.assertTrue(Files.exists(runDir.resolve(item.getStoredName())));
        }
        Assertions.assertEquals(3, lowerStoredNames.size(), "大小写不敏感维度必须分配 3 个不同 storedName");
    }

    @Test
    @DisplayName("3. [FEAT-005] 验证 512KB 预览截断与路径穿越防护、TraceCollector 序列化及会话级清理")
    public void testPreviewTruncationSecurityAndSessionCleanup(@TempDir Path tempDir) throws IOException {
        Path skillRoot = tempDir.resolve("skills");
        SkillArtifactTool tool = createTool(skillRoot, null);

        Path agentDir = skillRoot.resolve("agent_9");
        Files.createDirectories(agentDir);
        // 写入约 600KB UTF-8（包含多字节中文，确保截断处不崩溃）
        String chunk = "-- 测试交付物 SQL 语句与注释内容\nSELECT id, username FROM xxl_ai_user;\n";
        StringBuilder largeSql = new StringBuilder();
        while (largeSql.toString().getBytes(StandardCharsets.UTF_8).length <= 600 * 1024) {
            largeSql.append(chunk);
        }
        byte[] allBytes = largeSql.toString().getBytes(StandardCharsets.UTF_8);
        Files.write(agentDir.resolve("large_export.sql"), allBytes);

        List<TraceCollector.ArtifactItem> archived = tool.archiveRunArtifacts(9L, 201L, 601L, Map.of());
        Assertions.assertEquals(1, archived.size());
        TraceCollector.ArtifactItem item = archived.get(0);

        // (1) 验证 512KB 预览截断
        SkillArtifactTool.PreviewData preview = tool.readPreview(201L, 601L, item);
        Assertions.assertTrue(preview.truncated());
        Assertions.assertEquals(allBytes.length, preview.sizeBytes());
        Assertions.assertTrue(preview.content().contains("xxl_ai_user"));

        // (2) 验证下载定位成功
        SkillArtifactTool.DownloadFile dl = tool.resolveDownloadFile(201L, 601L, item);
        Assertions.assertEquals("large_export.sql", dl.fileName());
        Assertions.assertEquals(allBytes.length, dl.sizeBytes());

        // (3) 验证非法路径穿越 storedName 拦截
        TraceCollector.ArtifactItem tampered = new TraceCollector.ArtifactItem();
        tampered.setArtifactId(item.getArtifactId());
        tampered.setFileName("evil.sql");
        tampered.setStoredName("../evil.sql");
        Assertions.assertThrows(IllegalArgumentException.class, () -> tool.readPreview(201L, 601L, tampered));
        Assertions.assertThrows(IllegalArgumentException.class, () -> tool.resolveDownloadFile(201L, 601L, tampered));

        // (4) 验证 TraceCollector 序列化与静态检索 findArtifact
        TraceCollector collector = new TraceCollector(601L, 201L, 9L, "worker-0");
        collector.addArtifacts(archived);
        collector.completeRun("COMPLETED", null);
        String extJson = collector.toExtJson();
        JsonObject root = GSON.fromJson(extJson, JsonObject.class);
        Assertions.assertTrue(root.has("artifacts"));
        Assertions.assertEquals(1, root.getAsJsonArray("artifacts").size());

        TraceCollector.ArtifactItem found = TraceCollector.findArtifact(extJson, item.getArtifactId());
        Assertions.assertNotNull(found);
        Assertions.assertEquals("large_export.sql", found.getFileName());
        Assertions.assertNull(TraceCollector.findArtifact(extJson, "non-existent"));

        // (5) 验证会话级产物目录级联清理
        Path sessionDir = skillRoot.resolve("_artifacts").resolve("session_201");
        Assertions.assertTrue(Files.exists(sessionDir));
        tool.deleteBySessions(List.of(201L));
        Assertions.assertFalse(Files.exists(sessionDir));
    }

    private SkillArtifactTool createTool(Path skillRoot, Path artifactRoot) {
        SkillArtifactTool tool = new SkillArtifactTool();
        ReflectionTestUtils.setField(tool, "skillRoot", skillRoot.toString());
        ReflectionTestUtils.setField(tool, "artifactRoot", artifactRoot == null ? "" : artifactRoot.toString());
        return tool;
    }

}
