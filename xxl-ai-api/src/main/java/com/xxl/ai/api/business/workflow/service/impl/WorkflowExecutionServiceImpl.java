package com.xxl.ai.api.business.workflow.service.impl;

import com.xxl.ai.api.business.workflow.mapper.WorkflowExecutionMapper;
import com.xxl.ai.api.business.workflow.model.adaptor.WorkflowAdaptor;
import com.xxl.ai.api.business.workflow.model.dto.WorkflowExecutionDTO;
import com.xxl.ai.api.business.workflow.model.entity.WorkflowExecution;
import com.xxl.ai.api.business.workflow.service.WorkflowExecutionService;
import com.xxl.tool.core.CollectionTool;
import com.xxl.tool.response.PageModel;
import com.xxl.tool.response.Response;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流执行记录服务实现类
 *
 * @author xxl-ai 2026-11-12
 */
@Service
public class WorkflowExecutionServiceImpl implements WorkflowExecutionService {

    @Resource
    private WorkflowExecutionMapper workflowExecutionMapper;

    @Override
    public PageModel<WorkflowExecutionDTO> pageList(long spaceId, Long workflowId, int offset, int pagesize, Integer status) {
        List<WorkflowExecution> list = workflowExecutionMapper.pageList(spaceId, workflowId, offset, pagesize, status);
        int total = workflowExecutionMapper.pageListCount(spaceId, workflowId, offset, pagesize, status);

        List<WorkflowExecutionDTO> dtoList = new ArrayList<>();
        if (CollectionTool.isNotEmpty(list)) {
            for (WorkflowExecution item : list) {
                dtoList.add(WorkflowAdaptor.adapt2dto(item));
            }
        }
        return new PageModel<>(offset, pagesize, total, dtoList);
    }

    @Override
    public Response<WorkflowExecutionDTO> load(long spaceId, long id) {
        WorkflowExecution execution = workflowExecutionMapper.load(id);
        if (execution == null || execution.getSpaceId() != spaceId) {
            return Response.ofFail("执行记录不存在或无权访问");
        }
        return Response.ofSuccess(WorkflowAdaptor.adapt2dto(execution));
    }

    @Override
    public Response<String> delete(long spaceId, List<Long> ids) {
        if (CollectionTool.isEmpty(ids)) {
            return Response.ofFail("请选择待删除的执行记录");
        }
        for (Long id : ids) {
            if (id != null) {
                WorkflowExecution item = workflowExecutionMapper.load(id);
                if (item != null && item.getSpaceId() == spaceId) {
                    workflowExecutionMapper.delete(id);
                }
            }
        }
        return Response.ofSuccess();
    }
}
