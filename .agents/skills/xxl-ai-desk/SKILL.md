---
name: xxl-ai-desk
description: 在 XXL-AI 本地版（Desk 桌面客户端，xxl-ai-desk，Electron + Vue3 + Pi + SQLite/Drizzle 三进程架构）下新增或改造业务模块。当任务涉及修改 xxl-ai-desk/src/main（services/db/agent/ipc）、xxl-ai-desk/src/shared、xxl-ai-desk/src/preload 或 xxl-ai-desk/src/renderer/src（stores/modules/components/i18n）时加载本技能。
---

# XXL-AI-Desk · 本地版开发 Skill

Desk 是 **Electron + Vue3 + Pi + SQLite/Drizzle** 的三进程桌面应用；本 Skill 定义其**代码架构、约束原则与落位方式**。

> 仅适用于本地版 `xxl-ai-desk`。云版（`xxl-ai-api` + `xxl-ai-ui`）请加载 `xxl-ai` Skill，二者互不依赖。

## 1. 代码架构

### 1.1 三进程

```
Renderer（Vue3 + Element Plus，无 Node 直连）
  stores / modules / components / i18n
        │ preload 白名单（window.desk.*）
Main（Electron 主进程，CJS）
  ipc.ts（薄网关） · services/*（业务） · db/* · config/*
  agent/host/*（运行时托管 + 审批）
        │ utilityProcess + parentPort（shared/agentProtocol.ts）
Agent 运行时进程（CJS）
  agent/worker/{worker,session,tools,sandbox,models,mcp}
```

### 1.2 目录

```
xxl-ai-desk/src/
├── main/
│   ├── index.ts                 入口：DB 初始化 → 种子 → IPC → 建窗
│   ├── ipc.ts                   全部 IPC handler（薄网关）
│   ├── config/paths.ts          数据目录 / DB 路径（基础设施）
│   ├── db/{schema.ts,index.ts}  Drizzle 表结构 / better-sqlite3 + DDL + migrate
│   ├── services/*Service.ts     业务 CRUD 与编排（唯一 getDb 出口）
│   └── agent/
│       ├── host/                主进程侧：runtimeHost / approval / community（社区抓取）
│       └── worker/              运行时进程侧：worker / session / tools / sandbox / models / mcp
├── preload/index.ts             contextBridge 白名单
├── shared/
│   ├── ipc.ts                   聚合：re-export contracts + IPC 常量 + DeskApi
│   ├── contracts/*.ts           按域 DTO（app/provider/project/session/chat/terminal/fs/shell/mcp）
│   └── agentProtocol.ts         主进程 ⇄ 运行时进程协议
└── renderer/src/
    ├── api/index.ts             唯一能力入口：export const api = window.desk
    ├── stores/*.ts              Pinia（状态 + 调 api）
    ├── modules/{domain}/{module}/{pages,components,composables}
    ├── components/              仅跨模块通用件
    ├── composables / utils      含响应式 / 纯函数
    ├── router/index.ts          Hash 路由
    └── i18n/locales/{zh,en}.json
```

### 1.3 分层与依赖（硬约束）

| 层 | 允许依赖 | 禁止 |
|---|---|---|
| `services/*` | `db`、`config`、shared 契约、`agent/host` 门面 | import `agent/worker/*`；自写协议/SDK；在 `ipc.ts` 写业务 |
| `agent/host/*` | electron、services（读配置）、shared 契约 | 定义 `ipcMain` handler；写业务表 |
| `agent/worker/*` | shared 契约、官方运行时库 | 访问 DB、写业务状态、做业务校验 |
| `ipc.ts` | services / host 门面 | 业务编排、连接管理、SQL |
| `preload` | shared 契约 | 业务逻辑、暴露任意 Node 能力 |
| `renderer` | `@renderer/api`、本模块内部 | 散落 `window.desk`、跨模块 import 内部文件 |

判定：`services` import `agent/worker` 违规；组件里出现 `spawn / 协议 / DB` 违规；反向依赖一律违规。

补充：

