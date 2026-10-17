package com.xxl.ai.api.business.memory.mapper;

import com.xxl.ai.api.business.memory.model.dto.MemoryDTO;
import com.xxl.ai.api.business.memory.model.entity.Memory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 长期记忆 Mapper
 *
 * @author xxl-ai 2026-10-17
 */
@Mapper
public interface MemoryMapper {

    int insert(Memory memory);

    /** 按 唯一键(agent_id+visitor_id) 新增或更新（归档写入） */
    int upsert(Memory memory);

    int deleteByIds(@Param("ids") List<Long> ids);

    /** 修改记忆内容（限本空间内，返回受影响行数；0 表示不存在或不属于该空间） */
    int updateContent(@Param("id") long id, @Param("content") String content, @Param("spaceId") long spaceId);

    /** 按 Agent ID 删除记忆（Agent 删除时级联清理） */
    int deleteByAgentId(@Param("agentId") long agentId);

    Memory load(@Param("id") long id);

    /** 按 Agent×访客 查询记忆（对话注入与归档使用） */
    Memory loadByAgentVisitor(@Param("agentId") long agentId, @Param("visitorId") String visitorId);

    /** 管理端：分页查询空间内记忆（可按 Agent、访客ID 过滤，含所属 Agent 名称） */
    List<MemoryDTO> pageList(@Param("spaceId") long spaceId,
                             @Param("agentId") long agentId,
                             @Param("visitorId") String visitorId,
                             @Param("offset") int offset,
                             @Param("pagesize") int pagesize);

    /** 管理端：统计空间内记忆数量 */
    int pageListCount(@Param("spaceId") long spaceId,
                      @Param("agentId") long agentId,
                      @Param("visitorId") String visitorId);

}
