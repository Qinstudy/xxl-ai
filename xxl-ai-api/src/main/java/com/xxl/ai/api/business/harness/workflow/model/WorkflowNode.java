package com.xxl.ai.api.business.harness.workflow.model;

import java.io.Serializable;
import java.util.Map;

/**
 * 工作流节点定义
 *
 * @author xuxueli 2026-11-12
 */
public class WorkflowNode implements Serializable {
    private static final long serialVersionUID = 1L;

    /* 节点唯一标识（如 start_1, llm_1） */
    private String id;

    /* 节点展示名称 */
    private String name;

    /* 节点类型：START, LLM, RETRIEVAL, TOOL, CONDITION, END */
    private String type;

    /* 节点专有配置参数字典 */
    private Map<String, Object> config;

    /* 画布坐标位置（可选） */
    private Map<String, Object> position;

    public WorkflowNode() {
    }

    public WorkflowNode(String id, String name, String type, Map<String, Object> config) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.config = config;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public void setConfig(Map<String, Object> config) {
        this.config = config;
    }

    public Map<String, Object> getPosition() {
        return position;
    }

    public void setPosition(Map<String, Object> position) {
        this.position = position;
    }
}
