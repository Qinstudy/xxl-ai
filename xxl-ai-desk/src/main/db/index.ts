/*
 * 本地数据库初始化：better-sqlite3 + Drizzle，桌面端单文件库、无需外部服务。
 *   - 打开数据库后执行建表 DDL，并写入内置 MCP 种子。
 */

import { mkdirSync } from 'fs'
import { homedir } from 'os'
import { dirname } from 'path'
import Database from 'better-sqlite3'
import { drizzle, type BetterSQLite3Database } from 'drizzle-orm/better-sqlite3'
import { getDbFile, getSkillsDir } from '../config/paths'
import * as schema from './schema'

/* SQLite 建表脚本：桌面端本地库，无需外部服务 */
const DDL = `
CREATE TABLE IF NOT EXISTS desk_provider (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL DEFAULT '',
  base_url    TEXT NOT NULL DEFAULT '',
  api_key     TEXT NOT NULL DEFAULT '',
  headers     TEXT NOT NULL DEFAULT '{}',
  models      TEXT NOT NULL DEFAULT '[]',
  enabled     INTEGER NOT NULL DEFAULT 1,
  sort        INTEGER NOT NULL DEFAULT 0,
  add_time    TEXT NOT NULL DEFAULT '',
  update_time TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS desk_project (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL DEFAULT '',
  path        TEXT NOT NULL DEFAULT '',
  add_time    TEXT NOT NULL DEFAULT '',
  update_time TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS desk_session (
  id            TEXT PRIMARY KEY,
  title         TEXT NOT NULL DEFAULT '',
  project_id    TEXT NOT NULL DEFAULT '',
  provider_id   TEXT NOT NULL DEFAULT '',
  model_id      TEXT NOT NULL DEFAULT '',
  mode          TEXT NOT NULL DEFAULT 'build',
  add_time      TEXT NOT NULL DEFAULT '',
  update_time   TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS i_desk_session_project ON desk_session (project_id);
CREATE TABLE IF NOT EXISTS desk_message (
  id         TEXT PRIMARY KEY,
  session_id TEXT NOT NULL,
  seq        INTEGER NOT NULL DEFAULT 0,
  role       TEXT NOT NULL DEFAULT '',
  content    TEXT NOT NULL DEFAULT '',
  data       TEXT NOT NULL DEFAULT '',
  add_time   TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS i_desk_message_session ON desk_message (session_id, seq);
CREATE TABLE IF NOT EXISTS desk_setting (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS desk_mcp (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL DEFAULT '',
  type        TEXT NOT NULL DEFAULT 'remote',
  transport   TEXT NOT NULL DEFAULT 'streamable-http',
  url         TEXT NOT NULL DEFAULT '',
  headers     TEXT NOT NULL DEFAULT '{}',
  command     TEXT NOT NULL DEFAULT '',
  args        TEXT NOT NULL DEFAULT '[]',
  env         TEXT NOT NULL DEFAULT '{}',
  cwd         TEXT NOT NULL DEFAULT '',
  config      TEXT NOT NULL DEFAULT '{}',
  remark      TEXT NOT NULL DEFAULT '',
  enabled     INTEGER NOT NULL DEFAULT 0,
  health      TEXT NOT NULL DEFAULT '',
  add_time    TEXT NOT NULL DEFAULT '',
  update_time TEXT NOT NULL DEFAULT ''
);
CREATE UNIQUE INDEX IF NOT EXISTS i_desk_mcp_name ON desk_mcp (name);
CREATE TABLE IF NOT EXISTS desk_skill (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL DEFAULT '',
  description TEXT NOT NULL DEFAULT '',
  version     TEXT NOT NULL DEFAULT '',
  source      TEXT NOT NULL DEFAULT 'local',
  source_id   TEXT NOT NULL DEFAULT '',
  enabled     INTEGER NOT NULL DEFAULT 1,
  add_time    TEXT NOT NULL DEFAULT '',
  update_time TEXT NOT NULL DEFAULT ''
);
CREATE UNIQUE INDEX IF NOT EXISTS i_desk_skill_source_id ON desk_skill (source_id) WHERE source_id <> '';
CREATE INDEX IF NOT EXISTS i_desk_skill_enabled ON desk_skill (enabled);
CREATE TABLE IF NOT EXISTS desk_agent_memory (
  id              TEXT PRIMARY KEY,
  project_id      TEXT NOT NULL DEFAULT '',
  content         TEXT NOT NULL DEFAULT '',
  last_time       TEXT NOT NULL DEFAULT '',
  add_time        TEXT NOT NULL DEFAULT '',
  update_time     TEXT NOT NULL DEFAULT ''
);
CREATE UNIQUE INDEX IF NOT EXISTS i_desk_agent_memory_project ON desk_agent_memory (project_id);
CREATE TABLE IF NOT EXISTS desk_llm_trace (
  id               TEXT PRIMARY KEY,
  session_id       TEXT NOT NULL DEFAULT '',
  run_id           TEXT NOT NULL DEFAULT '',
  step             INTEGER NOT NULL DEFAULT 0,
  provider_id      TEXT NOT NULL DEFAULT '',
  model_id         TEXT NOT NULL DEFAULT '',
  phase            TEXT NOT NULL DEFAULT 'chat',
  status           TEXT NOT NULL DEFAULT 'success',
  http_status      INTEGER NOT NULL DEFAULT 0,
  duration_ms      INTEGER NOT NULL DEFAULT 0,
  input_tokens     INTEGER NOT NULL DEFAULT 0,
  output_tokens    INTEGER NOT NULL DEFAULT 0,
  reasoning_tokens INTEGER NOT NULL DEFAULT 0,
  request          TEXT NOT NULL DEFAULT '',
  response         TEXT NOT NULL DEFAULT '',
  tool_calls       TEXT NOT NULL DEFAULT '[]',
  error            TEXT NOT NULL DEFAULT '',
  add_time         TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS i_desk_llm_trace_session ON desk_llm_trace (session_id, add_time);
CREATE INDEX IF NOT EXISTS i_desk_llm_trace_run ON desk_llm_trace (run_id, step);
`

