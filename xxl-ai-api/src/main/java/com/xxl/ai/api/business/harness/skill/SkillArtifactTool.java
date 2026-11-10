package com.xxl.ai.api.business.harness.skill;

import com.xxl.ai.api.business.harness.chat.TraceCollector;
import com.xxl.tool.core.CollectionTool;
import com.xxl.tool.core.StringTool;
import com.xxl.tool.id.UUIDTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.CRC32;

/**
 * Agent 交付物产物沙箱工具（[FEAT-005]）：运行前基线采集、Run 结束差异归档、安全预览读取与会话级清理
 *
 * 目录隔离与生命周期约定：
 *  1、工作沙箱：{xxl-ai.skill.root}/agent_{agentId}（SkillToolFactory 物化与 Shell/File 工具读写目录）；
 *  2、产物归档区：{xxl-ai.artifact.root}（未配置时默认 {xxl-ai.skill.root}/_artifacts）/session_{sessionId}/run_{msgId}/{storedName}；
 *     与 agent_{agentId} 物理隔离，既避免 SkillToolFactory 全量重建误删历史交付物，又保证多轮/多访客产物互不覆盖；
 *  3、安全防线：扫描与读取全程禁止跟随符号链接、强制规范路径边界包含检查，阻断目录穿越与逃逸风险。
 *
 * @author xxl-ai 2026-11-08
 */
@Component
public class SkillArtifactTool {

    private static final Logger logger = LoggerFactory.getLogger(SkillArtifactTool.class);

    /** 支持自动归档的交付物扩展名（小写） */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("sql", "html", "csv", "md");
    /** 技能定义描述文件名（小写，作为工作区元数据严禁纳入产物归档） */
    private static final String SKILL_DEFINITION_FILE = "skill.md";
    /** 扫描跳过目录（包管理与虚拟环境目录） */
    private static final Set<String> IGNORED_DIR_NAMES = Set.of(".git", "node_modules", "__pycache__", "venv", ".venv");

    /** 沙箱目录最大遍历深度 */
    private static final int MAX_SCAN_DEPTH = 8;
    /** 单个交付物最大归档体积：10MB */
    private static final long MAX_ARCHIVE_FILE_BYTES = 10L * 1024 * 1024;
    /** 单次 Run 最多归档交付物数量 */
    private static final int MAX_ARTIFACTS_PER_RUN = 20;
    /** 在线预览读取最大字节数：512KB */
    private static final int MAX_PREVIEW_BYTES = 512 * 1024;

    /** Skill 沙箱根目录 */
    @Value("${xxl-ai.skill.root}")
    private String skillRoot;

    /** 产物归档根目录（可空；为空时回落至 skillRoot/_artifacts） */
    @Value("${xxl-ai.artifact.root:}")
    private String artifactRoot;

    /**
     * 文件指纹快照：大小 + 修改时间 + CRC32（避免同一秒内覆写同名文件漏检）
     */
    public record FileFingerprint(long sizeBytes, long lastModifiedMillis, long crc32) {
    }

    /**
     * 在线预览文本数据
     */
    public record PreviewData(String artifactId,
                              String fileName,
                              String ext,
                              long sizeBytes,
                              boolean truncated,
                              String content,
                              long createdTime) {
    }

    /**
     * 下载定位信息
     */
    public record DownloadFile(Path file, String fileName, String contentType, long sizeBytes) {
    }

    /**
     * 内部扫描候选文件信息
     */
    private record CandidateFile(Path path,
                                 String relativePath,
                                 String fileName,
                                 String ext,
                                 FileFingerprint fingerprint) {
    }

