package com.xxl.ai.api.business.chat.controller;

import com.xxl.ai.api.business.agent.model.entity.Agent;
import com.xxl.ai.api.business.chat.model.dto.ChatSendDTO;
import com.xxl.ai.api.business.chat.service.ChatService;
import com.xxl.ai.api.business.session.model.dto.ArtifactDownloadDTO;
import com.xxl.ai.api.business.session.model.dto.ArtifactPreviewDTO;
import com.xxl.sso.core.annotation.XxlSso;
import com.xxl.tool.json.GsonTool;
import com.xxl.tool.response.Response;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 对话流式 Controller：免管理端登录态，按访问 URL（UUID）直接访问
 *
 * 承载 agent loop 的入口（SSE 流式对话 / 断线续传 / 交付物预览与下载）；会话与消息元数据（创建/列表/改名/删除、消息列表）
 * 由 session 模块的 SessionController / ChatSessionController 承载。
 *
 * @author xxl-ai 2026-09-05
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    @Resource
    private ChatService chatService;

    /**
     * Load Agent 基础信息（公开）
     */
    @RequestMapping("/load")
    @XxlSso(login = false)
    public Response<Agent> load(@RequestParam("uuid") String uuid) {
        return chatService.load(uuid);
    }

    /**
     * 对话（公开，SSE 流式返回）
     */
    @RequestMapping("/send")
    @XxlSso(login = false)
    public SseEmitter send(@RequestBody ChatSendDTO dto) {
        return chatService.send(dto.getUuid(), dto.getVisitorId(), dto.getSessionId(), dto.getContent());
    }

    /**
     * 对话断线续传（公开，SSE 流式返回）
     *
     * 从结果流的 lastEventId 之后继续转发，不重新生成；msgId 为助手消息ID（即结果流标识）
     */
    @RequestMapping("/resume")
    @XxlSso(login = false)
    public SseEmitter resume(@RequestParam("msgId") long msgId,
                             @RequestParam(value = "lastEventId", required = false) String lastEventId) {
        return chatService.resume(msgId, lastEventId);
    }

    /**
     * 主动终止会话（公开）：中断进行中的生成，并清理工具调用进程
     */
    @RequestMapping("/stop")
    @XxlSso(login = false)
    public Response<String> stop(@RequestParam("sessionId") long sessionId) {
        return chatService.stop(sessionId);
    }

    /**
     * 在线预览对话交付物内容（公开：按 uuid + visitorId + sessionId + msgId + artifactId 隔离校验）
     */
    @RequestMapping("/artifact/preview")
    @XxlSso(login = false)
    public Response<ArtifactPreviewDTO> previewArtifact(@RequestParam("uuid") String uuid,
                                                        @RequestParam("visitorId") String visitorId,
                                                        @RequestParam("sessionId") long sessionId,
                                                        @RequestParam("msgId") long msgId,
                                                        @RequestParam("artifactId") String artifactId) {
        return chatService.previewArtifact(uuid, visitorId, sessionId, msgId, artifactId);
    }

    /**
     * 下载对话交付物文件（公开：统一八位流 + nosniff 防执行；异常时写 JSON Response）
     */
    @RequestMapping("/artifact/download")
    @XxlSso(login = false)
    public void downloadArtifact(HttpServletResponse response,
                                 @RequestParam("uuid") String uuid,
                                 @RequestParam("visitorId") String visitorId,
                                 @RequestParam("sessionId") long sessionId,
                                 @RequestParam("msgId") long msgId,
                                 @RequestParam("artifactId") String artifactId) throws Exception {
        Response<ArtifactDownloadDTO> fileResp = chatService.downloadArtifact(uuid, visitorId, sessionId, msgId, artifactId);
        if (!fileResp.isSuccess() || fileResp.getData() == null) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(GsonTool.toJson(Response.ofFail(fileResp.getMsg())));
            return;
        }
        ArtifactDownloadDTO fileDto = fileResp.getData();
        String encodedName = URLEncoder.encode(fileDto.getFileName(), StandardCharsets.UTF_8).replace("+", "%20");
        response.reset();
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/octet-stream");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encodedName);
        response.setContentLengthLong(fileDto.getSizeBytes());
        Files.copy(fileDto.getFile(), response.getOutputStream());
        response.getOutputStream().flush();
    }

}
