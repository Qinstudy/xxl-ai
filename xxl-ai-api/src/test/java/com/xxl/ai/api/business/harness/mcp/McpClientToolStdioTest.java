package com.xxl.ai.api.business.harness.mcp;

import io.modelcontextprotocol.client.transport.ServerParameters;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * McpClientTool 本地 stdio Windows 兼容适配单元测试
 *
 * @author maque 2026-11-14
 */
public class McpClientToolStdioTest {

    @Test
    void windowsWrapsPackageManagersAndBatchScriptsWithCmd() {
        List<String> originArgs = List.of("-y", "@modelcontextprotocol/server-filesystem", "/tmp");
        ServerParameters params = McpClientTool.buildStdioParameters(
                "npx",
                originArgs,
                Map.of("NODE_ENV", "production"),
                true
        );

        String expectedTmp = new File(System.getProperty("java.io.tmpdir")).getAbsolutePath();
        assertEquals("cmd.exe", params.getCommand());
        assertEquals(
                List.of("/c", "npx", "-y", "@modelcontextprotocol/server-filesystem", expectedTmp),
                params.getArgs()
        );
        assertEquals("production", params.getEnv().get("NODE_ENV"));
        // 不篡改调用方原始入参列表
        assertEquals(List.of("-y", "@modelcontextprotocol/server-filesystem", "/tmp"), originArgs);

        ServerParameters batParams = McpClientTool.buildStdioParameters(
                "C:\\tools\\mcp-server.bat",
                List.of("--port", "8000"),
                null,
                true
        );
        assertEquals("cmd.exe", batParams.getCommand());
        assertEquals(List.of("/c", "C:\\tools\\mcp-server.bat", "--port", "8000"), batParams.getArgs());
    }

    @Test
    void windowsDoesNotDoubleWrapCmdOrExecutableBinaries() {
        ServerParameters alreadyCmd = McpClientTool.buildStdioParameters(
                "cmd.exe",
                List.of("/c", "npx", "-y", "pkg"),
                null,
                true
        );
        assertEquals("cmd.exe", alreadyCmd.getCommand());
        assertEquals(List.of("/c", "npx", "-y", "pkg"), alreadyCmd.getArgs());

        ServerParameters pythonExe = McpClientTool.buildStdioParameters(
                "python",
                List.of("-m", "mcp_server"),
                null,
                true
        );
        assertEquals("python", pythonExe.getCommand());
        assertEquals(List.of("-m", "mcp_server"), pythonExe.getArgs());
    }

    @Test
    void nonWindowsKeepsCommandAndUnixTmpUntouched() {
        ServerParameters params = McpClientTool.buildStdioParameters(
                "npx",
                List.of("-y", "@modelcontextprotocol/server-filesystem", "/tmp/"),
                null,
                false
        );
        assertEquals("npx", params.getCommand());
        assertEquals(List.of("-y", "@modelcontextprotocol/server-filesystem", "/tmp/"), params.getArgs());
    }

}