- **唯一持久化出口**：`getDb()` 只在 `services/*`；`agent/*` 可经 service 读配置，不写业务表。
- **job 编排在 service**：如对话编排在 `services/chatService.ts`，`ipc.ts` 只注册与转发。
- **基础设施独立**：路径解析在 `config/paths.ts`，`db` 不反向依赖 `services`。
- **agent 按进程分层**：host 可用 electron API；worker 仅用 `electron-log` 等运行时能力；跨进程只走 `shared/agentProtocol.ts`。
- **运行时门面**：需要运行时能力时，由 `services/{module}RuntimeService.ts` 编排 → `agent/host` 门面 → worker；配置模块不 import 运行时实现。

### 1.4 模块边界（硬约束）

- **一个功能 = 一个模块**：后端 service、契约、IPC、preload、store、页面/组件、i18n、SQL、需求目录同属该模块。
- **只暴露公共 API**：跨模块只用 store 公开方法/状态与通用组件；禁止 import 其他模块的 `pages/`、内部 `components/`、内部 `utils/`。
- **工具上移条件**：`utils / composables / components` 至少被 2 个模块使用才上移；否则留在模块内。
- **禁止垃圾桶文件**：`utils.ts / helpers.ts / common.ts / manager.ts` 不得承载多领域逻辑，文件名必须表达领域语义。
- **闭环检验**：改一个功能应只动「模块目录 + 其契约」；若需动 3 个以上模块，先重划边界。
- **适配器扩展**：多实现场景用「接口 + 注册表」，新增实现只加文件、不改调用方。

## 2. 代码约束

> **[硬]** 违反打回；**[软]** 为评审阈值，超限需说明理由。
> 取值参考：ESLint `max-lines`、SonarQube 默认阈值、Vue 官方风格指南、Node.js 最佳实践。

### 2.1 契约与数据 [硬]

- 跨进程只传 `shared/contracts/{domain}.ts` 的 DTO；禁止 `any` / 匿名对象穿透 IPC。
- 通道命名 `desk:{module}:{action}`；时间统一 ISO；DTO 布尔由 INTEGER 归一。
- 只传可结构化克隆的纯数据：不传 Error、类实例、Vue 响应式代理；必要时深拷贝。
- **单一数据源**：同一配置只有一处权威存储；派生字段保存时统一重生，不允许两处并存。
- **写库结构必须合法**：写入前保证下游可消费；历史脏数据在读取边界归一化。
- 结构变更三处同步：`db/schema.ts`、`db/index.ts` 的 `DDL`、`migrate()`。
- 敏感值不落明文、不进日志；列表脱敏，编辑态才解密。

### 2.2 运行时与资源 [硬]

- **生命周期闭环**：创建/连接 → 缓存复用 → 失效重建（配置变更）→ 释放（重置/退出）；`close` 幂等。
- **超时 + 取消**：连接、请求、工具调用、命令执行均支持超时与 `AbortSignal`；终止后不得继续执行。
- **失败隔离**：单个外部服务失败只跳过自身并记录可定位日志，不阻断主流程。
- **副作用集中**：配置变更后的缓存失效（如 `resetAgents()`）在一个入口统一完成。
- **官方方案优先**：有官方/上游 SDK 或推荐接入方式时禁止自写协议。例：MCP 使用 `@earendil-works/pi-mcp`；模型/Agent 使用 `@earendil-works/pi-ai`、`pi-agent-core`。

### 2.3 UI 与样式 [硬]

- `AppTopbar` / `Sidebar` 只做外壳与路由上下文，域逻辑下沉 `modules/{module}`。
- 颜色/尺寸只用 tokens；组件样式 scoped；全局选择器（`:global`、`:has`、`body ...`）必须写明影响面并验证。
- 弹层统一 `append-to-body`，并处理 `-webkit-app-region` 拖拽区，避免关闭按钮点击被窗口拖动吞掉。
- 文案一律 `t()`，zh/en 成对；通用词复用 `common.*`。
- 列表/表格复用统一规范（字号、列宽、操作按钮间距），禁止各页面自行一套。

### 2.4 可读性与规模 [软]

