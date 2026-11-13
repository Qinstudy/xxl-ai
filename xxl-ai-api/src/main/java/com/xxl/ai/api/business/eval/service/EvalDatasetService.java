package com.xxl.ai.api.business.eval.service;

import com.xxl.ai.api.business.eval.model.dto.EvalDatasetDTO;
import com.xxl.tool.response.PageModel;
import com.xxl.tool.response.Response;

import java.util.List;

/**
 * 评测数据集业务接口
 *
 * @author xxl-ai 2026-11-12
 */
public interface EvalDatasetService {

    PageModel<EvalDatasetDTO> pageList(long spaceId, int offset, int pagesize, String name, String evalType);

    Response<EvalDatasetDTO> load(long spaceId, long id);

    Response<String> insert(long spaceId, EvalDatasetDTO datasetDTO);

    Response<String> update(long spaceId, EvalDatasetDTO datasetDTO);

    Response<String> delete(long spaceId, List<Long> ids);

    Response<String> importJson(long spaceId, long id, String jsonContent);
}
