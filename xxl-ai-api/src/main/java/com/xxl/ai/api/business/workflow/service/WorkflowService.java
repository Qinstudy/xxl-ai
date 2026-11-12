package com.xxl.ai.api.business.workflow.service;

import com.xxl.ai.api.business.workflow.model.dto.WorkflowDTO;
import com.xxl.ai.api.business.workflow.model.dto.WorkflowExecutionDTO;
import com.xxl.tool.response.PageModel;
import com.xxl.tool.response.Response;

import java.util.List;
import java.util.Map;

/**
 * 工作流服务接口
 *
 * @author xxl-ai 2026-11-12
 */
public interface WorkflowService {

    /**
     * 分页查询工作流列表
     */
    PageModel<WorkflowDTO> pageList(long spaceId, int offset, int pagesize, String name, Integer status);

    /**
     * 根据主键查询详情
     */
    Response<WorkflowDTO> load(long spaceId, long id);

    /**
     * 新增工作流定义
     */
    Response<String> insert(long spaceId, WorkflowDTO dto);

    /**
     * 批量删除工作流定义
     */
    Response<String> delete(long spaceId, List<Long> ids);

    /**
     * 更新工作流定义
     */
    Response<String> update(long spaceId, WorkflowDTO dto);

    /**
     * 更改工作流状态（0-草稿/正常, 1-发布, 2-停用）
     */
    Response<String> updateStatus(long spaceId, long id, int status);

    /**
     * 触发单次工作流调试运行
     *
     * @param spaceId 业务空间ID
     * @param id      工作流ID
     * @param inputs  外部入参字典
     * @return 执行结果与全链路 Trace
     */
    Response<WorkflowExecutionDTO> run(long spaceId, long id, Map<String, Object> inputs);
}
