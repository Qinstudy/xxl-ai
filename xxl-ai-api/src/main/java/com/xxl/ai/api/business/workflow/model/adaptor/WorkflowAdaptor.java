package com.xxl.ai.api.business.workflow.model.adaptor;

import com.xxl.ai.api.business.harness.workflow.model.WorkflowGraph;
import com.xxl.ai.api.business.workflow.model.dto.WorkflowDTO;
import com.xxl.ai.api.business.workflow.model.dto.WorkflowExecutionDTO;
import com.xxl.ai.api.business.workflow.model.entity.Workflow;
import com.xxl.ai.api.business.workflow.model.entity.WorkflowExecution;
import com.xxl.tool.core.DateTool;
import com.xxl.tool.core.StringTool;
import com.xxl.tool.json.GsonTool;

/**
 * 工作流模块实体与 DTO 互相转换适配器
 *
 * @author xxl-ai 2026-11-12
 */
public class WorkflowAdaptor {

    public static Workflow adapt(WorkflowDTO dto) {
        if (dto == null) {
            return null;
        }
        Workflow entity = new Workflow();
        if (dto.getId() != null) {
            entity.setId(dto.getId());
        }
        if (dto.getSpaceId() != null) {
            entity.setSpaceId(dto.getSpaceId());
        }
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setStatus(dto.getStatus() != null ? dto.getStatus() : 0);
        entity.setGraphJson(dto.getGraphJson());
        entity.setExtJson(dto.getExtJson());
        return entity;
    }

    public static WorkflowDTO adapt2dto(Workflow entity) {
        if (entity == null) {
            return null;
        }
        WorkflowDTO dto = new WorkflowDTO();
        dto.setId(entity.getId());
        dto.setSpaceId(entity.getSpaceId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setStatus(entity.getStatus());
        dto.setGraphJson(entity.getGraphJson());
        dto.setExtJson(entity.getExtJson());

        // 解析节点数
        int count = 0;
        if (StringTool.isNotBlank(entity.getGraphJson())) {
            try {
                WorkflowGraph graph = GsonTool.fromJson(entity.getGraphJson(), WorkflowGraph.class);
                if (graph != null && graph.getNodes() != null) {
                    count = graph.getNodes().size();
                }
            } catch (Exception ignore) {}
        }
        dto.setNodeCount(count);

        dto.setAddTime(DateTool.formatDateTime(entity.getAddTime()));
        dto.setUpdateTime(DateTool.formatDateTime(entity.getUpdateTime()));
        return dto;
    }

    public static WorkflowExecution adapt(WorkflowExecutionDTO dto) {
        if (dto == null) {
            return null;
        }
        WorkflowExecution entity = new WorkflowExecution();
        if (dto.getId() != null) {
            entity.setId(dto.getId());
        }
        if (dto.getSpaceId() != null) {
            entity.setSpaceId(dto.getSpaceId());
        }
        if (dto.getWorkflowId() != null) {
            entity.setWorkflowId(dto.getWorkflowId());
        }
        entity.setTriggerType(dto.getTriggerType() != null ? dto.getTriggerType() : "DEBUG");
        entity.setStatus(dto.getStatus() != null ? dto.getStatus() : 0);
        entity.setTotalTokens(dto.getTotalTokens() != null ? dto.getTotalTokens() : 0);
        entity.setDurationMs(dto.getDurationMs() != null ? dto.getDurationMs() : 0);
        entity.setInputJson(dto.getInputJson());
        entity.setOutputJson(dto.getOutputJson());
        entity.setExecutionJson(dto.getExecutionJson());
        entity.setErrorMsg(dto.getErrorMsg());
        return entity;
    }

    public static WorkflowExecutionDTO adapt2dto(WorkflowExecution entity) {
        if (entity == null) {
            return null;
        }
        WorkflowExecutionDTO dto = new WorkflowExecutionDTO();
        dto.setId(entity.getId());
        dto.setSpaceId(entity.getSpaceId());
        dto.setWorkflowId(entity.getWorkflowId());
        dto.setTriggerType(entity.getTriggerType());
        dto.setStatus(entity.getStatus());
        dto.setTotalTokens(entity.getTotalTokens());
        dto.setDurationMs(entity.getDurationMs());
        dto.setInputJson(entity.getInputJson());
        dto.setOutputJson(entity.getOutputJson());
        dto.setExecutionJson(entity.getExecutionJson());
        dto.setErrorMsg(entity.getErrorMsg());
        dto.setAddTime(DateTool.formatDateTime(entity.getAddTime()));
        dto.setUpdateTime(DateTool.formatDateTime(entity.getUpdateTime()));
        return dto;
    }
}
