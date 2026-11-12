package com.xxl.ai.api.business.eval.model.entity;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * AI 评测实验记录实体
 *
 * @author xxl-ai 2026-11-12
 */
public class EvalExperiment implements Serializable {

    private static final long serialVersionUID = 1L;

    private long id;                        /* 主键自增 */
    private long spaceId;                   /* 所属空间ID */
    private long datasetId;                 /* 关联数据集ID */
    private String name;                    /* 实验名称 */
    private String evalType;                /* 评测类型：RAG / AGENT */
    private long targetId;                  /* 评测目标ID */
    private String targetName;              /* 目标名称 */
    private Long judgeModelId;              /* 裁判大模型ID（为空使用默认模型） */
    private int status;                     /* 状态：0-待运行, 1-运行中, 2-已完成, 3-失败, 4-已取消 */
    private int totalCases;                 /* 总用例数 */
    private int completedCases;             /* 已完成用例数 */
    private BigDecimal avgHitRate;          /* 平均检索命中率 HitRate@3 */
    private BigDecimal avgMrr;              /* 平均倒数排名 MRR */
    private BigDecimal avgFaithfulness;     /* 平均忠实度（防幻觉） */
    private BigDecimal avgRelevance;        /* 平均答案相关性 */
    private BigDecimal avgGoalCompletion;   /* 平均目标达成率（仅AGENT） */
    private int avgLatencyMs;               /* 平均耗时（ms） */
    private int totalTokens;                /* 累计 Token 消耗 */
    private String summaryJson;             /* 详细实验指标汇总及用例结果明细 JSON */
    private String errorMsg;                /* 异常错误信息 */

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date addTime;                   /* 新增时间 */

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date updateTime;                /* 更新时间 */

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

    public long getDatasetId() {
        return datasetId;
    }

    public void setDatasetId(long datasetId) {
        this.datasetId = datasetId;
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

    public Long getJudgeModelId() {
        return judgeModelId;
    }

    public void setJudgeModelId(Long judgeModelId) {
        this.judgeModelId = judgeModelId;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public int getTotalCases() {
        return totalCases;
    }

    public void setTotalCases(int totalCases) {
        this.totalCases = totalCases;
    }

    public int getCompletedCases() {
        return completedCases;
    }

    public void setCompletedCases(int completedCases) {
        this.completedCases = completedCases;
    }

    public BigDecimal getAvgHitRate() {
        return avgHitRate;
    }

    public void setAvgHitRate(BigDecimal avgHitRate) {
        this.avgHitRate = avgHitRate;
    }

    public BigDecimal getAvgMrr() {
        return avgMrr;
    }

    public void setAvgMrr(BigDecimal avgMrr) {
        this.avgMrr = avgMrr;
    }

    public BigDecimal getAvgFaithfulness() {
        return avgFaithfulness;
    }

    public void setAvgFaithfulness(BigDecimal avgFaithfulness) {
        this.avgFaithfulness = avgFaithfulness;
    }

    public BigDecimal getAvgRelevance() {
        return avgRelevance;
    }

    public void setAvgRelevance(BigDecimal avgRelevance) {
        this.avgRelevance = avgRelevance;
    }

    public BigDecimal getAvgGoalCompletion() {
        return avgGoalCompletion;
    }

    public void setAvgGoalCompletion(BigDecimal avgGoalCompletion) {
        this.avgGoalCompletion = avgGoalCompletion;
    }

    public int getAvgLatencyMs() {
        return avgLatencyMs;
    }

    public void setAvgLatencyMs(int avgLatencyMs) {
        this.avgLatencyMs = avgLatencyMs;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }

    public String getSummaryJson() {
        return summaryJson;
    }

    public void setSummaryJson(String summaryJson) {
        this.summaryJson = summaryJson;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
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