- 文件顶部一句话职责；注释解释「为什么」；沿用 `/* */` 风格。
- 命名表达领域语义，禁止拼音/无意义缩写；布尔值不使用 `isXxx`。
- 早返回代替深层嵌套；边界处禁止 `any`，强制转换必须紧邻注释说明原因。
- 禁止死代码、调试残留、注释掉的旧实现、临时测试脚本。

| 对象 | 阈值 | 依据 |
|---|---|---|
| 函数 | ≤50 行（硬告警 75）；参数 ≤4（硬告警 7）；嵌套 ≤3；认知复杂度 ≤15 | SonarQube |
| Vue 组件 | ≤300 行；页面 SFC ≤500 行 | Vue 风格指南 |
| service | ≤500 行 / 导出方法 ≤15 | 模块宽度控制 |
| 单文件 TS | ≤500 行（新增代码按 300 行执行） | ESLint `max-lines` |
| 模块目录 | ≤20 个文件 | 边界控制 |

超限必须回答：**能否按职责拆出新文件/新模块？**

### 2.5 反例（真实踩坑）

- 配置模块直接连接 MCP / 实现协议 → 配置与运行时耦合。
- 失败轮次写入缺字段的消息 → 下一轮 `message.content is not iterable`。
- 全局 `:has(.el-overlay)` → 顶栏拖拽永久失效。
- 加载遮罩未限制定位上下文 → 抽屉关闭按钮被遮罩吞点击。
- 只做程序化点击测试 → 漏掉真实窗口拖拽区问题。

## 3. 开发流程

1. **需求确认（必须）**：模块命名、字段与校验、系统能力（对话框/shell/终端/文件）、页面形态、改动范围、验证范围；确认后写入需求目录的 `方案.md`。
2. **需求落盘**：`xxl-ai-spec/{yyyyMMdd}-{module}/` 建 `方案.md` 与建表 SQL（SQLite DDL）。
3. **数据结构**：同步 `db/schema.ts` + `db/index.ts` 的 `DDL` / `migrate()`。
4. **主进程落位**：`services/{module}Service.ts` + 契约 + IPC + preload；需要运行时再加 `{module}RuntimeService.ts` 与 `agent/*` 实现。
5. **渲染进程落位**：store + 页面/组件 + 路由 + i18n。
6. **验证**：`npm run type-check` → `npm run build` → 必要时 `npm run dev` 联调；涉及 IPC/运行时/生命周期必须做端到端验证（真实 UI + 真实调用 + 进程残留检查），完成后清理测试数据并恢复用户状态。

`方案.md` 最小结构：需求结论 / 数据库设计 / 后端改动 / 前端改动 / 验证结果。

## 4. 落位清单

### 4.1 主进程 6 件套（业务 `Widget` → `widget`）

| 文件 | 位置 | 说明 |
|---|---|---|
| `schema.ts` | `src/main/db/schema.ts` | `sqliteTable('desk_widget', {...})` |
| `index.ts` | `src/main/db/index.ts` | DDL 建表；新列在 `migrate()` 用 `ensureColumn` |
| `WidgetService.ts` | `src/main/services/widgetService.ts` | CRUD/编排；唯一 `getDb()` 出口 |
| handler | `src/main/ipc.ts` | `ipcMain.handle(IPC.widget*, ...)`，薄网关 |
| 契约 | `src/shared/contracts/widget.ts` + `src/shared/ipc.ts` | DTO + `IPC.widget*` + `DeskApi.widget` |
| 暴露 | `src/preload/index.ts` | `widget: { list, save, remove }` |

```ts
/* schema.ts */
export const widgetTable = sqliteTable('desk_widget', {
  id: text('id').primaryKey(),
  name: text('name').notNull().default(''),
  enabled: integer('enabled').notNull().default(1),
  addTime: text('add_time').notNull().default(''),
  updateTime: text('update_time').notNull().default('')
})
```

