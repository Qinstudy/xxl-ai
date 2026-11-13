package com.xxl.ai.api.business.eval;

import com.xxl.ai.api.business.agent.mapper.AgentMapper;
import com.xxl.ai.api.business.agent.model.entity.Agent;
import com.xxl.ai.api.business.eval.mapper.EvalDatasetMapper;
import com.xxl.ai.api.business.eval.mapper.EvalExperimentMapper;
import com.xxl.ai.api.business.eval.model.dto.EvalDatasetDTO;
import com.xxl.ai.api.business.eval.model.dto.EvalExperimentDTO;
import com.xxl.ai.api.business.eval.model.entity.EvalDataset;
import com.xxl.ai.api.business.eval.model.entity.EvalExperiment;
import com.xxl.ai.api.business.eval.service.impl.EvalDatasetServiceImpl;
import com.xxl.ai.api.business.eval.service.impl.EvalExperimentServiceImpl;
import com.xxl.ai.api.business.harness.chat.TraceCollector;
import com.xxl.ai.api.business.harness.eval.EvalEngineTool;
import com.xxl.ai.api.business.harness.llm.LlmChatTool;
import com.xxl.ai.api.business.harness.llm.LlmModelFactory;
import com.xxl.ai.api.business.harness.rag.RagTool;
import com.xxl.ai.api.business.knowledge.mapper.KnowledgeBaseMapper;
import com.xxl.ai.api.business.knowledge.model.entity.KnowledgeBase;
import com.xxl.ai.api.business.supplier.mapper.SupplierMapper;
import com.xxl.ai.api.business.supplier.mapper.SupplierModelMapper;
import com.xxl.ai.api.business.supplier.model.SupplierRuntime;
import com.xxl.ai.api.business.supplier.model.entity.Supplier;
import com.xxl.ai.api.business.supplier.model.entity.SupplierModel;
import com.xxl.ai.api.business.supplier.service.SupplierService;
import com.xxl.tool.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 评测模块（FEAT-014）多租户隔离、状态防覆盖与客观评测引擎单元测试
 *
 * @author xxl-ai 2026-11-12
 */
@ExtendWith(MockitoExtension.class)
public class EvalSecurityAndEngineTest {

    @Mock
    private EvalDatasetMapper evalDatasetMapper;
    @Mock
    private EvalExperimentMapper evalExperimentMapper;
    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Mock
    private AgentMapper agentMapper;
    @Mock
    private SupplierMapper supplierMapper;
    @Mock
    private SupplierModelMapper supplierModelMapper;
    @Mock
    private SupplierService supplierService;
    @Mock
    private EvalEngineTool evalEngineTool;
    @Mock
    private RagTool ragTool;
    @Mock
    private LlmChatTool llmChatTool;
    @Mock
    private LlmModelFactory llmModelFactory;

    @InjectMocks
    private EvalDatasetServiceImpl evalDatasetService;

    @InjectMocks
    private EvalExperimentServiceImpl evalExperimentService;

    @InjectMocks
    private EvalEngineTool realEvalEngineTool;

