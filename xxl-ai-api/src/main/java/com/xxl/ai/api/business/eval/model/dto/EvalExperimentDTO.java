package com.xxl.ai.api.business.eval.model.dto;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 评测实验 DTO
 *
 * @author xxl-ai 2026-11-12
 */
public class EvalExperimentDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private long id;
    private long spaceId;
    private long datasetId;
    private String name;
    private String evalType;
    private long targetId;
    private String targetName;
    private Long judgeModelId;
    private String judgeModelName;
    private int status;
    private int totalCases;
    private int completedCases;
    private BigDecimal avgHitRate;
    private BigDecimal avgMrr;
    private BigDecimal avgFaithfulness;
    private BigDecimal avgRelevance;
    private BigDecimal avgGoalCompletion;
    private int avgLatencyMs;
    private int totalTokens;
    private String summaryJson;
    private String errorMsg;
    private String addTime;
    private String updateTime;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getSpaceId() { return spaceId; }
    public void setSpaceId(long spaceId) { this.spaceId = spaceId; }
    public long getDatasetId() { return datasetId; }
    public void setDatasetId(long datasetId) { this.datasetId = datasetId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEvalType() { return evalType; }
    public void setEvalType(String evalType) { this.evalType = evalType; }
    public long getTargetId() { return targetId; }
    public void setTargetId(long targetId) { this.targetId = targetId; }
    public String getTargetName() { return targetName; }
    public void setTargetName(String targetName) { this.targetName = targetName; }
    public Long getJudgeModelId() { return judgeModelId; }
    public void setJudgeModelId(Long judgeModelId) { this.judgeModelId = judgeModelId; }
    public String getJudgeModelName() { return judgeModelName; }
    public void setJudgeModelName(String judgeModelName) { this.judgeModelName = judgeModelName; }
    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }
    public int getTotalCases() { return totalCases; }
    public void setTotalCases(int totalCases) { this.totalCases = totalCases; }
    public int getCompletedCases() { return completedCases; }
    public void setCompletedCases(int completedCases) { this.completedCases = completedCases; }
    public BigDecimal getAvgHitRate() { return avgHitRate; }
    public void setAvgHitRate(BigDecimal avgHitRate) { this.avgHitRate = avgHitRate; }
    public BigDecimal getAvgMrr() { return avgMrr; }
    public void setAvgMrr(BigDecimal avgMrr) { this.avgMrr = avgMrr; }
    public BigDecimal getAvgFaithfulness() { return avgFaithfulness; }
    public void setAvgFaithfulness(BigDecimal avgFaithfulness) { this.avgFaithfulness = avgFaithfulness; }
    public BigDecimal getAvgRelevance() { return avgRelevance; }
    public void setAvgRelevance(BigDecimal avgRelevance) { this.avgRelevance = avgRelevance; }
    public BigDecimal getAvgGoalCompletion() { return avgGoalCompletion; }
    public void setAvgGoalCompletion(BigDecimal avgGoalCompletion) { this.avgGoalCompletion = avgGoalCompletion; }
    public int getAvgLatencyMs() { return avgLatencyMs; }
    public void setAvgLatencyMs(int avgLatencyMs) { this.avgLatencyMs = avgLatencyMs; }
    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }
    public String getSummaryJson() { return summaryJson; }
    public void setSummaryJson(String summaryJson) { this.summaryJson = summaryJson; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
    public String getAddTime() { return addTime; }
    public void setAddTime(String addTime) { this.addTime = addTime; }
    public String getUpdateTime() { return updateTime; }
    public void setUpdateTime(String updateTime) { this.updateTime = updateTime; }
}
