package com.xxl.ai.api.business.session.model.dto;

/**
 * 交付物产物在线预览 DTO（供 C 端对话页与 B 端对话管理明细抽屉共用）
 *
 * @author xxl-ai 2026-11-08
 */
public class ArtifactPreviewDTO {

    private String artifactId;      /* 产物唯一标识 */
    private String fileName;        /* 原始展示文件名 */
    private String ext;             /* 文件后缀（sql / html / csv） */
    private long sizeBytes;         /* 磁盘文件大小（字节） */
    private boolean truncated;      /* 是否因超过在线预览上限而被截断 */
    private String content;         /* UTF-8 文本预览内容 */
    private long createdTime;       /* 生成时间戳（ms） */

    public String getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getExt() {
        return ext;
    }

    public void setExt(String ext) {
        this.ext = ext;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public long getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(long createdTime) {
        this.createdTime = createdTime;
    }

}
