package com.xxl.ai.api.business.harness.workflow.model;

import java.io.Serializable;
import java.util.Map;

/**
 * 工作流单节点执行追踪快照
 *
 * @author xuxueli 2026-11-12
 */
public class WorkflowExecutionTrace implements Serializable {
    private static final long serialVersionUID = 1L;

    /* 节点ID */
    private String nodeId;

    /* 节点名称 */
    private String nodeName;

    /* 节点类型 */
    private String nodeType;

    /* 状态：SUCCESS, FAILED, SKIPPED */
    private String status;

    /* 耗时毫秒 */
    private int durationMs;

    /* 本节点消耗 Token 数 */
    private int tokens;

    /* 输入参数快照 */
    private Map<String, Object> inputSnapshot;

    /* 输出结果快照 */
    private Map<String, Object> outputSnapshot;

    /* 错误信息（若失败） */
    private String errorMsg;

    public WorkflowExecutionTrace() {
    }

    public WorkflowExecutionTrace(String nodeId, String nodeName, String nodeType, String status) {
        this.nodeId = nodeId;
        this.nodeName = nodeName;
        this.nodeType = nodeType;
        this.status = status;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public String getNodeType() {
        return nodeType;
    }

    public void setNodeType(String nodeType) {
        this.nodeType = nodeType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(int durationMs) {
        this.durationMs = durationMs;
    }

    public int getTokens() {
        return tokens;
    }

    public void setTokens(int tokens) {
        this.tokens = tokens;
    }

    public Map<String, Object> getInputSnapshot() {
        return inputSnapshot;
    }

    public void setInputSnapshot(Map<String, Object> inputSnapshot) {
        this.inputSnapshot = inputSnapshot;
    }

    public Map<String, Object> getOutputSnapshot() {
        return outputSnapshot;
    }

    public void setOutputSnapshot(Map<String, Object> outputSnapshot) {
        this.outputSnapshot = outputSnapshot;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }
}
