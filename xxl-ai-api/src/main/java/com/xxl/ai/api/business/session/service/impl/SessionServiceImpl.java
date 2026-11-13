package com.xxl.ai.api.business.session.service.impl;

import com.xxl.ai.api.business.agent.mapper.AgentMapper;
import com.xxl.ai.api.business.agent.model.entity.Agent;
import com.xxl.ai.api.business.harness.chat.TraceCollector;
import com.xxl.ai.api.business.harness.skill.SkillArtifactTool;
import com.xxl.ai.api.business.session.constant.SessionConstant;
import com.xxl.ai.api.business.session.mapper.SessionMapper;
import com.xxl.ai.api.business.session.mapper.SessionMsgMapper;
import com.xxl.ai.api.business.session.model.dto.ArtifactDownloadDTO;
import com.xxl.ai.api.business.session.model.dto.ArtifactPreviewDTO;
import com.xxl.ai.api.business.session.model.dto.SessionDTO;
import com.xxl.ai.api.business.session.model.dto.SessionMsgDTO;
import com.xxl.ai.api.business.session.model.entity.Session;
import com.xxl.ai.api.business.session.model.entity.SessionMsg;
import com.xxl.ai.api.business.session.service.SessionService;
import com.xxl.tool.core.CollectionTool;
import com.xxl.tool.core.StringTool;
import com.xxl.tool.response.PageModel;
import com.xxl.tool.response.Response;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 对话 Service 实现
 *
 * 公开访问按 uuid + visitorId 隔离；管理端按业务空间隔离；会话与消息写库及交付物产物归属校验集中于本实现。
 *
 * @author xxl-ai 2026-09-05
 */
@Service
public class SessionServiceImpl implements SessionService {

    @Resource
    private AgentMapper agentMapper;
    @Resource
    private SessionMapper sessionMapper;
    @Resource
    private SessionMsgMapper sessionMsgMapper;
    @Resource
    private SkillArtifactTool skillArtifactTool;

    // ==================== 公开访问 ====================

    /**
     * 创建对话（先校验 Agent 可用）
     */
    @Override
    public Response<Session> create(String uuid, String visitorId, String title) {
        Agent agent = agentMapper.loadByUuid(uuid);
        if (agent == null) {
            return Response.ofFail("Agent 不存在或已删除");
        }
        if (agent.getPublishStatus() != 1) {
            return Response.ofFail("Agent 未发布，暂不可访问");
        }
        Session session = new Session();
        session.setAgentId(agent.getId());
        session.setVisitorId(visitorId);
        session.setTitle(StringTool.isBlank(title) ? "新对话" : title);
        sessionMapper.insert(session);
        return Response.ofSuccess(session);
    }

    /**
     * 对话列表（按访客隔离）
     */
    @Override
    public Response<List<Session>> list(String uuid, String visitorId) {
        Agent agent = agentMapper.loadByUuid(uuid);
        if (agent == null) {
            return Response.ofSuccess(new ArrayList<>());
        }
        return Response.ofSuccess(sessionMapper.listByVisitor(agent.getId(), visitorId));
    }

    /**
     * 修改对话标题（校验访客归属，最长50个字符）
     */
    @Override
    public Response<String> rename(long sessionId, String visitorId, String title) {
        if (StringTool.isBlank(title)) {
            return Response.ofFail("对话标题不能为空");
        }
        if (title.trim().length() > 50) {
            return Response.ofFail("对话标题最长50个字符");
        }
        Session session = sessionMapper.load(sessionId);
        if (session == null) {
            return Response.ofFail("对话不存在");
        }
        if (StringTool.isBlank(visitorId) || StringTool.isBlank(session.getVisitorId())
                || !session.getVisitorId().equals(visitorId.trim())) {
            return Response.ofFail("无权操作该对话");
        }
        sessionMapper.updateTitle(sessionId, title.trim());
        return Response.ofSuccess();
    }

    /**
     * 删除对话（校验访客归属，连带消息与归档交付物）
     */
    @Override
    public Response<String> delete(long sessionId, String visitorId) {
        Session session = sessionMapper.load(sessionId);
        if (session == null) {
            return Response.ofFail("对话不存在");
        }
        if (StringTool.isBlank(visitorId) || StringTool.isBlank(session.getVisitorId())
                || !session.getVisitorId().equals(visitorId.trim())) {
            return Response.ofFail("无权操作该对话");
        }
        sessionMsgMapper.deleteBySessionId(sessionId);
        int ret = sessionMapper.delete(sessionId);
        skillArtifactTool.deleteBySession(sessionId);
        return ret > 0 ? Response.ofSuccess() : Response.ofFail();
    }

    /**
     * 消息列表（公开访问，校验访客归属）
     */
    @Override
    public Response<List<SessionMsg>> msgList(long sessionId, String visitorId) {
        Session session = sessionMapper.load(sessionId);
        if (session == null) {
            return Response.ofFail("对话不存在");
        }
        if (StringTool.isBlank(visitorId) || StringTool.isBlank(session.getVisitorId())
                || !session.getVisitorId().equals(visitorId.trim())) {
            return Response.ofFail("无权访问该对话");
        }
        return Response.ofSuccess(sessionMsgMapper.listBySessionId(sessionId));
    }

