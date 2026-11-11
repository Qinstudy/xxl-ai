package com.xxl.ai.api.business.chat.service.impl;

import com.xxl.ai.api.business.agent.mapper.AgentMapper;
import com.xxl.ai.api.business.agent.model.entity.Agent;
import com.xxl.ai.api.business.chat.service.ChatService;
import com.xxl.ai.api.business.harness.chat.ChatStreamTool;
import com.xxl.ai.api.business.session.mapper.SessionMapper;
import com.xxl.ai.api.business.session.mapper.SessionMsgMapper;
import com.xxl.ai.api.business.session.model.dto.ArtifactDownloadDTO;
import com.xxl.ai.api.business.session.model.dto.ArtifactPreviewDTO;
import com.xxl.ai.api.business.session.model.entity.Session;
import com.xxl.ai.api.business.session.model.entity.SessionMsg;
import com.xxl.ai.api.business.session.service.SessionService;
import com.xxl.tool.core.StringTool;
import com.xxl.tool.response.Response;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 对话流式 Service 实现
 *
 * Agent 校验与会话/消息元数据分别委托 AgentMapper 与 session 模块的 SessionService；
 * 流式对话的生成与下发委托 ChatStreamTool。
 *
 * @author xxl-ai 2026-09-05
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatServiceImpl.class);

    @Resource
    private AgentMapper agentMapper;
    @Resource
    private SessionService sessionService;
    @Resource
    private SessionMapper sessionMapper;
    @Resource
    private SessionMsgMapper sessionMsgMapper;
    @Resource
    private ChatStreamTool chatStreamTool;

    /**
     * Load Agent 基础信息（仅已发布、正常的 Agent）
     */
    @Override
    public Response<Agent> load(String uuid) {
        try {
            return Response.ofSuccess(requireReadyAgent(uuid));
        } catch (IllegalArgumentException e) {
            return Response.ofFail(e.getMessage());
        }
    }

    /**
     * 校验并返回可用 Agent（存在、已发布），不满足抛 IllegalArgumentException
     */
    @Override
    public Agent requireReadyAgent(String uuid) {
        if (StringTool.isBlank(uuid)) {
            throw new IllegalArgumentException("访问地址无效");
        }
        return checkReady(agentMapper.loadByUuid(uuid));
    }

    /**
     * 按 Agent ID 校验并返回可用 Agent（worker 生成侧使用）
     */
    @Override
    public Agent requireReadyAgentById(long agentId) {
        return checkReady(agentMapper.load(agentId));
    }

    /**
     * 可用性校验：存在且已发布，否则抛 IllegalArgumentException
     */
    private Agent checkReady(Agent agent) {
        if (agent == null) {
            throw new IllegalArgumentException("Agent 不存在或已删除");
        }
        if (agent.getPublishStatus() != 1) {
            throw new IllegalArgumentException("Agent 未发布，暂不可访问");
        }
        return agent;
    }

    /**
     * 发起对话：校验会话 → 落库用户消息与助手占位 → 投递任务 → 打开 SSE 连接
     */
     @Override
     public SseEmitter send(String uuid, String visitorId, long sessionId, String content) {
         return send(uuid, visitorId, sessionId, content, null);
     }

     @Override
     public SseEmitter send(String uuid, String visitorId, long sessionId, String content, Map<String, String> variables) {
         try {
             if (StringTool.isBlank(content)) {
                 throw new IllegalArgumentException("请输入内容");
             }
             Agent agent = requireReadyAgent(uuid);
             Session session = sessionService.require(agent.getId(), sessionId);
             if (StringTool.isBlank(visitorId)
                     || StringTool.isBlank(session.getVisitorId())
                     || !session.getVisitorId().equals(visitorId.trim())) {
                 throw new IllegalArgumentException("无权访问该对话");
             }
             long msgId = sessionService.openRound(session, content);
             try {
                 // 任务队列属系统内部流转，使用 agent_id（uuid 仅用于 C 端投放入口）
                 chatStreamTool.submit(msgId, agent.getId(), sessionId, content, variables);
             } catch (Exception submitError) {
                 // 投递失败：占位消息回填失败态，避免永远停留在生成中（无任务可消费）
                 sessionService.fail(msgId, sessionId);
                 throw submitError;
             }
             return chatStreamTool.open(msgId, null);
         } catch (Exception e) {
             logger.warn("Agent 对话提交失败, uuid={}, err={}", uuid, e.getMessage());
             return chatStreamTool.error(e.getMessage());
         }
     }

    /**
     * 断线/刷新续传：从既有结果流的 {@code lastEventId} 之后继续转发，不重新生成
     */
    @Override
    public SseEmitter resume(long msgId, String lastEventId) {
        return resume(msgId, null, lastEventId);
    }

    @Override
    public SseEmitter resume(long msgId, String visitorId, String lastEventId) {
        if (msgId <= 0) {
            return chatStreamTool.error("无效的会话流标识");
        }
        if (StringTool.isNotBlank(visitorId)) {
            SessionMsg msg = sessionMsgMapper.load(msgId);
            if (msg == null) {
                return chatStreamTool.error("会话消息不存在");
            }
            Session session = sessionMapper.load(msg.getSessionId());
            if (session == null || session.getVisitorId() == null || !visitorId.trim().equals(session.getVisitorId())) {
                return chatStreamTool.error("无权访问该会话流");
            }
        }
        return chatStreamTool.open(msgId, lastEventId);
    }

    /**
     * 主动终止本节点会话进行中的生成：标记终止 → 中断生成 → 回收工具调用进程（幂等，无进行中生成亦成功）
     */
    @Override
    public Response<String> stop(long sessionId) {
        return stop(sessionId, null);
    }

    @Override
    public Response<String> stop(long sessionId, String visitorId) {
        if (sessionId <= 0) {
            return Response.ofFail("无效的会话标识");
        }
        if (StringTool.isNotBlank(visitorId)) {
            Session session = sessionMapper.load(sessionId);
            if (session == null) {
                return Response.ofFail("会话不存在");
            }
            if (session.getVisitorId() == null || !visitorId.trim().equals(session.getVisitorId())) {
                return Response.ofFail("无权操作该会话");
            }
        }
        chatStreamTool.cancel(sessionId);
        return Response.ofSuccess();
    }

    /**
     * 在线预览对话交付物内容（C端公开校验：Agent 就绪 + 会话归属访客 + 消息所属会话）
     */
    @Override
    public Response<ArtifactPreviewDTO> previewArtifact(String uuid, String visitorId, long sessionId, long msgId, String artifactId) {
        try {
            Agent agent = requireReadyAgent(uuid);
            Session session = sessionService.require(agent.getId(), sessionId);
            if (StringTool.isBlank(visitorId)
                    || StringTool.isBlank(session.getVisitorId())
                    || !session.getVisitorId().equals(visitorId.trim())) {
                return Response.ofFail("无权访问该对话产物");
            }
            return sessionService.previewMsgArtifact(sessionId, msgId, artifactId);
        } catch (IllegalArgumentException e) {
            return Response.ofFail(e.getMessage());
        }
    }

    /**
     * 解析对话交付物下载文件（C端公开校验：Agent 就绪 + 会话归属访客 + 消息所属会话）
     */
    @Override
    public Response<ArtifactDownloadDTO> downloadArtifact(String uuid, String visitorId, long sessionId, long msgId, String artifactId) {
        try {
            Agent agent = requireReadyAgent(uuid);
            Session session = sessionService.require(agent.getId(), sessionId);
            if (StringTool.isBlank(visitorId)
                    || StringTool.isBlank(session.getVisitorId())
                    || !session.getVisitorId().equals(visitorId.trim())) {
                return Response.ofFail("无权访问该对话产物");
            }
            return sessionService.downloadMsgArtifact(sessionId, msgId, artifactId);
        } catch (IllegalArgumentException e) {
            return Response.ofFail(e.getMessage());
        }
    }

}
