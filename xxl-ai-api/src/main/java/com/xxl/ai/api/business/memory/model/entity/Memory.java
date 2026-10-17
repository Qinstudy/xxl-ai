package com.xxl.ai.api.business.memory.model.entity;

import java.util.Date;

/**
 * 长期记忆 实体（Agent × 访客 维度，单字段 Markdown 维护记忆内容）
 *
 * @author xxl-ai 2026-10-17
 */
public class Memory {

    private long id;                /* 记忆ID */
    private long agentId;           /* AgentID */
    private String visitorId;       /* 访客标识 */
    private String content;         /* 记忆内容(Markdown，按维度分节) */
    private long lastMsgId;         /* 最后归档处理的消息ID（增量水位线） */
    private Date addTime;           /* 新增时间 */
    private Date updateTime;        /* 更新时间 */

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
