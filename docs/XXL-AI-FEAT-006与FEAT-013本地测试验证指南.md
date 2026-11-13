# XXL-AI 本地测试与验证指南：FEAT-006 & FEAT-013

> 本手册基于 `dev-cloud` 分支当前源码实现（菜单、表单校验与自适应单门面 SubAgent 运行时），用于在 Web 页面与接口层完整测试以下两项核心能力：
> - **[FEAT-006]** 大模型推理超参数动态配置 (`temperature` / `topP` / `maxTokens`)
> - **[FEAT-013]** SubAgents 多智能体协作、自主任务拆解与路由纠偏体系（`delegate_specialist` / `decompose_and_spawn` / `dispatch_subagents`）

---

## 目录
- [零、 前置准备（数据库升级、模型配置与服务启动）](#零-前置准备数据库升级模型配置与服务启动)
- [一、 [FEAT-006] 大模型推理超参数配置测试](#一-feat-006-大模型推理超参数配置测试)
  - [1.1 表单交互、Tooltip 与置空保存验证](#11-表单交互tooltip-与置空保存验证)
  - [1.2 对话与 Trace 注入日志验证](#12-对话与-trace-注入日志验证)
  - [1.3 前端自动夹逼与后端边界合法性拦截验证](#13-前端自动夹逼与后端边界合法性拦截验证)
- [二、 [FEAT-013] SubAgents 多智能体协作与自主拆解测试](#二-feat-013-subagents-多智能体协作与自主拆解测试)
  - [2.1 准备 2 个角色分工明确的子 Agent](#21-准备-2-个角色分工明确的子-agent)
  - [2.2 模式 A：纯预置专家并行委派（delegate_specialist）](#22-模式-a纯预置专家并行委派delegate_specialist)
  - [2.3 模式 B：纯自主任务拆解（decompose_and_spawn）](#23-模式-b纯自主任务拆解decompose_and_spawn)
  - [2.4 模式 C：混合双开与运行时路由纠偏（dispatch_subagents）](#24-模式-c混合双开与运行时路由纠偏dispatch_subagents)
  - [2.5 四维全景验收指标对照表](#25-四维全景验收指标对照表)
- [三、 常见排错与避坑指南](#三-常见排错与避坑指南)

---

## 零、 前置准备（数据库升级、模型配置与服务启动）

### 1. 数据库就绪（区分全新建库与存量升级）
- **全新空库**：仓库主脚本 [tables_xxl_ai.sql](../doc/db/tables_xxl_ai.sql) 已内置 FEAT-006 与 FEAT-013 的全部字段，直接导入即可：
  ```bash
  mysql --default-character-set=utf8mb4 -h127.0.0.1 -P3306 -uroot -p123456 < doc/db/tables_xxl_ai.sql
  ```
- **存量旧库升级**：若本地库缺少近期新增列，必须按顺序补齐 **`p2`（推理超参数）+ `p3`（关联 SubAgent）+ `p4`（自主拆解开关 `auto_decompose_status`）**，漏执行 `p4` 将直接导致页面报错：
  ```bash
  mysql --default-character-set=utf8mb4 -h127.0.0.1 -P3306 -uroot -p123456 xxl_ai < doc/db/patch_20261110_p2_agent_options.sql
  mysql --default-character-set=utf8mb4 -h127.0.0.1 -P3306 -uroot -p123456 xxl_ai < doc/db/patch_20261110_p3_agent_subagent.sql
  mysql --default-character-set=utf8mb4 -h127.0.0.1 -P3306 -uroot -p123456 xxl_ai < doc/db/patch_20261110_p4_auto_decompose.sql
  ```
  > 执行后可在 MySQL 中运行 `DESC xxl_ai_agent;` 确认存在 `sub_agent_ids`、`auto_decompose_status`、`temperature`、`top_p`、`max_tokens` 5 个字段。

### 2. 启动工程与模型前置配置
1. **中间件**：确认本地 MySQL（`3306`）与 Redis（`6379`）正常运行（若子 Agent 需测试知识库透传检索，还需启动 Milvus `19530` 并完成文档向量化）。
2. **后端 API**：根目录运行 `mvn -pl xxl-ai-api spring-boot:run`（端口 `8080`）。
3. **前端 UI**：`xxl-ai-ui/` 目录运行 `npm run dev`（端口 `3000`）。
4. **登录与模型配置**：访问 `http://localhost:3000` 使用 `admin / 123456` 登录；**务必先进入左侧【供应商模型】菜单**，为所用对话供应商（如 Deepseek、智谱GLM 或本地 Ollama）填入有效 `API密钥`（种子 SQL 默认留空）并启用至少 1 个支持 Function Calling / Tool Use 的对话模型。

---

## 一、 [FEAT-006] 大模型推理超参数配置测试

### 1.1 表单交互、Tooltip 与置空保存验证
1. 点击左侧一级菜单：**【Agent管理】**（路由 `/#/agent/index`，非子菜单）。
2. 点击工具栏 **【新增】**（或操作列 **【修改】** 编辑现有 Agent）。
3. 滚动到弹窗底部，检查表单是否出现 **【模型推理高级参数】** 分割线与 3 个数字输入组件：
   - **采样温度 (Temperature)**：
     - 输入 `0.7`（`min=0, max=2, step=0.1, precision=1`）；
     - 鼠标悬停标签旁 `?` 图标，检查 Tooltip 提示正常显示。
   - **核采样 (Top P)**：
     - 输入 `0.9`（`min=0, max=1, step=0.05, precision=2`）；
     - 悬停 `?` 图标检查 Tooltip 提示。
   - **单次最大 Token (Max Tokens)**：
     - 输入 `2048`（`min=1, max=131072, step=128`）；
     - 悬停 `?` 图标检查 Tooltip 提示。
4. 点击 **【确定】** 保存。
5. 刷新页面后再次点击该 Agent 操作列的 **【修改】**，确认 `0.7`、`0.9`、`2048` 均正确回显。
6. **置空回退默认值测试**：清空这 3 个输入框（点空或退格删净），点击 **【确定】** 保存，再次打开 **【修改】** 确认均保持为空（留空表示下发时不覆盖模型默认值）。

---

### 1.2 对话与 Trace 注入日志验证
1. 点击目标 Agent 操作列的 **【修改】**，重新填入 `temperature=0.7`、`topP=0.9`、`maxTokens=2048` 并点击 **【确定】**。
2. 在列表该行将 **【发布状态】** 列的开关拨动为 **开启（已发布）**，**【访问URL】** 列出现 **【前往Agent】** 链接（列表无独立的 `发布` 或 `对话` 按钮）。
3. 点击 **【前往Agent】** 在新标签页打开公开对话页 `/#/chat/{uuid}`。
4. 发送测试消息：
   ```text
   请写一首关于江南烟雨的七言绝句。
   ```
5. 回复生成完毕后，点击该条助手消息气泡**底部的【全链路追踪】** 链接打开追踪抽屉。
6. 切换到 **【实时日志流】** 页签，核对 `context` 阶段存在如下 `INFO` 日志：
   ```text
   [INFO][context] 注入推理超参数：temperature=0.7, topP=0.9, maxTokens=2048
   ```
7. 同时核对抽屉顶部 **推理模型** 卡片显示的是实际配置的模型标识（如 `deepseek-v4.1-flash`、`glm-5.3` 等），而非被误覆盖为 `gpt-5-mini`。

---

### 1.3 前端自动夹逼与后端边界合法性拦截验证
> ⚠️ **UI 与后端校验分层说明**：前端表单使用了 Element Plus `<el-input-number>` 并设置了 `:min` / `:max` 边界约束（Temperature `0~2`、Top P `0~1`、Max Tokens `1~131072`）。**直接在页面键盘输入 `3.0`、`1.5` 或 `0`，失焦时会被浏览器 UI 组件自动夹逼回 `2.0`、`1.0`、`1`，因此无法通过纯页面点击触发后端越界报错。** 需分以下两步验证：

1. **前端 UI 自动夹逼验证**：
   - 在 Agent 编辑弹窗中尝试向 **采样温度** 键盘输入 `3.0` 后失焦 → 自动修正为 `2.0`；
   - 向 **核采样** 输入 `1.5` 后失焦 → 自动修正为 `1.0`；
   - 向 **单次最大 Token** 输入 `0` 后失焦 → 自动修正为 `1`。
2. **后端防绕过校验验证（通过浏览器 Console 直调接口）**：
   - 在已登录后台的页面打开 `DevTools -> Console`，依次运行以下脚本测试 [`AgentServiceImpl.validateOptions`](../xxl-ai-api/src/main/java/com/xxl/ai/api/business/agent/service/impl/AgentServiceImpl.java#L110-L130) 后端硬拦截：
   ```javascript
   const ssoToken = document.cookie.match(/(?:^|;\s*)Admin-Token=([^;]*)/)?.[1] || '';
   const testInvalidOption = (payload) => fetch('/api/agent/insert', {
     method: 'POST',
     headers: {
       'Content-Type': 'application/json',
       'xxl-sso-login-token': decodeURIComponent(ssoToken),
       'xxl-space-id': '1'
     },
     body: JSON.stringify({
       name: '边界值测试Agent',
       modelSupplierId: 1,
       modelId: 1,
       ...payload
     })
   }).then(r => r.json()).then(d => console.log(payload, '=>', d));

   // 依次触发后端 3 条校验提示
   testInvalidOption({ temperature: 3.0 });
   // 预期：{"code":500,"msg":"采样温度 (Temperature) 必须在 0.0 到 2.0 之间"}

   testInvalidOption({ topP: 1.5 });
   // 预期：{"code":500,"msg":"核采样 (Top P) 必须在 0.0 到 1.0 之间"}

   testInvalidOption({ maxTokens: 0 });
   // 预期：{"code":500,"msg":"单次最大 Token 数 (Max Tokens) 必须在 1 到 131072 之间"}
   ```

---

## 二、 [FEAT-013] SubAgents 多智能体协作与自主拆解测试

当前版本采用 **自适应单门面（0 或 1 个工具）+ 并行调度（单批最多 4 项、单项 60s 超时）+ 一层深度硬隔离** 架构（注：早期每子 Agent 注册独立 `subagent_{id}` 工具的方案已演进废弃）。根据主 Agent 配置自动暴露对应门面工具：
- 仅配置 **关联 SubAgent**：暴露 **`delegate_specialist`**（模式 A）；
- 仅开启 **自主任务拆解**：暴露 **`decompose_and_spawn`**（模式 B）；
- 同时配置 **关联 SubAgent** 且开启 **自主任务拆解**：暴露 **`dispatch_subagents`** 并启用运行时 **`[路由纠偏]`**（模式 C）。

### 2.1 准备 2 个角色分工明确的子 Agent
在 **【Agent管理】** 中先创建以下 2 个专项子 Agent（均选择支持工具调用/函数调用的可用对话模型）：

#### 子 Agent 1：SQL 性能专家
- **Agent名称**：`SQL性能专家`
- **Agent介绍**：`专门分析慢查询 SQL 与索引优化方案`
- **关联知识库 / MCP（可选进阶验证）**：如需验证子 Agent 知识库检索标签 `[子智能体: SQL性能专家]` 或 MCP 工具穿透 `SUBAGENT_MCP`，可为其中一个子 Agent 绑定已向量化的知识库或示例 MCP。
- **系统指令**：
  ```text
  你是一个资深的 MySQL DBA 与数据库内核专家。你的职责是专门针对用户提供的 SQL 语句从全表扫描、索引覆盖、联合索引顺序、排序缓冲等角度进行深入的性能剖析，并给出精确的执行计划建议与建表/索引优化方案。请只聚焦在数据库性能维度。
  ```
- **发布状态**：保存后，在列表行开启 **【发布状态】** 开关（⚠️ 未发布的子 Agent 在运行时会被安全过滤而不挂载）。

#### 子 Agent 2：代码安全审计专家
- **Agent名称**：`代码安全审计专家`
- **Agent介绍**：`专门审计代码漏洞、SQL 注入与越权安全风险`
- **系统指令**：
  ```text
  你是一个专业的 Web 应用安全专家。你的职责是专门针对用户提供的代码和查询逻辑，深入审计是否存在 SQL 注入风险、字符串拼接漏洞、敏感信息泄露等安全隐患，并给出基于参数化查询 (PreparedStatement) 的标准修复方案。请只聚焦在系统安全防御维度。
  ```
- **发布状态**：保存后，在列表行开启 **【发布状态】** 开关。

---

### 2.2 模式 A：纯预置专家并行委派（delegate_specialist）
1. 在 **【Agent管理】** 点击 **【新增】** 创建主控 Agent `架构总控调度员`：
   - **对话模型**：选择推理与 Function Calling 能力较强的模型（如 DeepSeek、GLM 或 GPT-4o 系列）；
   - **系统指令**：
     ```text
     你是一个资深的软件架构总控调度智能体。
     当你面对用户提出的复合技术问题时：
     1. 若任务属于已绑定专家的职责范围，必须委派给对应的专项子智能体并行分析，严禁凭空猜测；
     2. 获取全部子智能体报告后，交叉核对并输出结构化综合评估报告。
     ```
   - **关联 SubAgent**：下拉勾选 `SQL性能专家` 与 `代码安全审计专家`（**交互校验点**：列表中已排除当前主 Agent 自身防自环；超选第 6 个时受 `:multiple-limit="5"` 限制；若有未发布 Agent 会标注 `(未发布)`）；
   - **自主任务拆解** 开关：保持 **关闭**。
2. 保存后开启 **【发布状态】** 开关，点击 **【前往Agent】** 进入公开对话页。
3. 发送以下复合提问：
   ```text
   请评估以下 Java 订单查询代码，从“SQL 执行性能”和“代码安全性”两个维度进行深入分析，并给出最终汇总建议：

   String sql = "SELECT * FROM orders WHERE user_id = " + userId + " ORDER BY create_time DESC";
   ResultSet rs = stmt.executeQuery(sql);
   ```
4. **预期验证点**：
   - 回复过程中出现实时工具状态胶囊 **`SUBAGENT` `delegate_specialist`**；
   - 点击底部 **【全链路追踪】** → **【工具证据链】**，可见 `delegate_specialist` 入参 `tasks` 数组中**一次性并行下发了 2 个子任务**（`agentId` 分别为两位专家的 ID）；
   - 切到 **【时延瀑布图】**，可见 `子智能体 [SQL性能专家] 执行` 与 `子智能体 [代码安全审计专家] 执行` 两条并行 Span。

---

### 2.3 模式 B：纯自主任务拆解（decompose_and_spawn）
1. 修改 `架构总控调度员`（或新建主控 Agent）：
   - **关联 SubAgent**：**清空全部选中项**；
   - **自主任务拆解** 开关：**开启**；
   - （可选）为该主 Agent 绑定已向量化的知识库（如 `三体知识库`），以验证动态瞬态专家对父 Agent 知识库的继承检索。
2. 保存后前往对话页，新建对话并发送一个无预设子 Agent 覆盖的跨维度分析问题：
   ```text
   请从企业架构稳定性、成本预算控制、安全合规治理三个独立视角，深入分析自建私有化大模型知识库平台的关键风险与落地策略，最终汇总成一份执行方案。
   ```
3. **预期验证点**：
   - 工具状态胶囊展示 **`SUBAGENT` `decompose_and_spawn`**；
   - **【工具证据链】** 中可见主 Agent 在无任何预置 SubAgent 的情况下，动态构造 `roleName`、`systemInstruction`、`taskPrompt` 与 `enableRag`，现场裂变出 2~4 个瞬态专家角色并行研究；
   - **【时延瀑布图】** 展示对应的 `瞬态专家 [角色名] 分析` 并行 Span；
   - 若主 Agent 绑定了知识库且检索命中，**【知识库引用】** 页签下对应分片的文档名会带有 `[瞬态专家: 角色名]` 前缀。

---

### 2.4 模式 C：混合双开与运行时路由纠偏（dispatch_subagents）
1. 修改 `架构总控调度员`：
   - **关联 SubAgent**：重新选上 `SQL性能专家` 与 `代码安全审计专家`；
   - **自主任务拆解** 开关：同时保持 **开启**。
2. 保存后在公开对话页新建对话，先后测试两类提示：
   - **混合补位测试**：发送既含已知专长又含空白新领域的问题：
     ```text
     请针对前面那段 Java 订单查询代码，综合评估：① SQL 索引性能隐患；② SQL 注入安全漏洞；③ 高并发下千万级订单表的冷热分离归档与前端埋点监控方案。
     ```
     - **预期结果**：工具胶囊显示 **`SUBAGENT` `dispatch_subagents`**；在单次 `tasks` 调用中同时混编了填写 `agentId` 的预置专家任务，以及填写 `roleName` + `systemInstruction` 的空白领域瞬态专家任务。
   - **路由纠偏验证（可选）**：当大模型误将属于已有专家的职责（例如未填 `agentId` 而是动态起名 `roleName: "SQL优化顾问"`）下发给 `dispatch_subagents` 时，观察 **【实时日志流】** 页签 `subagent` 阶段会出现 `WARN` 日志：
     `[路由纠偏] 动态角色 [SQL优化顾问] 与预置专家 [SQL性能专家 (ID=...)] 职责重叠，已自动重定向至预置专家以启用其专属知识库与工具`。
3. **级联终止验证**：发起复杂协作后在 `正在调用` 期间点击输入框右侧 **【终止】**，确认后续子任务停止推进且不残留悬挂运行态。

---

### 2.5 四维全景验收指标对照表

| 维度 | 验收环节 | 预期现象与指标 |
|---|---|---|
| **1. 实时状态胶囊** | 对话生成中 | 助手气泡顶部依次出现单一协同门面胶囊：<br>• 模式 A：`SUBAGENT` `delegate_specialist`<br>• 模式 B：`SUBAGENT` `decompose_and_spawn`<br>• 模式 C：`SUBAGENT` `dispatch_subagents`<br>• 若子专家调用了自身/继承 MCP，还会同步弹出 `SUBAGENT_MCP` 子工具胶囊 |
| **2. 结果综合汇总** | 最终流式输出 | 主 Agent 将并行分支结论交叉提炼后统一输出，避免生硬拼接原始分段报告（如性能建议 + 安全整改 + 新视角方案形成统一行动项） |
| **3. 全链路 Trace 透视** | 气泡底部 **【全链路追踪】** | • **时延瀑布图**：包含主上下文、大模型推理及并行子 Span（`子智能体 [名称] 执行` / `瞬态专家 [名称] 分析`）<br>• **工具证据链**：`arguments` 展示批处理 `tasks` JSON、`resultSummary` 记录汇总报告、记录真实毫秒耗时<br>• **知识库引用**（若启用）：子专家命中分片文档名自动打标 `[子智能体: 角色名]` 或 `[瞬态专家: 角色名]`<br>• **实时日志流**：完整记录 `subagent` 阶段调度日志与（如有触发）`[路由纠偏]` 告警 |
| **4. 全链路 Token 统计** | Trace 顶部 **Token 消耗** | 顶部 `Token 消耗`（含输入/输出明细）已线程安全汇总主 Agent 多轮推理与全部子智能体消耗；若上游模型接口未回传 `usage` 则附带 `预估` 标签 |

---

## 三、 常见排错与避坑指南

1. **页面报 `Unknown column 'sub_agent_ids'` 或 `Unknown column 'auto_decompose_status'` 错误？**
   - **原因**：存量数据库未按顺序补齐 DDL 补丁。
   - **排查**：依次执行 [patch_20261110_p2_agent_options.sql](../doc/db/patch_20261110_p2_agent_options.sql)、[patch_20261110_p3_agent_subagent.sql](../doc/db/patch_20261110_p3_agent_subagent.sql)、[patch_20261110_p4_auto_decompose.sql](../doc/db/patch_20261110_p4_auto_decompose.sql) 后重启后端。

2. **主 Agent 没有委派子 Agent，而是自己直接回答了？**
   - **原因 1（子 Agent 未发布）**：未将子 Agent 列表行的 **【发布状态】** 开关拨为开启。系统装配时强制过滤 `publish_status != 1` 的关联子 Agent；若全部未发布且未开启 `自主任务拆解`，主 Agent 将不会挂载任何协同门面工具。
   - **原因 2（跨空间）**：主 Agent 与子 Agent 必须归属于同一个业务空间。
   - **原因 3（模型工具调用能力弱）**：部分极小参数模型对复合 JSON Schema 参数生成不稳定，建议为主 Agent 选用主流支持 Tool Calling 的模型并在系统指令明确要求先调用协同工具。

3. **子 Agent 返回 `[委派失败]` 或 `[子智能体处理失败]`？**
   - **原因**：检查子 Agent 所绑定的模型供应商 API Key 是否有效、网络是否通畅、或者绑定的模型是否为对话模型（`type=0`）。Trace 抽屉的 **【实时日志流】** 会打印具体异常。

4. **【历史已修复缺陷回归说明】配置超参数后报 `400: 模型不存在 / gpt-5-mini`？**
   - **背景**：早期版本因 Spring AI `OpenAiChatOptions.builder()` 未显式设置 `.model(...)` 会默认回退 `"gpt-5-mini"`，导致请求第三方网关报错 400。
   - **当前状态**：已在 [`ChatStreamTool.buildChatOptions`](../xxl-ai-api/src/main/java/com/xxl/ai/api/business/harness/chat/ChatStreamTool.java#L955-L988) 与 `SubAgentTool` 中显式绑定真实 `modelName`。正常情况下第 [1.2 节](#12-对话与-trace-注入日志验证) 验证时不应再复现该报错；若自测中仍见 `"gpt-5-mini"` 报错，请立即核查后端编译产物是否为最新提交版本。
