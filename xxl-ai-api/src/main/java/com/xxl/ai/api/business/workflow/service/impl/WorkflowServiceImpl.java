package com.xxl.ai.api.business.workflow.service.impl;

import com.xxl.ai.api.business.harness.mcp.McpClientTool;
import com.xxl.ai.api.business.harness.mcp.McpToolFactory;
import com.xxl.ai.api.business.harness.workflow.WorkflowContext;
import com.xxl.ai.api.business.harness.workflow.WorkflowDagEngine;
import com.xxl.ai.api.business.harness.workflow.WorkflowRuntimeProvider;
import com.xxl.ai.api.business.harness.workflow.model.WorkflowGraph;
import com.xxl.ai.api.business.knowledge.mapper.KnowledgeBaseMapper;
import com.xxl.ai.api.business.knowledge.model.entity.KnowledgeBase;
import com.xxl.ai.api.business.mcp.model.entity.Mcp;
import com.xxl.ai.api.business.mcp.service.McpService;
import com.xxl.ai.api.business.supplier.model.SupplierRuntime;
import com.xxl.ai.api.business.supplier.service.SupplierService;
import com.xxl.ai.api.business.workflow.mapper.WorkflowExecutionMapper;
import com.xxl.ai.api.business.workflow.mapper.WorkflowMapper;
import com.xxl.ai.api.business.workflow.model.adaptor.WorkflowAdaptor;
import com.xxl.ai.api.business.workflow.model.dto.WorkflowDTO;
import com.xxl.ai.api.business.workflow.model.dto.WorkflowExecutionDTO;
import com.xxl.ai.api.business.workflow.model.entity.Workflow;
import com.xxl.ai.api.business.workflow.model.entity.WorkflowExecution;
import com.xxl.ai.api.business.workflow.service.WorkflowService;
import com.xxl.tool.core.CollectionTool;
import com.xxl.tool.core.StringTool;
import com.xxl.tool.json.GsonTool;
import com.xxl.tool.response.PageModel;
import com.xxl.tool.response.Response;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeoutException;

/**
 * 工作流服务实现类
 *
 * @author xxl-ai 2026-11-12
 */
