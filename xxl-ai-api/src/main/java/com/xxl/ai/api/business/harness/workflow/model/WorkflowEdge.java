package com.xxl.ai.api.business.harness.workflow.model;

import java.io.Serializable;

/**
 * 工作流连线边定义
 *
 * @author xuxueli 2026-11-12
 */
public class WorkflowEdge implements Serializable {
    private static final long serialVersionUID = 1L;

    /* 连线ID */
    private String id;

    /* 起始源节点ID */
    private String source;

    /* 目标节点ID */
    private String target;

    /* 起始源节点输出端口标识（如条件分支句柄 true / false / default） */
    private String sourceHandle;

    /* 目标节点输入端口标识（可选） */
    private String targetHandle;

    public WorkflowEdge() {
    }

    public WorkflowEdge(String id, String source, String target) {
        this.id = id;
        this.source = source;
        this.target = target;
    }

    public WorkflowEdge(String id, String source, String target, String sourceHandle) {
        this.id = id;
        this.source = source;
        this.target = target;
        this.sourceHandle = sourceHandle;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getSourceHandle() {
        return sourceHandle;
    }

    public void setSourceHandle(String sourceHandle) {
        this.sourceHandle = sourceHandle;
    }

    public String getTargetHandle() {
        return targetHandle;
    }

    public void setTargetHandle(String targetHandle) {
        this.targetHandle = targetHandle;
    }
}
