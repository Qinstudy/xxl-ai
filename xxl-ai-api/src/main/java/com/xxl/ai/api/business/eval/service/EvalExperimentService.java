package com.xxl.ai.api.business.eval.service;

import com.xxl.ai.api.business.eval.model.dto.EvalExperimentDTO;
import com.xxl.tool.response.PageModel;
import com.xxl.tool.response.Response;

import java.util.List;

/**
 * 评测实验业务接口
 *
 * @author xxl-ai 2026-11-12
 */
public interface EvalExperimentService {

    PageModel<EvalExperimentDTO> pageList(long spaceId, int offset, int pagesize, String name, Long datasetId, Integer status);

    Response<EvalExperimentDTO> load(long id);

    Response<Long> createAndRun(long spaceId, EvalExperimentDTO experimentDTO);

    Response<String> cancel(long spaceId, long id);

    Response<String> delete(long spaceId, List<Long> ids);
}