    /**
     * 采集 Run 执行前工作沙箱交付物基线快照（挂载完工具并同步完 Skill 目录后调用）
     *
     * @param agentId Agent ID
     * @return relativePath -> 文件指纹
     */
    public Map<String, FileFingerprint> captureBaseline(long agentId) {
        if (agentId <= 0) {
            return Collections.emptyMap();
        }
        Map<String, CandidateFile> scanned = scanDeliverables(agentRoot(agentId));
        if (scanned.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, FileFingerprint> baseline = new LinkedHashMap<>();
        for (Map.Entry<String, CandidateFile> entry : scanned.entrySet()) {
            baseline.put(entry.getKey(), entry.getValue().fingerprint());
        }
        return baseline;
    }

    /**
     * 比对基线并拷贝归档本次 Run 新增或修改的交付物到 session_{sessionId}/run_{msgId}
     *
     * @param agentId   Agent ID
     * @param sessionId 对话 ID
     * @param msgId     助手消息 ID（Run ID）
     * @param baseline  执行前基线快照
     * @return 已归档产物元数据列表
     */
    public List<TraceCollector.ArtifactItem> archiveRunArtifacts(long agentId,
                                                                 long sessionId,
                                                                 long msgId,
                                                                 Map<String, FileFingerprint> baseline) {
        if (agentId <= 0 || sessionId <= 0 || msgId <= 0) {
            return Collections.emptyList();
        }
        Path agentDir = agentRoot(agentId);
        Map<String, CandidateFile> currentFiles = scanDeliverables(agentDir);
        if (currentFiles.isEmpty()) {
            return Collections.emptyList();
        }

        // 1、筛选本次 Run 新产生或发生修改的交付物
        List<CandidateFile> changed = new ArrayList<>();
        for (CandidateFile candidate : currentFiles.values()) {
            FileFingerprint before = baseline == null ? null : baseline.get(candidate.relativePath());
            if (before == null || !Objects.equals(before, candidate.fingerprint())) {
                changed.add(candidate);
            }
        }
        if (changed.isEmpty()) {
            return Collections.emptyList();
        }

        // 2、按修改时间倒序 + 相对路径正序稳定排列，截断保护防单次刷盘过多文件
        changed.sort(Comparator.comparingLong((CandidateFile c) -> c.fingerprint().lastModifiedMillis())
                .reversed()
                .thenComparing(CandidateFile::relativePath));
        if (changed.size() > MAX_ARTIFACTS_PER_RUN) {
            changed = new ArrayList<>(changed.subList(0, MAX_ARTIFACTS_PER_RUN));
        }

        // 3、逐个拷贝归档到隔离目录 session_{sessionId}/run_{msgId}
        Path runDir = resolveRunDir(sessionId, msgId);
        Path baseRoot = artifactBaseRoot();
        try {
            if (!runDir.startsWith(baseRoot)) {
                logger.warn("产物目录越界已拦截, sessionId={}, msgId={}, runDir={}", sessionId, msgId, runDir);
                return Collections.emptyList();
            }
            Files.createDirectories(runDir);
        } catch (IOException e) {
            logger.warn("创建产物目录失败, sessionId={}, msgId={}, err={}", sessionId, msgId, e.getMessage());
            return Collections.emptyList();
        }

        Set<String> usedLowerNames = new HashSet<>();
        List<TraceCollector.ArtifactItem> result = new ArrayList<>();
        for (CandidateFile candidate : changed) {
            String storedName = allocateUniqueName(candidate.fileName(), usedLowerNames);
            Path target = runDir.resolve(storedName).normalize();
            if (!target.getParent().equals(runDir)) {
                continue;
            }
            try {
                // 采用拷贝而非移动：保留 Agent 工作目录现场，方便同一对话后续追问继续基于原文件加工
                Files.copy(candidate.path(), target, StandardCopyOption.REPLACE_EXISTING);

                TraceCollector.ArtifactItem item = new TraceCollector.ArtifactItem();
                item.setArtifactId("art-" + UUIDTool.getSimpleUUID().substring(0, 12));
                item.setFileName(candidate.fileName());
                item.setStoredName(storedName);
                item.setRelativePath(candidate.relativePath());
                item.setExt(candidate.ext());
                item.setContentType(resolveContentType(candidate.ext()));
                item.setSizeBytes(Files.size(target));
                item.setCreatedTime(candidate.fingerprint().lastModifiedMillis());
                result.add(item);
            } catch (IOException e) {
                logger.warn("交付物拷贝归档失败, sessionId={}, msgId={}, file={}, err={}",
                        sessionId, msgId, candidate.relativePath(), e.getMessage());
            }
        }
        return result;
    }

    /**
     * 安全读取交付物预览内容（限定 512KB UTF-8 文本，防大文件内存溢出与路径穿越）
     */
    public PreviewData readPreview(long sessionId, long msgId, TraceCollector.ArtifactItem item) throws IOException {
        Path file = resolveArtifactPath(sessionId, msgId, item);
        long totalSize = Files.size(file);

        // 多读 1 字节用于精准判定是否触发截断
        int cap = MAX_PREVIEW_BYTES + 1;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min((int) totalSize, MAX_PREVIEW_BYTES));
        byte[] chunk = new byte[8192];
        int totalRead = 0;
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while (totalRead < cap && (n = in.read(chunk, 0, Math.min(chunk.length, cap - totalRead))) != -1) {
                buffer.write(chunk, 0, n);
                totalRead += n;
            }
        }