@Service
public class WorkflowServiceImpl implements WorkflowService {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowServiceImpl.class);

    private static final int ERROR_MSG_MAX_LEN = 500;

    @Resource
    private WorkflowMapper workflowMapper;

    @Resource
    private WorkflowExecutionMapper workflowExecutionMapper;

    @Resource
    private WorkflowDagEngine workflowDagEngine;

    @Resource
    private SupplierService supplierService;

    @Resource
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Resource
    private McpService mcpService;

    @Resource
    private McpClientTool mcpClientTool;

    @Override
    public PageModel<WorkflowDTO> pageList(long spaceId, int offset, int pagesize, String name, Integer status) {
        List<Workflow> list = workflowMapper.pageList(spaceId, offset, pagesize, name, status);
        int total = workflowMapper.pageListCount(spaceId, offset, pagesize, name, status);

        List<WorkflowDTO> dtoList = new ArrayList<>();
        if (CollectionTool.isNotEmpty(list)) {
            for (Workflow item : list) {
                dtoList.add(WorkflowAdaptor.adapt2dto(item));
            }
        }
        PageModel<WorkflowDTO> pageModel = new PageModel<>();
        pageModel.setData(dtoList);
        pageModel.setTotal(total);
        return pageModel;
    }

    @Override
    public Response<WorkflowDTO> load(long spaceId, long id) {
        Workflow workflow = workflowMapper.load(id);
        if (workflow == null || workflow.getSpaceId() != spaceId) {
            return Response.ofFail("工作流不存在或无权访问");
        }
        return Response.ofSuccess(WorkflowAdaptor.adapt2dto(workflow));
    }

    @Override
    public Response<String> insert(long spaceId, WorkflowDTO dto) {
        if (dto == null) {
            return Response.ofFail("参数不能为空");
        }
        if (StringTool.isBlank(dto.getName())) {
            return Response.ofFail("工作流名称不能为空");
        }
        if (StringTool.isBlank(dto.getGraphJson())) {
            return Response.ofFail("工作流图定义 (graphJson) 不能为空");
        }

        // 校验 DAG 图合法性
        try {
            WorkflowGraph graph = GsonTool.fromJson(dto.getGraphJson(), WorkflowGraph.class);
            workflowDagEngine.validateGraph(graph);
        } catch (Exception e) {
            return Response.ofFail("工作流拓扑结构校验失败: " + e.getMessage());
        }

        Workflow entity = WorkflowAdaptor.adapt(dto);
        entity.setSpaceId(spaceId);
        entity.setStatus(dto.getStatus() != null ? dto.getStatus() : 0);

        workflowMapper.insert(entity);
        return Response.ofSuccess(String.valueOf(entity.getId()));
    }

    @Override
    public Response<String> delete(long spaceId, List<Long> ids) {
        if (CollectionTool.isEmpty(ids)) {
            return Response.ofFail("请选择待删除的工作流");
        }
        for (Long id : ids) {
            if (id != null) {
                Workflow wf = workflowMapper.load(id);
                if (wf != null && wf.getSpaceId() == spaceId) {
                    workflowMapper.delete(id);
                    workflowExecutionMapper.deleteByWorkflowId(id);
                }
            }
        }
        return Response.ofSuccess();
    }

    @Override
    public Response<String> update(long spaceId, WorkflowDTO dto) {
        if (dto == null || dto.getId() == null) {
            return Response.ofFail("工作流ID不能为空");
        }
        if (StringTool.isBlank(dto.getName())) {
            return Response.ofFail("工作流名称不能为空");
        }
        Workflow existing = workflowMapper.load(dto.getId());
        if (existing == null || existing.getSpaceId() != spaceId) {
            return Response.ofFail("工作流不存在或无权访问");
        }

        if (StringTool.isNotBlank(dto.getGraphJson())) {
            try {
                WorkflowGraph graph = GsonTool.fromJson(dto.getGraphJson(), WorkflowGraph.class);
                workflowDagEngine.validateGraph(graph);
            } catch (Exception e) {
                return Response.ofFail("工作流拓扑结构校验失败: " + e.getMessage());
            }
        }

        Workflow entity = WorkflowAdaptor.adapt(dto);
        entity.setSpaceId(spaceId);
        if (StringTool.isBlank(dto.getGraphJson())) {
            entity.setGraphJson(existing.getGraphJson());
        }
        if (dto.getStatus() == null) {
            entity.setStatus(existing.getStatus());
        }
        if (dto.getExtJson() == null) {
            entity.setExtJson(existing.getExtJson());
        }
        workflowMapper.update(entity);
        return Response.ofSuccess();
    }

    @Override
    public Response<String> updateStatus(long spaceId, long id, int status) {
        Workflow existing = workflowMapper.load(id);
        if (existing == null || existing.getSpaceId() != spaceId) {
            return Response.ofFail("工作流不存在或无权访问");
        }
        workflowMapper.updateStatus(id, status);
        return Response.ofSuccess();
    }

    @Override
    public Response<WorkflowExecutionDTO> run(long spaceId, long id, Map<String, Object> inputs) {
        Workflow workflow = workflowMapper.load(id);
        if (workflow == null || workflow.getSpaceId() != spaceId) {
            return Response.ofFail("工作流不存在或无权访问");
        }
        if (workflow.getStatus() == 2) {
            return Response.ofFail("工作流已停用，无法执行");
        }

        WorkflowGraph graph;
        try {
            graph = GsonTool.fromJson(workflow.getGraphJson(), WorkflowGraph.class);
            workflowDagEngine.validateGraph(graph);
        } catch (Exception e) {
            return Response.ofFail("工作流图定义非法: " + e.getMessage());
        }

        // 1、创建执行实例记录
        WorkflowExecution execution = new WorkflowExecution();
        execution.setSpaceId(spaceId);
        execution.setWorkflowId(id);
        execution.setTriggerType("DEBUG");
        execution.setStatus(0); // 运行中
        execution.setInputJson(GsonTool.toJson(inputs != null ? inputs : Collections.emptyMap()));
        workflowExecutionMapper.insert(execution);

        long executionId = execution.getId();

        // 2、组装执行上下文与运行时提供者
        WorkflowContext ctx = new WorkflowContext(spaceId, id, inputs);
        ctx.setExecutionId(executionId);
        ctx.setAllowMock(true);
        ctx.setRuntimeProvider(new WorkflowRuntimeProvider() {
            @Override
            public SupplierRuntime getSupplierRuntime(Long supplierId, Long modelId) {
                if (supplierId != null && modelId != null) {
                    Response<SupplierRuntime> resp = supplierService.loadRuntime(spaceId, supplierId, modelId);
                    if (resp.isSuccess()) {
                        return resp.getData();
                    }
                }
                return null;
            }

            @Override
            public KnowledgeBase getKnowledgeBase(Long kbId) {
                if (kbId != null && kbId > 0) {
                    KnowledgeBase kb = knowledgeBaseMapper.load(kbId);
                    // 强校验空间归属与启用状态，阻断跨租户 IDOR 越权检索
                    if (kb != null && kb.getSpaceId() == spaceId && kb.getStatus() == 0) {
                        return kb;
                    }
                }
                return null;
            }

            @Override
            public String executeTool(String toolType, String toolName, Map<String, Object> params) {
                return executeTool(toolType, null, toolName, params);
            }

            @Override
            public String executeTool(String toolType, Long targetId, String toolName, Map<String, Object> params) {
                return invokeSpaceTool(spaceId, toolType, targetId, toolName, params);
            }
        });

        // 3、触发执行引擎（同步阻塞返回方案 A）
        try {
            workflowDagEngine.execute(graph, inputs, ctx);

            // 回写成功状态
            execution.setStatus(1); // 成功
            execution.setTotalTokens(ctx.getTotalTokens());
            execution.setDurationMs(ctx.getDurationMs());
            execution.setOutputJson(GsonTool.toJson(ctx.getFinalOutput()));
            execution.setExecutionJson(GsonTool.toJson(ctx.getTraces()));
            workflowExecutionMapper.updateFinish(execution);

            return Response.ofSuccess(WorkflowAdaptor.adapt2dto(execution));
        } catch (Exception e) {
            logger.error("Workflow debug execution failed, id={}", id, e);

            // 回写失败/超时状态，并保留已产生的 Token 与节点轨迹
            execution.setStatus(e instanceof TimeoutException ? 3 : 2);
            execution.setTotalTokens(ctx.getTotalTokens());
            execution.setDurationMs(ctx.getDurationMs());
            execution.setErrorMsg(truncateErrorMsg(e.getMessage()));
            execution.setExecutionJson(GsonTool.toJson(ctx.getTraces()));
            workflowExecutionMapper.updateFinish(execution);

            WorkflowExecutionDTO dto = WorkflowAdaptor.adapt2dto(execution);
            return new Response<>(500, "工作流执行失败: " + e.getMessage(), dto);
        }
    }

    /**
     * 在当前空间边界内执行真实 MCP 工具调用
     */
    private String invokeSpaceTool(long spaceId, String toolType, Long mcpId, String toolName, Map<String, Object> params) {
        String normalizedType = StringTool.isNotBlank(toolType) ? toolType.trim().toUpperCase(Locale.ROOT) : "MCP";
        if (!"MCP".equals(normalizedType)) {
            throw new UnsupportedOperationException("阶段一 TOOL 节点仅支持 MCP 工具调用，不支持的类型: " + normalizedType);
        }
        if (StringTool.isBlank(toolName)) {
            throw new IllegalArgumentException("TOOL 节点工具名称 (toolName) 不能为空");
        }

        List<Mcp> candidateMcps = new ArrayList<>();
        if (mcpId != null && mcpId > 0) {
            List<Mcp> list = mcpService.listByIds(Collections.singletonList(mcpId));
            if (CollectionTool.isNotEmpty(list)) {
                for (Mcp mcp : list) {
                    if (mcp != null && mcp.getSpaceId() == spaceId && mcp.getStatus() == 0) {
                        candidateMcps.add(mcp);
                    }
                }
            }
            if (candidateMcps.isEmpty()) {
                throw new IllegalStateException("MCP 服务不存在、已停用或无权访问: mcpId=" + mcpId);
            }
        } else {
            List<Mcp> spaceMcps = mcpService.listBySpace(spaceId);
            if (CollectionTool.isNotEmpty(spaceMcps)) {
                for (Mcp mcp : spaceMcps) {
                    if (mcp != null && mcp.getSpaceId() == spaceId && mcp.getStatus() == 0) {
                        candidateMcps.add(mcp);
                    }
                }
            }
            if (candidateMcps.isEmpty()) {
                throw new IllegalStateException("当前空间下未配置已启用的 MCP 服务");
            }
        }

        String trimmedName = toolName.trim();
        Mcp matchedMcp = null;
        String matchedRawToolName = null;
        int matchCount = 0;

        for (Mcp mcp : candidateMcps) {
            List<McpClientTool.McpToolInfo> tools = mcpClientTool.listTools(mcp);
            if (CollectionTool.isEmpty(tools)) {
                continue;
            }
            for (McpClientTool.McpToolInfo toolInfo : tools) {
                String rawName = toolInfo.getToolName();
                String prefixedName = McpToolFactory.buildToolName(mcp.getName(), rawName);
                if (trimmedName.equals(rawName) || trimmedName.equals(prefixedName)) {
                    matchedMcp = mcp;
                    matchedRawToolName = rawName;
                    matchCount++;
                }
            }
        }

        if (matchCount == 0 || matchedMcp == null) {
            throw new IllegalStateException("未在当前空间已启用的 MCP 服务中找到工具: " + trimmedName);
        }
        if (matchCount > 1 && (mcpId == null || mcpId <= 0)) {
            throw new IllegalArgumentException("空间内存在多个同名 MCP 工具 [" + trimmedName + "]，请指定 mcpId 或使用 mcpName__toolName 前缀");
        }

        String argsJson = GsonTool.toJson(params != null ? params : Collections.emptyMap());
        return mcpClientTool.callTool(matchedMcp, matchedRawToolName, argsJson);
    }

    private String truncateErrorMsg(String msg) {
        if (msg == null) {
            return null;
        }
        return msg.length() <= ERROR_MSG_MAX_LEN ? msg : msg.substring(0, ERROR_MSG_MAX_LEN);
    }
}
