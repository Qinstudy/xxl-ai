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

import com.xxl.ai.api.framework.util.VisitorSecurityTool;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 对话会话公开访问 Controller（C端）：免管理端登录态，按访问 URL（UUID）直接访问
 *
 * 承载公开对话页的会话元数据操作（创建/列表/改名/删除、消息列表）；会话数据与校验经 session 模块的
 * SessionService，会话流式对话（发送/续传）由 ChatController 承载。
 * [SEC-001]：全量接口强校验访客归属与 VisitorToken 防篡改签名，杜绝 IDOR 水平越权。
 *
 * @author xxl-ai 2026-09-05
 */
@RestController
@RequestMapping("/chat/session")
public class ChatSessionController {

    @Resource
    private SessionService sessionService;
    @Resource
    private VisitorSecurityTool visitorSecurityTool;

    /**
     * 创建对话（公开，自动签发/刷新 VisitorToken）
     */
    @RequestMapping("/create")
    @XxlSso(login = false)
    public Response<Session> create(HttpServletRequest request,
                                    HttpServletResponse response,
                                    @RequestParam("uuid") String uuid,
                                    @RequestParam("visitorId") String visitorId,
                                    String title) {
        Response<String> check = visitorSecurityTool.validateAndRefreshToken(request, response, visitorId);
        if (!check.isSuccess()) {
            return Response.ofFail(check.getMsg());
        }
        return sessionService.create(uuid, visitorId, title);
    }

    /**
     * 对话列表（公开，按访客隔离，自动签发/刷新 VisitorToken）
     */
    @RequestMapping("/list")
    @XxlSso(login = false)
    public Response<List<Session>> list(HttpServletRequest request,
                                        HttpServletResponse response,
                                        @RequestParam("uuid") String uuid,
                                        @RequestParam("visitorId") String visitorId) {
        Response<String> check = visitorSecurityTool.validateAndRefreshToken(request, response, visitorId);
        if (!check.isSuccess()) {
            return Response.ofFail(check.getMsg());
        }
        return sessionService.list(uuid, visitorId);
    }

    /**
     * 修改对话标题（公开，强校验访客与会话归属）
     */
    @RequestMapping("/rename")
    @XxlSso(login = false)
    public Response<String> rename(HttpServletRequest request,
                                   @RequestParam("sessionId") long sessionId,
                                   @RequestParam(value = "visitorId", required = false) String visitorId,
                                   @RequestParam("title") String title) {
        Response<String> check = visitorSecurityTool.validateVisitor(request, visitorId);
        if (!check.isSuccess()) {
            return check;
        }
        return sessionService.rename(sessionId, visitorId, title);
    }

    /**
     * 删除对话（公开，强校验访客与会话归属）
     */
    @RequestMapping("/delete")
    @XxlSso(login = false)
    public Response<String> delete(HttpServletRequest request,
                                   @RequestParam("sessionId") long sessionId,
                                   @RequestParam(value = "visitorId", required = false) String visitorId) {
        Response<String> check = visitorSecurityTool.validateVisitor(request, visitorId);
        if (!check.isSuccess()) {
            return check;
        }
        return sessionService.delete(sessionId, visitorId);
    }

    /**
     * 消息列表（公开，强校验访客与会话归属）
     */
    @RequestMapping("/msgList")
    @XxlSso(login = false)
    public Response<List<SessionMsg>> msgList(HttpServletRequest request,
                                              @RequestParam("sessionId") long sessionId,
                                              @RequestParam(value = "visitorId", required = false) String visitorId) {
        Response<String> check = visitorSecurityTool.validateVisitor(request, visitorId);
        if (!check.isSuccess()) {
            return Response.ofFail(check.getMsg());
        }
        return sessionService.msgList(sessionId, visitorId);
    }

}
