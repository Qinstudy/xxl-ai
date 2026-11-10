package com.xxl.ai.api.business;

import com.xxl.ai.api.business.agent.mapper.AgentMapper;
import com.xxl.ai.api.business.agent.model.entity.Agent;
import com.xxl.ai.api.business.harness.llm.LlmChatTool;
import com.xxl.ai.api.business.harness.skill.SkillToolFactory;
import com.xxl.ai.api.business.supplier.model.SupplierRuntime;
import com.xxl.ai.api.business.supplier.service.SupplierService;
import com.xxl.tool.core.StringTool;
import com.xxl.tool.response.Response;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@SpringBootTest
public class AgentSkillExecutionTest {

    private static final Logger logger = LoggerFactory.getLogger(AgentSkillExecutionTest.class);

    @Resource
    private AgentMapper agentMapper;

    @Resource
    private SupplierService supplierService;

    @Resource
    private SkillToolFactory skillToolFactory;

    @Resource
    private LlmChatTool llmChatTool;

    @Resource
    private com.xxl.ai.api.business.harness.mcp.McpClientTool mcpClientTool;

    @Resource
    private com.xxl.ai.api.business.mcp.mapper.McpMapper mcpMapper;

    @Resource
    private com.xxl.ai.api.business.session.mapper.SessionMsgMapper chatMsgMapper;

    @Test
    @DisplayName("检查 Agent 7 配置与模型连通性")
    public void testCheckAgentConfig() {
        Agent agent = agentMapper.load(7L);
        Assertions.assertNotNull(agent, "Agent 7 不存在");
        logger.info("Agent 7 配置: id={}, name={}, spaceId={}, supplierId={}, modelId={}, skillIds={}",
                agent.getId(), agent.getName(), agent.getSpaceId(), agent.getModelSupplierId(), agent.getModelId(), agent.getSkillIds());

        Response<SupplierRuntime> runtimeResp = supplierService.loadRuntime(agent.getSpaceId(), agent.getModelSupplierId(), agent.getModelId());
        SupplierRuntime runtime = runtimeResp != null ? runtimeResp.getData() : null;
        Assertions.assertNotNull(runtime, "SupplierRuntime 不存在");
        logger.info("SupplierRuntime: baseUrl={}, modelName={}, hasApiKey={}",
                runtime.getBaseUrl(), runtime.getModelName(), runtime.getApiKey() != null && !runtime.getApiKey().isBlank());
    }

