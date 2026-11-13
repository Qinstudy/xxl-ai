package com.xxl.ai.api.business.harness.workflow;

import com.xxl.ai.api.business.harness.workflow.model.WorkflowExecutionTrace;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工作流执行上下文容器（贯穿整个 DAG 执行生命周期）
 *
 * @author xuxueli 2026-11-12
 */
public class WorkflowContext implements Serializable {
    private static final long serialVersionUID = 1L;

    /* 当前空间ID */
    private Long spaceId;

    /* 关联工作流ID */
    private Long workflowId;

    /* 执行实例ID（若已持久化） */
    private Long executionId;

    /* 运行时提供者（用于获取模型、知识库或执行工具） */
    private transient WorkflowRuntimeProvider runtimeProvider;

    /* 启动毫秒时间戳 */
    private long startTimeMs = System.currentTimeMillis();

    /* 总耗时 */
    private int durationMs;

    /* 全局外部输入入参字典 */
    private Map<String, Object> inputs = new LinkedHashMap<>();

    /* 各节点输出结果快照：nodeId -> { propertyKey: value } */
    private Map<String, Map<String, Object>> nodeOutputs = new ConcurrentHashMap<>();

    /* 条件分支路由结果：nodeId -> selectedTargetHandle */
    private Map<String, String> branchSelections = new ConcurrentHashMap<>();

    /* 逐节点执行链路快照 */
    private List<WorkflowExecutionTrace> traces = Collections.synchronizedList(new ArrayList<>());

    /* 累计消耗 Token 数 */
    private AtomicInteger totalTokens = new AtomicInteger(0);

    /* 工作流最终输出字典 */
    private Map<String, Object> finalOutput = new LinkedHashMap<>();

    /* 是否已被标记终止 */
    private volatile boolean terminated = false;

    /* 是否允许节点使用 Mock 配置兜底（仅 DEBUG 调试或单元测试允许，且优先执行真实绑定的资源） */
    private boolean allowMock = true;

    public WorkflowContext() {
    }

    public WorkflowContext(Long spaceId, Long workflowId, Map<String, Object> inputs) {
        this.spaceId = spaceId;
        this.workflowId = workflowId;
        if (inputs != null) {
            this.inputs.putAll(inputs);
        }
    }

    /**
     * 读取指定节点产出的属性值（支持顶层键与 a.b.0.c 嵌套点路径）
     */
    public Object getNodePropertyValue(String nodeId, String propertyPath) {
        if (nodeId == null || propertyPath == null) {
            return null;
        }
        // 支持全局 start / inputs 别名
        if ("start".equalsIgnoreCase(nodeId) || "inputs".equalsIgnoreCase(nodeId)) {
            Object val = resolvePropertyPath(inputs, propertyPath);
            if (val != null) {
                return val;
            }
        }

        Map<String, Object> output = nodeOutputs.get(nodeId);
        return resolvePropertyPath(output, propertyPath);
    }

    /**
     * 沿点分路径遍历 Map 与 List（纯结构化取值，无脚本执行风险）
     */
    private Object resolvePropertyPath(Map<String, Object> rootMap, String propertyPath) {
        if (rootMap == null || propertyPath == null || propertyPath.isEmpty()) {
            return null;
        }
        if (rootMap.containsKey(propertyPath)) {
            return rootMap.get(propertyPath);
        }
        if (!propertyPath.contains(".")) {
            return null;
        }

        String[] segments = propertyPath.split("\\.", -1);
        Object current = rootMap;
        for (String segment : segments) {
            if (current == null || segment.isEmpty()) {
                return null;
            }
            if (current instanceof Map<?, ?> subMap) {
                current = subMap.get(segment);
            } else if (current instanceof List<?> subList) {
                try {
                    int idx = Integer.parseInt(segment);
                    if (idx < 0 || idx >= subList.size()) {
                        return null;
                    }
                    current = subList.get(idx);
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return current;
    }

    /**
     * 写入某节点的执行结果
     */
    public void setNodeOutput(String nodeId, Map<String, Object> output) {
        if (nodeId != null && output != null) {
            nodeOutputs.put(nodeId, new LinkedHashMap<>(output));
        }
    }

    public Map<String, Object> getNodeOutput(String nodeId) {
        return nodeOutputs.get(nodeId);
    }

    /**
     * 记录条件分支选择
     */
    public void recordBranchSelection(String nodeId, String targetHandle) {
        if (nodeId != null && targetHandle != null) {
            branchSelections.put(nodeId, targetHandle);
        }
    }

    public String getBranchSelection(String nodeId) {
        return branchSelections.get(nodeId);
    }

    /**
     * 累加 Token 计数
     */
    public void addTokens(int tokens) {
        if (tokens > 0) {
            totalTokens.addAndGet(tokens);
        }
    }

    /**
     * 追加节点追踪快照
     */
    public void addTrace(WorkflowExecutionTrace trace) {
        if (trace != null) {
            traces.add(trace);
        }
    }

    public Long getSpaceId() {
        return spaceId;
    }

    public void setSpaceId(Long spaceId) {
        this.spaceId = spaceId;
    }

    public Long getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(Long workflowId) {
        this.workflowId = workflowId;
    }

    public Long getExecutionId() {
        return executionId;
    }

    public void setExecutionId(Long executionId) {
        this.executionId = executionId;
    }

    public long getStartTimeMs() {
        return startTimeMs;
    }

    public void setStartTimeMs(long startTimeMs) {
        this.startTimeMs = startTimeMs;
    }

    public int getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(int durationMs) {
        this.durationMs = durationMs;
    }

    public Map<String, Object> getInputs() {
        return inputs;
    }

    public void setInputs(Map<String, Object> inputs) {
        this.inputs = inputs;
    }

    public Map<String, Map<String, Object>> getNodeOutputs() {
        return nodeOutputs;
    }

    public void setNodeOutputs(Map<String, Map<String, Object>> nodeOutputs) {
        this.nodeOutputs = nodeOutputs;
    }

    public List<WorkflowExecutionTrace> getTraces() {
        return traces;
    }

    public void setTraces(List<WorkflowExecutionTrace> traces) {
        this.traces = traces;
    }

    public int getTotalTokens() {
        return totalTokens.get();
    }

    public void setTotalTokens(int tokens) {
        this.totalTokens.set(tokens);
    }

    public Map<String, Object> getFinalOutput() {
        return finalOutput;
    }

    public void setFinalOutput(Map<String, Object> finalOutput) {
        this.finalOutput = finalOutput;
    }

    public boolean isTerminated() {
        return terminated;
    }

    public void setTerminated(boolean terminated) {
        this.terminated = terminated;
    }

    public WorkflowRuntimeProvider getRuntimeProvider() {
        return runtimeProvider;
    }

    public void setRuntimeProvider(WorkflowRuntimeProvider runtimeProvider) {
        this.runtimeProvider = runtimeProvider;
    }

    public boolean isAllowMock() {
        return allowMock;
    }

    public void setAllowMock(boolean allowMock) {
        this.allowMock = allowMock;
    }
}
