package com.xxl.ai.api.business.harness.chat;

import com.google.gson.Gson;
import com.xxl.ai.api.business.agent.mapper.AgentMapper;
import com.xxl.ai.api.business.agent.model.entity.Agent;
import com.xxl.ai.api.business.harness.llm.LlmChatTool;
import com.xxl.ai.api.business.harness.mcp.McpToolFactory;
import com.xxl.ai.api.business.harness.rag.RagTool;
import com.xxl.ai.api.business.supplier.model.SupplierRuntime;
import com.xxl.ai.api.business.supplier.service.SupplierService;
import com.xxl.tool.core.CollectionTool;
import com.xxl.tool.core.StringTool;
import com.xxl.tool.response.Response;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;

/**
 * SubAgent 工具工厂（harness，Agent-as-a-Tool 模式）
 *
 * 核心设计：
 *  1、将主 Agent 关联的子智能体包装为 Spring AI {@link ToolCallback}（命名如 {@code subagent_{id}}）；
 *  2、大模型根据任务意图自主决策是否委派给子智能体，并在拿到子智能体执行结果后汇总回复；
 *  3、单层委派原则（Depth=1）：子智能体运行时不再挂载其自身关联的 SubAgent，无需防环逻辑；
 *  4、沙箱隔离：子智能体暂不开放宿主机 Shell/终端/文件工具，仅支持其自身绑定的 MCP 工具与知识库 RAG；
 *  5、全新上下文：子任务独立调用，无历史会话、无记忆注入、不持久化会话消息，主线程中断可级联取消；
 *  6、可观测整合：Trace 日志与 Token 消耗自动汇聚进父级 {@link TraceCollector}。
 *
 * @author xxl-ai 2026-11-10
 */
@Component
public class SubAgentTool {

    private static final Logger logger = LoggerFactory.getLogger(SubAgentTool.class);
    private static final Gson GSON = new Gson();

    /** 工具入参 JSON Schema */
    private static final String INPUT_SCHEMA = "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\",\"properties\":{\"task\":{\"type\":\"string\",\"description\":\"委派给子智能体的具体任务描述、需求或需要分析解答的问题\"}},\"required\":[\"task\"]}";

    @Resource
    private AgentMapper agentMapper;
    @Resource
    private SupplierService supplierService;
    @Resource
    private McpToolFactory mcpToolFactory;
    @Resource
    private RagTool ragTool;
    @Resource
    private LlmChatTool llmChatTool;

    /**
     * 为主 Agent 构建已挂载的 SubAgent 工具列表（可空集合）
     *
     * @param parentAgent    主 Agent
     * @param traceCollector 主会话全链路追踪器
     * @return 工具回调列表（内部工具类型由外层装饰器打标为 "SUBAGENT"）
     */
    public List<Object> buildTools(Agent parentAgent, TraceCollector traceCollector) {
        if (parentAgent == null || StringTool.isBlank(parentAgent.getSubAgentIds())) {
            return Collections.emptyList();
        }

        List<Long> subAgentIdList = splitIds(parentAgent.getSubAgentIds());
        if (CollectionTool.isEmpty(subAgentIdList)) {
            return Collections.emptyList();
        }

        List<Object> tools = new ArrayList<>();
        for (Long subAgentId : subAgentIdList) {
            if (subAgentId == null || subAgentId <= 0) {
                continue;
            }
            // 防自身循环引用
            if (parentAgent.getId() > 0 && subAgentId.equals(parentAgent.getId())) {
                continue;
            }
            Agent subAgent = agentMapper.load(subAgentId);
            if (subAgent == null) {
                logger.warn("SubAgent 不存在或已被删除，跳过挂载, parentAgentId={}, subAgentId={}",
                        parentAgent.getId(), subAgentId);
                continue;
            }
            if (subAgent.getPublishStatus() != 1) {
                logger.warn("SubAgent 处于未发布状态，跳过挂载, parentAgentId={}, subAgentId={}, name={}",
                        parentAgent.getId(), subAgentId, subAgent.getName());
                continue;
            }
            if (subAgent.getSpaceId() != parentAgent.getSpaceId()) {
                logger.warn("SubAgent 不属于当前工作空间，拒绝挂载, parentAgentId={}, subAgentId={}",
                        parentAgent.getId(), subAgentId);
                continue;
            }
            tools.add(buildSubAgentCallback(parentAgent, subAgent, traceCollector));
        }
        return tools;
    }

