package com.xxl.ai.api.business.workflow.mapper;

import com.xxl.ai.api.business.workflow.model.entity.Workflow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 工作流定义 Mapper
 *
 * @author xxl-ai 2026-11-12
 */
@Mapper
public interface WorkflowMapper {

    int insert(Workflow workflow);

    int delete(@Param("id") long id);

    int deleteByIds(@Param("ids") List<Long> ids);

    int update(Workflow workflow);

    int updateStatus(@Param("id") long id, @Param("status") int status);

    Workflow load(@Param("id") long id);

    List<Workflow> listBySpace(@Param("spaceId") long spaceId);

    List<Workflow> pageList(@Param("spaceId") long spaceId,
                            @Param("offset") int offset,
                            @Param("pagesize") int pagesize,
                            @Param("name") String name,
                            @Param("status") Integer status);

    int pageListCount(@Param("spaceId") long spaceId,
                      @Param("offset") int offset,
                      @Param("pagesize") int pagesize,
                      @Param("name") String name,
                      @Param("status") Integer status);

    int countBySpaceId(@Param("spaceId") long spaceId);
}
