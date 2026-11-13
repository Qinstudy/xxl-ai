package com.xxl.ai.api.business.eval.model.adaptor;

import com.xxl.ai.api.business.eval.model.dto.EvalDatasetDTO;
import com.xxl.ai.api.business.eval.model.dto.EvalExperimentDTO;
import com.xxl.ai.api.business.eval.model.entity.EvalDataset;
import com.xxl.ai.api.business.eval.model.entity.EvalExperiment;
import com.xxl.tool.core.DateTool;

/**
 * 评测模块适配器（实体 ↔ DTO 互转）
 *
 * @author xxl-ai 2026-11-12
 */
public class EvalAdaptor {

    public static EvalDataset adapt(EvalDatasetDTO dto) {
        if (dto == null) {
            return null;
        }
        EvalDataset dataset = new EvalDataset();
        dataset.setId(dto.getId());
        dataset.setSpaceId(dto.getSpaceId());
        dataset.setName(dto.getName());
        dataset.setEvalType(dto.getEvalType());
        dataset.setTargetId(dto.getTargetId());
        dataset.setTargetName(dto.getTargetName());
        dataset.setSampleCount(dto.getSampleCount());
        dataset.setContentJson(dto.getContentJson());
        dataset.setDescription(dto.getDescription());
        return dataset;
    }

    public static EvalDatasetDTO adapt2dto(EvalDataset entity) {
        if (entity == null) {
            return null;
        }
        EvalDatasetDTO dto = new EvalDatasetDTO();
        dto.setId(entity.getId());
        dto.setSpaceId(entity.getSpaceId());
        dto.setName(entity.getName());
        dto.setEvalType(entity.getEvalType());
        dto.setTargetId(entity.getTargetId());
        dto.setTargetName(entity.getTargetName());
        dto.setSampleCount(entity.getSampleCount());
        dto.setContentJson(entity.getContentJson());
        dto.setDescription(entity.getDescription());
        dto.setAddTime(entity.getAddTime() != null ? DateTool.formatDateTime(entity.getAddTime()) : null);
        dto.setUpdateTime(entity.getUpdateTime() != null ? DateTool.formatDateTime(entity.getUpdateTime()) : null);
        return dto;
    }

    public static EvalExperiment adapt(EvalExperimentDTO dto) {
        if (dto == null) {
            return null;
        }
        EvalExperiment experiment = new EvalExperiment();
        experiment.setId(dto.getId());
        experiment.setSpaceId(dto.getSpaceId());
        experiment.setDatasetId(dto.getDatasetId());
        experiment.setName(dto.getName());
        experiment.setEvalType(dto.getEvalType());
        experiment.setTargetId(dto.getTargetId());
        experiment.setTargetName(dto.getTargetName());
        experiment.setJudgeModelId(dto.getJudgeModelId());
        experiment.setStatus(dto.getStatus());
        experiment.setTotalCases(dto.getTotalCases());
        experiment.setCompletedCases(dto.getCompletedCases());
        experiment.setAvgHitRate(dto.getAvgHitRate());
        experiment.setAvgMrr(dto.getAvgMrr());
        experiment.setAvgFaithfulness(dto.getAvgFaithfulness());
        experiment.setAvgRelevance(dto.getAvgRelevance());
        experiment.setAvgGoalCompletion(dto.getAvgGoalCompletion());
        experiment.setAvgLatencyMs(dto.getAvgLatencyMs());
        experiment.setTotalTokens(dto.getTotalTokens());
        experiment.setSummaryJson(dto.getSummaryJson());
        experiment.setErrorMsg(dto.getErrorMsg());
        return experiment;
    }

    public static EvalExperimentDTO adapt2dto(EvalExperiment entity) {
        if (entity == null) {
            return null;
        }
        EvalExperimentDTO dto = new EvalExperimentDTO();
        dto.setId(entity.getId());
        dto.setSpaceId(entity.getSpaceId());
        dto.setDatasetId(entity.getDatasetId());
        dto.setName(entity.getName());
        dto.setEvalType(entity.getEvalType());
        dto.setTargetId(entity.getTargetId());
        dto.setTargetName(entity.getTargetName());
        dto.setJudgeModelId(entity.getJudgeModelId());
        dto.setStatus(entity.getStatus());
        dto.setTotalCases(entity.getTotalCases());
        dto.setCompletedCases(entity.getCompletedCases());
        dto.setAvgHitRate(entity.getAvgHitRate());
        dto.setAvgMrr(entity.getAvgMrr());
        dto.setAvgFaithfulness(entity.getAvgFaithfulness());
        dto.setAvgRelevance(entity.getAvgRelevance());
        dto.setAvgGoalCompletion(entity.getAvgGoalCompletion());
        dto.setAvgLatencyMs(entity.getAvgLatencyMs());
        dto.setTotalTokens(entity.getTotalTokens());
        dto.setSummaryJson(entity.getSummaryJson());
        dto.setErrorMsg(entity.getErrorMsg());
        dto.setAddTime(entity.getAddTime() != null ? DateTool.formatDateTime(entity.getAddTime()) : null);
        dto.setUpdateTime(entity.getUpdateTime() != null ? DateTool.formatDateTime(entity.getUpdateTime()) : null);
        return dto;
    }
}
