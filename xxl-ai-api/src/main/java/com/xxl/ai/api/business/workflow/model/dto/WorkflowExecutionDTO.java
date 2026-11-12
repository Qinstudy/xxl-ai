package com.xxl.ai.api.business.workflow.model.dto;

import java.io.Serializable;

/**
 * 工作流执行记录 DTO
 *
 * @author xxl-ai 2026-11-12
 */
public class WorkflowExecutionDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;                    /* 主键自增 */
    private Long spaceId;               /* 所属空间ID */
    private Long workflowId;            /* 关联工作流ID */
    private String triggerType;         /* 触发类型：DEBUG / API / CHAT */
    private Integer status;             /* 状态：0-运行中, 1-成功, 2-失败, 3-超时中断, 4-已取消 */
    private Integer totalTokens;        /* 累计消耗 Token */
    private Integer durationMs;         /* 总耗时毫秒 */
    private String inputJson;           /* 输入参数 JSON */
    private String outputJson;          /* 输出结果 JSON */
    private String executionJson;       /* 逐节点链路与快照 JSON */
    private String errorMsg;            /* 失败异常摘要 */
    private String addTime;             /* 启动时间字符串 */
    private String updateTime;          /* 结束时间字符串 */

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSpaceId() {
        return spaceId;
    }

    public void setSpaceId(Long spaceId) {
        this.spaceId = spaceId;
    }

    public Long getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(Long workflowId) {
        this.workflowId = workflowId;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Integer durationMs) {
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

    public String getAddTime() {
        return addTime;
    }

    public void setAddTime(String addTime) {
        this.addTime = addTime;
    }

    public String getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(String updateTime) {
        this.updateTime = updateTime;
    }
}