    /**
     * 校验并返回归属于指定访客的会话，不满足抛 IllegalArgumentException
     */
    @Override
    public Session requireVisitorSession(long sessionId, String visitorId) {
        Session session = sessionMapper.load(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("对话不存在或已删除");
        }
        if (StringTool.isBlank(visitorId) || StringTool.isBlank(session.getVisitorId())
                || !session.getVisitorId().equals(visitorId.trim())) {
            throw new IllegalArgumentException("无权访问该对话");
        }
        return session;
    }

    // ==================== 管理端 ====================

    /**
     * 分页查询空间内对话列表（可按 Agent、标题、访客ID 过滤）
     */
    @Override
    public PageModel<SessionDTO> pageList(long spaceId, long agentId, int offset, int pagesize, String title, String visitorId) {
        PageModel<SessionDTO> pageModel = new PageModel<>();
        List<SessionDTO> pageList = sessionMapper.pageList(spaceId, agentId, title, visitorId, offset, pagesize);
        int totalCount = sessionMapper.pageListCount(spaceId, agentId, title, visitorId);
        pageModel.setData(pageList != null ? pageList : new ArrayList<>());
        pageModel.setTotal(totalCount);
        return pageModel;
    }

    /**
     * 分页查询对话消息明细（校验对话归属当前空间与 Agent）
     */
    @Override
    public Response<PageModel<SessionMsgDTO>> pageMsgList(long spaceId, long agentId, long sessionId, int offset, int pagesize) {
        Session session = sessionMapper.load(sessionId);
        if (session == null) {
            return Response.ofFail("对话不存在");
        }
        Agent agent = agentMapper.load(session.getAgentId());
        if (agent == null || agent.getSpaceId() != spaceId || (agentId > 0 && agent.getId() != agentId)) {
            return Response.ofFail("对话不存在");
        }
        PageModel<SessionMsgDTO> pageModel = new PageModel<>();
        List<SessionMsgDTO> pageList = sessionMsgMapper.pageBySessionId(sessionId, offset, pagesize);
        // 查询按 id 倒序取页（最新在前），反转为时间正序，页面内按对话阅读顺序展示
        if (CollectionTool.isNotEmpty(pageList)) {
            Collections.reverse(pageList);
        }
        pageModel.setData(pageList != null ? pageList : new ArrayList<>());
        pageModel.setTotal(sessionMsgMapper.countBySessionId(sessionId));
        return Response.ofSuccess(pageModel);
    }

    /**
     * 在线预览对话消息交付物（管理端：校验空间与对话归属）
     */
    @Override
    public Response<ArtifactPreviewDTO> previewArtifact(long spaceId, long sessionId, long msgId, String artifactId) {
        if (!belongsToSpace(spaceId, sessionId)) {
            return Response.ofFail("对话不存在或无权访问");
        }
        return previewMsgArtifact(sessionId, msgId, artifactId);
    }

    /**
     * 解析对话消息交付物下载文件（管理端：校验空间与对话归属）
     */
    @Override
    public Response<ArtifactDownloadDTO> downloadArtifact(long spaceId, long sessionId, long msgId, String artifactId) {
        if (!belongsToSpace(spaceId, sessionId)) {
            return Response.ofFail("对话不存在或无权访问");
        }
        return downloadMsgArtifact(sessionId, msgId, artifactId);
    }

    // ==================== 运行时支撑（chat 模块调用） ====================

    /**
     * 校验并返回归属于该 Agent 的对话，不满足抛 IllegalArgumentException
     */
    @Override
    public Session require(long agentId, long sessionId) {
        Session session = sessionMapper.load(sessionId);
        if (session == null || session.getAgentId() != agentId) {
            throw new IllegalArgumentException("对话不存在");
        }
        return session;
    }

    /**
     * 开启一轮对话：首条消息自动生成标题 → 落库用户消息（status=1）与助手占位（status=0）→ 刷新对话活跃时间
     *
     * 两次落库纳入同一事务，避免中断留下半轮脏数据；助手占位主键即结果流标识，返回供投递生成任务。
     *
     * @param session 已校验归属的对话
     * @param content 用户提问内容
     * @return 助手消息 ID（结果流标识）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public long openRound(Session session, String content) {
        long sessionId = session.getId();
        // 首条消息自动生成对话标题（首次提问内容，超50字截断后补"..."）
        if (sessionMsgMapper.countBySessionId(sessionId) == 0
                && (StringTool.isBlank(session.getTitle()) || "新对话".equals(session.getTitle()))) {
            String sessionTitle = content.trim();
            sessionMapper.updateTitle(sessionId, sessionTitle.length() > 50 ? sessionTitle.substring(0, 47) + "..." : sessionTitle);
        }
        // 落库用户消息（status=1 已完成）
        SessionMsg userMsg = new SessionMsg();
        userMsg.setSessionId(sessionId);
        userMsg.setRole(SessionConstant.ROLE_USER);
        userMsg.setContent(content);
        userMsg.setStatus(SessionConstant.MSG_STATUS_DONE);
        sessionMsgMapper.insert(userMsg);
        // 落库助手消息占位（status=0 生成中），其ID即结果流标识，刷新页面后据此续传
        SessionMsg assistantMsg = new SessionMsg();
        assistantMsg.setSessionId(sessionId);
        assistantMsg.setRole(SessionConstant.ROLE_ASSISTANT);
        assistantMsg.setContent("");
        assistantMsg.setStatus(SessionConstant.MSG_STATUS_GENERATING);
        sessionMsgMapper.insert(assistantMsg);
        // 刷新对话更新时间
        sessionMapper.touch(sessionId);
        return assistantMsg.getId();
    }

    /**
     * 回填助手消息为失败态（发起生成失败时兜底）
     */
    @Override
    public void fail(long msgId, long sessionId) {
        sessionMsgMapper.updateAssistant(msgId, "", null, SessionConstant.MSG_STATUS_FAILED, null);
        sessionMapper.touch(sessionId);
    }

