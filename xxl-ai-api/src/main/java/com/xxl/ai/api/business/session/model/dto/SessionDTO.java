package com.xxl.ai.api.business.session.model.dto;

/**
 * 对话 展示DTO（管理端列表，含所属 Agent 名称）
 *
 * @author xxl-ai 2026-09-19
 */
public class SessionDTO {

    private long id;                /* 对话ID */
    private String agentUuid;       /* Agent访问UUID */
    private String agentName;       /* Agent名称 */
    private String visitorId;       /* 访客标识 */
    private String title;           /* 对话标题 */
    private String addTime;         /* 新增时间（格式化字符串） */
    private String updateTime;      /* 更新时间（格式化字符串） */

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getAgentUuid() {
        return agentUuid;
    }

    public void setAgentUuid(String agentUuid) {
        this.agentUuid = agentUuid;
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getVisitorId() {
        return visitorId;
    }

    public void setVisitorId(String visitorId) {
        this.visitorId = visitorId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
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
