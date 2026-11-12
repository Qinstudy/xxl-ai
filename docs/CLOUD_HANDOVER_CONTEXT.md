# XXL-AI 双端接力·工程现状与会话沉淀备忘录（Handover Briefing）

> **文档定位**：本文件由本地 Antigravity 会话自动提炼生成，专用于向 Antigravity 云端 Agent 传递工程上下文、已解决的技术深坑及架构决策。  
> **核心指令**：云端 Agent 启动新会话时，必须以此文档与根目录 `AGENTS.md` 作为事实基准，零沟通损耗接力本地研发。

---

## 一、工程基线与分支拓扑

1. **当前分支**：`dev-cloud`（基于官方 `upstream/master` 1.5.0-SNAPSHOT 模块化主干镜像构建）。
2. **远程关联**：
   * `origin`：`https://github.com/Qinstudy/xxl-ai.git`（个人开发 Fork 仓库，云端运行载体）。
   * `upstream`：`https://github.com/xuxueli/xxl-ai.git`（许雪里官方开源主干）。
3. **架构纪律（必须严格执行）**：
   * **方案先行铁律**：遇到任何新特性或缺陷改造，必须**先出实现方案与类影响清单**，待用户确认后再出码。
   * **PR 纯净分支铁律**：向上游官方贡献 PR 时，必须调用 `/xxl-ai-contributor` 检出独立纯净分支（如 `fix/xxx`），**严禁直接用 `dev-cloud` 发起 PR**。

---

## 二、本地已落地的核心能力资产（无需重复开发）

本地在 `master-local-study` 中已完成以下企业级深度能力的端到端研发，均已同步至 `dev-cloud`：

```mermaid
flowchart TD
    subgraph Observability["1. 全链路可观测性 (Web-trace)"]
        TC["TraceCollector (ThreadLocal 状态机)"]
        Waterfall["耗时瀑布图 & 工具调用证据链"]
        TraceUI["前端 TraceInspectorDrawer.vue 实时抽屉"]
    end

    subgraph Artifacts["2. Web 产物沙箱 (Artifacts)"]
        SAT["SkillArtifactTool (自动识别交付物)"]
        Cards["ArtifactCards.vue 卡片流"]
        Preview["ArtifactPreviewDialog.vue 沉浸式预览"]
    end

    subgraph RAG["3. RAG 深度治理与算法防断句"]
        RTS["RagTextSplitter (按中英文标点递归分块)"]
        Threshold["similarity_threshold (默认0.40过滤低质切片)"]
        ContextWin["ChatContextWindowTool (Token 滑动窗口保护)"]
    end

    subgraph Stability["4. 核心健壮性与超时穿透"]
        Timeout["LlmModelFactory 绑定 180s streamTimeout (防60s腰斩)"]
        MilvusFix["MilvusVectorStore 内存指纹动态自愈 (已合入官方主干)"]
    end
```

### 1. 全链路可观测性（Web-trace）
* **后端**：`TraceCollector.java` 采集 Agent 思考、工具调用耗时、LLM 首字延迟（TTFT）、Total Tokens 及证据链；
* **前端**：`TraceInspectorDrawer.vue` 提供可视化耗时瀑布图与实时追踪日志；
* **数据库**：`doc/db/update_v1.4_observability.sql`（包含 `xxl_ai_trace_record` 建表）。

### 2. Web 端产物沙箱（Artifacts）
* **后端**：`SkillArtifactTool.java`，自动捕获模型生成的代码、HTML、Markdown 与 SVG，持久化存储并暴露预览/下载端点；
* **前端**：`ArtifactCards.vue` 与 `ArtifactPreviewDialog.vue`，实现类似 Claude Artifacts 的即时交互。

### 3. RAG 深度切分与召回治理
* **递归分块器**：`RagTextSplitter.java`，彻底解决按生硬字符截断导致的中英文断句、代码块破损问题；
* **阈值过滤**：`KnowledgeBase` 引入 `similarity_threshold`（默认 0.40），底层向量检索低于阈值自动剔除；
* **数据库**：`doc/db/patch_20261025_p0_rag_threshold.sql`。

### 4. 传输超时穿透
* `LlmModelFactory.java` 读取 `@Value("${xxl-ai.chat.stream.timeout:180000}")`，向 `OpenAiChatOptions` 显式注入 `.timeout(Duration.ofMillis(streamTimeout))`，彻底解决 OkHttpClient 默认 60s 超时中断长文本生成的致命痛点。

### 5. AI 应用与 RAG 质量量化评测体系（FEAT-014）
* **后端引擎**：`EvalEngineTool.java` 提供离散检索指标（HitRate@K、MRR）及 LLM-as-a-Judge（Faithfulness 防幻觉、Answer Relevance 相关性、Goal Completion 达成率）；
* **业务与调度**：`EvalExperimentServiceImpl.java` 定长线程池（并发度 3）跑批，实时打点更新进度，快照防基线漂移；
* **前端面板**：`eval/pages/index.vue` 双页签（数据集管理 + 实验中心）与 `ExperimentDetailDrawer.vue` 报告抽屉；
* **数据库**：`doc/db/patch_20261112_feat014_eval.sql`（`xxl_ai_eval_dataset` 与 `xxl_ai_eval_experiment` 2 表内聚架构）。

