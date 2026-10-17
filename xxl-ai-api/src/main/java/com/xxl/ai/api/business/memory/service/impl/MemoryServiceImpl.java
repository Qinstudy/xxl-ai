package com.xxl.ai.api.business.memory.service.impl;

import com.xxl.ai.api.business.memory.mapper.MemoryMapper;
import com.xxl.ai.api.business.memory.model.dto.MemoryDTO;
import com.xxl.ai.api.business.memory.service.MemoryService;
import com.xxl.tool.core.CollectionTool;
import com.xxl.tool.core.StringTool;
import com.xxl.tool.response.PageModel;
import com.xxl.tool.response.Response;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 长期记忆 Service 实现
 *
 * @author xxl-ai 2026-10-17
 */
@Service
public class MemoryServiceImpl implements MemoryService {

    @Resource
    private MemoryMapper memoryMapper;

    /**
     * 分页查询空间内记忆列表（含所属 Agent 名称）
     */
    @Override
    public PageModel<MemoryDTO> pageList(long spaceId, long agentId, int offset, int pagesize, String visitorId) {
        List<MemoryDTO> pageList = memoryMapper.pageList(spaceId, agentId, visitorId, offset, pagesize);
        int totalCount = memoryMapper.pageListCount(spaceId, agentId, visitorId);
        PageModel<MemoryDTO> pageModel = new PageModel<>();
        pageModel.setData(pageList);
        pageModel.setTotal(totalCount);
        return pageModel;
    }

    /**
     * 修改记忆内容（校验空间归属）
     */
    @Override
    public Response<String> update(long spaceId, long id, String content) {
        if (id <= 0) {
            return Response.ofFail("记忆不存在");
        }
        if (StringTool.isBlank(content)) {
            return Response.ofFail("记忆内容不能为空");
        }
        int ret = memoryMapper.updateContent(id, content.trim(), spaceId);
        return ret > 0 ? Response.ofSuccess() : Response.ofFail("记忆不存在");
    }

    /**
     * 批量删除记忆
     */
    @Override
    public Response<String> deleteByIds(List<Long> ids) {
        if (CollectionTool.isEmpty(ids)) {
            return Response.ofFail("请选择要删除的记忆");
        }
        int ret = memoryMapper.deleteByIds(ids);
        return ret > 0 ? Response.ofSuccess() : Response.ofFail();
    }

    /**
     * 按 Agent ID 批量清理记忆（Agent 删除时级联）
     */
    @Override
    public void purgeByAgentIds(List<Long> agentIds) {
        if (CollectionTool.isEmpty(agentIds)) {
            return;
        }
        for (Long agentId : agentIds) {
            if (agentId != null && agentId > 0) {
                memoryMapper.deleteByAgentId(agentId);
            }
        }
    }

}
