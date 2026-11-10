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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

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
        Response<String> optValid = validateOptions(agent);
        if (optValid != null) {
            return optValid;
        }
        Response<String> subAgentValid = validateSubAgents(agent, spaceId);
        if (subAgentValid != null) {
            return subAgentValid;
        }
        agent.setSpaceId(spaceId);
        agent.setPublishStatus(0);
        agent.setUuid(null);
        agentMapper.insert(agent);
        return Response.ofSuccess();
    }

    /**
     * 校验大模型推理超参数合法性（[FEAT-006]）
     */
    private Response<String> validateOptions(Agent agent) {
        if (agent.getTemperature() != null) {
            double temp = agent.getTemperature();
            if (temp < 0.0 || temp > 2.0) {
                return Response.ofFail("采样温度 (Temperature) 必须在 0.0 到 2.0 之间");
            }
        }
        if (agent.getTopP() != null) {
            double topP = agent.getTopP();
            if (topP < 0.0 || topP > 1.0) {
                return Response.ofFail("核采样 (Top P) 必须在 0.0 到 1.0 之间");
            }
        }
        if (agent.getMaxTokens() != null) {
            int maxTokens = agent.getMaxTokens();
            if (maxTokens <= 0 || maxTokens > 131072) {
                return Response.ofFail("单次最大 Token 数 (Max Tokens) 必须在 1 到 131072 之间");
            }
        }
        return null;
    }

    /**
     * 校验关联 SubAgent 的合法性（[FEAT-013]：最多5个、不能包含自身、同空间必须存在）
     */
    private Response<String> validateSubAgents(Agent agent, long spaceId) {
        if (StringTool.isBlank(agent.getSubAgentIds())) {
            agent.setSubAgentIds(null);
            return null;
        }
        List<Long> subIds = Arrays.stream(agent.getSubAgentIds().split(","))
                .filter(StringTool::isNotBlank)
                .map(s -> {
                    try {
                        return Long.parseLong(s.trim());
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (subIds.isEmpty()) {
            agent.setSubAgentIds(null);
            return null;
        }
        if (subIds.size() > 5) {
            return Response.ofFail("关联的 SubAgent 数量最多为 5 个");
        }
        if (agent.getId() > 0 && subIds.contains(agent.getId())) {
            return Response.ofFail("不能将当前 Agent 关联为其自身的 SubAgent");
        }
        // 校验目标 SubAgent 必须归属同一空间且有效存在
        List<Agent> targetList = agentMapper.listByIds(subIds);
        if (targetList == null || targetList.size() != subIds.size()) {
            return Response.ofFail("部分关联的 SubAgent 不存在");
        }
        for (Agent sub : targetList) {
            if (sub.getSpaceId() != spaceId) {
                return Response.ofFail("不能关联非当前工作空间的 SubAgent");
            }
        }
        agent.setSubAgentIds(subIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
        return null;
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
        Agent existing = agentMapper.load(agent.getId());
        if (existing == null) {
            return Response.ofFail("Agent 不存在或已被删除");
        }
        Response<String> optValid = validateOptions(agent);
        if (optValid != null) {
            return optValid;
        }
        Response<String> subAgentValid = validateSubAgents(agent, existing.getSpaceId());
        if (subAgentValid != null) {
            return subAgentValid;
        }
        agent.setSpaceId(existing.getSpaceId());
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