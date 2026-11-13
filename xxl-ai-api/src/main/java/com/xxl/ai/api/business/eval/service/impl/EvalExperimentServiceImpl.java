package com.xxl.ai.api.business.eval.service.impl;

import com.xxl.ai.api.business.agent.mapper.AgentMapper;
import com.xxl.ai.api.business.agent.model.entity.Agent;
import com.xxl.ai.api.business.eval.mapper.EvalDatasetMapper;
import com.xxl.ai.api.business.eval.mapper.EvalExperimentMapper;
import com.xxl.ai.api.business.eval.model.adaptor.EvalAdaptor;
import com.xxl.ai.api.business.eval.model.dto.EvalExperimentDTO;
import com.xxl.ai.api.business.eval.model.entity.EvalDataset;
import com.xxl.ai.api.business.eval.model.entity.EvalExperiment;
import com.xxl.ai.api.business.eval.service.EvalExperimentService;
import com.xxl.ai.api.business.harness.eval.EvalEngineTool;
import com.xxl.ai.api.business.knowledge.mapper.KnowledgeBaseMapper;
import com.xxl.ai.api.business.knowledge.model.entity.KnowledgeBase;
import com.xxl.ai.api.business.supplier.mapper.SupplierMapper;
import com.xxl.ai.api.business.supplier.mapper.SupplierModelMapper;
import com.xxl.ai.api.business.supplier.model.SupplierRuntime;
import com.xxl.ai.api.business.supplier.model.entity.Supplier;
import com.xxl.ai.api.business.supplier.model.entity.SupplierModel;
import com.xxl.ai.api.business.supplier.service.SupplierService;
import com.xxl.tool.core.CollectionTool;
import com.xxl.tool.core.StringTool;
import com.xxl.tool.json.GsonTool;
import com.xxl.tool.response.PageModel;
import com.xxl.tool.response.Response;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.*;

/**
 * 评测实验业务实现（支持定长线程池异步跑批、打点更新与取消）
 *
 * @author xxl-ai 2026-11-12
 */
@Service
public class EvalExperimentServiceImpl implements EvalExperimentService {

    private static final Logger logger = LoggerFactory.getLogger(EvalExperimentServiceImpl.class);

    /** 异常错误字段库表最大长度（xxl_ai_eval_experiment.error_msg VARCHAR(500)） */
    private static final int MAX_ERROR_MSG_LEN = 500;

    @Resource
    private EvalExperimentMapper evalExperimentMapper;

    @Resource
    private EvalDatasetMapper evalDatasetMapper;

    @Resource
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Resource
    private AgentMapper agentMapper;

    @Resource
    private SupplierMapper supplierMapper;

    @Resource
    private SupplierModelMapper supplierModelMapper;

    @Resource
    private SupplierService supplierService;

    @Resource
    private EvalEngineTool evalEngineTool;

    /** 异步评测任务线程池（限制并发度为 3，防止瞬间打满模型 API 限制） */
    private ExecutorService evalExecutor;