    /**
     * 构建单个 SubAgent 的 ToolCallback
     */
    private ToolCallback buildSubAgentCallback(Agent parentAgent, Agent subAgent, TraceCollector traceCollector) {
        String toolName = "subagent_" + subAgent.getId();
        String intro = StringTool.isNotBlank(subAgent.getIntro()) ? subAgent.getIntro().trim() : "专项子智能体";
        String description = subAgent.getName() + "：" + intro + "。用于将专项任务委派给该子智能体处理。";

        Function<Map<String, Object>, String> function = arguments -> {
            String task = extractTask(arguments);
            return executeSubAgent(parentAgent, subAgent.getId(), task, traceCollector);
        };

        return FunctionToolCallback.builder(toolName, function)
                .description(description)
                .inputType(Map.class)
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    /**
     * 同步执行子智能体任务（运行于主会话 Worker 线程，全新上下文）
     */
    private String executeSubAgent(Agent parentAgent, long subAgentId, String task, TraceCollector traceCollector) {
        if (StringTool.isBlank(task)) {
            return "[SubAgent 委派失败] 委派给子智能体的任务描述不能为空。";
        }

        // 1、加载子智能体元数据（运行时校验存在性与发布状态）
        Agent subAgent = agentMapper.load(subAgentId);
        if (subAgent == null) {
            return "[SubAgent 委派失败] 子智能体 (ID=" + subAgentId + ") 不存在或已被删除。";
        }
        if (subAgent.getPublishStatus() != 1) {
            return "[SubAgent 委派失败] 子智能体 [" + subAgent.getName() + "] 当前未发布，暂不可调用。";
        }
        if (subAgent.getSpaceId() != parentAgent.getSpaceId()) {
            return "[SubAgent 委派失败] 越权访问：子智能体 [" + subAgent.getName() + "] 不属于当前工作空间。";
        }

        // 2、加载子智能体模型运行时
        Response<SupplierRuntime> runtimeResp = supplierService.loadRuntime(subAgent.getSpaceId(),
                subAgent.getModelSupplierId(), subAgent.getModelId());
        if (!runtimeResp.isSuccess() || runtimeResp.getData() == null) {
            return "[SubAgent 委派失败] 无法加载子智能体 [" + subAgent.getName() + "] 的对话模型：" + runtimeResp.getMsg();
        }
        SupplierRuntime runtime = runtimeResp.getData();
        if (runtime.getModelType() != 0) {
            return "[SubAgent 委派失败] 子智能体 [" + subAgent.getName() + "] 绑定的模型不是对话模型。";
        }

        // 3、装配子智能体专属能力（一层委派：仅装配 MCP 工具与知识库 RAG，不级联装配 SubAgent，不开放 Skill/EXEC 沙箱）
        List<Object> subTools = mcpToolFactory.buildTools(subAgent);

        List<TraceCollector.RagReference> references = Collections.emptyList();
        List<Long> kbIds = splitIds(subAgent.getKbIds());
        if (!kbIds.isEmpty()) {
            List<String> degradeNotices = new ArrayList<>();
            references = ragTool.retrieveForChat(subAgent.getSpaceId(), kbIds, task, degradeNotices, traceCollector);
        }
        String contextualQuestion = ragTool.buildContextualQuestion(task, references);

        // 4、提取子智能体动态推理超参数（显式绑定子智能体模型代码，防覆盖）
        OpenAiChatOptions chatOptions = ChatStreamTool.buildChatOptions(subAgent, runtime.getModelName(), traceCollector);

        // 5、提取子智能体系统指令
        String systemPrompt = StringTool.isNotBlank(subAgent.getSystemPrompt()) ? subAgent.getSystemPrompt().trim() : "";

        if (traceCollector != null) {
            traceCollector.addLog("INFO", "subagent",
                    "向子智能体 [" + subAgent.getName() + "] 委派任务：" + (task.length() > 200 ? task.substring(0, 200) + "..." : task));
        }

        // 6、同步调用 LLM（空历史上下文，不落库会话/消息，不分发单字增量流；Token Usage 并入主 TraceCollector）
        try {
            LlmChatTool.ChatText chatText = llmChatTool.chat(
                    runtime,
                    systemPrompt,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    contextualQuestion,
                    subTools.toArray(new Object[0]),
                    Collections.emptyList(),
                    "subagent-run-" + subAgentId + "-" + System.currentTimeMillis(),
                    chatOptions,
                    null,
                    null,
                    traceCollector,
                    null
            );

            String reply = chatText != null && chatText.getContent() != null ? chatText.getContent().trim() : "";
            if (traceCollector != null) {
                traceCollector.addLog("INFO", "subagent",
                        "子智能体 [" + subAgent.getName() + "] 执行完成，产出 " + reply.length() + " 字符");
            }
            return reply;
        } catch (Exception e) {
            logger.warn("子智能体调用异常, parentAgentId={}, subAgentId={}, err={}",
                    parentAgent.getId(), subAgentId, e.getMessage());
            return "[SubAgent 委派异常] 子智能体 [" + subAgent.getName() + "] 处理失败：" + e.getMessage();
        }
    }

    /**
     * 安全提取 task 入参文本
     */
    @SuppressWarnings("unchecked")
    private String extractTask(Object arguments) {
        if (arguments == null) {
            return "";
        }
        if (arguments instanceof Map<?, ?> map) {
            Object task = map.get("task");
            return task != null ? String.valueOf(task).trim() : "";
        }
        if (arguments instanceof String str) {
            try {
                Map<String, Object> map = GSON.fromJson(str, Map.class);
                if (map != null && map.containsKey("task")) {
                    return String.valueOf(map.get("task")).trim();
                }
            } catch (Exception ignored) {
            }
            return str.trim();
        }
        return String.valueOf(arguments).trim();
    }

    /**
     * 逗号分隔字符串转 ID 集合
     */
    private List<Long> splitIds(String ids) {
        List<Long> list = new ArrayList<>();
        if (StringTool.isBlank(ids)) {
            return list;
        }
        for (String id : ids.split(",")) {
            if (StringTool.isNotBlank(id)) {
                try {
                    list.add(Long.parseLong(id.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return list;
    }

}
