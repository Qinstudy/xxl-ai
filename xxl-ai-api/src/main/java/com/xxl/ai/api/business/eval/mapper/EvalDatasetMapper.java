package com.xxl.ai.api.business.eval.mapper;

import com.xxl.ai.api.business.eval.model.entity.EvalDataset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 评测数据集 Mapper
 *
 * @author xxl-ai 2026-11-12
 */
@Mapper
public interface EvalDatasetMapper {

    int insert(EvalDataset dataset);

    int delete(@Param("id") long id);

    int deleteByIds(@Param("ids") List<Long> ids);

    int update(EvalDataset dataset);

    EvalDataset load(@Param("id") long id);

    List<EvalDataset> listBySpace(@Param("spaceId") long spaceId);

    List<EvalDataset> pageList(@Param("spaceId") long spaceId,
                               @Param("offset") int offset,
                               @Param("pagesize") int pagesize,
                               @Param("name") String name,
                               @Param("evalType") String evalType);

    int pageListCount(@Param("spaceId") long spaceId,
                      @Param("offset") int offset,
                      @Param("pagesize") int pagesize,
                      @Param("name") String name,
                      @Param("evalType") String evalType);

    int countBySpaceId(@Param("spaceId") long spaceId);
}
