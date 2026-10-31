# XXL-AI 脚手架使用与开发规范

XXL-AI 以 Monorepo 托管「Spring Boot 后端 + Vue3 前端 + Electron 桌面端」。按运行模式加载对应 Skill：**模式专属的流程、结构、落位、命令与校验一律在 Skill 内**，本文件只保留跨模式通用的原则、约定与索引。

## 一、项目概览

| 模块 | 说明 |
|---|---|
| `xxl-ai-api` | 后端 API（Spring Boot），端口 8080，SSO 登录态存 Redis；部署期前端产物内嵌于此，单包单端口对外 |
| `xxl-ai-ui` | Vue3 前端（Element Plus + TypeScript + Vite），开发端口 3000（Hash 路由） |
| `xxl-ai-sample` | 示例 MCP 服务（spring-ai `@McpTool`，Streamable HTTP），端口 8091 |
| `xxl-ai-desk` | 本地版桌面客户端（Electron + Vue3 + Pi + SQLite/Drizzle） |
| `doc/db` | 数据库初始化脚本（`xxl_ai`：用户/配置/审计日志等框架表与种子数据） |
| `docker` | 一键部署栈（mysql + redis + milvus(etcd/minio/attu) + api(内嵌前端) + sample） |

通用依赖：`xxl-tool`（工具与统一响应，经 `xxl-sso-core` 传递）、`xxl-sso`（`@XxlSso` 鉴权）、MyBatis（Mapper + XML）、MySQL、Redis、spring-ai（OpenAI 兼容模型 / Milvus / MCP SDK）。

## 二、Skill 索引

| 模式 | Skill | 覆盖范围 |
|---|---|---|
| 前后端分离（云版） | `.agents/skills/xxl-ai/SKILL.md` | `xxl-ai-api` + `xxl-ai-ui`：环境与命令、工程结构、business/harness 边界、落位清单与骨架、菜单/枚举、数据库、前端与 i18n 落位、校验清单 |
| 本地版（Desk） | `.agents/skills/xxl-ai-desk/SKILL.md` | `xxl-ai-desk`：三进程架构、依赖方向、契约与 IPC、落位模板、界面外壳、Agent 运行时、校验清单 |

两者互不依赖。新增/改造功能前先加载匹配的 Skill，按其标准流程作业；两边的规则冲突时以对应 Skill 为准。

## 三、设计原则

- **DRY，但只在必要时抽象**：同一段逻辑出现**第三次**再抽公共实现；两处相似但边界不同的代码可以保留。禁止为「将来复用」预建抽象层。
- **KISS 优先**：先写最直接的实现，能删就删。禁止非必要的接口/工厂/配置项/包装函数，禁止为假想需求预留扩展点。
- **单一职责**：一个文件、函数、组件只承担**一个变更理由**；渲染、消毒、交互、状态等边界一旦混在一起就拆开，拆到能一句话描述职责为止。
- **同类场景保持同一套方案**：已有工具/组件能覆盖就不另起一套；新方案必须说明旧方案为何不适用。

## 四、通用约定

- 使用中文沟通、中文注释。
- 命名表达真实语义：类名大驼峰、变量/方法小驼峰、常量全大写下划线；避免无意义单字母与拼音；布尔属性不用 `isXxx` 前缀（避免与 getter 冲突）。
- 注释：文件顶部一行功能描述 + 空行 + `@author 作者 yyyy-mm-dd`；方法用 `/* xxx */` 多行注释；属性注释右侧 `/* xxx */` 垂直对齐；分支逻辑需注释；无死代码与调试残留。
- **依赖版本一律固定**：`package.json` / `pom.xml` 写精确版本（如 `"yaml": "2.9.1"`、`<xxl-sso.version>2.4.1</xxl-sso.version>`），禁止 `^` / `~` / `*` / 区间；新增或升级依赖须同步 lock（`package-lock.json` 根 `packages[""]` 同样精确），上游传递依赖由 lock 固化。

## 五、国际化（通用）

- 用户可见文案一律经 `t()` 引用，**禁止硬编码中文**（中文注释除外）。
- 新增文案 zh/en **成对**提交；缺失键回退中文，再回退 key。
- 通用词复用公共词条 `common.*`；模块特有词按 `{domain}.{module}.*`（云版）或模块节点（Desk）组织。
- 插值用 `t('key', [v])`（`{0}` 下标）或 `t('key', { name })`（`{name}`），禁止字符串拼接。
- 语言由配置决定、**不支持运行时切换**；文件位置、节点顺序与模块分组见对应 Skill。

## 六、验收与提交

- 验证命令与检查项以对应 Skill 的「校验清单」为准。
- 只提交任务相关文件；不提交 `target` / `dist` / `node_modules` 等产物。
- 提交信息简洁、符合仓库风格。
