package com.xxl.ai.api.business.chat.model.dto;

/**
 * 对话发送 请求DTO（公开对话 SSE 流式入参）
 *
 * @author xxl-ai 2026-10-02
 */
public class ChatSendDTO {

    private String uuid;        /* Agent 访问 UUID */
    private String visitorId;   /* 访客标识 */
    private long sessionId;     /* 对话ID */
    private String content;     /* 用户消息内容 */

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getVisitorId() {
        return visitorId;
    }

    public void setVisitorId(String visitorId) {
        this.visitorId = visitorId;
    }

    public long getSessionId() {
        return sessionId;
    }

    public void setSessionId(long sessionId) {
        this.sessionId = sessionId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

}
