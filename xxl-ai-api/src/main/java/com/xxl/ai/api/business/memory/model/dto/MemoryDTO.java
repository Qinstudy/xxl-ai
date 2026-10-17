package com.xxl.ai.api.business.memory.model.dto;

/**
 * 长期记忆 展示DTO（含所属 Agent 名称，时间格式化字符串）
 *
 * @author xxl-ai 2026-10-17
 */
public class MemoryDTO {

    private long id;                /* 记忆ID */
    private long agentId;           /* AgentID */
    private String agentName;       /* Agent名称 */
    private String visitorId;       /* 访客标识 */
    private String content;         /* 记忆内容(Markdown，按维度分节) */
    private long lastMsgId;         /* 最后归档处理的消息ID */
    private String addTime;         /* 新增时间（格式化字符串） */
    private String updateTime;      /* 更新时间（格式化字符串） */

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getAgentId() {
        return agentId;
    }

    public void setAgentId(long agentId) {
        this.agentId = agentId;
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

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public long getLastMsgId() {
        return lastMsgId;
    }

    public void setLastMsgId(long lastMsgId) {
        this.lastMsgId = lastMsgId;
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