    @Test
    void testDatasetLoadAndTargetSpaceIsolation() {
        EvalDataset spaceBDataset = new EvalDataset();
        spaceBDataset.setId(10L);
        spaceBDataset.setSpaceId(2L);
        spaceBDataset.setName("空间B评测集");
        when(evalDatasetMapper.load(10L)).thenReturn(spaceBDataset);

        Response<EvalDatasetDTO> deniedLoad = evalDatasetService.load(1L, 10L);
        assertFalse(deniedLoad.isSuccess());
        assertEquals("评测数据集不存在或无权限访问", deniedLoad.getMsg());

        Response<EvalDatasetDTO> allowedLoad = evalDatasetService.load(2L, 10L);
        assertTrue(allowedLoad.isSuccess());
        assertEquals("空间B评测集", allowedLoad.getData().getName());

        // 跨空间挂载知识库拦截
        KnowledgeBase spaceBKb = new KnowledgeBase();
        spaceBKb.setId(20L);
        spaceBKb.setSpaceId(2L);
        spaceBKb.setName("空间B知识库");
        when(knowledgeBaseMapper.load(20L)).thenReturn(spaceBKb);

        EvalDatasetDTO crossKbDto = new EvalDatasetDTO();
        crossKbDto.setName("越权数据集");
        crossKbDto.setEvalType("RAG");
        crossKbDto.setTargetId(20L);
        Response<String> crossKbResp = evalDatasetService.insert(1L, crossKbDto);
        assertFalse(crossKbResp.isSuccess());
        assertEquals("目标知识库不存在或无权限访问", crossKbResp.getMsg());
        verify(evalDatasetMapper, never()).insert(any());

        // 跨空间挂载 Agent 拦截
        Agent spaceBAgent = new Agent();
        spaceBAgent.setId(30L);
        spaceBAgent.setSpaceId(2L);
        spaceBAgent.setName("空间B智能体");
        when(agentMapper.load(30L)).thenReturn(spaceBAgent);

        EvalDatasetDTO crossAgentDto = new EvalDatasetDTO();
        crossAgentDto.setName("越权Agent数据集");
        crossAgentDto.setEvalType("AGENT");
        crossAgentDto.setTargetId(30L);
        Response<String> crossAgentResp = evalDatasetService.insert(1L, crossAgentDto);
        assertFalse(crossAgentResp.isSuccess());
        assertEquals("目标 Agent 不存在或无权限访问", crossAgentResp.getMsg());
        verify(evalDatasetMapper, never()).insert(any());

        // 同空间合法知识库挂载
        KnowledgeBase spaceAKb = new KnowledgeBase();
        spaceAKb.setId(21L);
        spaceAKb.setSpaceId(1L);
        spaceAKb.setName("空间A知识库");
        when(knowledgeBaseMapper.load(21L)).thenReturn(spaceAKb);
        when(evalDatasetMapper.insert(any())).thenReturn(1);

        EvalDatasetDTO validDto = new EvalDatasetDTO();
        validDto.setName("合法数据集");
        validDto.setEvalType("rag");
        validDto.setTargetId(21L);
        Response<String> okResp = evalDatasetService.insert(1L, validDto);
        assertTrue(okResp.isSuccess());
        assertEquals("RAG", validDto.getEvalType());
        assertEquals("空间A知识库", validDto.getTargetName());
    }

    @Test
    void testExperimentLoadAndCreateValidation() {
        EvalExperiment spaceBExp = new EvalExperiment();
        spaceBExp.setId(88L);
        spaceBExp.setSpaceId(2L);
        when(evalExperimentMapper.load(88L)).thenReturn(spaceBExp);

        Response<EvalExperimentDTO> deniedLoad = evalExperimentService.load(1L, 88L);
        assertFalse(deniedLoad.isSuccess());
        assertEquals("评测实验记录不存在或无权限访问", deniedLoad.getMsg());

        EvalDataset dataset = new EvalDataset();
        dataset.setId(5L);
        dataset.setSpaceId(1L);
        dataset.setName("标准测试集");
        dataset.setEvalType("RAG");
        dataset.setTargetId(21L);
        dataset.setSampleCount(1);
        dataset.setContentJson("[{\"id\":\"c1\",\"query\":\"hello\"}]");
        when(evalDatasetMapper.load(5L)).thenReturn(dataset);

        KnowledgeBase spaceAKb = new KnowledgeBase();
        spaceAKb.setId(21L);
        spaceAKb.setSpaceId(1L);
        spaceAKb.setName("空间A知识库");
        when(knowledgeBaseMapper.load(21L)).thenReturn(spaceAKb);

        // 1、拦截跨空间 judgeModelId
        SupplierModel otherSpaceModel = new SupplierModel();
        otherSpaceModel.setId(999L);
        otherSpaceModel.setSupplierId(77L);
        otherSpaceModel.setType(0);
        when(supplierModelMapper.load(999L)).thenReturn(otherSpaceModel);
        when(supplierService.loadRuntime(1L, 77L, 999L)).thenReturn(Response.ofFail("供应商不存在或不属于当前空间"));

        EvalExperimentDTO crossJudgeDto = new EvalExperimentDTO();
        crossJudgeDto.setDatasetId(5L);
        crossJudgeDto.setJudgeModelId(999L);
        Response<Long> crossJudgeResp = evalExperimentService.createAndRun(1L, crossJudgeDto);
        assertFalse(crossJudgeResp.isSuccess());
        assertTrue(crossJudgeResp.getMsg().contains("裁判模型不可用"));
        verify(evalExperimentMapper, never()).insert(any());

        // 2、拦截嵌入模型作为裁判模型
        SupplierModel embedModel = new SupplierModel();
        embedModel.setId(998L);
        embedModel.setSupplierId(78L);
        embedModel.setType(1);
        when(supplierModelMapper.load(998L)).thenReturn(embedModel);

        EvalExperimentDTO embedJudgeDto = new EvalExperimentDTO();
        embedJudgeDto.setDatasetId(5L);
        embedJudgeDto.setJudgeModelId(998L);
        Response<Long> embedResp = evalExperimentService.createAndRun(1L, embedJudgeDto);
        assertFalse(embedResp.isSuccess());
        assertEquals("裁判模型必须为对话模型", embedResp.getMsg());

        // 3、未指定裁判模型且当前空间无任何可用对话模型时快速失败
        when(supplierMapper.listBySpace(1L)).thenReturn(Collections.emptyList());
        EvalExperimentDTO noJudgeDto = new EvalExperimentDTO();
        noJudgeDto.setDatasetId(5L);
        Response<Long> noJudgeResp = evalExperimentService.createAndRun(1L, noJudgeDto);
        assertFalse(noJudgeResp.isSuccess());
        assertTrue(noJudgeResp.getMsg().contains("暂无可用的对话大模型"));
    }

