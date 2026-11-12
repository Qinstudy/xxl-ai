package com.xxl.ai.api.business.eval.model.entity;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.io.Serializable;
import java.util.Date;

/**
 * AI 评测数据集实体
 *
 * @author xxl-ai 2026-11-12
 */
public class EvalDataset implements Serializable {

    private static final long serialVersionUID = 1L;

    private long id;                    /* 主键自增 */
    private long spaceId;               /* 所属空间ID */
    private String name;                /* 数据集名称 */
    private String evalType;            /* 评测类型：RAG / AGENT */
    private long targetId;              /* 评测目标ID（RAG为kb_id，AGENT为agent_id） */
    private String targetName;          /* 冗余目标名称 */
    private int sampleCount;            /* 样本总数 */
    private String contentJson;         /* 样本集 JSON 数组 */
    private String description;         /* 评测集描述 */

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date addTime;               /* 新增时间 */

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date updateTime;            /* 更新时间 */

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getSpaceId() {
        return spaceId;
    }

    public void setSpaceId(long spaceId) {
        this.spaceId = spaceId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEvalType() {
        return evalType;
    }

    public void setEvalType(String evalType) {
        this.evalType = evalType;
    }

    public long getTargetId() {
        return targetId;
    }

    public void setTargetId(long targetId) {
        this.targetId = targetId;
    }

    public String getTargetName() {
        return targetName;
    }

    public void setTargetName(String targetName) {
        this.targetName = targetName;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public void setSampleCount(int sampleCount) {
        this.sampleCount = sampleCount;
    }

    public String getContentJson() {
        return contentJson;
    }

    public void setContentJson(String contentJson) {
        this.contentJson = contentJson;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Date getAddTime() {
        return addTime;
    }

    public void setAddTime(Date addTime) {
        this.addTime = addTime;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }
}
