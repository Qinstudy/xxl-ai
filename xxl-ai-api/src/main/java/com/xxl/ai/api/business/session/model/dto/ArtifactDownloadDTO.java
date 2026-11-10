package com.xxl.ai.api.business.session.model.dto;

import java.nio.file.Path;

/**
 * 交付物产物下载定位 DTO（供 C/B 端下载接口输出文件流使用）
 *
 * @author xxl-ai 2026-11-08
 */
public class ArtifactDownloadDTO {

    private Path file;              /* 物理文件路径 */
    private String fileName;        /* 下载展示文件名 */
    private String contentType;     /* MIME 类型 */
    private long sizeBytes;         /* 文件大小（字节） */

    public Path getFile() {
        return file;
    }

    public void setFile(Path file) {
        this.file = file;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

}