    @Test
    void testExperimentCancelFailureTruncationAndCompletionLifecycle() {
        // 1、cancel 仅在 status == 1 时调用专用 updateCancel
        EvalExperiment doneExp = new EvalExperiment();
        doneExp.setId(50L);
        doneExp.setSpaceId(1L);
        doneExp.setStatus(2);
        when(evalExperimentMapper.load(50L)).thenReturn(doneExp);
        assertFalse(evalExperimentService.cancel(1L, 50L).isSuccess());
        verify(evalExperimentMapper, never()).updateCancel(anyLong(), anyString());

        EvalExperiment runningExp = new EvalExperiment();
        runningExp.setId(51L);
        runningExp.setSpaceId(1L);
        runningExp.setStatus(1);
        when(evalExperimentMapper.load(51L)).thenReturn(runningExp);
        when(evalExperimentMapper.updateCancel(51L, "用户主动取消实验")).thenReturn(1);
        assertTrue(evalExperimentService.cancel(1L, 51L).isSuccess());
        verify(evalExperimentMapper, never()).updateProgress(any());

        // 2、异常时 errorMsg 严格截断至 <= 500 字符并调用 updateFailure
        EvalDataset dataset = new EvalDataset();
        dataset.setId(6L);
        dataset.setSpaceId(1L);
        dataset.setName("长异常测试集");
        dataset.setEvalType("RAG");
        dataset.setTargetId(21L);
        dataset.setSampleCount(1);
        dataset.setContentJson("[{\"id\":\"c1\",\"query\":\"hello\"}]");
        when(evalDatasetMapper.load(6L)).thenReturn(dataset);

        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(21L);
        kb.setSpaceId(1L);
        kb.setName("KB");
        kb.setSimilarityThreshold(0.4);
        kb.setTopK(3);
        when(knowledgeBaseMapper.load(21L)).thenReturn(kb);

        Supplier supplier = new Supplier();
        supplier.setId(11L);
        supplier.setSpaceId(1L);
        supplier.setStatus(0);
        SupplierModel model = new SupplierModel();
        model.setId(101L);
        model.setSupplierId(11L);
        model.setType(0);
        model.setStatus(0);
        SupplierRuntime runtime = new SupplierRuntime();
        runtime.setSupplierId(11L);
        runtime.setModelId(101L);
        runtime.setModelName("gpt-test");
        when(supplierMapper.listBySpace(1L)).thenReturn(List.of(supplier));
        when(supplierModelMapper.listBySupplier(11L)).thenReturn(List.of(model));
        when(supplierModelMapper.load(101L)).thenReturn(model);
        when(supplierService.loadRuntime(1L, 11L, 101L)).thenReturn(Response.ofSuccess(runtime));

        doAnswer(invocation -> {
            EvalExperiment arg = invocation.getArgument(0);
            arg.setId(200L);
            return 1;
        }).when(evalExperimentMapper).insert(any());

        EvalExperiment running200 = new EvalExperiment();
        running200.setId(200L);
        running200.setSpaceId(1L);
        running200.setStatus(1);
        when(evalExperimentMapper.load(200L)).thenReturn(running200);

        String longMsg = "X".repeat(600);
        when(evalEngineTool.runRagCase(eq(1L), anyList(), any(), eq(runtime), eq(runtime)))
                .thenThrow(new RuntimeException(longMsg));

        EvalExperimentDTO dto = new EvalExperimentDTO();
        dto.setDatasetId(6L);
        Response<Long> runResp = evalExperimentService.createAndRun(1L, dto);
        assertTrue(runResp.isSuccess());
        assertEquals(200L, runResp.getData());

        ArgumentCaptor<String> errCaptor = ArgumentCaptor.forClass(String.class);
        verify(evalExperimentMapper, times(1)).updateFailure(eq(200L), errCaptor.capture());
        assertNotNull(errCaptor.getValue());
        assertTrue(errCaptor.getValue().length() <= 500);
        assertTrue(errCaptor.getValue().endsWith("..."));
        verify(evalExperimentMapper, never()).updateFinish(any());
    }

