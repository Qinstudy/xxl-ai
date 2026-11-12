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
import com.xxl.ai.api.business.supplier.mapper.SupplierModelMapper;
import com.xxl.ai.api.business.supplier.model.SupplierRuntime;
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

    @Resource
    private EvalExperimentMapper evalExperimentMapper;

    @Resource
    private EvalDatasetMapper evalDatasetMapper;

    @Resource
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Resource
    private AgentMapper agentMapper;

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
            for (EvalExperiment exp : list) {
                EvalExperimentDTO dto = EvalAdaptor.adapt2dto(exp);
                if (exp.getJudgeModelId() != null && exp.getJudgeModelId() > 0) {
                    SupplierModel sm = supplierModelMapper.load(exp.getJudgeModelId());
                    if (sm != null) {
                        dto.setJudgeModelName(sm.getName());
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
    public Response<EvalExperimentDTO> load(long id) {
        EvalExperiment exp = evalExperimentMapper.load(id);
        if (exp == null) {
            return Response.ofFail("评测实验记录不存在");
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

        // 初始化实验记录
        EvalExperiment experiment = new EvalExperiment();
        experiment.setSpaceId(spaceId);
        experiment.setDatasetId(dataset.getId());
        experiment.setName(StringTool.isNotBlank(experimentDTO.getName()) ? experimentDTO.getName() : dataset.getName() + "-实验" + System.currentTimeMillis());
        experiment.setEvalType(dataset.getEvalType());
        experiment.setTargetId(dataset.getTargetId());
        experiment.setTargetName(dataset.getTargetName());
        experiment.setJudgeModelId(experimentDTO.getJudgeModelId());
        experiment.setStatus(1); // 运行中
        experiment.setTotalCases(dataset.getSampleCount());
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

        // 异步提交跑批
        evalExecutor.submit(() -> executeExperiment(spaceId, experimentId, dataset, experimentDTO.getJudgeModelId()));

        return Response.ofSuccess(experimentId);
    }

    @Override
    public Response<String> cancel(long spaceId, long id) {
        EvalExperiment exp = evalExperimentMapper.load(id);
        if (exp == null || exp.getSpaceId() != spaceId) {
            return Response.ofFail("实验不存在或无权限");
        }
        if (exp.getStatus() == 1) { // 仅运行中可取消
            exp.setStatus(4); // 已取消
            exp.setErrorMsg("用户主动取消实验");
            evalExperimentMapper.updateProgress(exp);
            return Response.ofSuccess();
        }
        return Response.ofFail("当前实验状态不可取消");
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

            // 1、解析裁判模型运行时
            SupplierRuntime judgeRuntime = resolveJudgeRuntime(spaceId, judgeModelId);

            // 2、解析生成/目标运行时及快照
            SupplierRuntime targetRuntime = null;
            Map<String, Object> targetSnapshot = new HashMap<>();
            targetSnapshot.put("targetId", dataset.getTargetId());
            targetSnapshot.put("targetName", dataset.getTargetName());
            targetSnapshot.put("evalType", dataset.getEvalType());

            Agent agentTarget = null;
            List<Long> kbIds = new ArrayList<>();

            if ("RAG".equalsIgnoreCase(dataset.getEvalType())) {
                KnowledgeBase kb = knowledgeBaseMapper.load(dataset.getTargetId());
                if (kb != null) {
                    kbIds.add(kb.getId());
                    targetSnapshot.put("similarityThreshold", kb.getSimilarityThreshold());
                    targetSnapshot.put("topK", kb.getTopK());
                }
                // 默认使用空间默认模型生成回答
                targetRuntime = judgeRuntime;
            } else if ("AGENT".equalsIgnoreCase(dataset.getEvalType())) {
                agentTarget = agentMapper.load(dataset.getTargetId());
                if (agentTarget != null) {
                    targetSnapshot.put("systemPrompt", agentTarget.getSystemPrompt());
                    targetSnapshot.put("temperature", agentTarget.getTemperature());
                    Response<SupplierRuntime> rtResp = supplierService.loadRuntime(spaceId, agentTarget.getModelSupplierId(), agentTarget.getModelId());
                    if (rtResp.isSuccess()) {
                        targetRuntime = rtResp.getData();
                    }
                }
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
                // 状态检测：是否被取消
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
                // 打点更新当前进度与均值
                EvalExperiment progress = new EvalExperiment();
                progress.setId(experimentId);
                progress.setStatus(1);
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

            evalExperimentMapper.updateProgress(finish);
            logger.info("Eval experiment {} finished successfully.", experimentId);
        } catch (Exception e) {
            logger.error("Eval experiment {} failed with exception", experimentId, e);
            markFailed(experimentId, "执行异常: " + e.getMessage());
        }
    }

    private void markFailed(long experimentId, String errorMsg) {
        EvalExperiment fail = new EvalExperiment();
        fail.setId(experimentId);
        fail.setStatus(3); // 失败
        fail.setErrorMsg(StringTool.isNotBlank(errorMsg) ? errorMsg : "未知异常");
        evalExperimentMapper.updateProgress(fail);
    }

    private SupplierRuntime resolveJudgeRuntime(long spaceId, Long judgeModelId) {
        if (judgeModelId != null && judgeModelId > 0) {
            SupplierModel sm = supplierModelMapper.load(judgeModelId);
            if (sm != null) {
                Response<SupplierRuntime> resp = supplierService.loadRuntime(spaceId, sm.getSupplierId(), sm.getId());
                if (resp.isSuccess()) {
                    return resp.getData();
                }
            }
        }
        return null;
    }
}
