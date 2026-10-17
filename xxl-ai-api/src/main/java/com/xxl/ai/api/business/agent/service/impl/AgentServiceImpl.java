package com.xxl.ai.api.business.agent.service.impl;

import com.xxl.ai.api.business.agent.enums.PublishStatusEnum;
import com.xxl.ai.api.business.agent.mapper.AgentMapper;
import com.xxl.ai.api.business.agent.model.adaptor.AgentAdaptor;
import com.xxl.ai.api.business.agent.model.dto.AgentDTO;
import com.xxl.ai.api.business.agent.model.entity.Agent;
import com.xxl.ai.api.business.agent.service.AgentService;
import com.xxl.ai.api.business.harness.skill.SkillToolFactory;
import com.xxl.ai.api.business.memory.service.MemoryService;
import com.xxl.ai.api.business.session.service.SessionService;
import com.xxl.tool.core.CollectionTool;
import com.xxl.tool.core.StringTool;
import com.xxl.tool.response.PageModel;
import com.xxl.tool.response.Response;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Agent Service 实现
 *
 * 元数据 CRUD 与发布/取消发布在本模块；删除后运行时沙箱清理委托 harness 的 {@link SkillToolFactory}。
 *
 * @author xxl-ai 2026-09-05
 */
@Service
public class AgentServiceImpl implements AgentService {

    @Resource
    private AgentMapper agentMapper;
    @Resource
    private SessionService sessionService;
    @Resource
    private MemoryService memoryService;
    @Resource
    private SkillToolFactory skillToolFactory;

    /**
     * 分页查询 Agent 列表
     */
    @Override
    public PageModel<AgentDTO> pageList(long spaceId, int offset, int pagesize, String name, int publishStatus) {
        List<Agent> pageList = agentMapper.pageList(spaceId, offset, pagesize, name, publishStatus);
        int totalCount = agentMapper.pageListCount(spaceId, offset, pagesize, name, publishStatus);
        List<AgentDTO> pageListDto = new java.util.ArrayList<>();
        if (CollectionTool.isNotEmpty(pageList)) {
            for (Agent agent : pageList) {
                pageListDto.add(AgentAdaptor.adapt2dto(agent));
            }
        }
        PageModel<AgentDTO> pageModel = new PageModel<>();
        pageModel.setData(pageListDto);
        pageModel.setTotal(totalCount);
        return pageModel;
    }

    /**
     * 按ID查询 Agent
     */
    @Override
    public Response<Agent> load(long id) {
        Agent agent = agentMapper.load(id);
        return agent != null ? Response.ofSuccess(agent) : Response.ofFail("Agent 不存在");
    }

    /**
     * 查询空间下全部 Agent（轻量列表，供下拉选择）
     */
    @Override
    public List<Agent> loadAll(long spaceId) {
        return agentMapper.listBySpace(spaceId);
    }

    /**
     * 新增 Agent
     */
    @Override
    public Response<String> insert(long spaceId, AgentDTO dto) {
        Agent agent = AgentAdaptor.adapt(dto);
        if (agent == null || StringTool.isBlank(agent.getName())) {
            return Response.ofFail("Agent 名称不能为空");
        }
        if (agent.getModelSupplierId() == 0 || agent.getModelId() == 0) {
            return Response.ofFail("请选择 Agent 对话模型");
        }
        agent.setSpaceId(spaceId);
        agent.setPublishStatus(0);
        agent.setUuid(null);
        agentMapper.insert(agent);
        return Response.ofSuccess();
    }

    /**
     * 批量删除 Agent（同一事务内同步级联清理对话、消息与长期记忆）
     */
    @Override
    public Response<String> deleteByIds(List<Long> ids) {
        if (CollectionTool.isEmpty(ids)) {
            return Response.ofFail("请选择要删除的 Agent");
        }
        int ret = agentMapper.deleteByIds(ids);
        if (ret <= 0) {
            return Response.ofFail();
        }
        // 同步级联清理对话/消息与长期记忆（按 Agent ID），与 Agent 删除同事务，避免异步清理带来的不一致
        sessionService.purgeByAgentIds(ids);
        memoryService.purgeByAgentIds(ids);
        // 清理已删除 Agent 的运行时沙箱（skill 物化目录等），避免残留
        for (Long id : ids) {
            if (id != null && id > 0) {
                skillToolFactory.evict(id);
            }
        }
        return Response.ofSuccess();
    }

    /**
     * 更新 Agent（已发布状态下修改配置后需重新发布）
     */
    @Override
    public Response<String> update(AgentDTO dto) {
        Agent agent = AgentAdaptor.adapt(dto);
        if (agent == null || StringTool.isBlank(agent.getName())) {
            return Response.ofFail("Agent 名称不能为空");
        }
        int ret = agentMapper.update(agent);
        return ret > 0 ? Response.ofSuccess() : Response.ofFail();
    }

    /**
     * 发布：生成访问 URL（UUID）、置已发布
     */
    @Override
    public Response<String> publish(long id) {
        Agent agent = agentMapper.load(id);
        if (agent == null) {
            return Response.ofFail("Agent 不存在");
        }
        if (StringTool.isBlank(agent.getUuid())) {
            agent.setUuid(UUID.randomUUID().toString().replace("-", ""));
        }
        agent.setPublishStatus(PublishStatusEnum.PUBLISHED.getCode());
        agentMapper.update(agent);
        return Response.ofSuccess(agent.getUuid());
    }

    /**
     * 取消发布：置未发布
     */
    @Override
    public Response<String> unpublish(long id) {
        Agent agent = agentMapper.load(id);
        if (agent == null) {
            return Response.ofFail("Agent 不存在");
        }
        agent.setPublishStatus(PublishStatusEnum.UNPUBLISHED.getCode());
        agentMapper.update(agent);
        return Response.ofSuccess();
    }

}