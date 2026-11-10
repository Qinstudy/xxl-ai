package com.xxl.ai.api.business.harness.chat;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
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
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * SubAgent 多智能体协同工具工厂（harness，自适应单门面 + 混合并行 + 路由劫持防重造）
 *
 * 核心演进：
 *  1、模式 A（纯预置专家 delegate_specialist）：支持单次下发批量任务，由专用线程池并行调度；
 *  2、模式 B（纯动态自裂变 decompose_and_spawn）：主 Agent 现场自适应将复杂难题拆解为 2~4 个瞬态专家角色并行执行并汇总；
 *  3、模式 C（复合双开混合协同 dispatch_subagents）：单一门面协议，统一支持静态预置专家与动态瞬态专家混编并行，且具备运行时静态专家优先路由劫持（Hijack & Redirect）；
 *  4、三道安全红线：
 *     ① 深度硬隔离（Depth=1）：子智能体装配时绝不挂载 SubAgentTool，根除循环委派与指数级裂变死锁；
 *     ② 单批次并发上限与超时熔断：单批次全局共享最多 4 个子任务（MAX_PARALLEL_SUBTASKS=4），单个子任务硬超时 60s；
 *     ③ 级联取消（Cascade Cancellation）：监听主会话 cancelled 状态，用户终止时立即停止子任务推进；
 *  5、全链路白盒化可观测：
 *     ① 子智能体 RAG 切片实时打标 [子智能体: 角色名] 透传主 TraceCollector 与 EVENT_REFERENCE 结果流；
 *     ② 子智能体内部 MCP 工具以 ObservableToolCallback 包装，实时推送 EVENT_TOOL 驱动前端胶囊；
 *     ③ 各子任务 Span 与 Token 消耗线程安全汇聚进主 Run 结果流与 ext_json。
 *
 * @author xxl-ai 2026-11-10
 */