    @Test
    @DisplayName("实测 Agent 7 执行 SQL 优化技能并将结果保存到本地沙箱目录")
    public void testExecuteSqlOptimizerAndSaveFile() throws IOException {
        long agentId = 7L;
        Agent agent = agentMapper.load(agentId);
        Assertions.assertNotNull(agent, "Agent 7 不存在");

        Response<SupplierRuntime> runtimeResp = supplierService.loadRuntime(agent.getSpaceId(), agent.getModelSupplierId(), agent.getModelId());
        SupplierRuntime runtime = runtimeResp != null ? runtimeResp.getData() : null;
        Assertions.assertNotNull(runtime, "模型运行时为空");

        // 1. 组装技能与沙箱执行工具
        List<Object> tools = new ArrayList<>();
        Object skillTool = skillToolFactory.buildTool(agent);
        if (skillTool != null) {
            tools.add(skillTool);
        }
        tools.addAll(skillToolFactory.buildExecutorTools(agent, skillToolFactory.newShellTracker(agent.getId())));
        logger.info("成功为 Agent 7 装配工具数: {}", tools.size());

        // 2. 准备沙箱生成的目标文件路径
        String fileName = "auto_test_sql_result.md";
        Path targetFile = Path.of("E:/data/applogs/xxl-ai/skills/agent_" + agentId + "/" + fileName);
        Files.deleteIfExists(targetFile); // 预清理旧测试文件

        // 3. 构造提示词，明确要求优化慢 SQL 并调用文件写入工具
        String prompt = "我有一条 MySQL 慢查询 SQL：\n" +
                "SELECT * FROM user_orders WHERE status = 1 AND create_time >= '2026-01-01' ORDER BY update_time DESC LIMIT 80000, 20;\n\n" +
                "请结合你的 sql-optimizer 技能分析其深分页和索引失效原因，给出优化重写后的 SQL。\n" +
                "并且【必须】使用你的文件工具（FileSystemTools.WriteFile 或类似写文件工具），在当前目录下创建并写入一个名为 " + fileName + " 的文件，" +
                "将你的诊断分析结论与重写后的优化 SQL 保存到该文件中。";

        String systemPrompt = StringTool.isNotBlank(agent.getSystemPrompt()) ? agent.getSystemPrompt().trim() : "你是数据库性能调优专家。";

        // 4. 调用大模型流式对话（支持 Tool Calling）
        logger.info("开始请求模型并执行工具...");
        LlmChatTool.ChatText chatText = llmChatTool.chat(
                runtime,
                systemPrompt,
                List.of(),
                List.of(),
                prompt,
                tools.toArray(),
                List.of(),
                "test-agent-7-" + System.currentTimeMillis(),
                thinking -> {
                    if (StringTool.isNotBlank(thinking)) {
                        logger.info("[Thinking]: {}", thinking);
                    }
                },
                content -> {
                    if (StringTool.isNotBlank(content)) {
                        logger.info("[Content]: {}", content);
                    }
                }
        );

        logger.info("对话完成，最终响应: {}", chatText.getContent());

        // 5. 校验物理文件落盘
        boolean fileExists = Files.exists(targetFile);
        logger.info("验证物理文件路径: {}, 是否存在: {}", targetFile.toAbsolutePath(), fileExists);
        Assertions.assertTrue(fileExists, "文件未能生成于沙箱路径: " + targetFile.toAbsolutePath());

        String content = Files.readString(targetFile);
        logger.info(">>> 成功读取沙箱生成的文件内容（前 500 字符）:\n{}", content.substring(0, Math.min(content.length(), 500)));
        Assertions.assertTrue(content.length() > 0, "生成的文件内容为空");
    }

