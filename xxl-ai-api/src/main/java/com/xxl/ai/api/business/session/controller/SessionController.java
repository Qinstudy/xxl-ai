package com.xxl.ai.api.business.session.controller;

import com.xxl.ai.api.business.session.model.dto.ArtifactDownloadDTO;
import com.xxl.ai.api.business.session.model.dto.ArtifactPreviewDTO;
import com.xxl.ai.api.business.session.model.dto.SessionDTO;
import com.xxl.ai.api.business.session.model.dto.SessionMsgDTO;
import com.xxl.ai.api.business.session.service.SessionService;
import com.xxl.ai.api.business.space.model.SpaceContext;
import com.xxl.ai.api.business.space.service.SpaceService;
import com.xxl.sso.core.annotation.XxlSso;
import com.xxl.tool.json.GsonTool;
import com.xxl.tool.response.PageModel;
import com.xxl.tool.response.Response;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 对话 Controller（B端管理）：按业务空间查看访客对话列表、消息明细与交付物预览下载（对话管理菜单）
 *
 * C端（免登录）的公开对话入口见 chat 模块的 ChatController / ChatSessionController。
 *
 * @author xxl-ai 2026-09-19
 */
@RestController
@RequestMapping("/session")
public class SessionController {

    @Resource
    private SessionService sessionService;
    @Resource
    private SpaceService spaceService;

    /**
     * 分页查询空间内对话列表（可按 Agent、标题、访客ID 模糊查询）
     */
    @RequestMapping("/pageList")
    @XxlSso(permission = "session:default")
    public Response<PageModel<SessionDTO>> pageList(HttpServletRequest request,
                                                    @RequestHeader(value = "xxl-space-id", required = false) Integer spaceId,
                                                    @RequestParam(required = false, defaultValue = "0") long agentId,
                                                    @RequestParam(required = false, defaultValue = "0") int offset,
                                                    @RequestParam(required = false, defaultValue = "10") int pagesize,
                                                    String title,
                                                    String visitorId) {
        Response<SpaceContext> spaceResp = spaceService.checkSpace(request, spaceId);
        if (!spaceResp.isSuccess()) {
            return Response.ofFail(spaceResp.getMsg());
        }
        PageModel<SessionDTO> pageModel = sessionService.pageList(spaceResp.getData().getSpaceId(), agentId, offset, pagesize, title, visitorId);
        return Response.ofSuccess(pageModel);
    }

    /**
     * 分页查询对话消息明细（校验空间与 Agent 归属）
     */
    @RequestMapping("/msgList")
    @XxlSso(permission = "session:default")
    public Response<PageModel<SessionMsgDTO>> msgList(HttpServletRequest request,
                                                   @RequestHeader(value = "xxl-space-id", required = false) Integer spaceId,
                                                   @RequestParam(required = false, defaultValue = "0") long agentId,
                                                   @RequestParam("sessionId") long sessionId,
                                                   @RequestParam(required = false, defaultValue = "0") int offset,
                                                   @RequestParam(required = false, defaultValue = "10") int pagesize) {
        Response<SpaceContext> spaceResp = spaceService.checkSpace(request, spaceId);
        if (!spaceResp.isSuccess()) {
            return Response.ofFail(spaceResp.getMsg());
        }
        return sessionService.pageMsgList(spaceResp.getData().getSpaceId(), agentId, sessionId, offset, pagesize);
    }

    /**
     * 在线预览对话消息挂载的交付物内容（校验当前空间归属）
     */
    @RequestMapping("/artifact/preview")
    @XxlSso(permission = "session:default")
    public Response<ArtifactPreviewDTO> previewArtifact(HttpServletRequest request,
                                                        @RequestHeader(value = "xxl-space-id", required = false) Integer spaceId,
                                                        @RequestParam("sessionId") long sessionId,
                                                        @RequestParam("msgId") long msgId,
                                                        @RequestParam("artifactId") String artifactId) {
        Response<SpaceContext> spaceResp = spaceService.checkSpace(request, spaceId);
        if (!spaceResp.isSuccess()) {
            return Response.ofFail(spaceResp.getMsg());
        }
        return sessionService.previewArtifact(spaceResp.getData().getSpaceId(), sessionId, msgId, artifactId);
    }

    /**
     * 下载对话消息挂载的交付物文件（校验当前空间归属；异常时返回 JSON Response）
     */
    @RequestMapping("/artifact/download")
    @XxlSso(permission = "session:default")
    public void downloadArtifact(HttpServletRequest request,
                                 HttpServletResponse response,
                                 @RequestHeader(value = "xxl-space-id", required = false) Integer spaceId,
                                 @RequestParam("sessionId") long sessionId,
                                 @RequestParam("msgId") long msgId,
                                 @RequestParam("artifactId") String artifactId) throws Exception {
        Response<SpaceContext> spaceResp = spaceService.checkSpace(request, spaceId);
        if (!spaceResp.isSuccess()) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(GsonTool.toJson(Response.ofFail(spaceResp.getMsg())));
            return;
        }
        Response<ArtifactDownloadDTO> fileResp = sessionService.downloadArtifact(
                spaceResp.getData().getSpaceId(), sessionId, msgId, artifactId);
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