@Component
public class SubAgentTool implements DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(SubAgentTool.class);
    private static final Gson GSON = new Gson();

    /** 单批次最大并发子任务数 */
    public static final int MAX_PARALLEL_SUBTASKS = 4;
    /** 单个子任务超时时间（秒） */
    public static final int SUBTASK_TIMEOUT_SECONDS = 60;

    /** 预置专家批量委派工具 Schema */
    private static final String DELEGATE_SPECIALIST_SCHEMA = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "type": "object",
              "properties": {
                "tasks": {
                  "type": "array",
                  "description": "委派给子智能体的任务列表（最多4个并行任务）",
                  "items": {
                    "type": "object",
                    "properties": {
                      "agentId": {
                        "type": "integer",
                        "description": "目标子智能体 ID"
                      },
                      "task": {
                        "type": "string",
                        "description": "委派给该子智能体的具体任务描述、需求或待分析问题"
                      }
                    },
                    "required": ["agentId", "task"]
                  }
                }
              },
              "required": ["tasks"]
            }
            """;

    /** 动态任务自裂变工具 Schema */
    private static final String DECOMPOSE_SPAWN_SCHEMA = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "type": "object",
              "properties": {
                "tasks": {
                  "type": "array",
                  "description": "动态拆解出的子智能体任务列表（建议 2~4 个并行任务）",
                  "items": {
                    "type": "object",
                    "properties": {
                      "roleName": {
                        "type": "string",
                        "description": "动态赋予该子智能体的专家角色名称（如：数据库性能专家、安全审计专家）"
                      },
                      "systemInstruction": {
                        "type": "string",
                        "description": "该专家角色的专属系统人设与分析指令（引导其以特定专业视角分析）"
                      },
                      "taskPrompt": {
                        "type": "string",
                        "description": "委派给该专家的具体研究分析任务与具体问题"
                      },
                      "enableRag": {
                        "type": "boolean",
                        "description": "是否允许该子智能体检索主智能体绑定的知识库（默认 true）"
                      }
                    },
                    "required": ["roleName", "systemInstruction", "taskPrompt"]
                  }
                }
              },
              "required": ["tasks"]
            }
            """;

    /** 复合双开混合协同门面工具 Schema */
    private static final String HYBRID_DISPATCH_SCHEMA = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "type": "object",
              "properties": {
                "tasks": {
                  "type": "array",
                  "description": "并行子任务列表（总数不超过4个）。已有预置专家覆盖的子任务必须填写 agentId；仅当现有专家均无法覆盖的新领域子任务，才填写 roleName 与 systemInstruction 动态创建临时专家。",
                  "items": {
                    "type": "object",
                    "properties": {
                      "agentId": {
                        "type": "integer",
                        "description": "【优先】预置静态子智能体 ID（若命中已绑定专家名录则必填，填此项时无需填 roleName 和 systemInstruction）"
                      },
                      "roleName": {
                        "type": "string",
                        "description": "【兜底】动态专家角色名称（仅当未填 agentId 时必填，严禁与已绑定专家重名或职责重叠）"
                      },
                      "systemInstruction": {
                        "type": "string",
                        "description": "【兜底】动态专家的系统人设与分析指令（仅当未填 agentId 时必填）"
                      },
                      "task": {
                        "type": "string",
                        "description": "委派给该子智能体的具体任务描述、需求或待分析问题"
                      },
                      "enableRag": {
                        "type": "boolean",
                        "description": "动态专家是否检索主智能体知识库（默认 true；静态专家固定使用其自身绑定的知识库）"
                      }
                    },
                    "required": ["task"]
                  }
                }
              },
              "required": ["tasks"]
            }
            """;

    /** 专用子智能体并行执行线程池（核心 4 线程，最大 12 线程，有界队列 50） */
    private final ExecutorService subAgentExecutor = new ThreadPoolExecutor(
            4, 12, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(50),
            new ThreadFactory() {
                private final AtomicInteger count = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "xxl-ai-subagent-worker-" + count.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

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

    @Override
    public void destroy() {
        subAgentExecutor.shutdown();
    }

    /**
     * 为主 Agent 构建已挂载的 SubAgent 工具列表（兼容原有双参数签名）
     */
    public List<Object> buildTools(Agent parentAgent, TraceCollector traceCollector) {
        return buildTools(parentAgent, traceCollector, null, null);
    }

    /**
     * 为主 Agent 构建已挂载的 SubAgent 工具列表（完整上下文，单门面自适应暴露）
     *
     * 门面裁决原则：
     *  - 若同时具备有效预置专家与自主拆解能力（双开复合态） -> 暴露单一混合协同工具 dispatch_subagents，杜绝工具选择瘫痪与 Spring AI 串行等待；
     *  - 若仅具备有效预置专家 -> 暴露单一工具 delegate_specialist；
     *  - 若仅开启自主拆解 -> 暴露单一工具 decompose_and_spawn；
     *  - 若均未开启 -> 返回空集合。
     *
     * @param parentAgent    主 Agent
     * @param traceCollector 主会话全链路追踪器
     * @param cancelled      主会话取消信号
     * @param eventConsumer  流事件实时回调 (event, data) -> void
     * @return 工具回调列表（严格控制为 0 或 1 个门面工具）
     */
    public List<Object> buildTools(Agent parentAgent,
                                   TraceCollector traceCollector,
                                   AtomicBoolean cancelled,
                                   BiConsumer<String, String> eventConsumer) {
        if (parentAgent == null) {
            return Collections.emptyList();
        }

        // 1、解析同空间有效已发布的预置专家列表
        List<Agent> validSubAgents = new ArrayList<>();
        if (StringTool.isNotBlank(parentAgent.getSubAgentIds())) {
            List<Long> subAgentIdList = splitIds(parentAgent.getSubAgentIds());
            if (CollectionTool.isNotEmpty(subAgentIdList)) {
                for (Long subAgentId : subAgentIdList) {
                    if (subAgentId == null || subAgentId <= 0) {
                        continue;
                    }
                    if (parentAgent.getId() > 0 && subAgentId.equals(parentAgent.getId())) {
                        continue;
                    }
                    Agent subAgent = agentMapper.load(subAgentId);
                    if (subAgent == null || subAgent.getPublishStatus() != 1 || subAgent.getSpaceId() != parentAgent.getSpaceId()) {
                        continue;
                    }
                    validSubAgents.add(subAgent);
                }
            }
        }

        boolean hasSpecialists = !validSubAgents.isEmpty();
        boolean hasAutoDecompose = parentAgent.getAutoDecomposeStatus() == 1;

        List<Object> tools = new ArrayList<>();

        if (hasSpecialists && hasAutoDecompose) {
            // 模式 C：复合双开混合协同单门面（dispatch_subagents）
            tools.add(buildHybridDispatchCallback(parentAgent, validSubAgents, traceCollector, cancelled, eventConsumer));
        } else if (hasSpecialists) {
            // 模式 A：纯预置专家批量委派单门面（delegate_specialist）
            tools.add(buildDelegateSpecialistCallback(parentAgent, validSubAgents, traceCollector, cancelled, eventConsumer));
        } else if (hasAutoDecompose) {
            // 模式 B：纯动态任务自裂变单门面（decompose_and_spawn）
            tools.add(buildDecomposeAndSpawnCallback(parentAgent, traceCollector, cancelled, eventConsumer));
        }

        return tools;
    }

    // ==================== 模式 C：复合双开混合协同门面 ====================

    /**
     * 构建复合双开混合协同 ToolCallback（dispatch_subagents）
     */
    private ToolCallback buildHybridDispatchCallback(Agent parentAgent,
                                                     List<Agent> validSubAgents,
                                                     TraceCollector traceCollector,
                                                     AtomicBoolean cancelled,
                                                     BiConsumer<String, String> eventConsumer) {
        String toolName = "dispatch_subagents";
        StringBuilder desc = new StringBuilder("混合多智能体协同分发门面（支持单次同时并行委派预置专家与动态瞬态专家）。\n已绑定的预置专家名录（优先调用）：\n");
        for (Agent sub : validSubAgents) {
            String intro = StringTool.isNotBlank(sub.getIntro()) ? sub.getIntro().trim() : "专项业务专家";
            desc.append("- ID=").append(sub.getId()).append(" [").append(sub.getName()).append("]：").append(intro).append("\n");
        }
        desc.append("\n【多智能体调度准则】：\n");
        desc.append("1. 【预置专家优先】：拆分任务时必须优先核对已绑定专家名录，凡属于专家职责的子任务必须在 tasks 数组项中填写对应的 agentId（填 agentId 时无需填 roleName 与 systemInstruction）；\n");
        desc.append("2. 【空白领域动态补位】：仅当部分子任务超出所有已绑定专家的职责边界时，才在同一 tasks 数组项中填写 roleName 与 systemInstruction 动态定义互补专家；\n");
        desc.append("3. 单批次 tasks 最多下发 4 项任务，系统将全部并行调度执行并统一汇总返回。");

        Map<Long, Agent> specialistMap = new HashMap<>();
        for (Agent sub : validSubAgents) {
            specialistMap.put(sub.getId(), sub);
        }

        Function<Map<String, Object>, String> function = arguments -> {
            List<HybridTaskInput> tasks = parseHybridTasks(arguments);
            return executeHybridBatch(parentAgent, specialistMap, tasks, traceCollector, cancelled, eventConsumer);
        };

        return FunctionToolCallback.builder(toolName, function)
                .description(desc.toString())
                .inputType(Map.class)
                .inputSchema(HYBRID_DISPATCH_SCHEMA)
                .build();
    }

    /**
     * 并行批处理混合任务（共享全局最大 4 并发配额，含静态专家优先路由仲裁与劫持）
     */
    private String executeHybridBatch(Agent parentAgent,
                                      Map<Long, Agent> specialistMap,
                                      List<HybridTaskInput> tasks,
                                      TraceCollector traceCollector,
                                      AtomicBoolean cancelled,
                                      BiConsumer<String, String> eventConsumer) {
        if (CollectionTool.isEmpty(tasks)) {
            return "[混合协同委派失败] 委派任务列表为空，请指定至少一项有效的子任务。";
        }
        if (cancelled != null && cancelled.get()) {
            return "[混合协同委派已终止] 会话已由用户取消。";
        }

        // 统一批次截取前 4 个任务，全局锁死单批次并发配额
        List<HybridTaskInput> boundedTasks = tasks.subList(0, Math.min(tasks.size(), MAX_PARALLEL_SUBTASKS));
        if (traceCollector != null) {
            traceCollector.addLog("INFO", "subagent",
                    "开始并行执行混合协同任务批次（共 " + boundedTasks.size() + " 项任务）");
        }

        List<CompletableFuture<SubTaskResult>> futures = new ArrayList<>();
        for (HybridTaskInput taskInput : boundedTasks) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                if (cancelled != null && cancelled.get()) {
                    return new SubTaskResult(0L, resolveRoleName(taskInput, specialistMap),
                            "[任务已终止] 用户已终止对话生成。", 0L, false);
                }

                // 运行时静态专家优先路由仲裁（Specialist-First Arbiter）
                SpecialistMatch match = matchExistingSpecialist(taskInput, specialistMap);
                if (match != null) {
                    Agent targetAgent = match.getAgent();
                    if (match.isHijacked() && traceCollector != null) {
                        traceCollector.addLog("WARN", "subagent",
                                "[路由纠偏] 动态角色 [" + taskInput.getRoleName() + "] 与预置专家 ["
                                        + targetAgent.getName() + " (ID=" + targetAgent.getId() + ")] 职责重叠，已自动重定向至预置专家以启用其专属知识库与工具");
                    }
                    return executeSingleSpecialist(parentAgent, targetAgent, taskInput.getTask(), traceCollector, cancelled, eventConsumer);
                }

                // 确属空白领域，执行动态瞬态专家
                DecomposeTaskInput decInput = new DecomposeTaskInput(
                        taskInput.getRoleName(),
                        taskInput.getSystemInstruction(),
                        taskInput.getTask(),
                        taskInput.getEnableRag()
                );
                return executeSingleDecompose(parentAgent, decInput, traceCollector, cancelled, eventConsumer);
            }, subAgentExecutor).orTimeout(SUBTASK_TIMEOUT_SECONDS, TimeUnit.SECONDS).exceptionally(ex -> {
                String errorMsg = ex instanceof TimeoutException ? "任务执行超时（" + SUBTASK_TIMEOUT_SECONDS + "s）" : ex.getMessage();
                return new SubTaskResult(0L, resolveRoleName(taskInput, specialistMap),
                        "[子智能体执行异常] " + errorMsg, 0L, false);
            }));
        }

        return awaitAndFormatResults(futures, traceCollector);
    }

    /**
     * 运行时静态专家优先路由仲裁：防止大模型绕过预置专家重复造轮子
     */
    public SpecialistMatch matchExistingSpecialist(HybridTaskInput input, Map<Long, Agent> specialistMap) {
        if (input == null || specialistMap == null || specialistMap.isEmpty()) {
            return null;
        }

        // 1. 显式指定了有效 agentId，直接命中静态专家
        if (input.getAgentId() != null && input.getAgentId() > 0 && specialistMap.containsKey(input.getAgentId())) {
            return new SpecialistMatch(specialistMap.get(input.getAgentId()), false);
        }

        // 2. 未指定 agentId，但动态指定了 roleName，进行领域重叠检测与自动劫持
        if (StringTool.isNotBlank(input.getRoleName())) {
            String dynamicClean = stripStopwords(input.getRoleName());
            if (StringTool.isNotBlank(dynamicClean)) {
                for (Agent staticAgent : specialistMap.values()) {
                    String staticClean = stripStopwords(staticAgent.getName());
                    if (isRoleOverlapped(dynamicClean, staticClean, input.getTask(), staticAgent.getIntro())) {
                        return new SpecialistMatch(staticAgent, true);
                    }
                }
            }
        }
        return null;
    }

    /**
     * 去除通用停用词，提取核心领域词（如 "SQL优化专家" -> "sql优化"）
     */
    public static String stripStopwords(String text) {
        if (StringTool.isBlank(text)) {
            return "";
        }
        return text.trim().toLowerCase()
                .replaceAll("(专家|顾问|大师|工程师|研究员|分析师|助理|助手|专员|agent|subagent)", "")
                .replaceAll("[\\s_\\-\\.]+", "");
    }

    /**
     * 判定动态角色与静态专家的语义核心重叠度
     */
    public static boolean isRoleOverlapped(String dynamicRoleClean, String staticNameClean, String task, String staticIntro) {
        if (StringTool.isBlank(dynamicRoleClean) || StringTool.isBlank(staticNameClean)) {
            return false;
        }
        // 核心词直接互相包含（例如 "sql优化" 与 "sql优化" 或 "mysql调优" 与 "mysql"）
        if (dynamicRoleClean.contains(staticNameClean) || staticNameClean.contains(dynamicRoleClean)) {
            return true;
        }
        // 核心 Bigram 重叠度 >= 0.50
        if (calculateBigramOverlap(dynamicRoleClean, staticNameClean) >= 0.50) {
            return true;
        }
        return false;
    }

    /**
     * 计算两个字符串的字符二元组（Bigram）Dice 系数
     */
    public static double calculateBigramOverlap(String s1, String s2) {
        if (s1.length() < 2 || s2.length() < 2) {
            return s1.equals(s2) ? 1.0 : 0.0;
        }
        Set<String> set1 = new HashSet<>();
        for (int i = 0; i < s1.length() - 1; i++) {
            set1.add(s1.substring(i, i + 2));
        }
        Set<String> set2 = new HashSet<>();
        for (int i = 0; i < s2.length() - 1; i++) {
            set2.add(s2.substring(i, i + 2));
        }
        int intersection = 0;
        for (String bi : set1) {
            if (set2.contains(bi)) {
                intersection++;
            }
        }
        return (2.0 * intersection) / (set1.size() + set2.size());
    }

    private static String resolveRoleName(HybridTaskInput input, Map<Long, Agent> specialistMap) {
        if (input.getAgentId() != null && specialistMap.containsKey(input.getAgentId())) {
            return specialistMap.get(input.getAgentId()).getName();
        }
        if (StringTool.isNotBlank(input.getRoleName())) {
            return input.getRoleName();
        }
        return "协同子任务";
    }

    // ==================== 模式 A：预置专家批量委派 ====================

    /**
     * 构建预置专家批量委派 ToolCallback（delegate_specialist）
     */
    private ToolCallback buildDelegateSpecialistCallback(Agent parentAgent,
                                                         List<Agent> validSubAgents,
                                                         TraceCollector traceCollector,
                                                         AtomicBoolean cancelled,
                                                         BiConsumer<String, String> eventConsumer) {
        String toolName = "delegate_specialist";
        StringBuilder desc = new StringBuilder("将一个或多个专项任务委派给预先绑定的专业子智能体并行执行并汇聚结果。支持单次委派多个任务。\n已绑定的专家名录：\n");
        for (Agent sub : validSubAgents) {
            String intro = StringTool.isNotBlank(sub.getIntro()) ? sub.getIntro().trim() : "专项业务专家";
            desc.append("- ID=").append(sub.getId()).append(" [").append(sub.getName()).append("]：").append(intro).append("\n");
        }
        desc.append("请以 JSON 格式提供 tasks 数组，每个任务包含 agentId 与 task。");

        Map<Long, Agent> specialistMap = new HashMap<>();
        for (Agent sub : validSubAgents) {
            specialistMap.put(sub.getId(), sub);
        }

        Function<Map<String, Object>, String> function = arguments -> {
            List<SpecialistTaskInput> tasks = parseSpecialistTasks(arguments);
            return executeSpecialistBatch(parentAgent, specialistMap, tasks, traceCollector, cancelled, eventConsumer);
        };

        return FunctionToolCallback.builder(toolName, function)
                .description(desc.toString())
                .inputType(Map.class)
                .inputSchema(DELEGATE_SPECIALIST_SCHEMA)
                .build();
    }

    /**
     * 并行批处理预置专家任务
     */
    private String executeSpecialistBatch(Agent parentAgent,
                                          Map<Long, Agent> specialistMap,
                                          List<SpecialistTaskInput> tasks,
                                          TraceCollector traceCollector,
                                          AtomicBoolean cancelled,
                                          BiConsumer<String, String> eventConsumer) {
        if (CollectionTool.isEmpty(tasks)) {
            return "[SubAgent 委派失败] 委派任务列表为空，请指定至少一项有效的任务需求。";
        }
        if (cancelled != null && cancelled.get()) {
            return "[SubAgent 委派已终止] 会话已由用户取消。";
        }

        // 截取前 4 个并发任务，防止大模型单次委派爆炸
        List<SpecialistTaskInput> boundedTasks = tasks.subList(0, Math.min(tasks.size(), MAX_PARALLEL_SUBTASKS));
        if (traceCollector != null) {
            traceCollector.addLog("INFO", "subagent",
                    "开始并行委派预置专家任务（共 " + boundedTasks.size() + " 项）");
        }

        List<CompletableFuture<SubTaskResult>> futures = new ArrayList<>();
        for (SpecialistTaskInput taskInput : boundedTasks) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                if (cancelled != null && cancelled.get()) {
                    return new SubTaskResult(taskInput.getAgentId(), "子智能体 (ID=" + taskInput.getAgentId() + ")",
                            "[任务已终止] 用户已终止对话生成。", 0L, false);
                }
                Agent subAgent = specialistMap.get(taskInput.getAgentId());
                if (subAgent == null) {
                    return new SubTaskResult(taskInput.getAgentId(), "未知子智能体",
                            "[委派失败] 子智能体 (ID=" + taskInput.getAgentId() + ") 未在主 Agent 绑定名录中或未发布。", 0L, false);
                }
                return executeSingleSpecialist(parentAgent, subAgent, taskInput.getTask(), traceCollector, cancelled, eventConsumer);
            }, subAgentExecutor).orTimeout(SUBTASK_TIMEOUT_SECONDS, TimeUnit.SECONDS).exceptionally(ex -> {
                String errorMsg = ex instanceof TimeoutException ? "任务执行超时（" + SUBTASK_TIMEOUT_SECONDS + "s）" : ex.getMessage();
                return new SubTaskResult(taskInput.getAgentId(), "子智能体 (ID=" + taskInput.getAgentId() + ")",
                        "[子智能体执行异常] " + errorMsg, 0L, false);
            }));
        }

        return awaitAndFormatResults(futures, traceCollector);
    }

    /**
     * 单个预置专家执行（运行于 subAgentExecutor 线程，白盒化透传 RAG 引用与 MCP 工具）
     */
    private SubTaskResult executeSingleSpecialist(Agent parentAgent,
                                                  Agent subAgent,
                                                  String task,
                                                  TraceCollector traceCollector,
                                                  AtomicBoolean cancelled,
                                                  BiConsumer<String, String> eventConsumer) {
        long startTime = System.currentTimeMillis();
        String roleName = subAgent.getName();

        TraceCollector.TraceSpan span = traceCollector != null
                ? traceCollector.startSpan("subagent", "子智能体 [" + roleName + "] 执行")
                : null;

        if (traceCollector != null) {
            traceCollector.addLog("INFO", "subagent",
                    "子智能体 [" + roleName + "] 开始处理任务：" + (task.length() > 100 ? task.substring(0, 100) + "..." : task));
        }

        try {
            if (cancelled != null && cancelled.get()) {
                if (traceCollector != null && span != null) {
                    traceCollector.finishSpan(span, "CANCELLED", "任务已由用户主动取消");
                }
                return new SubTaskResult(subAgent.getId(), roleName, "[任务已终止] 用户主动终止生成。", System.currentTimeMillis() - startTime, false);
            }

            // 1、加载子智能体对话模型运行时
            Response<SupplierRuntime> runtimeResp = supplierService.loadRuntime(subAgent.getSpaceId(),
                    subAgent.getModelSupplierId(), subAgent.getModelId());
            if (!runtimeResp.isSuccess() || runtimeResp.getData() == null) {
                String err = "无法加载子智能体对话模型：" + runtimeResp.getMsg();
                if (traceCollector != null && span != null) {
                    traceCollector.finishSpan(span, "ERROR", err);
                }
                return new SubTaskResult(subAgent.getId(), roleName, "[委派失败] " + err, System.currentTimeMillis() - startTime, false);
            }
            SupplierRuntime runtime = runtimeResp.getData();

            // 2、知识库 RAG 检索（带子角色前缀透传）
            List<TraceCollector.RagReference> subReferences = Collections.emptyList();
            List<Long> kbIds = splitIds(subAgent.getKbIds());
            if (!kbIds.isEmpty()) {
                List<String> degradeNotices = new ArrayList<>();
                subReferences = ragTool.retrieveForChat(subAgent.getSpaceId(), kbIds, task, degradeNotices, traceCollector);
                if (!subReferences.isEmpty()) {
                    List<TraceCollector.RagReference> taggedRefs = new ArrayList<>();
                    for (TraceCollector.RagReference ref : subReferences) {
                        TraceCollector.RagReference tagged = new TraceCollector.RagReference();
                        tagged.setKbId(ref.getKbId());
                        tagged.setKbName(ref.getKbName());
                        tagged.setDocId(ref.getDocId());
                        tagged.setDocName("[子智能体: " + roleName + "] " + ref.getDocName());
                        tagged.setChunkIndex(ref.getChunkIndex());
                        tagged.setScore(ref.getScore());
                        tagged.setContent(ref.getContent());
                        taggedRefs.add(tagged);
                    }
                    if (traceCollector != null) {
                        traceCollector.addReferences(taggedRefs);
                    }
                    if (eventConsumer != null && traceCollector != null) {
                        eventConsumer.accept(ChatStreamTool.EVENT_REFERENCE, traceCollector.toReferencesJson());
                    }
                }
            }
            String contextualQuestion = ragTool.buildContextualQuestion(task, subReferences);

            // 3、装配子智能体专属工具（一层隔离：仅装配 MCP 工具，包装为 ObservableToolCallback，绝不挂载 SubAgentTool）
            List<ObservableToolCallback.TaggedTool> taggedSubTools = new ArrayList<>();
            for (Object rawMcp : mcpToolFactory.buildTools(subAgent)) {
                taggedSubTools.add(new ObservableToolCallback.TaggedTool("SUBAGENT_MCP", rawMcp));
            }
            ToolCallback[] wrappedSubTools = ObservableToolCallback.wrapAll(taggedSubTools, traceCollector, toolJson -> {
                if (eventConsumer != null) {
                    eventConsumer.accept(ChatStreamTool.EVENT_TOOL, toolJson);
                }
            });

            // 4、构建推理超参数（显式锁定子智能体模型代码，防覆盖）
            OpenAiChatOptions chatOptions = ChatStreamTool.buildChatOptions(subAgent, runtime.getModelName(), traceCollector);
            String systemPrompt = StringTool.isNotBlank(subAgent.getSystemPrompt()) ? subAgent.getSystemPrompt().trim() : "";

            // 5、调用大模型（单次无历史会话，Token 统计与 Span 汇聚进主 traceCollector）
            LlmChatTool.ChatText chatText = llmChatTool.chat(
                    runtime,
                    systemPrompt,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    contextualQuestion,
                    wrappedSubTools,
                    Collections.emptyList(),
                    "subagent-specialist-" + subAgent.getId() + "-" + System.currentTimeMillis(),
                    chatOptions,
                    null,
                    null,
                    traceCollector,
                    null
            );

            long elapsed = System.currentTimeMillis() - startTime;
            String reply = chatText != null && chatText.getContent() != null ? chatText.getContent().trim() : "";
            if (traceCollector != null && span != null) {
                traceCollector.finishSpan(span, "SUCCESS", "耗时 " + elapsed + "ms，产出 " + reply.length() + " 字符");
                traceCollector.addLog("INFO", "subagent",
                        "子智能体 [" + roleName + "] 执行完成 (" + elapsed + "ms)");
            }
            return new SubTaskResult(subAgent.getId(), roleName, reply, elapsed, true);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            logger.warn("子智能体执行异常, parentAgentId={}, subAgentId={}, err={}",
                    parentAgent.getId(), subAgent.getId(), e.getMessage());
            if (traceCollector != null && span != null) {
                traceCollector.finishSpan(span, "ERROR", e.getMessage());
                traceCollector.addLog("ERROR", "subagent",
                        "子智能体 [" + roleName + "] 执行失败：" + e.getMessage());
            }
            return new SubTaskResult(subAgent.getId(), roleName, "[子智能体处理失败] " + e.getMessage(), elapsed, false);
        }
    }

    // ==================== 模式 B：动态任务自裂变 ====================

    /**
     * 构建动态任务自裂变 ToolCallback（decompose_and_spawn）
     */
    private ToolCallback buildDecomposeAndSpawnCallback(Agent parentAgent,
                                                        TraceCollector traceCollector,
                                                        AtomicBoolean cancelled,
                                                        BiConsumer<String, String> eventConsumer) {
        String toolName = "decompose_and_spawn";
        String description = """
                自适应动态任务拆解与瞬态智能体裂变工具。
                当面对复杂的跨领域、多维度问题时，自主将总任务拆解为 2~4 个互补的瞬态专家角色并行分析并汇聚结果。
                各瞬态专家继承主智能体的模型与知识库检索能力。
                请以 JSON 格式提供 tasks 数组，每个任务包含 roleName（专家名称）、systemInstruction（专家人设系统指令）、taskPrompt（专职分析问题）与 enableRag（是否开启知识库检索，默认 true）。
                """;

        Function<Map<String, Object>, String> function = arguments -> {
            List<DecomposeTaskInput> tasks = parseDecomposeTasks(arguments);
            return executeDecomposeBatch(parentAgent, tasks, traceCollector, cancelled, eventConsumer);
        };

        return FunctionToolCallback.builder(toolName, function)
                .description(description)
                .inputType(Map.class)
                .inputSchema(DECOMPOSE_SPAWN_SCHEMA)
                .build();
    }

    /**
     * 并行批处理动态瞬态专家任务
     */
    private String executeDecomposeBatch(Agent parentAgent,
                                         List<DecomposeTaskInput> tasks,
                                         TraceCollector traceCollector,
                                         AtomicBoolean cancelled,
                                         BiConsumer<String, String> eventConsumer) {
        if (CollectionTool.isEmpty(tasks)) {
            return "[自适应拆解失败] 拆解出的瞬态专家列表为空，请提供至少一个具体的子任务。";
        }
        if (cancelled != null && cancelled.get()) {
            return "[自适应拆解已终止] 会话已由用户取消。";
        }

        List<DecomposeTaskInput> boundedTasks = tasks.subList(0, Math.min(tasks.size(), MAX_PARALLEL_SUBTASKS));
        if (traceCollector != null) {
            traceCollector.addLog("INFO", "subagent",
                    "主 Agent 自适应裂变出 " + boundedTasks.size() + " 个瞬态专家并行分析");
        }

        List<CompletableFuture<SubTaskResult>> futures = new ArrayList<>();
        for (DecomposeTaskInput taskInput : boundedTasks) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                if (cancelled != null && cancelled.get()) {
                    return new SubTaskResult(0L, taskInput.getRoleName(), "[任务已终止] 用户主动终止生成。", 0L, false);
                }
                return executeSingleDecompose(parentAgent, taskInput, traceCollector, cancelled, eventConsumer);
            }, subAgentExecutor).orTimeout(SUBTASK_TIMEOUT_SECONDS, TimeUnit.SECONDS).exceptionally(ex -> {
                String errorMsg = ex instanceof TimeoutException ? "任务执行超时（" + SUBTASK_TIMEOUT_SECONDS + "s）" : ex.getMessage();
                return new SubTaskResult(0L, taskInput.getRoleName(), "[瞬态专家执行异常] " + errorMsg, 0L, false);
            }));
        }

        return awaitAndFormatResults(futures, traceCollector);
    }

    /**
     * 单个瞬态专家执行（继承父模型 + 父知识库 RAG）
     */
    private SubTaskResult executeSingleDecompose(Agent parentAgent,
                                                 DecomposeTaskInput taskInput,
                                                 TraceCollector traceCollector,
                                                 AtomicBoolean cancelled,
                                                 BiConsumer<String, String> eventConsumer) {
        long startTime = System.currentTimeMillis();
        String roleName = StringTool.isNotBlank(taskInput.getRoleName()) ? taskInput.getRoleName().trim() : "动态业务专家";

        TraceCollector.TraceSpan span = traceCollector != null
                ? traceCollector.startSpan("subagent", "瞬态专家 [" + roleName + "] 分析")
                : null;

        if (traceCollector != null) {
            traceCollector.addLog("INFO", "subagent",
                    "瞬态专家 [" + roleName + "] 开始分析任务：" + (taskInput.getTaskPrompt().length() > 100
                            ? taskInput.getTaskPrompt().substring(0, 100) + "..." : taskInput.getTaskPrompt()));
        }

        try {
            if (cancelled != null && cancelled.get()) {
                if (traceCollector != null && span != null) {
                    traceCollector.finishSpan(span, "CANCELLED", "任务已由用户主动取消");
                }
                return new SubTaskResult(0L, roleName, "[任务已终止] 用户主动终止生成。", System.currentTimeMillis() - startTime, false);
            }

            // 1、继承主 Agent 的模型运行时
            Response<SupplierRuntime> runtimeResp = supplierService.loadRuntime(parentAgent.getSpaceId(),
                    parentAgent.getModelSupplierId(), parentAgent.getModelId());
            if (!runtimeResp.isSuccess() || runtimeResp.getData() == null) {
                String err = "无法继承主智能体对话模型：" + runtimeResp.getMsg();
                if (traceCollector != null && span != null) {
                    traceCollector.finishSpan(span, "ERROR", err);
                }
                return new SubTaskResult(0L, roleName, "[拆解执行失败] " + err, System.currentTimeMillis() - startTime, false);
            }
            SupplierRuntime runtime = runtimeResp.getData();

            // 2、继承主 Agent 知识库进行专向 RAG 检索（若启用）
            List<TraceCollector.RagReference> subReferences = Collections.emptyList();
            boolean enableRag = taskInput.getEnableRag() == null || Boolean.TRUE.equals(taskInput.getEnableRag());
            List<Long> kbIds = splitIds(parentAgent.getKbIds());
            if (enableRag && !kbIds.isEmpty()) {
                List<String> degradeNotices = new ArrayList<>();
                subReferences = ragTool.retrieveForChat(parentAgent.getSpaceId(), kbIds, taskInput.getTaskPrompt(), degradeNotices, traceCollector);
                if (!subReferences.isEmpty()) {
                    List<TraceCollector.RagReference> taggedRefs = new ArrayList<>();
                    for (TraceCollector.RagReference ref : subReferences) {
                        TraceCollector.RagReference tagged = new TraceCollector.RagReference();
                        tagged.setKbId(ref.getKbId());
                        tagged.setKbName(ref.getKbName());
                        tagged.setDocId(ref.getDocId());
                        tagged.setDocName("[瞬态专家: " + roleName + "] " + ref.getDocName());
                        tagged.setChunkIndex(ref.getChunkIndex());
                        tagged.setScore(ref.getScore());
                        tagged.setContent(ref.getContent());
                        taggedRefs.add(tagged);
                    }
                    if (traceCollector != null) {
                        traceCollector.addReferences(taggedRefs);
                    }
                    if (eventConsumer != null && traceCollector != null) {
                        eventConsumer.accept(ChatStreamTool.EVENT_REFERENCE, traceCollector.toReferencesJson());
                    }
                }
            }
            String contextualQuestion = ragTool.buildContextualQuestion(taskInput.getTaskPrompt(), subReferences);

            // 3、继承主 Agent 的 MCP 工具并包装（严禁装配 SubAgentTool）
            List<ObservableToolCallback.TaggedTool> taggedSubTools = new ArrayList<>();
            for (Object rawMcp : mcpToolFactory.buildTools(parentAgent)) {
                taggedSubTools.add(new ObservableToolCallback.TaggedTool("SUBAGENT_MCP", rawMcp));
            }
            ToolCallback[] wrappedSubTools = ObservableToolCallback.wrapAll(taggedSubTools, traceCollector, toolJson -> {
                if (eventConsumer != null) {
                    eventConsumer.accept(ChatStreamTool.EVENT_TOOL, toolJson);
                }
            });

            // 4、构建推理超参数与系统指令
            OpenAiChatOptions chatOptions = ChatStreamTool.buildChatOptions(parentAgent, runtime.getModelName(), traceCollector);
            String systemPrompt = StringTool.isNotBlank(taskInput.getSystemInstruction())
                    ? taskInput.getSystemInstruction().trim()
                    : "你是一名资深专职专家，专注于协助主智能体深度解答专项业务问题。请根据给定问题给出全面、严谨、深度的研究分析报告。";

            // 5、调用大模型
            LlmChatTool.ChatText chatText = llmChatTool.chat(
                    runtime,
                    systemPrompt,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    contextualQuestion,
                    wrappedSubTools,
                    Collections.emptyList(),
                    "subagent-spawn-" + System.currentTimeMillis(),
                    chatOptions,
                    null,
                    null,
                    traceCollector,
                    null
            );

            long elapsed = System.currentTimeMillis() - startTime;
            String reply = chatText != null && chatText.getContent() != null ? chatText.getContent().trim() : "";
            if (traceCollector != null && span != null) {
                traceCollector.finishSpan(span, "SUCCESS", "耗时 " + elapsed + "ms，产出 " + reply.length() + " 字符");
                traceCollector.addLog("INFO", "subagent",
                        "瞬态专家 [" + roleName + "] 分析完成 (" + elapsed + "ms)");
            }
            return new SubTaskResult(0L, roleName, reply, elapsed, true);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            logger.warn("瞬态专家执行异常, roleName={}, err={}", roleName, e.getMessage());
            if (traceCollector != null && span != null) {
                traceCollector.finishSpan(span, "ERROR", e.getMessage());
                traceCollector.addLog("ERROR", "subagent",
                        "瞬态专家 [" + roleName + "] 分析失败：" + e.getMessage());
            }
            return new SubTaskResult(0L, roleName, "[瞬态专家分析失败] " + e.getMessage(), elapsed, false);
        }
    }

    // ==================== 统一结果等待与格式化 ====================

    /**
     * 等待所有子任务 Future 并格式化为 Markdown 报告供主 Agent 综合提炼
     */
    private String awaitAndFormatResults(List<CompletableFuture<SubTaskResult>> futures, TraceCollector traceCollector) {
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(SUBTASK_TIMEOUT_SECONDS + 5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            futures.forEach(f -> f.cancel(true));
            return "[SubAgent 委派中断] 子任务执行已被中断。";
        } catch (Exception ignored) {
        }

        List<SubTaskResult> results = new ArrayList<>();
        for (CompletableFuture<SubTaskResult> future : futures) {
            try {
                if (future.isDone() && !future.isCancelled()) {
                    results.add(future.getNow(null));
                } else {
                    results.add(new SubTaskResult(0L, "子智能体", "[执行超时或被取消]", 0L, false));
                }
            } catch (Exception e) {
                results.add(new SubTaskResult(0L, "子智能体", "[执行异常] " + e.getMessage(), 0L, false));
            }
        }

        StringBuilder output = new StringBuilder();
        output.append("### 子智能体并行委派执行报告（共 ").append(results.size()).append(" 项子任务）\n\n");
        for (SubTaskResult res : results) {
            if (res == null) continue;
            output.append("#### 【").append(res.getRoleName()).append("】\n");
            output.append("- 执行状态: ").append(res.isSuccess() ? "成功" : "异常")
                    .append(" (耗时: ").append(res.getElapsedMs()).append("ms)\n");
            output.append("- 专向分析内容:\n").append(res.getContent()).append("\n\n");
        }
        return output.toString();
    }

    // ==================== 入参解析与辅助类 ====================

    /**
     * 解析预置专家任务入参
     */
    @SuppressWarnings("unchecked")
    public List<SpecialistTaskInput> parseSpecialistTasks(Object arguments) {
        if (arguments == null) {
            return Collections.emptyList();
        }
        try {
            if (arguments instanceof Map<?, ?> map) {
                Object tasksObj = map.get("tasks");
                if (tasksObj instanceof List<?> list) {
                    List<SpecialistTaskInput> result = new ArrayList<>();
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> taskMap) {
                            long agentId = parseLong(taskMap.get("agentId"));
                            String task = String.valueOf(taskMap.get("task") != null ? taskMap.get("task") : "");
                            if (agentId > 0 && StringTool.isNotBlank(task)) {
                                result.add(new SpecialistTaskInput(agentId, task.trim()));
                            }
                        }
                    }
                    return result;
                }
                // 兼容单任务入参
                long agentId = parseLong(map.get("agentId"));
                String task = String.valueOf(map.get("task") != null ? map.get("task") : "");
                if (agentId > 0 && StringTool.isNotBlank(task)) {
                    return List.of(new SpecialistTaskInput(agentId, task.trim()));
                }
            } else if (arguments instanceof String str) {
                Map<String, Object> map = GSON.fromJson(str, new TypeToken<Map<String, Object>>() {}.getType());
                return parseSpecialistTasks(map);
            }
        } catch (Exception e) {
            logger.warn("解析 Specialist 任务参数异常: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * 解析动态拆解任务入参
     */
    @SuppressWarnings("unchecked")
    public List<DecomposeTaskInput> parseDecomposeTasks(Object arguments) {
        if (arguments == null) {
            return Collections.emptyList();
        }
        try {
            if (arguments instanceof Map<?, ?> map) {
                Object tasksObj = map.get("tasks");
                if (tasksObj instanceof List<?> list) {
                    List<DecomposeTaskInput> result = new ArrayList<>();
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> taskMap) {
                            String roleName = String.valueOf(taskMap.get("roleName") != null ? taskMap.get("roleName") : "");
                            String sys = String.valueOf(taskMap.get("systemInstruction") != null ? taskMap.get("systemInstruction") : "");
                            String prompt = String.valueOf(taskMap.get("taskPrompt") != null ? taskMap.get("taskPrompt") : "");
                            Boolean rag = taskMap.get("enableRag") instanceof Boolean b ? b : Boolean.TRUE;
                            if (StringTool.isNotBlank(prompt)) {
                                result.add(new DecomposeTaskInput(roleName, sys, prompt.trim(), rag));
                            }
                        }
                    }
                    return result;
                }
                // 兼容单任务入参
                String roleName = String.valueOf(map.get("roleName") != null ? map.get("roleName") : "");
                String sys = String.valueOf(map.get("systemInstruction") != null ? map.get("systemInstruction") : "");
                String prompt = String.valueOf(map.get("taskPrompt") != null ? map.get("taskPrompt") : "");
                Boolean rag = map.get("enableRag") instanceof Boolean b ? b : Boolean.TRUE;
                if (StringTool.isNotBlank(prompt)) {
                    return List.of(new DecomposeTaskInput(roleName, sys, prompt.trim(), rag));
                }
            } else if (arguments instanceof String str) {
                Map<String, Object> map = GSON.fromJson(str, new TypeToken<Map<String, Object>>() {}.getType());
                return parseDecomposeTasks(map);
            }
        } catch (Exception e) {
            logger.warn("解析 Decompose 任务参数异常: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * 解析复合双开混合任务入参
     */
    @SuppressWarnings("unchecked")
    public List<HybridTaskInput> parseHybridTasks(Object arguments) {
        if (arguments == null) {
            return Collections.emptyList();
        }
        try {
            if (arguments instanceof Map<?, ?> map) {
                Object tasksObj = map.get("tasks");
                if (tasksObj instanceof List<?> list) {
                    List<HybridTaskInput> result = new ArrayList<>();
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> taskMap) {
                            Long agentId = taskMap.get("agentId") != null ? parseLong(taskMap.get("agentId")) : null;
                            String roleName = taskMap.get("roleName") != null ? String.valueOf(taskMap.get("roleName")).trim() : null;
                            String sys = taskMap.get("systemInstruction") != null ? String.valueOf(taskMap.get("systemInstruction")).trim() : null;
                            Object taskVal = taskMap.get("task") != null ? taskMap.get("task") : taskMap.get("taskPrompt");
                            String task = taskVal != null ? String.valueOf(taskVal).trim() : "";
                            Boolean rag = taskMap.get("enableRag") instanceof Boolean b ? b : Boolean.TRUE;
                            if (StringTool.isNotBlank(task)) {
                                result.add(new HybridTaskInput(agentId != null && agentId > 0 ? agentId : null, roleName, sys, task, rag));
                            }
                        }
                    }
                    return result;
                }
                // 兼容单任务入参
                Long agentId = map.get("agentId") != null ? parseLong(map.get("agentId")) : null;
                String roleName = map.get("roleName") != null ? String.valueOf(map.get("roleName")).trim() : null;
                String sys = map.get("systemInstruction") != null ? String.valueOf(map.get("systemInstruction")).trim() : null;
                Object taskVal = map.get("task") != null ? map.get("task") : map.get("taskPrompt");
                String task = taskVal != null ? String.valueOf(taskVal).trim() : "";
                Boolean rag = map.get("enableRag") instanceof Boolean b ? b : Boolean.TRUE;
                if (StringTool.isNotBlank(task)) {
                    return List.of(new HybridTaskInput(agentId != null && agentId > 0 ? agentId : null, roleName, sys, task, rag));
                }
            } else if (arguments instanceof String str) {
                Map<String, Object> map = GSON.fromJson(str, new TypeToken<Map<String, Object>>() {}.getType());
                return parseHybridTasks(map);
            }
        } catch (Exception e) {
            logger.warn("解析 Hybrid 任务参数异常: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    private static long parseLong(Object val) {
        if (val == null) return 0L;
        if (val instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(val).trim());
        } catch (Exception e) {
            return 0L;
        }
    }

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

    // ==================== 内部模型与结果包装 ====================

    public static class SpecialistTaskInput {
        private final long agentId;
        private final String task;

        public SpecialistTaskInput(long agentId, String task) {
            this.agentId = agentId;
            this.task = task;
        }

        public long getAgentId() {
            return agentId;
        }

        public String getTask() {
            return task;
        }
    }

    public static class DecomposeTaskInput {
        private final String roleName;
        private final String systemInstruction;
        private final String taskPrompt;
        private final Boolean enableRag;

        public DecomposeTaskInput(String roleName, String systemInstruction, String taskPrompt, Boolean enableRag) {
            this.roleName = roleName;
            this.systemInstruction = systemInstruction;
            this.taskPrompt = taskPrompt;
            this.enableRag = enableRag;
        }

        public String getRoleName() {
            return roleName;
        }

        public String getSystemInstruction() {
            return systemInstruction;
        }

        public String getTaskPrompt() {
            return taskPrompt;
        }

        public Boolean getEnableRag() {
            return enableRag;
        }
    }

    public static class HybridTaskInput {
        private final Long agentId;
        private final String roleName;
        private final String systemInstruction;
        private final String task;
        private final Boolean enableRag;

        public HybridTaskInput(Long agentId, String roleName, String systemInstruction, String task, Boolean enableRag) {
            this.agentId = agentId;
            this.roleName = roleName;
            this.systemInstruction = systemInstruction;
            this.task = task;
            this.enableRag = enableRag;
        }

        public Long getAgentId() {
            return agentId;
        }

        public String getRoleName() {
            return roleName;
        }

        public String getSystemInstruction() {
            return systemInstruction;
        }

        public String getTask() {
            return task;
        }

        public Boolean getEnableRag() {
            return enableRag;
        }
    }

    public static class SpecialistMatch {
        private final Agent agent;
        private final boolean hijacked;

        public SpecialistMatch(Agent agent, boolean hijacked) {
            this.agent = agent;
            this.hijacked = hijacked;
        }

        public Agent getAgent() {
            return agent;
        }

        public boolean isHijacked() {
            return hijacked;
        }
    }

    public static class SubTaskResult {
        private final long agentId;
        private final String roleName;
        private final String content;
        private final long elapsedMs;
        private final boolean success;

        public SubTaskResult(long agentId, String roleName, String content, long elapsedMs, boolean success) {
            this.agentId = agentId;
            this.roleName = roleName;
            this.content = content;
            this.elapsedMs = elapsedMs;
            this.success = success;
        }

        public long getAgentId() {
            return agentId;
        }

        public String getRoleName() {
            return roleName;
        }

        public String getContent() {
            return content;
        }

        public long getElapsedMs() {
            return elapsedMs;
        }

        public boolean isSuccess() {
            return success;
        }
    }

}
