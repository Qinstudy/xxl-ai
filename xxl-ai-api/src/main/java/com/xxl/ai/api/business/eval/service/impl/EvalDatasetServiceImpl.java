package com.xxl.ai.api.business.eval.service.impl;

import com.xxl.ai.api.business.agent.mapper.AgentMapper;
import com.xxl.ai.api.business.agent.model.entity.Agent;
import com.xxl.ai.api.business.eval.mapper.EvalDatasetMapper;
import com.xxl.ai.api.business.eval.model.adaptor.EvalAdaptor;
import com.xxl.ai.api.business.eval.model.dto.EvalDatasetDTO;
import com.xxl.ai.api.business.eval.model.entity.EvalDataset;
import com.xxl.ai.api.business.eval.service.EvalDatasetService;
import com.xxl.ai.api.business.harness.eval.EvalEngineTool;
import com.xxl.ai.api.business.knowledge.mapper.KnowledgeBaseMapper;
import com.xxl.ai.api.business.knowledge.model.entity.KnowledgeBase;
import com.xxl.tool.core.CollectionTool;
import com.xxl.tool.core.StringTool;
import com.xxl.tool.json.GsonTool;
import com.xxl.tool.response.PageModel;
import com.xxl.tool.response.Response;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 评测数据集业务实现
 *
 * @author xxl-ai 2026-11-12
 */
@Service
public class EvalDatasetServiceImpl implements EvalDatasetService {

    @Resource
    private EvalDatasetMapper evalDatasetMapper;

    @Resource
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Resource
    private AgentMapper agentMapper;

    @Override
    public PageModel<EvalDatasetDTO> pageList(long spaceId, int offset, int pagesize, String name, String evalType) {
        List<EvalDataset> list = evalDatasetMapper.pageList(spaceId, offset, pagesize, name, evalType);
        int total = evalDatasetMapper.pageListCount(spaceId, offset, pagesize, name, evalType);

        List<EvalDatasetDTO> dtoList = new ArrayList<>();
        if (CollectionTool.isNotEmpty(list)) {
            for (EvalDataset dataset : list) {
                dtoList.add(EvalAdaptor.adapt2dto(dataset));
            }
        }
        PageModel<EvalDatasetDTO> pageModel = new PageModel<>();
        pageModel.setData(dtoList);
        pageModel.setTotal(total);
        return pageModel;
    }

    @Override
    public Response<EvalDatasetDTO> load(long spaceId, long id) {
        EvalDataset dataset = evalDatasetMapper.load(id);
        if (dataset == null || dataset.getSpaceId() != spaceId) {
            return Response.ofFail("评测数据集不存在或无权限访问");
        }
        return Response.ofSuccess(EvalAdaptor.adapt2dto(dataset));
    }

    @Override
    public Response<String> insert(long spaceId, EvalDatasetDTO datasetDTO) {
        if (datasetDTO == null) {
            return Response.ofFail("参数不能为空");
        }
        if (StringTool.isBlank(datasetDTO.getName())) {
            return Response.ofFail("评测集名称不能为空");
        }
        if (StringTool.isBlank(datasetDTO.getEvalType())) {
            datasetDTO.setEvalType("RAG");
        }
        datasetDTO.setSpaceId(spaceId);

        // 校验目标归属并填充目标名称（防跨空间挂载）
        Response<String> targetResp = validateAndFillTarget(spaceId, datasetDTO);
        if (!targetResp.isSuccess()) {
            return targetResp;
        }

        // 计算样本数量与初值
        int count = countSamples(datasetDTO.getContentJson());
        datasetDTO.setSampleCount(count);
        if (StringTool.isBlank(datasetDTO.getContentJson())) {
            datasetDTO.setContentJson("[]");
        }

        EvalDataset entity = EvalAdaptor.adapt(datasetDTO);
        int ret = evalDatasetMapper.insert(entity);
        return ret > 0 ? Response.ofSuccess() : Response.ofFail("新增评测集失败");
    }