```ts
/* widgetService.ts：行 → DTO、list/save/delete；save 以 onConflictDoUpdate 幂等写入 */
function toDTO(row: WidgetRow): WidgetDTO {
  return { id: row.id, name: row.name, enabled: row.enabled === 1,
    addTime: row.addTime, updateTime: row.updateTime }
}
export function saveWidget(input: Partial<WidgetDTO>): WidgetDTO {
  const db = getDb()
  const now = new Date().toISOString()
  const id = input.id || randomUUID()
  const existing = db.select().from(widgetTable).where(eq(widgetTable.id, id)).get()
  const record = { id, name: input.name ?? existing?.name ?? '',
    enabled: (input.enabled ?? (existing ? existing.enabled === 1 : true)) ? 1 : 0,
    addTime: existing?.addTime ?? now, updateTime: now }
  db.insert(widgetTable).values(record)
    .onConflictDoUpdate({ target: widgetTable.id, set: record }).run()
  return toDTO(record)
}
```

```ts
/* ipc.ts（若影响运行时，在 handler 内统一 resetAgents()/evictAgent()） */
ipcMain.handle(IPC.widgetList, () => listWidgets())
ipcMain.handle(IPC.widgetSave, (_e, dto: Partial<WidgetDTO>) => saveWidget(dto))
ipcMain.handle(IPC.widgetRemove, (_e, id: string) => deleteWidget(id))
```

```ts
/* shared/contracts/widget.ts + IPC/DeskApi 片段 */
export interface WidgetDTO { id: string; name: string; enabled: boolean; addTime: string; updateTime: string }
// IPC: widgetList: 'desk:widget:list' ...
```

### 4.2 渲染进程 4 件套

| 文件 | 位置 | 说明 |
|---|---|---|
| store | `src/renderer/src/stores/{module}.ts` | Pinia；只经 `api.{module}` 访问能力 |
| 页面/组件 | `src/renderer/src/modules/{domain}/{module}/{pages,components}` | 模块自包含 |
| 路由 | `src/renderer/src/router/index.ts` | 新页面登记（Hash） |
| i18n | `src/renderer/src/i18n/locales/{zh,en}.json` | 成对新增 |

```ts
export const useWidgetStore = defineStore('widget', () => {
  const widgets = ref<WidgetDTO[]>([])
  const loaded = ref(false)
  async function loadWidgets(): Promise<void> {
    widgets.value = await api.widget.list()
    loaded.value = true
  }
  async function removeWidget(id: string): Promise<void> {
    await api.widget.remove(id)
    widgets.value = widgets.value.filter((item) => item.id !== id)
  }
  return { widgets, loaded, loadWidgets, removeWidget }
})
```

要点：

- 能力访问统一 `import { api } from '@renderer/api'`；禁止直接 `window.desk`。
- 引用优先别名：`@renderer/*`、`@shared/*`；禁止 3 层以上 `../`。
- `utils/*` 无 Vue 依赖；`composables/*` 含响应式；单模块专用不放公共目录。
- 组件 PascalCase；文案 `t()`，禁止硬编码中文（注释除外）。
- i18n 单一文件，语言由设置驱动（不支持运行时切换），zh/en 成对。

## 5. 界面外壳

```
desk-shell（纵向）
├─ AppTopbar   全局顶栏（固定最顶部，整体 -webkit-app-region: drag）
└─ desk-body（横向）
   ├─ Sidebar  活动栏（常驻）+ 子菜单栏（可折叠/可拖拽）
   └─ desk-main 正文
```

- **导航单一来源**：板块 ↔ 入口路径 ↔ 顶栏标题 ↔ 子菜单项均在 `config/nav.ts`；新增板块只改此处（ActivityRail 加按钮 + 注册路由 + 正文页）。
- **顶栏**：标题/操作随路由（首页=会话标题，插件=「插件」，设置=「系统设置」）；标题与 NavControls 间的细线仅在有标题时展示；交互控件 `no-drag`。
- **子菜单**：标题 18px/600，条目不超 14px；插件子菜单名加粗、设置常规；不展示 Logo；点击当前板块按钮切换子菜单栏折叠/展开，图标栏常驻。
- **shell 不承载域逻辑**：域内逻辑下沉 `modules/{module}`。
- **面板交互统一复用**：拖拽用 `composables/usePanelResize.ts`（edge：左右缘/上下缘）；尺寸用 `utils/persistedWidth.ts#createPersistedWidth`（读取/夹取/持久化/复位），左侧栏、右侧栏、底部终端共用。
- **共享选中态归属域**：跨「子菜单 ↔ 页面」的选中态放所属域 store（如 `settings.activeTab`），不塞进 `layout`。
- **配色分层**：`--desk-chrome-bg`（顶栏+图标栏）> `--desk-panel-bg`（子菜单栏）> `--desk-bg`（正文）；不加分隔线，仅「子菜单栏 ↔ 正文」保留 1px `--desk-border`。
- **尺寸令牌**：`--desk-header-height`、`--desk-rail-width`；自绘 SVG 统一 `.desk-svg-icon`，尺寸由使用处覆盖。

