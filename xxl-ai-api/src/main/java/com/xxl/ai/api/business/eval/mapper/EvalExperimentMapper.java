package com.xxl.ai.api.business.eval.mapper;

import com.xxl.ai.api.business.eval.model.entity.EvalExperiment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 评测实验记录 Mapper
 *
 * @author xxl-ai 2026-11-12
 */
@Mapper
public interface EvalExperimentMapper {

    int insert(EvalExperiment experiment);

    int delete(@Param("id") long id);

    int deleteByIds(@Param("ids") List<Long> ids);

    int update(EvalExperiment experiment);

    int updateProgress(EvalExperiment experiment);

    int updateFinish(EvalExperiment experiment);

    int updateFailure(@Param("id") long id, @Param("errorMsg") String errorMsg);

    int updateCancel(@Param("id") long id, @Param("errorMsg") String errorMsg);

    EvalExperiment load(@Param("id") long id);

    List<EvalExperiment> listByDatasetId(@Param("datasetId") long datasetId);

    List<EvalExperiment> pageList(@Param("spaceId") long spaceId,
                                  @Param("offset") int offset,
                                  @Param("pagesize") int pagesize,
                                  @Param("name") String name,
                                  @Param("datasetId") Long datasetId,
                                  @Param("status") Integer status);

    int pageListCount(@Param("spaceId") long spaceId,
                      @Param("offset") int offset,
                      @Param("pagesize") int pagesize,
                      @Param("name") String name,
                      @Param("datasetId") Long datasetId,
                      @Param("status") Integer status);

    int countBySpaceId(@Param("spaceId") long spaceId);
}
