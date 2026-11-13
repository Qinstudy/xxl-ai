package com.xxl.ai.api.business.workflow;

import com.xxl.ai.api.business.harness.mcp.McpClientTool;
import com.xxl.ai.api.business.harness.workflow.WorkflowContext;
import com.xxl.ai.api.business.harness.workflow.WorkflowDagEngine;
import com.xxl.ai.api.business.harness.workflow.model.WorkflowGraph;
import com.xxl.ai.api.business.knowledge.mapper.KnowledgeBaseMapper;
import com.xxl.ai.api.business.mcp.service.McpService;
import com.xxl.ai.api.business.supplier.service.SupplierService;
import com.xxl.ai.api.business.workflow.mapper.WorkflowExecutionMapper;
import com.xxl.ai.api.business.workflow.mapper.WorkflowMapper;
import com.xxl.ai.api.business.workflow.model.dto.WorkflowDTO;
import com.xxl.ai.api.business.workflow.model.dto.WorkflowExecutionDTO;
import com.xxl.ai.api.business.workflow.model.entity.Workflow;
import com.xxl.ai.api.business.workflow.model.entity.WorkflowExecution;
import com.xxl.ai.api.business.workflow.service.impl.WorkflowServiceImpl;
import com.xxl.tool.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 工作流服务（FEAT-009）空间隔离、参数校验与调试执行失败链路回传单元测试
 *
 * @author xxl-ai 2026-11-12
 */
@ExtendWith(MockitoExtension.class)
public class WorkflowServiceTest {

    private static final String VALID_GRAPH_JSON = """
            {
              "nodes": [
                {"id": "start_1", "name": "开始", "type": "START", "config": {}},
                {"id": "end_1", "name": "结束", "type": "END", "config": {}}
              ],
              "edges": [
                {"id": "e1", "source": "start_1", "target": "end_1"}
              ]
            }
            """;

    @Mock
    private WorkflowMapper workflowMapper;
    @Mock
    private WorkflowExecutionMapper workflowExecutionMapper;
    @Mock
    private WorkflowDagEngine workflowDagEngine;
    @Mock
    private SupplierService supplierService;
    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Mock
    private McpService mcpService;
    @Mock
    private McpClientTool mcpClientTool;

    @InjectMocks
    private WorkflowServiceImpl workflowService;

    @Test
    void testLoadAndRunSpaceIsolationAndValidation() {
        Workflow spaceBWf = new Workflow();
        spaceBWf.setId(10L);
        spaceBWf.setSpaceId(2L);
        spaceBWf.setName("空间B工作流");
        spaceBWf.setStatus(1);
        spaceBWf.setGraphJson(VALID_GRAPH_JSON);
        when(workflowMapper.load(10L)).thenReturn(spaceBWf);

        // 1、跨空间读取与执行阻断
        Response<WorkflowDTO> deniedLoad = workflowService.load(1L, 10L);
        assertFalse(deniedLoad.isSuccess());
        Response<WorkflowExecutionDTO> deniedRun = workflowService.run(1L, 10L, Collections.emptyMap());
        assertFalse(deniedRun.isSuccess());

        // 同空间读取成功（且无时间戳时 adapt2dto 不触发 NPE）
        Response<WorkflowDTO> allowedLoad = workflowService.load(2L, 10L);
        assertTrue(allowedLoad.isSuccess());
        assertEquals(2, allowedLoad.getData().getNodeCount());

        // 2、已停用工作流禁止试跑
        Workflow disabledWf = new Workflow();
        disabledWf.setId(11L);
        disabledWf.setSpaceId(1L);
        disabledWf.setStatus(2);
        disabledWf.setGraphJson(VALID_GRAPH_JSON);
        when(workflowMapper.load(11L)).thenReturn(disabledWf);
        Response<WorkflowExecutionDTO> disabledRun = workflowService.run(1L, 11L, Collections.emptyMap());
        assertFalse(disabledRun.isSuccess());
        assertTrue(disabledRun.getMsg().contains("停用"));

        // 3、非法状态与超长入参阻断
        WorkflowDTO badStatusDto = new WorkflowDTO();
        badStatusDto.setName("非法状态");
        badStatusDto.setStatus(99);
        badStatusDto.setGraphJson(VALID_GRAPH_JSON);
        assertFalse(workflowService.insert(1L, badStatusDto).isSuccess());
        assertFalse(workflowService.updateStatus(1L, 10L, 99).isSuccess());

        Workflow activeWf = new Workflow();
        activeWf.setId(12L);
        activeWf.setSpaceId(1L);
        activeWf.setStatus(0);
        activeWf.setGraphJson(VALID_GRAPH_JSON);
        when(workflowMapper.load(12L)).thenReturn(activeWf);

        String hugeVal = "A".repeat(70000);
        Response<WorkflowExecutionDTO> hugeRun = workflowService.run(1L, 12L, Map.of("query", hugeVal));
        assertFalse(hugeRun.isSuccess());
        verify(workflowExecutionMapper, never()).insert(any());
    }