## 6. Agent 运行时

新增 Agent 能力落 `src/main/agent/`，不改 IPC；按进程归属落位。

| 需求 | 落位 | 要点 |
|---|---|---|
| 内置工具 | `agent/worker/tools.ts` | `createBuiltinTools()` 用 `Type.Object` 定义 `AgentTool`；只读工具常驻，写/命令类仅 build |
| 文件类工具 | `tools.ts` + `sandbox.ts` | 必须先过 `guardPath({ sessionId, rootDir, target, action, tool })`，越界返回拦截错误 |
| 越界审批 | `sandbox.ts` + `host/runtimeHost.ts` + `host/approval.ts` | worker 经 `parentPort` 发 `approval-request`，主进程弹窗回传 `once/session/deny` |
| MCP | `agent/worker/mcp/{client,tools}.ts` | 使用 Pi 官方 `@earendil-works/pi-mcp`；工具转换 `toLlmContent` + `Type.Unsafe` + `signal` |
| 会话装配 | `agent/worker/session.ts` | 会话级 Agent 缓存 + 事件流 + 工具合并 |
| 协议 | `shared/agentProtocol.ts` | 改 `HostEvent`/`WorkerRequest` 需同步 worker.ts、session.ts、runtimeHost.ts |
| 模型 | `agent/worker/models.ts` | `normalizeBaseUrl`、`buildModels`、`{session}` 占位替换 |

工具结果 `content` 为文本块数组；失败用 `isError: true`。新增工具必须自测越界拦截与 Plan 模式不可见。

## 7. 校验清单

- [ ] 需求目录 `xxl-ai-spec/{yyyyMMdd}-{module}/` 存在，`方案.md` 与 SQL 同步。
- [ ] 结构三处一致：`schema.ts` / `DDL` / `migrate()`。
- [ ] `services/*` 是唯一 `getDb()` 出口；`ipc.ts` 仅网关；契约 / IPC 常量 / `DeskApi` / preload 齐备。
- [ ] 依赖方向符合 §1.3；配置与运行时未耦合；运行时使用官方方案。
- [ ] 通道 `desk:{module}:{action}`；时间 ISO；布尔归一。
- [ ] 渲染仅经 `api`；路由已登记；文案 `t()` 且 zh/en 成对；无硬编码中文。
- [ ] UI 复用 tokens/外壳/列表规范；弹层 append-to-body 且拖拽区可用；全局样式已验证影响面。
- [ ] 资源生命周期闭环；超时/取消/失败隔离/进程无残留。
- [ ] `npm run type-check`、`npm run build` 通过；涉及 IPC/运行时完成端到端验证。
- [ ] 注释与命名符合既有风格；无死代码/调试残留/测试数据。

## 8. 参考文件

- 主进程：`services/{provider,project,session,chat,mcp}Service.ts`、`config/paths.ts`、`db/{schema,index}.ts`
- 契约与网关：`shared/ipc.ts`、`shared/contracts/*`、`main/ipc.ts`、`preload/index.ts`
- 运行时：`agent/host/{runtimeHost,approval}.ts`、`agent/worker/{worker,session,tools,sandbox,models}.ts`、`agent/worker/mcp/*`、`shared/agentProtocol.ts`
- 渲染：`api/index.ts`、`stores/{project,settings,chat,mcp}.ts`、`modules/chat/*`、`modules/plugin/*`、`modules/settings/pages/index.vue`
- 构建：`package.json`（`type-check` / `build` / `dev`）、`electron.vite.config.ts`