    @Test
    void testEvalEngineZeroScoresWhenMissingAnswerOrJudgeAndRealTokenAggregation() {
        SupplierRuntime judgeRuntime = new SupplierRuntime();
        judgeRuntime.setModelName("judge-model");

        // 1、回答为空或无裁判模型时客观返回 0.0 分（严禁虚高 1.0）
        assertEquals(0.0, realEvalEngineTool.judgeFaithfulness("q", "ctx", "", judgeRuntime).getScore(), 0.0001);
        assertEquals(0.0, realEvalEngineTool.judgeAnswerRelevance("q", "   ", judgeRuntime).getScore(), 0.0001);
        assertEquals(0.0, realEvalEngineTool.judgeGoalCompletion("q", "answer", null).getScore(), 0.0001);

        // 2、RAG/Agent 在无模型时返回 0.0 分且 tokens=0
        EvalEngineTool.EvalCase evalCase = new EvalEngineTool.EvalCase();
        evalCase.setId("case-1");
        evalCase.setQuery("什么是 XXL-AI?");

        when(ragTool.retrieveForChat(eq(1L), anyList(), eq("什么是 XXL-AI?"), anyList(), any()))
                .thenReturn(Collections.emptyList());

        EvalEngineTool.CaseResult emptyRagRes = realEvalEngineTool.runRagCase(1L, List.of(1L), evalCase, null, judgeRuntime);
        assertEquals(0.0, emptyRagRes.getFaithfulnessScore(), 0.0001);
        assertEquals(0.0, emptyRagRes.getRelevanceScore(), 0.0001);
        assertEquals(0, emptyRagRes.getTokens());

        EvalEngineTool.CaseResult emptyAgentRes = realEvalEngineTool.runAgentCase(1L, new Agent(), evalCase, null, judgeRuntime);
        assertEquals(0.0, emptyAgentRes.getGoalCompletionScore(), 0.0001);
        assertEquals(0.0, emptyAgentRes.getRelevanceScore(), 0.0001);
        assertEquals(0, emptyAgentRes.getTokens());

        // 3、真实流式响应接入 TraceCollector 累加生成 + 裁判 Token
        when(llmChatTool.chat(any(), anyString(), anyList(), anyList(), anyString(), any(), anyList(),
                anyString(), any(), any(), any(TraceCollector.class), any()))
                .thenAnswer(invocation -> {
                    TraceCollector tc = invocation.getArgument(10);
                    String sid = invocation.getArgument(7);
                    if (sid.startsWith("eval-rag-")) {
                        tc.addTokenUsage(20, 30, 50);
                        return new LlmChatTool.ChatText("XXL-AI 是企业级 AI 应用开发平台。", "");
                    }
                    tc.addTokenUsage(40, 10, 50);
                    return new LlmChatTool.ChatText("{\"score\": 0.9, \"reason\": \"准确\"}", "");
                });

        EvalEngineTool.CaseResult fullRagRes = realEvalEngineTool.runRagCase(1L, List.of(1L), evalCase, judgeRuntime, judgeRuntime);
        assertEquals(0.9, fullRagRes.getFaithfulnessScore(), 0.0001);
        assertEquals(0.9, fullRagRes.getRelevanceScore(), 0.0001);
        // 生成(50) + 忠实度裁判(50) + 相关性裁判(50) = 150
        assertEquals(150, fullRagRes.getTokens());
    }
}
