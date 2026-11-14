package com.xxl.ai.api.business.chat.controller;

import com.xxl.ai.api.business.agent.model.entity.Agent;
import com.xxl.ai.api.business.chat.model.dto.ChatSendDTO;
import com.xxl.ai.api.business.chat.service.ChatService;
import com.xxl.ai.api.framework.util.VisitorSecurityTool;
import com.xxl.sso.core.annotation.XxlSso;
import com.xxl.tool.response.Response;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 对话流式 Controller：免管理端登录态，按访问 URL（UUID）直接访问
 *
 * 承载 agent loop 的入口（SSE 流式对话 / 断线续传）；会话与消息元数据（创建/列表/改名/删除、消息列表）
 * 由 session 模块的 SessionController / ChatSessionController 承载。
 * [SEC-001]：全量入口强校验访客身份与 VisitorToken 签名，根绝 IDOR 水平越权。
 *
 * @author xxl-ai 2026-09-05
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    @Resource
    private ChatService chatService;
    @Resource
    private VisitorSecurityTool visitorSecurityTool;

    /**
     * Load Agent 基础信息（公开握手入口：已携带有效 Token 时刷新；否则忽略不可信 visitorId 并由服务端签发全新标识与签名）
     */
    @RequestMapping("/load")
    @XxlSso(login = false)
    public Response<Agent> load(HttpServletRequest request,
                                HttpServletResponse response,
                                @RequestParam("uuid") String uuid,
                                @RequestParam(value = "visitorId", required = false) String visitorId) {
        Response<Agent> resp = chatService.load(uuid);
        if (resp.isSuccess()) {
            visitorSecurityTool.issueOrRefreshIdentity(request, response, visitorId);
        }
        return resp;
    }

    /**
     * 对话（公开，SSE 流式返回，强校验访客令牌与会话归属）
     */
    @RequestMapping("/send")
    @XxlSso(login = false)
    public SseEmitter send(HttpServletRequest request, @RequestBody ChatSendDTO dto) {
        String visitorId = dto == null ? null : dto.getVisitorId();
        Response<String> check = visitorSecurityTool.validateVisitor(request, visitorId);
        if (!check.isSuccess()) {
            throw new IllegalArgumentException(check.getMsg());
        }
        return chatService.send(dto.getUuid(), visitorId, dto.getSessionId(), dto.getContent());
    }

    /**
     * 对话断线续传（公开，SSE 流式返回，强校验访客令牌与会话归属）
     *
     * 从结果流的 lastEventId 之后继续转发，不重新生成；msgId 为助手消息ID（即结果流标识）
     */
    @RequestMapping("/resume")
    @XxlSso(login = false)
    public SseEmitter resume(HttpServletRequest request,
                             @RequestParam("msgId") long msgId,
                             @RequestParam(value = "visitorId", required = false) String visitorId,
                             @RequestParam(value = "lastEventId", required = false) String lastEventId) {
        Response<String> check = visitorSecurityTool.validateVisitor(request, visitorId);
        if (!check.isSuccess()) {
            throw new IllegalArgumentException(check.getMsg());
        }
        return chatService.resume(msgId, visitorId, lastEventId);
    }

    /**
     * 主动终止会话（公开，强校验访客令牌与会话归属）：中断进行中的生成，并清理工具调用进程
     */
    @RequestMapping("/stop")
    @XxlSso(login = false)
    public Response<String> stop(HttpServletRequest request,
                                 @RequestParam("sessionId") long sessionId,
                                 @RequestParam(value = "visitorId", required = false) String visitorId) {
        Response<String> check = visitorSecurityTool.validateVisitor(request, visitorId);
        if (!check.isSuccess()) {
            return check;
        }
        return chatService.stop(sessionId, visitorId);
    }

}