### 6. 工作流编排与轻量 DAG 执行引擎（FEAT-009 阶段一）
* **执行引擎**：`WorkflowDagEngine.java`（Kahn 算法拓扑排序与环路检测、分支剪枝、50步防死循环熔断、180s超时保护）；
* **基础节点**：6 类节点处理器（`START`、`LLM`、`RETRIEVAL`、`TOOL`、`CONDITION`、`END`）；
* **上下文流转**：`WorkflowContext.java` 全局变量池与 `WorkflowVariableResolver.java` 纯静态变量插值（`{{nodeId.outputKey}}`），解耦反向依赖防循环死锁；
* **前端面板**：`workflow/pages/index.vue` 双页签（定义管理 + 执行历史）与 `WorkflowDebugDrawer.vue` 调试抽屉（含时延瀑布与节点快照）；
* **数据库**：`doc/db/patch_20261112_feat009_workflow.sql`（`xxl_ai_workflow` 与 `xxl_ai_workflow_execution` 2 表微创架构）。

---

## 三、核心参考文档与测试指南导航

云端与本地研发以仓库内以下核心文档作为基准与验收依据：
1. **`docs/XXL-AI-核心缺陷与功能演进优先级总表-v1.0.md`**：
   * 包含缺陷总表（P0~P3）与功能演进蓝图，标注了当前各任务的状态与优先级。
2. **`docs/三项目对标总表-v2.0.md`**：
   * 对标 `LightBot` 与 `aiagentdemo` 的架构全景、算法选型（模型路由、记忆压缩、提示词工程），是后续架构优化的设计库。
3. **`docs/XXL-AI-近期核心特性页面端到端测试验证指南.md`**：
   * 近期交付特性的完整 Web 页面测试实操手册（覆盖 FEAT-014 评测中心、FEAT-010 动态变量、FEAT-011/004 检索测试台与多路 RRF、SEC-001/002 安全防线）。
4. **`docs/XXL-AI-FEAT-006与FEAT-013本地测试验证指南.md`**：
   * 包含大模型推理超参数动态配置与 SubAgents 多智能体委派体系的端到端测试步骤。

---

## 四、历史踩坑血泪经验（避坑指南）

1. **单向依赖红线（防 Spring 循环依赖死锁）**：
   * 业务模块（`business`）可调用底层支撑（`harness`）；
   * `harness` 底层组件绝不能反向注入上层业务 Service（如 `RagTool` 绝不能反向注入 `SupplierService`），否则 Spring 容器启动必定崩溃。
2. **多租户/空间隔离铁律**：
   * 任何业务查询必须强制携带 `SpaceContext.getCurrentSpaceId()`，禁止在 Java 内存中做全量查出后的伪过滤。
3. **Spring AI OpenAI 扩展挂载**：
   * 自定义请求头必须挂到每个 `model.headers`，而不是 `provider.headers`（Spring AI 底层只有 model 级 header 会被合并）。
4. **外部参考库隔离**：
   * `refs/` 与 `xxl-ai-spec/` 属本地历史文件，禁止 `git add` 推送，保持仓库纯净。
5. **Spring AI Options 动态超参数隐式篡改模型名陷阱（防 400 模型不存在）**：
   * `OpenAiChatOptions.builder()` 在仅设置 `temperature/topP/maxTokens` 而未显式调用 `.model(...)` 时，Spring AI 构造函数默认隐式赋值为 `DEFAULT_CHAT_MODEL`（`gpt-5-mini`）；
   * 通过 `ChatClientRequestSpec.options(...)` 注入 Prompt 级超参数时，拥有更高优先级的 options 会将 `gpt-5-mini` 覆盖到底层配置的真实模型名上，导致请求智谱 GLM、DeepSeek、Ollama 等第三方兼容 OpenAI 协议的模型全量报 `400: 模型不存在，请检查模型代码`；
   * **避坑红线**：凡在运行时构建或变异 `OpenAiChatOptions`，必须强制显式绑定 `runtime.getModelName()`；并在调用层（`LlmChatTool`）二次强校验兜底，严禁裸跑 Options Builder。

---

## 五、云端接力建议指令

在 Antigravity 云端开启会话后，直接发送以下指令即可启动接力：
> *“已阅读 `docs/CLOUD_HANDOVER_CONTEXT.md`。我已知晓已落地的 Web-trace、Artifacts、RAG 递归分块及超时对齐等功能，以及两份核心对标文档。请告诉我下一步我们推进哪项任务，我将严格遵循先方案后编码的规范执行。”*
