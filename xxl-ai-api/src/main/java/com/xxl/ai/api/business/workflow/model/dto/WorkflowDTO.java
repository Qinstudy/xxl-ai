package com.xxl.ai.api.business.workflow.model.dto;

import java.io.Serializable;

/**
 * 工作流交互 DTO
 *
 * @author xxl-ai 2026-11-12
 */
public class WorkflowDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;                    /* 主键自增 */
    private Long spaceId;               /* 所属空间ID */
    private String name;                /* 工作流名称 */
    private String description;         /* 功能描述 */
    private Integer status;             /* 状态：0-草稿/正常, 1-已发布, 2-已停用 */
    private String graphJson;           /* DAG 图结构 JSON */
    private String extJson;             /* 扩展配置 JSON */
    private Integer nodeCount;          /* 节点数量展示 */
    private String addTime;             /* 创建时间字符串 */
    private String updateTime;          /* 更新时间字符串 */

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getGraphJson() {
        return graphJson;
    }

    public void setGraphJson(String graphJson) {
        this.graphJson = graphJson;
    }

    public String getExtJson() {
        return extJson;
    }

    public void setExtJson(String extJson) {
        this.extJson = extJson;
    }

    public Integer getNodeCount() {
        return nodeCount;
    }

    public void setNodeCount(Integer nodeCount) {
        this.nodeCount = nodeCount;
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
