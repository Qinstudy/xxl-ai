package com.xxl.ai.api.business.chat.controller;

import com.xxl.ai.api.business.session.model.entity.Session;
import com.xxl.ai.api.business.session.model.entity.SessionMsg;
import com.xxl.ai.api.business.session.service.SessionService;
import com.xxl.sso.core.annotation.XxlSso;
import com.xxl.tool.response.Response;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 对话会话公开访问 Controller（C端）：免管理端登录态，按访问 URL（UUID）直接访问
 *
 * 承载公开对话页的会话元数据操作（创建/列表/改名/删除、消息列表）；会话数据与校验经 session 模块的
 * SessionService，会话流式对话（发送/续传）由 ChatController 承载。
 *
 * @author xxl-ai 2026-09-05
 */
@RestController
@RequestMapping("/chat/session")
public class ChatSessionController {

    @Resource
    private SessionService sessionService;

    /**
     * 创建对话（公开）
     */
    @RequestMapping("/create")
    @XxlSso(login = false)
    public Response<Session> create(@RequestParam("uuid") String uuid,
                                    @RequestParam("visitorId") String visitorId,
                                    String title) {
        return sessionService.create(uuid, visitorId, title);
    }

    /**
     * 对话列表（公开，按访客隔离）
     */
    @RequestMapping("/list")
    @XxlSso(login = false)
    public Response<List<Session>> list(@RequestParam("uuid") String uuid,
                                        @RequestParam("visitorId") String visitorId) {
        return sessionService.list(uuid, visitorId);
    }

    /**
     * 修改对话标题（公开）
     */
    @RequestMapping("/rename")
    @XxlSso(login = false)
    public Response<String> rename(@RequestParam("sessionId") long sessionId,
                                   @RequestParam("title") String title) {
        return sessionService.rename(sessionId, title);
    }

    /**
     * 删除对话（公开）
     */
    @RequestMapping("/delete")
    @XxlSso(login = false)
    public Response<String> delete(@RequestParam("sessionId") long sessionId) {
        return sessionService.delete(sessionId);
    }

    /**
     * 消息列表（公开）
     */
    @RequestMapping("/msgList")
    @XxlSso(login = false)
    public Response<List<SessionMsg>> msgList(@RequestParam("sessionId") long sessionId) {
        return sessionService.msgList(sessionId);
    }

}