    @PostConstruct
    public void init() {
        evalExecutor = new ThreadPoolExecutor(
                3,
                6,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "EvalExperiment-Worker-" + System.currentTimeMillis());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    @PreDestroy
    public void destroy() {
        if (evalExecutor != null) {
            evalExecutor.shutdown();
        }
    }

    @Override
    public PageModel<EvalExperimentDTO> pageList(long spaceId, int offset, int pagesize, String name, Long datasetId, Integer status) {
        List<EvalExperiment> list = evalExperimentMapper.pageList(spaceId, offset, pagesize, name, datasetId, status);
        int total = evalExperimentMapper.pageListCount(spaceId, offset, pagesize, name, datasetId, status);

        List<EvalExperimentDTO> dtoList = new ArrayList<>();
        if (CollectionTool.isNotEmpty(list)) {
            Map<Long, String> judgeNameCache = new HashMap<>();
            for (EvalExperiment exp : list) {
                EvalExperimentDTO dto = EvalAdaptor.adapt2dto(exp);
                if (exp.getJudgeModelId() != null && exp.getJudgeModelId() > 0) {
                    String modelName = judgeNameCache.computeIfAbsent(exp.getJudgeModelId(), id -> {
                        SupplierModel sm = supplierModelMapper.load(id);
                        return sm != null ? sm.getName() : null;
                    });
                    if (modelName != null) {
                        dto.setJudgeModelName(modelName);
                    }
                }
                dtoList.add(dto);
            }
        }
        PageModel<EvalExperimentDTO> pageModel = new PageModel<>();
        pageModel.setData(dtoList);
        pageModel.setTotal(total);
        return pageModel;
    }

    @Override
    public Response<EvalExperimentDTO> load(long spaceId, long id) {
        EvalExperiment exp = evalExperimentMapper.load(id);
        if (exp == null || exp.getSpaceId() != spaceId) {
            return Response.ofFail("评测实验记录不存在或无权限访问");
        }
        EvalExperimentDTO dto = EvalAdaptor.adapt2dto(exp);
        if (exp.getJudgeModelId() != null && exp.getJudgeModelId() > 0) {
            SupplierModel sm = supplierModelMapper.load(exp.getJudgeModelId());
            if (sm != null) {
                dto.setJudgeModelName(sm.getName());
            }
        }
        return Response.ofSuccess(dto);
    }

    @Override
    public Response<Long> createAndRun(long spaceId, EvalExperimentDTO experimentDTO) {
        if (experimentDTO == null || experimentDTO.getDatasetId() <= 0) {
            return Response.ofFail("请指定评测数据集");
        }
        EvalDataset dataset = evalDatasetMapper.load(experimentDTO.getDatasetId());
        if (dataset == null || dataset.getSpaceId() != spaceId) {
            return Response.ofFail("评测数据集不存在或无权限访问");
        }
        if (dataset.getSampleCount() <= 0 || StringTool.isBlank(dataset.getContentJson())) {
            return Response.ofFail("该评测集暂无有效测试用例，请先导入样本");
        }

        // 前置解析并校验测试用例非空
        List<EvalEngineTool.EvalCase> parsedCases;
        try {
            parsedCases = GsonTool.fromJsonList(dataset.getContentJson(), EvalEngineTool.EvalCase.class);
        } catch (Exception e) {
            return Response.ofFail("评测数据集样本格式损坏，请重新导入");
        }
        if (CollectionTool.isEmpty(parsedCases)) {
            return Response.ofFail("该评测集暂无有效测试用例，请先导入样本");
        }

        // 前置校验评测目标归属（防跨空间挂载）
        Agent agentTarget = null;
        String resolvedTargetName = dataset.getTargetName();
        if ("RAG".equalsIgnoreCase(dataset.getEvalType())) {
            KnowledgeBase kb = knowledgeBaseMapper.load(dataset.getTargetId());
            if (kb == null || kb.getSpaceId() != spaceId) {
                return Response.ofFail("评测目标知识库不存在或无权限访问");
            }
            resolvedTargetName = StringTool.isNotBlank(kb.getName()) ? kb.getName() : resolvedTargetName;
        } else if ("AGENT".equalsIgnoreCase(dataset.getEvalType())) {
            agentTarget = agentMapper.load(dataset.getTargetId());
            if (agentTarget == null || agentTarget.getSpaceId() != spaceId) {
                return Response.ofFail("评测目标 Agent 不存在或无权限访问");
            }
            resolvedTargetName = StringTool.isNotBlank(agentTarget.getName()) ? agentTarget.getName() : resolvedTargetName;
        } else {
            return Response.ofFail("不支持的评测类型: " + dataset.getEvalType());
        }

        // 前置校验并解析裁判模型 ID（归属当前空间、启用态对话模型；未传时自动选择同空间默认对话模型）
        Response<Long> judgeResp = resolveAndValidateJudgeModelId(spaceId, experimentDTO.getJudgeModelId(), agentTarget);
        if (!judgeResp.isSuccess()) {
            return Response.ofFail(judgeResp.getMsg());
        }
        Long resolvedJudgeModelId = judgeResp.getData();

        // 初始化实验记录
        EvalExperiment experiment = new EvalExperiment();
        experiment.setSpaceId(spaceId);
        experiment.setDatasetId(dataset.getId());
        experiment.setName(StringTool.isNotBlank(experimentDTO.getName()) ? experimentDTO.getName() : dataset.getName() + "-实验" + System.currentTimeMillis());
        experiment.setEvalType(dataset.getEvalType());
        experiment.setTargetId(dataset.getTargetId());
        experiment.setTargetName(resolvedTargetName != null ? resolvedTargetName : "");
        experiment.setJudgeModelId(resolvedJudgeModelId);
        experiment.setStatus(1); // 运行中
        experiment.setTotalCases(parsedCases.size());
        experiment.setCompletedCases(0);
        experiment.setAvgHitRate(BigDecimal.ZERO);
        experiment.setAvgMrr(BigDecimal.ZERO);
        experiment.setAvgFaithfulness(BigDecimal.ZERO);
        experiment.setAvgRelevance(BigDecimal.ZERO);
        experiment.setAvgGoalCompletion(BigDecimal.ZERO);
        experiment.setAvgLatencyMs(0);
        experiment.setTotalTokens(0);

        evalExperimentMapper.insert(experiment);
        final long experimentId = experiment.getId();

        // 异步提交跑批（兜底支持单测未走 PostConstruct 的情况）
        ExecutorService executor = this.evalExecutor;
        if (executor != null) {
            executor.submit(() -> executeExperiment(spaceId, experimentId, dataset, resolvedJudgeModelId));
        } else {
            executeExperiment(spaceId, experimentId, dataset, resolvedJudgeModelId);
        }

        return Response.ofSuccess(experimentId);
    }

    @Override
    public Response<String> cancel(long spaceId, long id) {
        EvalExperiment exp = evalExperimentMapper.load(id);
        if (exp == null || exp.getSpaceId() != spaceId) {
            return Response.ofFail("实验不存在或无权限");
        }
        if (exp.getStatus() != 1) { // 仅运行中可取消
            return Response.ofFail("当前实验状态不可取消");
        }
        int updated = evalExperimentMapper.updateCancel(id, "用户主动取消实验");
        return updated > 0 ? Response.ofSuccess() : Response.ofFail("实验状态已变更，无法取消");
    }

    @Override
    public Response<String> delete(long spaceId, List<Long> ids) {
        if (CollectionTool.isEmpty(ids)) {
            return Response.ofFail("请勾选待删除记录");
        }
        for (Long id : ids) {
            if (id != null) {
                EvalExperiment exp = evalExperimentMapper.load(id);
                if (exp != null && exp.getSpaceId() == spaceId) {
                    evalExperimentMapper.delete(id);
                }
            }
        }
        return Response.ofSuccess();
    }

    /**
     * 核心异步跑批执行逻辑
     */
    private void executeExperiment(long spaceId, long experimentId, EvalDataset dataset, Long judgeModelId) {
        logger.info("Start running eval experiment: id={}, name={}", experimentId, dataset.getName());
        List<EvalEngineTool.CaseResult> caseResults = new ArrayList<>();
        Map<String, Object> summaryReport = new HashMap<>();

        try {
            List<EvalEngineTool.EvalCase> cases = GsonTool.fromJsonList(dataset.getContentJson(), EvalEngineTool.EvalCase.class);
            if (CollectionTool.isEmpty(cases)) {
                markFailed(experimentId, "评测用例集解析为空");
                return;
            }

            // 1、解析并校验裁判模型运行时
            SupplierRuntime judgeRuntime = resolveJudgeRuntime(spaceId, judgeModelId);
            if (judgeRuntime == null) {
                markFailed(experimentId, "裁判大模型配置不可用，请检查供应商与模型状态");
                return;
            }

            // 2、解析并二次校验生成/目标运行时及快照（纵深防御）
            SupplierRuntime targetRuntime = null;
            Map<String, Object> targetSnapshot = new HashMap<>();
            targetSnapshot.put("targetId", dataset.getTargetId());
            targetSnapshot.put("targetName", dataset.getTargetName());
            targetSnapshot.put("evalType", dataset.getEvalType());

            Agent agentTarget = null;
            List<Long> kbIds = new ArrayList<>();

            if ("RAG".equalsIgnoreCase(dataset.getEvalType())) {
                KnowledgeBase kb = knowledgeBaseMapper.load(dataset.getTargetId());
                if (kb == null || kb.getSpaceId() != spaceId) {
                    markFailed(experimentId, "评测目标知识库不存在或无权限访问");
                    return;
                }
                kbIds.add(kb.getId());
                targetSnapshot.put("similarityThreshold", kb.getSimilarityThreshold());
                targetSnapshot.put("topK", kb.getTopK());
                // RAG 生成默认复用当前空间已解析的有效对话模型
                targetRuntime = judgeRuntime;
            } else if ("AGENT".equalsIgnoreCase(dataset.getEvalType())) {
                agentTarget = agentMapper.load(dataset.getTargetId());
                if (agentTarget == null || agentTarget.getSpaceId() != spaceId) {
                    markFailed(experimentId, "评测目标 Agent 不存在或无权限访问");
                    return;
                }
                targetSnapshot.put("systemPrompt", agentTarget.getSystemPrompt());
                targetSnapshot.put("temperature", agentTarget.getTemperature());
                Response<SupplierRuntime> rtResp = supplierService.loadRuntime(spaceId, agentTarget.getModelSupplierId(), agentTarget.getModelId());
                if (rtResp != null && rtResp.isSuccess()) {
                    targetRuntime = rtResp.getData();
                }
            } else {
                markFailed(experimentId, "不支持的评测类型: " + dataset.getEvalType());
                return;
            }
            summaryReport.put("targetSnapshot", targetSnapshot);

            // 3、逐个执行用例
            double totalHit1 = 0;
            double totalHit3 = 0;
            double totalMrr = 0;
            double totalFaith = 0;
            double totalRelev = 0;
            double totalGoal = 0;
            long totalLatency = 0;
            int totalTokens = 0;

            for (int i = 0; i < cases.size(); i++) {
                // 状态检测：是否被取消或删除
                EvalExperiment current = evalExperimentMapper.load(experimentId);
                if (current == null || current.getStatus() == 4) {
                    logger.info("Experiment {} was cancelled, terminate execution.", experimentId);
                    return;
                }

                EvalEngineTool.EvalCase evalCase = cases.get(i);
                EvalEngineTool.CaseResult result;

                if ("RAG".equalsIgnoreCase(dataset.getEvalType())) {
                    result = evalEngineTool.runRagCase(spaceId, kbIds, evalCase, targetRuntime, judgeRuntime);
                } else {
                    result = evalEngineTool.runAgentCase(spaceId, agentTarget, evalCase, targetRuntime, judgeRuntime);
                }
                caseResults.add(result);

                totalHit1 += result.getHitRateAt1();
                totalHit3 += result.getHitRateAt3();
                totalMrr += result.getReciprocalRank();
                totalFaith += result.getFaithfulnessScore();
                totalRelev += result.getRelevanceScore();
                totalGoal += result.getGoalCompletionScore();
                totalLatency += result.getLatencyMs();
                totalTokens += result.getTokens();

                int completed = i + 1;
                // 打点更新当前进度与均值（SQL 层限定 WHERE status = 1，防止覆盖已取消状态）
                EvalExperiment progress = new EvalExperiment();
                progress.setId(experimentId);
                progress.setCompletedCases(completed);
                progress.setAvgHitRate(BigDecimal.valueOf(totalHit3 / completed).setScale(4, RoundingMode.HALF_UP));
                progress.setAvgMrr(BigDecimal.valueOf(totalMrr / completed).setScale(4, RoundingMode.HALF_UP));
                progress.setAvgFaithfulness(BigDecimal.valueOf(totalFaith / completed).setScale(4, RoundingMode.HALF_UP));
                progress.setAvgRelevance(BigDecimal.valueOf(totalRelev / completed).setScale(4, RoundingMode.HALF_UP));
                progress.setAvgGoalCompletion(BigDecimal.valueOf(totalGoal / completed).setScale(4, RoundingMode.HALF_UP));
                progress.setAvgLatencyMs((int) (totalLatency / completed));
                progress.setTotalTokens(totalTokens);

                evalExperimentMapper.updateProgress(progress);
            }

            // 完成前二次确认未被取消
            EvalExperiment preFinish = evalExperimentMapper.load(experimentId);
            if (preFinish == null || preFinish.getStatus() == 4) {
                logger.info("Experiment {} cancelled before finalizing report.", experimentId);
                return;
            }

            // 4、评测完成，汇总明细报告
            Map<String, Object> metrics = new HashMap<>();
            int count = cases.size();
            metrics.put("hitRateAt1", BigDecimal.valueOf(totalHit1 / count).setScale(4, RoundingMode.HALF_UP));
            metrics.put("hitRateAt3", BigDecimal.valueOf(totalHit3 / count).setScale(4, RoundingMode.HALF_UP));
            metrics.put("mrr", BigDecimal.valueOf(totalMrr / count).setScale(4, RoundingMode.HALF_UP));
            metrics.put("faithfulness", BigDecimal.valueOf(totalFaith / count).setScale(4, RoundingMode.HALF_UP));
            metrics.put("relevance", BigDecimal.valueOf(totalRelev / count).setScale(4, RoundingMode.HALF_UP));
            metrics.put("goalCompletion", BigDecimal.valueOf(totalGoal / count).setScale(4, RoundingMode.HALF_UP));
            metrics.put("avgLatencyMs", (int) (totalLatency / count));
            metrics.put("totalTokens", totalTokens);

            summaryReport.put("metrics", metrics);
            summaryReport.put("cases", caseResults);

            EvalExperiment finish = new EvalExperiment();
            finish.setId(experimentId);
            finish.setStatus(2); // 已完成
            finish.setCompletedCases(count);
            finish.setAvgHitRate(BigDecimal.valueOf(totalHit3 / count).setScale(4, RoundingMode.HALF_UP));
            finish.setAvgMrr(BigDecimal.valueOf(totalMrr / count).setScale(4, RoundingMode.HALF_UP));
            finish.setAvgFaithfulness(BigDecimal.valueOf(totalFaith / count).setScale(4, RoundingMode.HALF_UP));
            finish.setAvgRelevance(BigDecimal.valueOf(totalRelev / count).setScale(4, RoundingMode.HALF_UP));
            finish.setAvgGoalCompletion(BigDecimal.valueOf(totalGoal / count).setScale(4, RoundingMode.HALF_UP));
            finish.setAvgLatencyMs((int) (totalLatency / count));
            finish.setTotalTokens(totalTokens);
            finish.setSummaryJson(GsonTool.toJson(summaryReport));

            evalExperimentMapper.updateFinish(finish);
            logger.info("Eval experiment {} finished successfully.", experimentId);
        } catch (Throwable t) {
            logger.error("Eval experiment {} failed with throwable", experimentId, t);
            markFailed(experimentId, "执行异常: " + t.getMessage());
        }
    }

    /**
     * 校验用户指定裁判模型或回退解析同空间可用对话模型
     */
    private Response<Long> resolveAndValidateJudgeModelId(long spaceId, Long requestedJudgeModelId, Agent agentTarget) {
        if (requestedJudgeModelId != null && requestedJudgeModelId > 0) {
            SupplierModel sm = supplierModelMapper.load(requestedJudgeModelId);
            if (sm == null) {
                return Response.ofFail("指定的裁判模型不存在");
            }
            if (sm.getType() != 0) {
                return Response.ofFail("裁判模型必须为对话模型");
            }
            Response<SupplierRuntime> rtResp = supplierService.loadRuntime(spaceId, sm.getSupplierId(), sm.getId());
            if (rtResp == null || !rtResp.isSuccess() || rtResp.getData() == null) {
                return Response.ofFail("裁判模型不可用: " + (rtResp != null ? rtResp.getMsg() : "无法加载运行时"));
            }
            return Response.ofSuccess(sm.getId());
        }

        // 未显式指定时，若为 Agent 评测则优先复用目标 Agent 自带的对话模型
        if (agentTarget != null && agentTarget.getModelSupplierId() > 0 && agentTarget.getModelId() > 0) {
            SupplierModel agentModel = supplierModelMapper.load(agentTarget.getModelId());
            if (agentModel != null && agentModel.getType() == 0 && agentModel.getStatus() == 0) {
                Response<SupplierRuntime> rtResp = supplierService.loadRuntime(spaceId, agentTarget.getModelSupplierId(), agentTarget.getModelId());
                if (rtResp != null && rtResp.isSuccess() && rtResp.getData() != null) {
                    return Response.ofSuccess(agentModel.getId());
                }
            }
        }

        // 回退选择当前空间内首个启用态对话模型
        List<Supplier> suppliers = supplierMapper.listBySpace(spaceId);
        if (CollectionTool.isNotEmpty(suppliers)) {
            for (Supplier supplier : suppliers) {
                if (supplier == null || supplier.getStatus() != 0) {
                    continue;
                }
                List<SupplierModel> models = supplierModelMapper.listBySupplier(supplier.getId());
                if (CollectionTool.isEmpty(models)) {
                    continue;
                }
                for (SupplierModel model : models) {
                    if (model == null || model.getType() != 0 || model.getStatus() != 0) {
                        continue;
                    }
                    Response<SupplierRuntime> rtResp = supplierService.loadRuntime(spaceId, supplier.getId(), model.getId());
                    if (rtResp != null && rtResp.isSuccess() && rtResp.getData() != null) {
                        return Response.ofSuccess(model.getId());
                    }
                }
            }
        }
        return Response.ofFail("当前空间暂无可用的对话大模型，请先在供应商模型中配置或选择裁判模型");
    }

    private void markFailed(long experimentId, String errorMsg) {
        evalExperimentMapper.updateFailure(experimentId, truncateErrorMsg(errorMsg));
    }

    private String truncateErrorMsg(String errorMsg) {
        String msg = StringTool.isNotBlank(errorMsg) ? errorMsg : "未知异常";
        if (msg.length() <= MAX_ERROR_MSG_LEN) {
            return msg;
        }
        return msg.substring(0, MAX_ERROR_MSG_LEN - 3) + "...";
    }

    private SupplierRuntime resolveJudgeRuntime(long spaceId, Long judgeModelId) {
        if (judgeModelId != null && judgeModelId > 0) {
            SupplierModel sm = supplierModelMapper.load(judgeModelId);
            if (sm != null && sm.getType() == 0) {
                Response<SupplierRuntime> resp = supplierService.loadRuntime(spaceId, sm.getSupplierId(), sm.getId());
                if (resp != null && resp.isSuccess()) {
                    return resp.getData();
                }
            }
        }
        return null;
    }
}
