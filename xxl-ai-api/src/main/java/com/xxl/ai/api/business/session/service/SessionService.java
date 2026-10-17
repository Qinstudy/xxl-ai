package com.xxl.ai.api.business.session.service;

import com.xxl.ai.api.business.session.model.dto.SessionDTO;
import com.xxl.ai.api.business.session.model.entity.Session;
import com.xxl.ai.api.business.session.model.entity.SessionMsg;
import com.xxl.tool.response.PageModel;
import com.xxl.tool.response.Response;

import java.util.List;

/**
 * 对话 Service
 *
 * 1、公开访问（免管理端登录态，按 uuid + visitorId 隔离会话）：创建/列表/改名/删除、消息列表；
 * 2、管理端（按业务空间隔离，支持按 Agent 过滤）：对话分页列表、消息明细；
 * 3、运行时支撑（供 chat 模块流式对话调用）：会话校验、开启一轮对话、按 Agent 级联清理。
 *
 * @author xxl-ai 2026-09-05
 */
public interface SessionService {

    // ==================== 公开访问 ====================

    /**
     * 创建对话（先校验 Agent 可用）
     */
    Response<Session> create(String uuid, String visitorId, String title);

    /**
     * 对话列表（按访客隔离）
     */
    Response<List<Session>> list(String uuid, String visitorId);

    /**
     * 修改对话标题（最长50个字符）
     */
    Response<String> rename(long sessionId, String title);

    /**
     * 删除对话（连带消息）
     */
    Response<String> delete(long sessionId);

    /**
     * 消息列表（公开访问，按对话ID）
     */
    Response<List<SessionMsg>> msgList(long sessionId);

    // ==================== 管理端 ====================

    /**
     * 分页查询空间内对话列表（可按 Agent、标题、访客ID 过滤）
     *
     * @param spaceId   当前空间ID（隔离校验）
     * @param agentId   Agent ID（<=0 表示全部 Agent）
     * @param offset    分页偏移
     * @param pagesize  分页大小
     * @param title     对话标题（模糊）
     * @param visitorId 访客标识（模糊）
     * @return 对话分页数据（含所属 Agent 名称）
     */
    PageModel<SessionDTO> pageList(long spaceId, long agentId, int offset, int pagesize, String title, String visitorId);

    /**
     * 分页查询对话消息明细（校验对话归属当前空间与 Agent）
     *
     * @param spaceId   当前空间ID（隔离校验）
     * @param agentId   Agent ID（<=0 表示不做 Agent 过滤）
     * @param sessionId 对话ID
     * @param offset    分页偏移
     * @param pagesize  分页大小
     * @return 消息分页数据（页面内按时间正序）
     */
    Response<PageModel<SessionMsg>> pageMsgList(long spaceId, long agentId, long sessionId, int offset, int pagesize);

    // ==================== 运行时支撑（chat 模块调用） ====================

    /**
     * 校验并返回归属于该 Agent 的对话，不满足抛 IllegalArgumentException
     */
    Session require(String uuid, long sessionId);

    /**
     * 开启一轮对话：首条消息自动生成标题 → 落库用户消息与助手占位 → 返回助手消息ID（结果流标识）
     */
    long openRound(Session session, String content);

    /**
     * 按 Agent 访问 UUID 批量清理对话与消息（Agent 删除时同步级联）
     */
    void purgeByAgentUuids(List<String> agentUuids);

}
