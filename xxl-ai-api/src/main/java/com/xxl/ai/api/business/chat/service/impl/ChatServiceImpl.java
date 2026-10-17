package com.xxl.ai.api.business.chat.service.impl;

import com.xxl.ai.api.business.agent.mapper.AgentMapper;
import com.xxl.ai.api.business.agent.model.entity.Agent;
import com.xxl.ai.api.business.chat.service.ChatService;
import com.xxl.ai.api.business.harness.chat.ChatStreamTool;
import com.xxl.ai.api.business.session.model.entity.Session;
import com.xxl.ai.api.business.session.service.SessionService;
import com.xxl.tool.core.StringTool;
import com.xxl.tool.response.Response;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
        try {
            if (StringTool.isBlank(content)) {
                throw new IllegalArgumentException("请输入内容");
            }
            Agent agent = requireReadyAgent(uuid);
            Session session = sessionService.require(agent.getId(), sessionId);
            long msgId = sessionService.openRound(session, content);
            // 任务队列属系统内部流转，使用 agent_id（uuid 仅用于 C 端投放入口）
            chatStreamTool.submit(msgId, agent.getId(), sessionId, content);
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
        if (msgId <= 0) {
            return chatStreamTool.error("无效的会话流标识");
        }
        return chatStreamTool.open(msgId, lastEventId);
    }

}
