package com.xxl.ai.api.business.workflow.model.entity;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.io.Serializable;
import java.util.Date;

/**
 * 工作流执行记录实体
 *
 * @author xxl-ai 2026-11-12
 */
public class WorkflowExecution implements Serializable {

    private static final long serialVersionUID = 1L;

    private long id;                    /* 主键自增 */
    private long spaceId;               /* 所属空间ID */
    private long workflowId;            /* 关联工作流ID */
    private String triggerType;         /* 触发类型：DEBUG / API / CHAT */
    private int status;                 /* 状态：0-运行中, 1-成功, 2-失败, 3-超时中断, 4-已取消 */
    private int totalTokens;            /* 累计消耗 Token */
    private int durationMs;             /* 总耗时毫秒 */
    private String inputJson;           /* 输入参数 JSON */
    private String outputJson;          /* 输出结果 JSON */
    private String executionJson;       /* 逐节点链路与快照 JSON */
    private String errorMsg;            /* 失败异常摘要 */

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date addTime;               /* 启动时间 */

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

    public long getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(long workflowId) {
        this.workflowId = workflowId;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }

    public int getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(int durationMs) {
        this.durationMs = durationMs;
    }

    public String getInputJson() {
        return inputJson;
    }

    public void setInputJson(String inputJson) {
        this.inputJson = inputJson;
    }

    public String getOutputJson() {
        return outputJson;
    }

    public void setOutputJson(String outputJson) {
        this.outputJson = outputJson;
    }

    public String getExecutionJson() {
        return executionJson;
    }

    public void setExecutionJson(String executionJson) {
        this.executionJson = executionJson;
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