    /**
     * 按 Agent ID 批量清理对话、消息与归档交付物（Agent 删除时同步级联）
     */
    @Override
    public void purgeByAgentIds(List<Long> agentIds) {
        if (CollectionTool.isEmpty(agentIds)) {
            return;
        }
        for (Long agentId : agentIds) {
            if (agentId == null || agentId <= 0) {
                continue;
            }
            List<Long> sessionIds = sessionMapper.listIdsByAgentId(agentId);
            sessionMsgMapper.deleteByAgentId(agentId);
            sessionMapper.deleteByAgentId(agentId);
            skillArtifactTool.deleteBySessions(sessionIds);
        }
    }

    /**
     * 读取已校验归属对话的消息交付物预览内容（供 C 端与管理端复用）
     */
    @Override
    public Response<ArtifactPreviewDTO> previewMsgArtifact(long sessionId, long msgId, String artifactId) {
        Response<TraceCollector.ArtifactItem> itemResp = resolveMsgArtifact(sessionId, msgId, artifactId);
        if (!itemResp.isSuccess()) {
            return Response.ofFail(itemResp.getMsg());
        }
        try {
            SkillArtifactTool.PreviewData data = skillArtifactTool.readPreview(sessionId, msgId, itemResp.getData());
            ArtifactPreviewDTO dto = new ArtifactPreviewDTO();
            dto.setArtifactId(data.artifactId());
            dto.setFileName(data.fileName());
            dto.setExt(data.ext());
            dto.setSizeBytes(data.sizeBytes());
            dto.setTruncated(data.truncated());
            dto.setContent(data.content());
            dto.setCreatedTime(data.createdTime());
            return Response.ofSuccess(dto);
        } catch (Exception e) {
            return Response.ofFail(e.getMessage());
        }
    }

    /**
     * 解析已校验归属对话的消息交付物下载文件（供 C 端与管理端复用）
     */
    @Override
    public Response<ArtifactDownloadDTO> downloadMsgArtifact(long sessionId, long msgId, String artifactId) {
        Response<TraceCollector.ArtifactItem> itemResp = resolveMsgArtifact(sessionId, msgId, artifactId);
        if (!itemResp.isSuccess()) {
            return Response.ofFail(itemResp.getMsg());
        }
        try {
            SkillArtifactTool.DownloadFile file = skillArtifactTool.resolveDownloadFile(sessionId, msgId, itemResp.getData());
            ArtifactDownloadDTO dto = new ArtifactDownloadDTO();
            dto.setFile(file.file());
            dto.setFileName(file.fileName());
            dto.setContentType(file.contentType());
            dto.setSizeBytes(file.sizeBytes());
            return Response.ofSuccess(dto);
        } catch (Exception e) {
            return Response.ofFail(e.getMessage());
        }
    }

    /**
     * 校验对话是否属于指定业务空间
     */
    private boolean belongsToSpace(long spaceId, long sessionId) {
        if (spaceId <= 0 || sessionId <= 0) {
            return false;
        }
        Session session = sessionMapper.load(sessionId);
        if (session == null) {
            return false;
        }
        Agent agent = agentMapper.load(session.getAgentId());
        return agent != null && agent.getSpaceId() == spaceId;
    }

    /**
     * 校验消息归属对话并从 ext_json 解析目标产物项
     */
    private Response<TraceCollector.ArtifactItem> resolveMsgArtifact(long sessionId, long msgId, String artifactId) {
        if (sessionId <= 0 || msgId <= 0 || StringTool.isBlank(artifactId)) {
            return Response.ofFail("产物定位参数无效");
        }
        SessionMsg msg = sessionMsgMapper.load(msgId);
        if (msg == null || msg.getSessionId() != sessionId) {
            return Response.ofFail("消息不存在或不属于当前对话");
        }
        TraceCollector.ArtifactItem item = TraceCollector.findArtifact(msg.getExtJson(), artifactId.trim());
        if (item == null) {
            return Response.ofFail("交付物不存在或已失效");
        }
        return Response.ofSuccess(item);
    }

}