    @Test
    @DisplayName("探查分析 XXL-AI Agent 挂载的所有沙箱通用工具清单")
    public void testInspectAllBuiltinTools() {
        Agent agent = agentMapper.load(7L);
        Assertions.assertNotNull(agent);

        List<Object> tools = new ArrayList<>();
        Object skillTool = skillToolFactory.buildTool(agent);
        if (skillTool != null) {
            tools.add(skillTool);
        }
        tools.addAll(skillToolFactory.buildExecutorTools(agent, skillToolFactory.newShellTracker(agent.getId())));

        logger.info("=== 装配的工具对象列表 (共 {} 个组件) ===", tools.size());
        for (int i = 0; i < tools.size(); i++) {
            Object toolObj = tools.get(i);
            logger.info("[组件 #{}] Class: {}", i + 1, toolObj.getClass().getName());
            if (toolObj instanceof org.springframework.ai.tool.ToolCallback tc) {
                logger.info("  => ToolCallback 名称: [{}], 描述: [{}]", 
                        tc.getToolDefinition().name(), tc.getToolDefinition().description());
            } else {
                for (java.lang.reflect.Method m : toolObj.getClass().getMethods()) {
                    for (java.lang.annotation.Annotation ann : m.getAnnotations()) {
                        if (ann.annotationType().getSimpleName().equals("Tool")) {
                            logger.info("  => @Tool 注解方法: {}(), 注解详情: {}", m.getName(), ann);
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("测试 Windows 平台下 stdio MCP (如 npx -y mcp-fetch-server) 的自动兼容连通性")
    public void testMcpStdioWindowsCompatibility() {
        com.xxl.ai.api.business.mcp.model.entity.Mcp mcp = new com.xxl.ai.api.business.mcp.model.entity.Mcp();
        mcp.setId(999L);
        mcp.setName("Fetch 网页抓取");
        mcp.setType(1);
        // 原生配置里仍然写 "command": "npx"，测试框架底层是否自动适配 Windows cmd.exe /c
        mcp.setConfig("{\"transport\":\"stdio\",\"command\":\"npx\",\"args\":[\"-y\",\"mcp-fetch-server\"],\"env\":{}}");

        com.xxl.ai.api.business.harness.mcp.McpClientTool.McpConnectResult result = mcpClientTool.test(mcp);
        logger.info("=== MCP 测试结果 ===");
        logger.info("是否连通: {}", result.isConnectable());
        logger.info("返回消息: {}", result.getMessage());
        logger.info("工具数量: {}", result.getToolCount());
        if (result.getTools() != null) {
            result.getTools().forEach(t -> logger.info("  => 发现工具: [{}], 描述: [{}]", t.getName(), t.getDescription()));
        }
        Assertions.assertTrue(result.isConnectable(), "MCP stdio 连通失败: " + result.getMessage());
    }

    @Resource
    private com.xxl.ai.api.business.session.mapper.SessionMapper chatConvMapper;

    @Test
    @DisplayName("探查对话 16 的消息记录与生成截断分析")
    public void testInspectConv16() {
        com.xxl.ai.api.business.session.model.entity.Session conv = chatConvMapper.load(16L);
        Assertions.assertNotNull(conv, "对话 16 不存在");
        Agent agent = agentMapper.load(conv.getAgentId());
        logger.info("=== 对话 16 详情 ===");
        logger.info("Agent: id={}, name={}, supplierId={}, modelId={}", 
                agent.getId(), agent.getName(), agent.getModelSupplierId(), agent.getModelId());
        Response<SupplierRuntime> runtimeResp = supplierService.loadRuntime(agent.getSpaceId(), agent.getModelSupplierId(), agent.getModelId());
        SupplierRuntime runtime = runtimeResp.getData();
        logger.info("SupplierRuntime: baseUrl={}, modelName={}", runtime.getBaseUrl(), runtime.getModelName());

        List<com.xxl.ai.api.business.session.model.entity.SessionMsg> msgList = chatMsgMapper.listBySessionId(16L);
        logger.info("=== 对话 16 消息记录 (共 {} 条) ===", msgList.size());
        for (com.xxl.ai.api.business.session.model.entity.SessionMsg msg : msgList) {
            String content = msg.getContent() != null ? msg.getContent() : "";
            long durationMs = msg.getUpdateTime() != null && msg.getAddTime() != null 
                    ? msg.getUpdateTime().getTime() - msg.getAddTime().getTime() : 0;
            logger.info("[Msg #{}] Role: {}, Status: {}, Length: {}, AddTime: {}, UpdateTime: {}, Duration: {} ms", 
                    msg.getId(), msg.getRole(), msg.getStatus(), content.length(), msg.getAddTime(), msg.getUpdateTime(), durationMs);
            if (content.length() > 0) {
                int start = Math.max(0, content.length() - 300);
                logger.info("  Content 末尾 300 字符: >>>{}<<<", content.substring(start));
            }
            if (msg.getReasoning() != null && !msg.getReasoning().isBlank()) {
                logger.info("  Reasoning Length: {}", msg.getReasoning().length());
            }
        }
    }

    @Test
    @DisplayName("测试 Filesystem MCP (序号 3) 在 Windows 平台下的连通性与工具拉取")
    public void testMcpFilesystemWindows() {
        com.xxl.ai.api.business.mcp.model.entity.Mcp mcp = new com.xxl.ai.api.business.mcp.model.entity.Mcp();
        mcp.setId(993L);
        mcp.setName("Filesystem 文件系统");
        mcp.setType(1);
        // 配置传入默认 /tmp，测试底层是否自动 fallback 至 Windows 有效目录 (如 E:\data)
        mcp.setConfig("{\"transport\":\"stdio\",\"command\":\"npx\",\"args\":[\"-y\",\"@modelcontextprotocol/server-filesystem\",\"/tmp\"],\"env\":{}}");

        com.xxl.ai.api.business.harness.mcp.McpClientTool.McpConnectResult result = mcpClientTool.test(mcp);
        logger.info("=== Filesystem MCP 测试结果 ===");
        logger.info("是否连通: {}", result.isConnectable());
        logger.info("返回消息: {}", result.getMessage());
        logger.info("工具数量: {}", result.getToolCount());
        if (result.getTools() != null) {
            result.getTools().forEach(t -> logger.info("  => 工具: [{}], 描述: [{}]", t.getName(), t.getDescription()));
        }
        Assertions.assertTrue(result.isConnectable(), "Filesystem MCP 连通失败: " + result.getMessage());
        Assertions.assertTrue(result.getToolCount() > 0, "Filesystem MCP 工具数应大于 0");
    }

    @Test
    @DisplayName("测试 MySQL MCP (序号 6) 自动包名纠偏与连接串解析连通性")
    public void testMcpMysqlAutoCompatibility() {
        com.xxl.ai.api.business.mcp.model.entity.Mcp mcp = new com.xxl.ai.api.business.mcp.model.entity.Mcp();
        mcp.setId(996L);
        mcp.setName("MySQL 数据库操作工具");
        mcp.setType(1);
        // 原样传入用户之前的错误配置包名与连接串，验证自动纠偏与环境参数解析
        mcp.setConfig("{\"transport\":\"stdio\",\"command\":\"npx\",\"args\":[\"-y\",\"@modelcontextprotocol/server-mysql\",\"mysql://root:123456@127.0.0.1:3306/xxl_ai\"],\"env\":{}}");

        com.xxl.ai.api.business.harness.mcp.McpClientTool.McpConnectResult result = mcpClientTool.test(mcp);
        logger.info("=== MySQL MCP 测试结果 ===");
        logger.info("是否连通: {}", result.isConnectable());
        logger.info("返回消息: {}", result.getMessage());
        logger.info("工具数量: {}", result.getToolCount());
        if (result.getTools() != null) {
            result.getTools().forEach(t -> logger.info("  => 工具: [{}], 描述: [{}]", t.getName(), t.getDescription()));
        }
        Assertions.assertTrue(result.isConnectable(), "MySQL MCP 连通失败: " + result.getMessage());
        Assertions.assertTrue(result.getToolCount() > 0, "MySQL MCP 工具数应大于 0");
    }

    @Test
    @DisplayName("更新并验证数据库中 MCP ID 3 (Filesystem) 与 ID 6 (MySQL)")
    public void testUpdateAndVerifyDatabaseMcp3And6() {
        // 1. 检查并更新 ID 3 (Filesystem)
        com.xxl.ai.api.business.mcp.model.entity.Mcp mcp3 = mcpMapper.load(3L);
        if (mcp3 != null) {
            logger.info("当前 DB MCP 3: name={}, config={}", mcp3.getName(), mcp3.getConfig());
            mcp3.setConfig("{\"transport\":\"stdio\",\"command\":\"npx\",\"args\":[\"-y\",\"@modelcontextprotocol/server-filesystem\",\"E:\\\\data\\\\tmp\"],\"env\":{}}");
            mcpMapper.update(mcp3);
            logger.info("MCP 3 配置已持久化更新为 E:\\\\data\\\\tmp");

            com.xxl.ai.api.business.harness.mcp.McpClientTool.McpConnectResult res3 = mcpClientTool.test(mcp3);
            logger.info("DB MCP 3 测试结果: connectable={}, toolCount={}, msg={}", res3.isConnectable(), res3.getToolCount(), res3.getMessage());
            Assertions.assertTrue(res3.isConnectable(), "MCP 3 连通失败: " + res3.getMessage());
        }

        // 2. 检查并更新 ID 6 (MySQL)
        com.xxl.ai.api.business.mcp.model.entity.Mcp mcp6 = mcpMapper.load(6L);
        if (mcp6 == null) {
            // 若之前未持久化为 ID 6，则查询所有或按名称查询
            List<com.xxl.ai.api.business.mcp.model.entity.Mcp> all = mcpMapper.listBySpace(1L);
            for (com.xxl.ai.api.business.mcp.model.entity.Mcp item : all) {
                if (item.getName() != null && item.getName().contains("MySQL")) {
                    mcp6 = item;
                    break;
                }
            }
        }
        if (mcp6 != null) {
            logger.info("当前 DB MySQL MCP: id={}, name={}, config={}", mcp6.getId(), mcp6.getName(), mcp6.getConfig());
            mcp6.setConfig("{\"transport\":\"stdio\",\"command\":\"npx\",\"args\":[\"-y\",\"@benborla29/mcp-server-mysql\"],\"env\":{\"MYSQL_HOST\":\"127.0.0.1\",\"MYSQL_PORT\":\"3306\",\"MYSQL_USER\":\"root\",\"MYSQL_PASS\":\"123456\",\"MYSQL_DB\":\"xxl_ai\"}}");
            mcpMapper.update(mcp6);
            logger.info("MCP {} 配置已持久化更新为 @benborla29/mcp-server-mysql 及环境变量", mcp6.getId());

            com.xxl.ai.api.business.harness.mcp.McpClientTool.McpConnectResult res6 = mcpClientTool.test(mcp6);
            logger.info("DB MySQL MCP 测试结果: connectable={}, toolCount={}, msg={}", res6.isConnectable(), res6.getToolCount(), res6.getMessage());
            Assertions.assertTrue(res6.isConnectable(), "MySQL MCP 连通失败: " + res6.getMessage());
        } else {
            logger.warn("数据库中尚未存在 MySQL MCP 记录，准备自动插入...");
            com.xxl.ai.api.business.mcp.model.entity.Mcp newMcp = new com.xxl.ai.api.business.mcp.model.entity.Mcp();
            newMcp.setSpaceId(1L);
            newMcp.setName("MySQL 数据库操作工具");
            newMcp.setType(1);
            newMcp.setConfig("{\"transport\":\"stdio\",\"command\":\"npx\",\"args\":[\"-y\",\"@benborla29/mcp-server-mysql\"],\"env\":{\"MYSQL_HOST\":\"127.0.0.1\",\"MYSQL_PORT\":\"3306\",\"MYSQL_USER\":\"root\",\"MYSQL_PASS\":\"123456\",\"MYSQL_DB\":\"xxl_ai\"}}");
            newMcp.setRemark("MySQL 数据库读写操作工具");
            newMcp.setStatus(0);
            mcpMapper.insert(newMcp);
            logger.info("已新增插入 MySQL MCP, id={}", newMcp.getId());

            com.xxl.ai.api.business.harness.mcp.McpClientTool.McpConnectResult resNew = mcpClientTool.test(newMcp);
            Assertions.assertTrue(resNew.isConnectable(), "新建 MySQL MCP 连通失败: " + resNew.getMessage());
        }
    }

    @Test
    @DisplayName("实际调用执行序号 3 (Filesystem) 与序号 6 (MySQL) 的工具")
    public void testActualCallToolsMcp3And6() {
        // ==================== 1. 序号 3 Filesystem 工具实际调用 ====================
        com.xxl.ai.api.business.mcp.model.entity.Mcp mcp3 = mcpMapper.load(3L);
        Assertions.assertNotNull(mcp3, "MCP 3 不存在");
        logger.info("=== [MCP 3 Filesystem] 开始实际工具调用 ===");

        // (1) 调用 write_file 写入测试文件至 E:/data/tmp
        String testFileName = "E:/data/tmp/mcp_verify_" + System.currentTimeMillis() + ".txt";
        String testContent = "Hello XXL-AI, this file was written by MCP Filesystem write_file at " + new java.util.Date();
        String writeArgs = String.format("{\"path\":\"%s\",\"content\":\"%s\"}", testFileName.replace("\\", "/"), testContent);
        logger.info("调用 write_file, args: {}", writeArgs);
        String writeResult = mcpClientTool.callTool(mcp3, "write_file", writeArgs);
        logger.info("write_file 返回结果: {}", writeResult);
        Assertions.assertNotNull(writeResult);

        // (2) 调用 read_text_file / read_file 读取文件内容
        String readArgs = String.format("{\"path\":\"%s\"}", testFileName.replace("\\", "/"));
        logger.info("调用 read_file, args: {}", readArgs);
        String readResult = null;
        try {
            readResult = mcpClientTool.callTool(mcp3, "read_text_file", readArgs);
        } catch (Exception e) {
            readResult = mcpClientTool.callTool(mcp3, "read_file", readArgs);
        }
        logger.info("read_file 返回结果: {}", readResult);
        Assertions.assertNotNull(readResult);
        Assertions.assertTrue(readResult.contains("Hello XXL-AI"), "读取内容未包含预期文本");

        // (3) 调用 list_directory 列出目录
        String listArgs = "{\"path\":\"E:/data/tmp\"}";
        logger.info("调用 list_directory, args: {}", listArgs);
        String listResult = mcpClientTool.callTool(mcp3, "list_directory", listArgs);
        logger.info("list_directory 返回结果: {}", listResult);
        Assertions.assertNotNull(listResult);

        // ==================== 2. 序号 6 MySQL 工具实际调用 ====================
        com.xxl.ai.api.business.mcp.model.entity.Mcp mcp6 = mcpMapper.load(6L);
        Assertions.assertNotNull(mcp6, "MCP 6 不存在");
        logger.info("=== [MCP 6 MySQL] 开始实际工具调用 ===");

        // (1) 调用 mysql_query 查询用户信息
        String queryUserSql = "{\"sql\":\"SELECT id, username, role, real_name, status FROM xxl_ai_user;\"}";
        logger.info("调用 mysql_query 查询用户, args: {}", queryUserSql);
        String userQueryResult = mcpClientTool.callTool(mcp6, "mysql_query", queryUserSql);
        logger.info("mysql_query 查询用户返回结果: {}", userQueryResult);
        Assertions.assertNotNull(userQueryResult);
        Assertions.assertTrue(userQueryResult.contains("admin"), "查询结果应包含 admin 用户");

        // (2) 调用 mysql_query 查询 MCP 配置列表
        String queryMcpSql = "{\"sql\":\"SELECT id, name, type, remark FROM xxl_ai_mcp WHERE id IN (3, 6);\"}";
        logger.info("调用 mysql_query 查询 MCP, args: {}", queryMcpSql);
        String mcpQueryResult = mcpClientTool.callTool(mcp6, "mysql_query", queryMcpSql);
        logger.info("mysql_query 查询 MCP 返回结果: {}", mcpQueryResult);
        Assertions.assertNotNull(mcpQueryResult);
        Assertions.assertTrue(mcpQueryResult.contains("Filesystem") || mcpQueryResult.contains("MySQL"), "查询结果应包含 MCP 记录");
    }

    @Test
    @DisplayName("全面诊断排查 MCP 服务可用性")
    public void testDiagnoseAllMcp() {
        Agent agent7 = agentMapper.load(7L);
        logger.info("=== Agent 7 绑定的 MCP ID 列表: {} ===", agent7 != null ? agent7.getMcpIds() : "null");

        List<com.xxl.ai.api.business.mcp.model.entity.Mcp> allMcps = mcpMapper.listBySpace(1L);
        logger.info("=== 空间 1 下所有 MCP 服务 (共 {} 个) ===", allMcps != null ? allMcps.size() : 0);
        if (allMcps != null) {
            for (com.xxl.ai.api.business.mcp.model.entity.Mcp mcp : allMcps) {
                logger.info("--------------------------------------------------");
                logger.info("检查 MCP [id={}, name={}, type={}, status={}, url={}]",
                        mcp.getId(), mcp.getName(), mcp.getType() == 0 ? "远程(HTTP)" : "本地(stdio)",
                        mcp.getStatus() == 0 ? "正常" : "停用", mcp.getUrl());
                logger.info("  Config: {}", mcp.getConfig());
                try {
                    com.xxl.ai.api.business.harness.mcp.McpClientTool.McpConnectResult result = mcpClientTool.test(mcp);
                    logger.info("  测试结果: connectable={}, toolCount={}, elapsedMs={}ms, msg={}",
                            result.isConnectable(), result.getToolCount(), result.getElapsedMs(), result.getMessage());
                } catch (Exception e) {
                    logger.error("  测试异常: {}", e.getMessage());
                }
            }
        }
    }
}

