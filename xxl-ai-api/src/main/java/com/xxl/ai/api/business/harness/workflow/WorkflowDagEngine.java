package com.xxl.ai.api.business.harness.workflow;

import com.xxl.ai.api.business.harness.workflow.model.WorkflowEdge;
import com.xxl.ai.api.business.harness.workflow.model.WorkflowExecutionTrace;
import com.xxl.ai.api.business.harness.workflow.model.WorkflowGraph;
import com.xxl.ai.api.business.harness.workflow.model.WorkflowNode;
import com.xxl.tool.core.CollectionTool;
import com.xxl.tool.core.StringTool;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeoutException;

/**
 * 工作流轻量 DAG 执行引擎（基于 Kahn 拓扑排序、分支条件动态流转与死循环熔断）
 *
 * @author xuxueli 2026-11-12
 */
@Component
public class WorkflowDagEngine {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowDagEngine.class);

    /** 单次工作流最大执行步数防线（防止动态分支循环导致的 API 账单雪崩） */
    public static final int MAX_STEPS = 50;

    /** 单次工作流全局执行超时时间（180 秒） */
    public static final long MAX_TIMEOUT_MS = 180_000L;

    /** 阶段一引擎支持的内置节点类型 */
    private static final Set<String> SUPPORTED_NODE_TYPES = Set.of(
            "START", "END", "LLM", "RETRIEVAL", "CONDITION", "TOOL"
    );

    @Resource
    private List<WorkflowNodeHandler> handlerList;

    private final Map<String, WorkflowNodeHandler> handlerMap = new HashMap<>();

    @PostConstruct
    public void init() {
        if (CollectionTool.isNotEmpty(handlerList)) {
            for (WorkflowNodeHandler handler : handlerList) {
                handlerMap.put(handler.getNodeType().toUpperCase(Locale.ROOT), handler);
            }
        }
    }

    /**
     * 注册或覆盖节点处理器（供单元测试注入 Mock）
     */
    public void registerHandler(WorkflowNodeHandler handler) {
        if (handler != null && StringTool.isNotBlank(handler.getNodeType())) {
            handlerMap.put(handler.getNodeType().toUpperCase(Locale.ROOT), handler);
        }
    }

    /**
     * 静态校验 DAG 图结构合法性（Kahn 算法检测有向环、开始/结束节点孤立性检查）
     */
    public void validateGraph(WorkflowGraph graph) {
        if (graph == null || CollectionTool.isEmpty(graph.getNodes())) {
            throw new IllegalArgumentException("工作流图定义不能为空且至少包含一个节点");
        }

        List<WorkflowNode> nodes = graph.getNodes();
        List<WorkflowEdge> edges = graph.getEdges() != null ? graph.getEdges() : Collections.emptyList();

        Map<String, WorkflowNode> nodeMap = new HashMap<>();
        int startCount = 0;
        int endCount = 0;
        String startNodeId = null;

        for (WorkflowNode node : nodes) {
            if (StringTool.isBlank(node.getId())) {
                throw new IllegalArgumentException("工作流存在 ID 为空的节点");
            }
            if (nodeMap.containsKey(node.getId())) {
                throw new IllegalArgumentException("工作流存在重复节点 ID: " + node.getId());
            }
            if (StringTool.isBlank(node.getType())) {
                throw new IllegalArgumentException("工作流节点 [" + node.getId() + "] 的类型不能为空");
            }

            String type = node.getType().trim().toUpperCase(Locale.ROOT);
            if (!SUPPORTED_NODE_TYPES.contains(type) && !handlerMap.containsKey(type)) {
                throw new IllegalArgumentException("工作流节点 [" + node.getId() + "] 存在不支持的类型: " + node.getType());
            }

            nodeMap.put(node.getId(), node);

            if ("START".equals(type)) {
                startCount++;
                startNodeId = node.getId();
            } else if ("END".equals(type)) {
                endCount++;
            }
        }

        if (startCount != 1) {
            throw new IllegalArgumentException("工作流必须且只能包含一个 START 节点，当前数量: " + startCount);
        }
        if (endCount < 1) {
            throw new IllegalArgumentException("工作流必须至少包含一个 END 节点，当前数量: " + endCount);
        }

        // 边合法性与入度统计
        Map<String, Integer> inDegreeMap = new HashMap<>();
        Map<String, List<String>> adjList = new HashMap<>();
        for (WorkflowNode node : nodes) {
            inDegreeMap.put(node.getId(), 0);
            adjList.put(node.getId(), new ArrayList<>());
        }

        for (WorkflowEdge edge : edges) {
            if (StringTool.isBlank(edge.getSource()) || !nodeMap.containsKey(edge.getSource())) {
                throw new IllegalArgumentException("边 " + edge.getId() + " 的起始节点不存在: " + edge.getSource());
            }
            if (StringTool.isBlank(edge.getTarget()) || !nodeMap.containsKey(edge.getTarget())) {
                throw new IllegalArgumentException("边 " + edge.getId() + " 的目标节点不存在: " + edge.getTarget());
            }
            if (edge.getSource().equals(edge.getTarget())) {
                throw new IllegalArgumentException("节点禁止直接自环连接: " + edge.getSource());
            }
            WorkflowNode sourceNode = nodeMap.get(edge.getSource());
            if (sourceNode != null && sourceNode.getType() != null
                    && "CONDITION".equals(sourceNode.getType().trim().toUpperCase(Locale.ROOT))
                    && StringTool.isBlank(edge.getSourceHandle())) {
                throw new IllegalArgumentException("条件节点 [" + sourceNode.getId() + "] 的出边 [" + edge.getId() + "] 必须指定分支标识 (sourceHandle)");
            }

            inDegreeMap.put(edge.getTarget(), inDegreeMap.get(edge.getTarget()) + 1);
            adjList.get(edge.getSource()).add(edge.getTarget());
        }

        // 孤立节点检测：除 START 节点外，所有节点入度必须 >= 1，避免合流节点死锁静默跳过
        for (Map.Entry<String, Integer> entry : inDegreeMap.entrySet()) {
            if (!entry.getKey().equals(startNodeId) && entry.getValue() == 0) {
                throw new IllegalArgumentException("工作流存在孤立节点（无前置连线），无法执行: " + entry.getKey());
            }
        }

        // Kahn 算法检测是否存在有向环
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegreeMap.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }

        int visitedCount = 0;
        while (!queue.isEmpty()) {
            String curr = queue.poll();
            visitedCount++;
            for (String neighbor : adjList.get(curr)) {
                int deg = inDegreeMap.get(neighbor) - 1;
                inDegreeMap.put(neighbor, deg);
                if (deg == 0) {
                    queue.offer(neighbor);
                }
            }
        }

        if (visitedCount < nodes.size()) {
            throw new IllegalArgumentException("工作流拓扑结构存在有向环路 (Cycle Detected)，无法执行！");
        }

        // 连通性校验：从 START 节点出发应可到达全部节点（拦截断链子图）
        Set<String> reachable = new HashSet<>();
        Queue<String> bfs = new LinkedList<>();
        bfs.offer(startNodeId);
        reachable.add(startNodeId);
        while (!bfs.isEmpty()) {
            String curr = bfs.poll();
            for (String neighbor : adjList.get(curr)) {
                if (reachable.add(neighbor)) {
                    bfs.offer(neighbor);
                }
            }
        }
        if (reachable.size() < nodes.size()) {
            throw new IllegalArgumentException("工作流存在从 START 节点不可达的孤立子图，请检查连线完整性");
        }
    }

    /**
     * 执行工作流图
     *
     * @param graph  工作流拓扑图
     * @param inputs 外部传入参数字典
     * @param ctx    执行上下文
     * @return 最终产出的执行上下文（含 finalOutput 与 traces）
     * @throws Exception 执行异常或超时/熔断
     */
    public WorkflowContext execute(WorkflowGraph graph, Map<String, Object> inputs, WorkflowContext ctx) throws Exception {
        validateGraph(graph);

        if (ctx == null) {
            ctx = new WorkflowContext();
        }
        if (inputs != null) {
            ctx.getInputs().putAll(inputs);
        }
        long startTime = System.currentTimeMillis();
        ctx.setStartTimeMs(startTime);

        Map<String, WorkflowNode> nodeMap = new LinkedHashMap<>();
        WorkflowNode startNode = null;
        for (WorkflowNode node : graph.getNodes()) {
            nodeMap.put(node.getId(), node);
            String nodeType = node.getType() != null ? node.getType().trim().toUpperCase(Locale.ROOT) : "";
            if ("START".equals(nodeType)) {
                startNode = node;
            }
        }

        // 构建邻接边关系
        List<WorkflowEdge> edges = graph.getEdges() != null ? graph.getEdges() : Collections.emptyList();
        Map<String, List<WorkflowEdge>> outgoingEdges = new HashMap<>();
        Map<String, List<WorkflowEdge>> incomingEdges = new HashMap<>();
        for (WorkflowNode node : graph.getNodes()) {
            outgoingEdges.put(node.getId(), new ArrayList<>());
            incomingEdges.put(node.getId(), new ArrayList<>());
        }
        for (WorkflowEdge edge : edges) {
            outgoingEdges.get(edge.getSource()).add(edge);
            incomingEdges.get(edge.getTarget()).add(edge);
        }

        // 状态跟踪
        Set<String> executedNodes = new HashSet<>();
        Set<String> skippedNodes = new HashSet<>();
        Map<String, Boolean> edgeActiveState = new HashMap<>(); // edgeId -> active/inactive

        Queue<String> readyQueue = new LinkedList<>();
        readyQueue.offer(startNode.getId());

        int stepCount = 0;

        while (!readyQueue.isEmpty()) {
            // 1、防死循环熔断检查
            stepCount++;
            if (stepCount > MAX_STEPS) {
                String msg = "工作流执行步数超过上限 (" + MAX_STEPS + ")，触发防死循环熔断保护！";
                logger.error(msg);
                ctx.setDurationMs((int) (System.currentTimeMillis() - startTime));
                throw new IllegalStateException(msg);
            }

            // 2、超时检查
            if (System.currentTimeMillis() - startTime > MAX_TIMEOUT_MS) {
                String msg = "工作流执行耗时超过上限 (" + (MAX_TIMEOUT_MS / 1000) + "s)，已超时中断！";
                logger.error(msg);
                ctx.setDurationMs((int) (System.currentTimeMillis() - startTime));
                throw new TimeoutException(msg);
            }

            String nodeId = readyQueue.poll();
            WorkflowNode node = nodeMap.get(nodeId);
            if (node == null || executedNodes.contains(nodeId) || skippedNodes.contains(nodeId)) {
                continue;
            }

            // 3、检查当前节点的入边激活状态
            List<WorkflowEdge> inEdges = incomingEdges.get(nodeId);
            boolean shouldSkip = false;
            if (CollectionTool.isNotEmpty(inEdges)) {
                boolean hasAnyActiveInEdge = false;
                for (WorkflowEdge inEdge : inEdges) {
                    Boolean active = edgeActiveState.get(inEdge.getId());
                    if (Boolean.TRUE.equals(active)) {
                        hasAnyActiveInEdge = true;
                        break;
                    }
                }
                // 若所有入边均非激活，说明当前分支已被条件节点跳过
                if (!hasAnyActiveInEdge) {
                    shouldSkip = true;
                }
            }

            // 4、分支跳过处理
            if (shouldSkip) {
                skippedNodes.add(nodeId);
                WorkflowExecutionTrace skipTrace = new WorkflowExecutionTrace(node.getId(), node.getName(), node.getType(), "SKIPPED");
                skipTrace.setDurationMs(0);
                skipTrace.setInputSnapshot(Collections.emptyMap());
                skipTrace.setOutputSnapshot(Collections.emptyMap());
                ctx.addTrace(skipTrace);

                // 下游连线全部标记为不激活
                for (WorkflowEdge outEdge : outgoingEdges.get(nodeId)) {
                    edgeActiveState.put(outEdge.getId(), false);
                    checkAndEnqueue(outEdge.getTarget(), incomingEdges, edgeActiveState, readyQueue, executedNodes, skippedNodes);
                }
                continue;
            }

            // 5、执行当前节点
            long nodeStart = System.currentTimeMillis();
            String normalizedType = node.getType() != null ? node.getType().trim().toUpperCase(Locale.ROOT) : "";
            WorkflowNodeHandler handler = handlerMap.get(normalizedType);
            if (handler == null) {
                throw new UnsupportedOperationException("未找到节点类型 [" + node.getType() + "] 对应的执行处理器");
            }

            WorkflowExecutionTrace trace = new WorkflowExecutionTrace(node.getId(), node.getName(), normalizedType, "RUNNING");
            trace.setInputSnapshot(node.getConfig() != null ? new LinkedHashMap<>(node.getConfig()) : Collections.emptyMap());

            try {
                handler.execute(node, ctx);
                int duration = (int) (System.currentTimeMillis() - nodeStart);
                trace.setStatus("SUCCESS");
                trace.setDurationMs(duration);
                Map<String, Object> nodeOutput = ctx.getNodeOutput(node.getId());
                trace.setOutputSnapshot(nodeOutput);
                if (nodeOutput != null && nodeOutput.get("tokens") instanceof Number numTokens) {
                    trace.setTokens(numTokens.intValue());
                }
                ctx.addTrace(trace);
                executedNodes.add(nodeId);

                // 6、激活下游边
                if ("CONDITION".equals(normalizedType)) {
                    String selectedHandle = ctx.getBranchSelection(node.getId());
                    for (WorkflowEdge outEdge : outgoingEdges.get(nodeId)) {
                        boolean match = StringTool.isNotBlank(selectedHandle)
                                && StringTool.isNotBlank(outEdge.getSourceHandle())
                                && outEdge.getSourceHandle().trim().equalsIgnoreCase(selectedHandle.trim());
                        edgeActiveState.put(outEdge.getId(), match);
                        checkAndEnqueue(outEdge.getTarget(), incomingEdges, edgeActiveState, readyQueue, executedNodes, skippedNodes);
                    }
                } else {
                    for (WorkflowEdge outEdge : outgoingEdges.get(nodeId)) {
                        edgeActiveState.put(outEdge.getId(), true);
                        checkAndEnqueue(outEdge.getTarget(), incomingEdges, edgeActiveState, readyQueue, executedNodes, skippedNodes);
                    }
                }

                // 7、若遇到 END 节点且无后续执行分支，可提前结束
                if ("END".equals(normalizedType) && ctx.isTerminated()) {
                    if (readyQueue.isEmpty()) {
                        break;
                    }
                }

            } catch (Exception e) {
                int duration = (int) (System.currentTimeMillis() - nodeStart);
                trace.setStatus("FAILED");
                trace.setDurationMs(duration);
                trace.setErrorMsg(e.getMessage());
                ctx.addTrace(trace);
                ctx.setDurationMs((int) (System.currentTimeMillis() - startTime));
                logger.error("节点 [{}] 执行失败: {}", node.getId(), e.getMessage(), e);
                throw e;
            }
        }

        ctx.setDurationMs((int) (System.currentTimeMillis() - startTime));
        if (!ctx.isTerminated()) {
            String msg = "工作流未到达任何 END 结束节点（请检查条件分支 targetHandle 与连线 sourceHandle 是否匹配）";
            logger.error(msg);
            throw new IllegalStateException(msg);
        }
        return ctx;
    }

    private void checkAndEnqueue(String targetNodeId,
                                 Map<String, List<WorkflowEdge>> incomingEdges,
                                 Map<String, Boolean> edgeActiveState,
                                 Queue<String> readyQueue,
                                 Set<String> executedNodes,
                                 Set<String> skippedNodes) {
        if (executedNodes.contains(targetNodeId) || skippedNodes.contains(targetNodeId) || readyQueue.contains(targetNodeId)) {
            return;
        }

        List<WorkflowEdge> inEdges = incomingEdges.get(targetNodeId);
        if (CollectionTool.isEmpty(inEdges)) {
            readyQueue.offer(targetNodeId);
            return;
        }

        // 只有所有入边的激活/非激活状态都已确立，下游节点才就绪
        boolean allInEdgesDecided = true;
        for (WorkflowEdge inEdge : inEdges) {
            if (!edgeActiveState.containsKey(inEdge.getId())) {
                allInEdgesDecided = false;
                break;
            }
        }

        if (allInEdgesDecided) {
            readyQueue.offer(targetNodeId);
        }
    }
}
