package com.xxl.ai.api.business.harness.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [PROT-001] MCP 客户端传输协议端点路径判定单元测试
 *
 * @author xxl-ai 2026-11-10
 */
public class McpClientTransportTest {

    @Test
    @DisplayName("1. 验证标准 SSE 端点与包含斜杠/参数时正确识别为 SSE")
    public void testValidSseEndpoints() {
        assertTrue(McpClientTool.isSseEndpoint("http://localhost:8080/sse"));
        assertTrue(McpClientTool.isSseEndpoint("http://localhost:8080/sse/"));
        assertTrue(McpClientTool.isSseEndpoint("https://mcp.company.com/api/v1/sse"));
        assertTrue(McpClientTool.isSseEndpoint("https://mcp.company.com/api/v1/sse/"));
        assertTrue(McpClientTool.isSseEndpoint("http://127.0.0.1:8091/sse?token=xyz123"));
        assertTrue(McpClientTool.isSseEndpoint("http://127.0.0.1:8091/api/sse/?key=abc&env=prod"));
        assertTrue(McpClientTool.isSseEndpoint("http://localhost:8080/SSE"));
    }

    @Test
    @DisplayName("2. 验证非 SSE 地址（包含 asset/classes 等子串）严禁误判为 SSE（防误判核心）")
    public void testNonSseEndpointsNotMisjudged() {
        // 包含 asset 子串（旧代码 config.url.contains("/sse") 必定误判）
        assertFalse(McpClientTool.isSseEndpoint("http://api.company.com/v1/asset/mcp"));
        assertFalse(McpClientTool.isSseEndpoint("http://localhost:8080/asset/events"));

        // 包含 classes 子串
        assertFalse(McpClientTool.isSseEndpoint("http://service.internal/classes/tool"));

        // 包含 processor 子串
        assertFalse(McpClientTool.isSseEndpoint("http://service.internal/processor/stream"));

        // 标淮 Streamable HTTP 端点 /mcp
        assertFalse(McpClientTool.isSseEndpoint("http://localhost:8091/mcp"));
        assertFalse(McpClientTool.isSseEndpoint("https://api.github.com/mcp"));

        // query 参数中包含 /sse，但实际路径为 /mcp
        assertFalse(McpClientTool.isSseEndpoint("http://localhost:8080/mcp?filter=/sse"));
        assertFalse(McpClientTool.isSseEndpoint("http://localhost:8080/api/tools?name=/sse"));

        // 根路径或空路径
        assertFalse(McpClientTool.isSseEndpoint("http://localhost:8080"));
        assertFalse(McpClientTool.isSseEndpoint("http://localhost:8080/"));
    }

    @Test
    @DisplayName("3. 验证空值与非法 URL 优雅降级返回 false")
    public void testEdgeCasesAndInvalidUrls() {
        assertFalse(McpClientTool.isSseEndpoint(null));
        assertFalse(McpClientTool.isSseEndpoint(""));
        assertFalse(McpClientTool.isSseEndpoint("   "));
        assertFalse(McpClientTool.isSseEndpoint("not a valid uri ::: http"));
    }
}
