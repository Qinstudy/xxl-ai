/*
 * Drizzle 表结构定义：与 db/index.ts 的建表 DDL 一一对应。
 * 字段 camelCase 映射到数据库下划线列名。
 */

import { sqliteTable, text, integer } from 'drizzle-orm/sqlite-core'

/* 供应商表：一个供应商对应一个 OpenAI 兼容端点与一组模型 */
export const providerTable = sqliteTable('desk_provider', {
  id: text('id').primaryKey(),
  name: text('name').notNull(),
  baseUrl: text('base_url').notNull().default(''), /* OpenAI 兼容端点地址 */
  apiKey: text('api_key').notNull().default(''), /* 密钥（编码后存储） */
  headers: text('headers').notNull().default('{}'), /* 自定义请求头 JSON */
  models: text('models').notNull().default('[]'), /* 模型 ID 列表 JSON */
  enabled: integer('enabled').notNull().default(1), /* 1 启用 / 0 停用 */
  sort: integer('sort').notNull().default(0), /* 列表排序值 */
  addTime: text('add_time').notNull().default(''),
  updateTime: text('update_time').notNull().default('')
})

/* 项目表：1:1 强绑定本地磁盘目录，会话归属项目之下 */
export const projectTable = sqliteTable('desk_project', {
  id: text('id').primaryKey(),
  name: text('name').notNull().default(''),
  path: text('path').notNull().default(''), /* 绑定的本地目录绝对路径 */
  addTime: text('add_time').notNull().default(''),
  updateTime: text('update_time').notNull().default('')
})

/* 会话表：一次会话绑定一个供应商与模型，并保存对话模式；归属某项目 */
export const sessionTable = sqliteTable('desk_session', {
  id: text('id').primaryKey(),
  title: text('title').notNull().default(''),
  projectId: text('project_id').notNull().default(''), /* 归属项目 ID */
  providerId: text('provider_id').notNull().default(''), /* 绑定供应商 ID */
  modelId: text('model_id').notNull().default(''), /* 绑定模型 ID */
  mode: text('mode').notNull().default('build'), /* 对话模式 plan / build */
  addTime: text('add_time').notNull().default(''),
  updateTime: text('update_time').notNull().default('')
})

/* 消息表：按会话顺序保存消息，data 为 Agent 原始消息 JSON */
export const messageTable = sqliteTable('desk_message', {
  id: text('id').primaryKey(),
  sessionId: text('session_id').notNull(),
  seq: integer('seq').notNull().default(0), /* 会话内顺序号（升序） */
  role: text('role').notNull().default(''),
  content: text('content').notNull().default(''), /* 归一化纯文本（列表预览） */
  data: text('data').notNull().default(''), /* Agent 原始消息 JSON */
  addTime: text('add_time').notNull().default('')
})

/* 应用设置表：key-value 形式保存主题、语言、当前模型等 */
export const settingTable = sqliteTable('desk_setting', {
  key: text('key').primaryKey(), /* 设置项键名 */
  value: text('value').notNull().default('') /* 设置项值（对象以 JSON 存储） */
})

/* SKILL 技能表：目录内容为权威，本表保存启停、来源等可变状态 */
export const skillTable = sqliteTable('desk_skill', {
  id: text('id').primaryKey(), /* 技能目录名 */
  name: text('name').notNull().default(''), /* 技能名（frontmatter） */
  description: text('description').notNull().default(''), /* 技能描述 */
  version: text('version').notNull().default(''), /* 版本（可选） */
  source: text('source').notNull().default('local'), /* local / skillssh / github */
  sourceId: text('source_id').notNull().default(''), /* owner/repo/slug 统一标识 */
  enabled: integer('enabled').notNull().default(1), /* 1 启用 / 0 停用 */
  addTime: text('add_time').notNull().default(''),
  updateTime: text('update_time').notNull().default('')
})

/* MCP 服务表：远程（Streamable HTTP）/ 本地（stdio），开启后参与对话 */
export const mcpTable = sqliteTable('desk_mcp', {
  id: text('id').primaryKey(),
  name: text('name').notNull().default(''), /* MCP 名称（工具名前缀） */
  type: text('type').notNull().default('remote'), /* remote 远程 / local 本地 */
  transport: text('transport').notNull().default('streamable-http'), /* streamable-http / stdio */
  url: text('url').notNull().default(''), /* 远程服务地址 */
  headers: text('headers').notNull().default('{}'), /* 远程请求头 JSON（值编码存储） */
  command: text('command').notNull().default(''), /* 本地命令 */
  args: text('args').notNull().default('[]'), /* 本地命令参数 JSON 数组 */
  env: text('env').notNull().default('{}'), /* 本地环境变量 JSON（值编码存储） */
  cwd: text('cwd').notNull().default(''), /* 本地工作目录 */
  config: text('config').notNull().default('{}'), /* 规范配置 JSON */
  remark: text('remark').notNull().default(''), /* 备注 */
  enabled: integer('enabled').notNull().default(0), /* 1 生效 / 0 停用 */
  health: text('health').notNull().default(''), /* 最近检测结果 JSON */
  addTime: text('add_time').notNull().default(''),
  updateTime: text('update_time').notNull().default('')
})

/* Agent 记忆表：按项目维度保存，一个项目一条，异步压缩生成后覆盖 */
export const agentMemoryTable = sqliteTable('desk_agent_memory', {
  id: text('id').primaryKey(),
  projectId: text('project_id').notNull().default(''), /* 归属项目 ID（唯一） */
  content: text('content').notNull().default(''), /* 记忆正文（Markdown，固定四段，≤2000 字） */
  lastMessageTime: text('last_time').notNull().default(''), /* 上次生成纳入的最后一条消息时间（项目级游标） */
  addTime: text('add_time').notNull().default(''),
  updateTime: text('update_time').notNull().default('')
})

/* 模型通讯 trace 表：仅开发者模式开启时写入，一次模型请求一条 */
export const llmTraceTable = sqliteTable('desk_llm_trace', {
  id: text('id').primaryKey(),
  sessionId: text('session_id').notNull().default(''), /* 归属会话 ID */
  runId: text('run_id').notNull().default(''), /* 一轮对话的运行标识 */
  step: integer('step').notNull().default(0), /* 本轮第几次模型调用 */
  providerId: text('provider_id').notNull().default(''), /* 供应商 ID */
  modelId: text('model_id').notNull().default(''), /* 模型 ID */
  phase: text('phase').notNull().default('chat'), /* chat 对话 / memory 记忆生成 */
  status: text('status').notNull().default('success'), /* success / error / aborted */
  httpStatus: integer('http_status').notNull().default(0), /* HTTP 状态码 */
  durationMs: integer('duration_ms').notNull().default(0), /* 请求到响应耗时 */
  inputTokens: integer('input_tokens').notNull().default(0),
  outputTokens: integer('output_tokens').notNull().default(0),
  reasoningTokens: integer('reasoning_tokens').notNull().default(0),
  request: text('request').notNull().default(''), /* 请求体 JSON（已截断） */
  response: text('response').notNull().default(''), /* 响应体 JSON（已截断） */
  toolCalls: text('tool_calls').notNull().default('[]'), /* 工具调用明细 JSON */
  error: text('error').notNull().default(''), /* 错误信息 */
  addTime: text('add_time').notNull().default('')
})
