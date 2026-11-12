package com.xxl.ai.api.business.workflow.service;

import com.xxl.ai.api.business.workflow.model.dto.WorkflowExecutionDTO;
import com.xxl.tool.response.PageModel;
import com.xxl.tool.response.Response;

import java.util.List;

/**
 * 工作流执行记录服务接口
 *
 * @author xxl-ai 2026-11-12
 */
public interface WorkflowExecutionService {

    /**
     * 分页查询执行记录
     */
    PageModel<WorkflowExecutionDTO> pageList(long spaceId, Long workflowId, int offset, int pagesize, Integer status);

    /**
     * 查询执行详情
     */
    Response<WorkflowExecutionDTO> load(long spaceId, long id);

    /**
     * 批量删除执行记录
     */
    Response<String> delete(long spaceId, List<Long> ids);
}
