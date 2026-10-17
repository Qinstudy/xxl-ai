package com.xxl.ai.api.business.session.mapper;

import com.xxl.ai.api.business.session.model.dto.SessionDTO;
import com.xxl.ai.api.business.session.model.entity.Session;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 对话 Mapper
 *
 * @author xxl-ai 2026-09-05
 */
@Mapper
public interface SessionMapper {

    int insert(Session session);

    int delete(@Param("id") long id);

    int updateTitle(@Param("id") long id, @Param("title") String title);

    /** 刷新对话更新时间（收到新消息时调用，列表展示最近活跃时间） */
    int touch(@Param("id") long id);

    /** 按 Agent 访问 UUID 删除对话（Agent 删除时级联清理） */
    int deleteByAgentUuid(@Param("agentUuid") String agentUuid);

    Session load(@Param("id") long id);

    List<Session> listByVisitor(@Param("agentUuid") String agentUuid, @Param("visitorId") String visitorId);

    /** 管理端：分页查询空间内对话（可按 Agent、标题、访客ID 过滤，含所属 Agent 名称） */
    List<SessionDTO> pageList(@Param("spaceId") long spaceId,
                              @Param("agentId") long agentId,
                              @Param("title") String title,
                              @Param("visitorId") String visitorId,
                              @Param("offset") int offset,
                              @Param("pagesize") int pagesize);

    /** 管理端：统计空间内对话数量 */
    int pageListCount(@Param("spaceId") long spaceId,
                      @Param("agentId") long agentId,
                      @Param("title") String title,
                      @Param("visitorId") String visitorId);

}
