package com.xxl.ai.api.business.chat.service;

import com.xxl.ai.api.business.agent.model.entity.Agent;
import com.xxl.tool.response.Response;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 对话流式 Service（公开访问：免管理端登录态，按 uuid + visitorId 隔离会话）
 *
 * 1、Agent 校验：Agent 就绪（存在、已发布），供发送与 worker 生成复用；
 * 2、流式对话：发送/续传编排（校验会话 → 落库一轮 → 投递任务 → 打开 SSE 连接）。
 *
 * 会话/消息的元数据操作（创建/列表/改名/删除、消息列表、管理端查询）收敛在 session 模块的 SessionService。
 *
 * @author xxl-ai 2026-09-05
 */
public interface ChatService {

    /**
     * Load Agent 基础信息（仅已发布、正常的 Agent）
     */
    Response<Agent> load(String uuid);

    /**
     * 校验并返回可用 Agent（存在、未停用、已发布），不满足抛 IllegalArgumentException
     */
    Agent requireReadyAgent(String uuid);

    /**
     * 按 Agent ID 校验并返回可用 Agent（worker 生成侧使用）。
     *
     * uuid 仅用于 C 端投放入口，系统内部流转一律使用 agent_id。
     */
    Agent requireReadyAgentById(long agentId);

    /**
     * 发起流式对话：校验会话 → 落库用户消息与助手占位 → 投递任务 → 打开 SSE 连接
     */
    SseEmitter send(String uuid, String visitorId, long sessionId, String content);

    /**
     * 断线/刷新续传：从结果流 {@code lastEventId} 之后继续转发，不重新生成
     */
    SseEmitter resume(long msgId, String lastEventId);

    /**
     * 主动终止本节点会话进行中的生成：标记终止 → 中断生成线程 → 回收工具调用进程
     *
     * 已生成的部分内容按失败状态回填（正文追加终止提示），生成侧写 done 终态结束结果流；
     * 其他节点上的在途生成不受影响（无进行中生成时亦返回成功）。
     */
    Response<String> stop(long sessionId);

}