    @Override
    public Response<String> update(long spaceId, EvalDatasetDTO datasetDTO) {
        if (datasetDTO == null || datasetDTO.getId() <= 0) {
            return Response.ofFail("ID 参数非法");
        }
        EvalDataset old = evalDatasetMapper.load(datasetDTO.getId());
        if (old == null || old.getSpaceId() != spaceId) {
            return Response.ofFail("评测集不存在或无权限修改");
        }
        if (StringTool.isBlank(datasetDTO.getName())) {
            return Response.ofFail("评测集名称不能为空");
        }
        if (StringTool.isBlank(datasetDTO.getEvalType())) {
            datasetDTO.setEvalType(old.getEvalType());
        }

        datasetDTO.setSpaceId(spaceId);
        // 校验目标归属并填充目标名称（防跨空间挂载）
        Response<String> targetResp = validateAndFillTarget(spaceId, datasetDTO);
        if (!targetResp.isSuccess()) {
            return targetResp;
        }

        if (StringTool.isBlank(datasetDTO.getContentJson())) {
            datasetDTO.setContentJson(old.getContentJson());
            datasetDTO.setSampleCount(old.getSampleCount());
        } else {
            int count = countSamples(datasetDTO.getContentJson());
            datasetDTO.setSampleCount(count);
        }

        EvalDataset entity = EvalAdaptor.adapt(datasetDTO);
        int ret = evalDatasetMapper.update(entity);
        return ret > 0 ? Response.ofSuccess() : Response.ofFail("更新评测集失败");
    }

    @Override
    public Response<String> delete(long spaceId, List<Long> ids) {
        if (CollectionTool.isEmpty(ids)) {
            return Response.ofFail("请勾选待删除评测集");
        }
        for (Long id : ids) {
            if (id != null) {
                EvalDataset old = evalDatasetMapper.load(id);
                if (old != null && old.getSpaceId() == spaceId) {
                    evalDatasetMapper.delete(id);
                }
            }
        }
        return Response.ofSuccess();
    }

    @Override
    public Response<String> importJson(long spaceId, long id, String jsonContent) {
        if (id <= 0 || StringTool.isBlank(jsonContent)) {
            return Response.ofFail("参数非法");
        }
        EvalDataset old = evalDatasetMapper.load(id);
        if (old == null || old.getSpaceId() != spaceId) {
            return Response.ofFail("评测集不存在或无权限");
        }
        try {
            List<EvalEngineTool.EvalCase> cases = GsonTool.fromJsonList(jsonContent, EvalEngineTool.EvalCase.class);
            if (cases == null) {
                return Response.ofFail("JSON 格式错误，必须为数组格式");
            }
            if (cases.size() > 50) {
                return Response.ofFail("单个评测集样本数不能超过 50 条（防止评测超时）");
            }
            // 校验必填 query 并自动补齐缺失 ID
            for (int i = 0; i < cases.size(); i++) {
                EvalEngineTool.EvalCase item = cases.get(i);
                if (item == null || StringTool.isBlank(item.getQuery())) {
                    return Response.ofFail("第 " + (i + 1) + " 条样本缺少有效的 query 问题内容");
                }
                if (StringTool.isBlank(item.getId())) {
                    item.setId("case-" + (i + 1));
                }
            }
            String finalJson = GsonTool.toJson(cases);
            old.setContentJson(finalJson);
            old.setSampleCount(cases.size());
            evalDatasetMapper.update(old);
            return Response.ofSuccess();
        } catch (Exception e) {
            return Response.ofFail("解析样本集失败: " + e.getMessage());
        }
    }

    /**
     * 校验评测类型及目标资源归属，填充冗余目标名称
     */
    private Response<String> validateAndFillTarget(long spaceId, EvalDatasetDTO dto) {
        String evalType = StringTool.isBlank(dto.getEvalType()) ? "" : dto.getEvalType().trim().toUpperCase();
        if (!"RAG".equals(evalType) && !"AGENT".equals(evalType)) {
            return Response.ofFail("评测类型仅支持 RAG 或 AGENT");
        }
        dto.setEvalType(evalType);
        if (dto.getTargetId() <= 0) {
            return Response.ofFail("请选择评测目标");
        }
        if ("RAG".equals(evalType)) {
            KnowledgeBase kb = knowledgeBaseMapper.load(dto.getTargetId());
            if (kb == null || kb.getSpaceId() != spaceId) {
                return Response.ofFail("目标知识库不存在或无权限访问");
            }
            dto.setTargetName(StringTool.isNotBlank(kb.getName()) ? kb.getName() : "");
        } else {
            Agent agent = agentMapper.load(dto.getTargetId());
            if (agent == null || agent.getSpaceId() != spaceId) {
                return Response.ofFail("目标 Agent 不存在或无权限访问");
            }
            dto.setTargetName(StringTool.isNotBlank(agent.getName()) ? agent.getName() : "");
        }
        return Response.ofSuccess();
    }

    private int countSamples(String contentJson) {
        if (StringTool.isBlank(contentJson)) {
            return 0;
        }
        try {
            List<EvalEngineTool.EvalCase> list = GsonTool.fromJsonList(contentJson, EvalEngineTool.EvalCase.class);
            return list != null ? list.size() : 0;
        } catch (Exception ignore) {
            return 0;
        }
    }
}