/* 原生 better-sqlite3 连接（迁移直接执行 SQL 用） */
let sqlite: Database.Database | null = null
/* Drizzle 实例（业务读写用） */
let database: BetterSQLite3Database<typeof schema> | null = null

/* 初始化本地数据库（应用就绪后调用） */
export function initDatabase(): void {
  const file = getDbFile()
  mkdirSync(dirname(file), { recursive: true })
  /* 技能统一维护在数据目录下的 skills（与数据库同生命周期） */
  mkdirSync(getSkillsDir(), { recursive: true })
  sqlite = new Database(file)
  sqlite.pragma('journal_mode = WAL')
  sqlite.exec(DDL)
  migrateAgentMemoryCursor(sqlite)
  seedMcpServers(sqlite)
  database = drizzle(sqlite, { schema })
}

/*
 * 记忆游标列迁移：last_message_id（会话内 ID）→ last_time（项目级时间）。
 *   - 仅存量库需要；新库由 DDL 直接建新列；
 *   - 先用旧 ID 回填新列，避免已纳入内容被重复压缩，再删除废弃列。
 */
function migrateAgentMemoryCursor(db: Database.Database): void {
  const columns = db.prepare('PRAGMA table_info(desk_agent_memory)').all() as Array<{
    name?: string
  }>
  const names = new Set(columns.map((column) => column.name ?? ''))
  if (!names.has('last_time')) {
    db.exec("ALTER TABLE desk_agent_memory ADD COLUMN last_time TEXT NOT NULL DEFAULT ''")
  }
  if (!names.has('last_message_id')) {
    return
  }
  db.exec(`
    UPDATE desk_agent_memory
    SET last_time = COALESCE(
      (SELECT add_time FROM desk_message WHERE id = desk_agent_memory.last_message_id),
      last_time
    )
    WHERE last_message_id <> ''
  `)
  db.exec('ALTER TABLE desk_agent_memory DROP COLUMN last_message_id')
}

/* 内置 MCP 初始化标记：写入成功后不再重置，避免用户删除后被重新写回 */
const MCP_SEED_KEY = 'seed.mcp'

/* 内置 MCP 种子：首次初始化写入 3 个常用 MCP（Fetch / Filesystem / Playwright） */
function seedMcpServers(db: Database.Database): void {
  const seeded = db.prepare('SELECT 1 FROM desk_setting WHERE key = ?').get(MCP_SEED_KEY)
  if (seeded) {
    return
  }

  const now = new Date().toISOString()
  /* Filesystem 服务限定用户主目录，随安装机器自动适配 */
  const servers = [
    {
      id: 'seed-mcp-fetch',
      name: 'Fetch MCP Server',
      command: 'uvx',
      args: ['mcp-server-fetch'],
      /* 内置默认关闭：需现场安装 Python 依赖（uvx），由用户按需开启 */
      enabled: 0,
      remark:
        '网页抓取：获取网页内容并转换为 Markdown 返回给 Agent，适合阅读和总结网页。依赖本机可用的 uv/uvx。'
    },
    {
      id: 'seed-mcp-filesystem',
      name: 'Filesystem MCP Server',
      command: 'npx',
      args: ['-y', '@modelcontextprotocol/server-filesystem', homedir()],
      /* 内置默认关闭：涉及本地文件读写，由用户按需开启 */
      enabled: 0,
      remark:
        '本地文件：支持读取、写入、编辑、移动与搜索文件，访问范围仅限当前用户主目录，防止越界访问。依赖 Node.js/npx。'
    },
    {
      id: 'seed-mcp-playwright',
      name: 'Playwright MCP Server',
      command: 'npx',
      args: ['@playwright/mcp@latest', '--headless'],
      /* 内置默认关闭：需下载浏览器且资源占用较高，由用户按需开启 */
      enabled: 0,
      remark:
        '浏览器自动化：支持打开网页、点击、填表、截图并读取页面内容，由 Microsoft 官方维护。默认无头模式（不弹出浏览器窗口），移除 --headless 参数即可改为可见窗口。首次运行会下载 Chromium，依赖 Node.js/npx。'
    }
  ]

  /* 幂等写入：主键或名称已存在时跳过，不覆盖用户已修改的配置 */
  const insert = db.prepare(`
    INSERT OR IGNORE INTO desk_mcp
      (id, name, type, transport, url, headers, command, args, env, cwd, config, remark, enabled, health, add_time, update_time)
    VALUES (?, ?, 'local', 'stdio', '', '{}', ?, ?, '{}', '', ?, ?, ?, '', ?, ?)
  `)
  const markSeeded = db.prepare('INSERT OR REPLACE INTO desk_setting (key, value) VALUES (?, ?)')

  db.transaction(() => {
    for (const server of servers) {
      const config = JSON.stringify({
        transport: 'stdio',
        command: server.command,
        args: server.args,
        env: {},
        cwd: ''
      })
      insert.run(
        server.id,
        server.name,
        server.command,
        JSON.stringify(server.args),
        config,
        server.remark,
        server.enabled,
        now,
        now
      )
    }
    markSeeded.run(MCP_SEED_KEY, now)
  })()
}

/* 获取 Drizzle 实例 */
export function getDb(): BetterSQLite3Database<typeof schema> {
  if (!database) {
    throw new Error('数据库尚未初始化')
  }
  return database
}
