package com.xxl.ai.api.business.memory.service;

import com.xxl.ai.api.business.memory.model.dto.MemoryDTO;
import com.xxl.tool.response.PageModel;
import com.xxl.tool.response.Response;

import java.util.List;

/**
 * 长期记忆 Service（B端管理）
 *
 * 承载记忆元数据的分页查询与删除；记忆的生成与注入属运行时能力，由 harness 的
 * {@code MemoryTool} / {@code MemoryArchiveTool} 承担。
 *
 * @author xxl-ai 2026-10-17
 */
public interface MemoryService {

    /**
     * 分页查询空间内记忆列表（可按 Agent、访客ID 过滤）
     */
    PageModel<MemoryDTO> pageList(long spaceId, long agentId, int offset, int pagesize, String visitorId);

    /**
     * 修改记忆内容（校验空间归属）
     */
    Response<String> update(long spaceId, long id, String content);

    /**
     * 批量删除记忆
     */
    Response<String> deleteByIds(List<Long> ids);

    /**
     * 按 Agent ID 批量清理记忆（Agent 删除时级联）
     */
    void purgeByAgentIds(List<Long> agentIds);

}