        byte[] raw = buffer.toByteArray();
        boolean truncated = raw.length > MAX_PREVIEW_BYTES || totalSize > MAX_PREVIEW_BYTES;
        int decodeLen = Math.min(raw.length, MAX_PREVIEW_BYTES);

        // 宽容模式解码 UTF-8，避免截断恰好落在多字节字符中间导致异常
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        CharBuffer out = decoder.decode(ByteBuffer.wrap(raw, 0, decodeLen));

        return new PreviewData(
                item.getArtifactId(),
                safeDisplayFileName(item),
                item.getExt(),
                totalSize,
                truncated,
                out.toString(),
                item.getCreatedTime()
        );
    }

    /**
     * 校验并定位可供下载的物理文件
     */
    public DownloadFile resolveDownloadFile(long sessionId, long msgId, TraceCollector.ArtifactItem item) throws IOException {
        Path file = resolveArtifactPath(sessionId, msgId, item);
        long totalSize = Files.size(file);
        return new DownloadFile(
                file,
                safeDisplayFileName(item),
                resolveContentType(item.getExt()),
                totalSize
        );
    }

    /**
     * 清理指定对话的全部归档交付物目录
     */
    public void deleteBySession(long sessionId) {
        if (sessionId <= 0) {
            return;
        }
        Path baseRoot = artifactBaseRoot();
        Path sessionDir = baseRoot.resolve("session_" + sessionId).normalize();
        if (!sessionDir.getParent().equals(baseRoot) || !Files.exists(sessionDir)) {
            return;
        }
        try {
            deleteRecursively(sessionDir);
        } catch (Exception e) {
            logger.warn("清理对话交付物失败, sessionId={}, err={}", sessionId, e.getMessage());
        }
    }

    /**
     * 批量清理多个对话的归档交付物目录（Agent 级联删除时调用）
     */
    public void deleteBySessions(List<Long> sessionIds) {
        if (CollectionTool.isEmpty(sessionIds)) {
            return;
        }
        for (Long sessionId : sessionIds) {
            if (sessionId != null && sessionId > 0) {
                deleteBySession(sessionId);
            }
        }
    }

    // ==================== 内部路径与安全校验 ====================

    /**
     * 扫描 Agent 工作沙箱下全部支持格式的合法交付物（防符号链接与越界）
     */
    private Map<String, CandidateFile> scanDeliverables(Path agentDir) {
        if (agentDir == null || !Files.isDirectory(agentDir) || Files.isSymbolicLink(agentDir)) {
            return Collections.emptyMap();
        }
        final Path realRoot;
        try {
            realRoot = agentDir.toRealPath().normalize();
        } catch (IOException e) {
            return Collections.emptyMap();
        }

        Map<String, CandidateFile> result = new LinkedHashMap<>();
        try {
            Files.walkFileTree(realRoot, Collections.emptySet(), MAX_SCAN_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(realRoot)) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path namePath = dir.getFileName();
                    String dirName = namePath == null ? "" : namePath.toString();
                    if (dirName.startsWith(".")
                            || IGNORED_DIR_NAMES.contains(dirName.toLowerCase(Locale.ROOT))
                            || Files.isSymbolicLink(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    try {
                        Path realDir = dir.toRealPath().normalize();
                        if (!realDir.startsWith(realRoot)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    } catch (IOException e) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (Files.isSymbolicLink(file) || !attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path namePath = file.getFileName();
                    if (namePath == null) {
                        return FileVisitResult.CONTINUE;
                    }
                    String fileName = namePath.toString();
                    if (fileName.startsWith(".") || SKILL_DEFINITION_FILE.equalsIgnoreCase(fileName)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String ext = extractSupportedExt(fileName);
                    if (ext == null) {
                        return FileVisitResult.CONTINUE;
                    }
                    long size = attrs.size();
                    if (size < 0 || size > MAX_ARCHIVE_FILE_BYTES) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        Path realFile = file.toRealPath().normalize();
                        if (!realFile.startsWith(realRoot)) {
                            return FileVisitResult.CONTINUE;
                        }
                        long mtime = attrs.lastModifiedTime().toMillis();
                        long crc32 = computeCrc32(realFile);
                        String relPath = realRoot.relativize(file).toString().replace('\\', '/');
                        result.put(relPath, new CandidateFile(
                                realFile,
                                relPath,
                                fileName,
                                ext,
                                new FileFingerprint(size, mtime, crc32)
                        ));
                    } catch (IOException ignored) {
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            logger.warn("扫描 Agent 交付物失败, dir={}, err={}", realRoot, e.getMessage());
        }
        return result;
    }

    /**
     * 解析并严格校验已归档产物文件路径（仅允许单段 storedName 命中 run_{msgId} 内常规文件）
     */
    private Path resolveArtifactPath(long sessionId, long msgId, TraceCollector.ArtifactItem item) throws IOException {
        if (sessionId <= 0 || msgId <= 0 || item == null) {
            throw new IllegalArgumentException("产物标识参数无效");
        }
        String storedName = StringTool.isNotBlank(item.getStoredName()) ? item.getStoredName() : item.getFileName();
        if (StringTool.isBlank(storedName)
                || storedName.contains("/")
                || storedName.contains("\\")
                || storedName.contains("..")) {
            throw new IllegalArgumentException("非法的产物文件名");
        }
        Path segment = Path.of(storedName);
        if (segment.isAbsolute() || segment.getNameCount() != 1) {
            throw new IllegalArgumentException("非法的产物文件路径");
        }

        Path runDir = resolveRunDir(sessionId, msgId);
        if (!Files.isDirectory(runDir) || Files.isSymbolicLink(runDir)) {
            throw new IOException("产物文件不存在或已清理");
        }
        Path realRunDir = runDir.toRealPath().normalize();
        Path candidate = realRunDir.resolve(storedName).normalize();
        if (!candidate.getParent().equals(realRunDir)
                || !Files.exists(candidate)
                || Files.isSymbolicLink(candidate)
                || !Files.isRegularFile(candidate)) {
            throw new IOException("产物文件不存在或已清理");
        }
        Path realFile = candidate.toRealPath().normalize();
        if (!realFile.startsWith(realRunDir) || !realFile.getParent().equals(realRunDir)) {
            throw new IllegalArgumentException("产物路径越界");
        }
        return realFile;
    }

    /**
     * 分配 run 目录内唯一且安全的持久化文件名（大小写不敏感去重，兼容 Windows）
     */
    private String allocateUniqueName(String rawName, Set<String> usedLowerNames) {
        String cleaned = sanitizeFileName(rawName);
        int dot = cleaned.lastIndexOf('.');
        String stem = dot > 0 ? cleaned.substring(0, dot) : cleaned;
        String suffix = dot > 0 ? cleaned.substring(dot) : "";

        String candidate = cleaned;
        int seq = 1;
        while (usedLowerNames.contains(candidate.toLowerCase(Locale.ROOT))) {
            seq++;
            candidate = stem + "_" + seq + suffix;
        }
        usedLowerNames.add(candidate.toLowerCase(Locale.ROOT));
        return candidate;
    }

    /**
     * 净化文件名：剔除控制字符、路径分隔符与穿越片段
     */
    private String sanitizeFileName(String name) {
        if (StringTool.isBlank(name)) {
            return "artifact.txt";
        }
        String cleaned = name.replaceAll("[\\r\\n]", "")
                .replace('\\', '_')
                .replace('/', '_')
                .replace("..", "_")
                .trim();
        return cleaned.isEmpty() ? "artifact.txt" : cleaned;
    }

    /**
     * 安全生成展示与下载文件名
     */
    private String safeDisplayFileName(TraceCollector.ArtifactItem item) {
        String raw = StringTool.isNotBlank(item.getFileName()) ? item.getFileName() : item.getStoredName();
        return sanitizeFileName(raw);
    }

    /**
     * 提取受支持的扩展名（小写），不在白名单内返回 null
     */
    private String extractSupportedExt(String fileName) {
        int idx = fileName.lastIndexOf('.');
        if (idx <= 0 || idx >= fileName.length() - 1) {
            return null;
        }
        String ext = fileName.substring(idx + 1).toLowerCase(Locale.ROOT);
        return SUPPORTED_EXTENSIONS.contains(ext) ? ext : null;
    }

    /**
     * 按扩展名映射语义 Content-Type（下载头仍统一走 octet-stream 防嗅探）
     */
    private String resolveContentType(String ext) {
        if ("html".equalsIgnoreCase(ext)) {
            return "text/html; charset=UTF-8";
        }
        if ("csv".equalsIgnoreCase(ext)) {
            return "text/csv; charset=UTF-8";
        }
        if ("md".equalsIgnoreCase(ext) || "markdown".equalsIgnoreCase(ext)) {
            return "text/markdown; charset=UTF-8";
        }
        return "text/plain; charset=UTF-8";
    }

    /**
     * 计算文件 CRC32 摘要
     */
    private long computeCrc32(Path file) throws IOException {
        CRC32 crc32 = new CRC32();
        byte[] buf = new byte[8192];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                crc32.update(buf, 0, n);
            }
        }
        return crc32.getValue();
    }

    /**
     * 递归删除目录
     */
    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            List<Path> list = paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            for (Path item : list) {
                Files.deleteIfExists(item);
            }
        }
    }

    /**
     * Agent 物化根目录：{skill.root}/agent_{agentId}
     */
    private Path agentRoot(long agentId) {
        return Path.of(skillRoot).toAbsolutePath().normalize().resolve("agent_" + agentId);
    }

    /**
     * 产物归档总根目录
     */
    private Path artifactBaseRoot() {
        if (StringTool.isNotBlank(artifactRoot)) {
            return Path.of(artifactRoot.trim()).toAbsolutePath().normalize();
        }
        return Path.of(skillRoot).toAbsolutePath().normalize().resolve("_artifacts");
    }

    /**
     * 单次 Run 产物归档目录：{artifactBaseRoot}/session_{sessionId}/run_{msgId}
     */
    private Path resolveRunDir(long sessionId, long msgId) {
        return artifactBaseRoot()
                .resolve("session_" + sessionId)
                .resolve("run_" + msgId)
                .normalize();
    }

}
