package com.xxl.ai.api.business.harness.workflow.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作流 DAG 图结构模型
 *
 * @author xuxueli 2026-11-12
 */
public class WorkflowGraph implements Serializable {
    private static final long serialVersionUID = 1L;

    /* 图中全部节点集合 */
    private List<WorkflowNode> nodes = new ArrayList<>();

    /* 图中全部有向边集合 */
    private List<WorkflowEdge> edges = new ArrayList<>();

    public WorkflowGraph() {
    }

    public WorkflowGraph(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        this.nodes = nodes != null ? nodes : new ArrayList<>();
        this.edges = edges != null ? edges : new ArrayList<>();
    }

    public List<WorkflowNode> getNodes() {
        return nodes;
    }

    public void setNodes(List<WorkflowNode> nodes) {
        this.nodes = nodes;
    }

    public List<WorkflowEdge> getEdges() {
        return edges;
    }

    public void setEdges(List<WorkflowEdge> edges) {
        this.edges = edges;
    }
}
