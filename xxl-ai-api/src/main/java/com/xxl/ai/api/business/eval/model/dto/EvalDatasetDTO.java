package com.xxl.ai.api.business.eval.model.dto;

import java.io.Serializable;

/**
 * 评测数据集 DTO
 *
 * @author xxl-ai 2026-11-12
 */
public class EvalDatasetDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private long id;
    private long spaceId;
    private String name;
    private String evalType;
    private long targetId;
    private String targetName;
    private int sampleCount;
    private String contentJson;
    private String description;
    private String addTime;
    private String updateTime;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getSpaceId() { return spaceId; }
    public void setSpaceId(long spaceId) { this.spaceId = spaceId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEvalType() { return evalType; }
    public void setEvalType(String evalType) { this.evalType = evalType; }
    public long getTargetId() { return targetId; }
    public void setTargetId(long targetId) { this.targetId = targetId; }
    public String getTargetName() { return targetName; }
    public void setTargetName(String targetName) { this.targetName = targetName; }
    public int getSampleCount() { return sampleCount; }
    public void setSampleCount(int sampleCount) { this.sampleCount = sampleCount; }
    public String getContentJson() { return contentJson; }
    public void setContentJson(String contentJson) { this.contentJson = contentJson; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAddTime() { return addTime; }
    public void setAddTime(String addTime) { this.addTime = addTime; }
    public String getUpdateTime() { return updateTime; }
    public void setUpdateTime(String updateTime) { this.updateTime = updateTime; }
}
