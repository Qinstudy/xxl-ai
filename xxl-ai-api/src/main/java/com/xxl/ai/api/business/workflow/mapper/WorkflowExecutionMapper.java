package com.xxl.ai.api.business.workflow.mapper;

import com.xxl.ai.api.business.workflow.model.entity.WorkflowExecution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 工作流执行记录 Mapper
 *
 * @author xxl-ai 2026-11-12
 */
@Mapper
public interface WorkflowExecutionMapper {

    int insert(WorkflowExecution execution);

    int delete(@Param("id") long id);

    int deleteByIds(@Param("ids") List<Long> ids);

    int deleteByWorkflowId(@Param("workflowId") long workflowId);

    int update(WorkflowExecution execution);

    int updateFinish(WorkflowExecution execution);

    WorkflowExecution load(@Param("id") long id);

    List<WorkflowExecution> pageList(@Param("spaceId") long spaceId,
                                     @Param("workflowId") Long workflowId,
                                     @Param("offset") int offset,
                                     @Param("pagesize") int pagesize,
                                     @Param("status") Integer status);

    int pageListCount(@Param("spaceId") long spaceId,
                      @Param("workflowId") Long workflowId,
                      @Param("offset") int offset,
                      @Param("pagesize") int pagesize,
                      @Param("status") Integer status);
}