    @Test
    void testRunFailureReturnsSuccessWrapperWithPersistedFailedTraceAndTruncatedError() throws Exception {
        Workflow wf = new Workflow();
        wf.setId(20L);
        wf.setSpaceId(1L);
        wf.setName("故障工作流");
        wf.setStatus(0);
        wf.setGraphJson(VALID_GRAPH_JSON);
        when(workflowMapper.load(20L)).thenReturn(wf);

        doAnswer(invocation -> {
            WorkflowExecution exec = invocation.getArgument(0);
            exec.setId(301L);
            return 1;
        }).when(workflowExecutionMapper).insert(any());

        String longErr = "ERR-".repeat(200);
        doAnswer(invocation -> {
            WorkflowContext ctx = invocation.getArgument(2);
            ctx.addTokens(45);
            ctx.setDurationMs(120);
            throw new RuntimeException(longErr);
        }).when(workflowDagEngine).execute(any(WorkflowGraph.class), any(), any(WorkflowContext.class));

        // 失败时外层必须返回 code=200，由 dto.status=2 与 errorMsg 传递详情给前端抽屉
        Response<WorkflowExecutionDTO> resp = workflowService.run(1L, 20L, Map.of("query", "hello"));
        assertTrue(resp.isSuccess());
        assertNotNull(resp.getData());
        assertEquals(301L, resp.getData().getId());
        assertEquals(2, resp.getData().getStatus());
        assertEquals(45, resp.getData().getTotalTokens());
        assertEquals(120, resp.getData().getDurationMs());
        assertNotNull(resp.getData().getErrorMsg());
        assertEquals(500, resp.getData().getErrorMsg().length());

        ArgumentCaptor<WorkflowExecution> execCaptor = ArgumentCaptor.forClass(WorkflowExecution.class);
        verify(workflowExecutionMapper, times(1)).updateFinish(execCaptor.capture());
        assertEquals(2, execCaptor.getValue().getStatus());
    }

    @Test
    void testRunTimeoutMarksStatusTimeoutAndPreservesDto() throws Exception {
        Workflow wf = new Workflow();
        wf.setId(21L);
        wf.setSpaceId(1L);
        wf.setName("超时工作流");
        wf.setStatus(1);
        wf.setGraphJson(VALID_GRAPH_JSON);
        when(workflowMapper.load(21L)).thenReturn(wf);

        doAnswer(invocation -> {
            WorkflowExecution exec = invocation.getArgument(0);
            exec.setId(302L);
            return 1;
        }).when(workflowExecutionMapper).insert(any());

        doThrow(new TimeoutException()).when(workflowDagEngine)
                .execute(any(WorkflowGraph.class), any(), any(WorkflowContext.class));

        Response<WorkflowExecutionDTO> resp = workflowService.run(1L, 21L, Collections.emptyMap());
        assertTrue(resp.isSuccess());
        assertEquals(3, resp.getData().getStatus());
        assertEquals("TimeoutException", resp.getData().getErrorMsg());
    }
}
