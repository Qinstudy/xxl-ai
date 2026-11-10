package com.xxl.ai.api.business.harness.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [FEAT-010] Prompt 动态变量解析与安全渲染单元测试
 *
 * @author xxl-ai 2026-11-10
 */
public class PromptVariableToolTest {

    @Test
    @DisplayName("1. 验证变量定义提取、默认值解析、中英文支持与有序去重")
    public void testExtractVariables() {
        String template = "你是一名{{role:架构师}}，精通{{language:Java}}开发。审查重点为{{focus}}，遵循{{role:重复角色定义}}。";
        List<PromptVariableTool.VariableDef> defs = PromptVariableTool.extractVariables(template);

        assertEquals(3, defs.size());

        assertEquals("role", defs.get(0).getName());
        assertEquals("架构师", defs.get(0).getDefaultValue());

        assertEquals("language", defs.get(1).getName());
        assertEquals("Java", defs.get(1).getDefaultValue());

        assertEquals("focus", defs.get(2).getName());
        assertEquals("", defs.get(2).getDefaultValue());
    }

    @Test
    @DisplayName("2. 验证用户变量值覆盖与默认值回退")
    public void testRenderWithUserValuesAndDefaults() {
        String template = "角色：{{role:程序员}}，语言：{{lang:Java}}，规范：{{standard}}。";

        Map<String, String> userVars = new HashMap<>();
        userVars.put("role", "技术专家"); // 覆盖默认值
        // lang 保持未传，应回退默认值 Java
        // standard 无默认值且未传，应替换为空串

        Map<String, String> resolved = new LinkedHashMap<>();
        String rendered = PromptVariableTool.render(template, userVars, resolved);

        assertEquals("角色：技术专家，语言：Java，规范：。", rendered);
        assertEquals("技术专家", resolved.get("role"));
        assertEquals("Java", resolved.get("lang"));
        assertEquals("", resolved.get("standard"));
    }

    @Test
    @DisplayName("3. 验证正则特殊字符（$1、\\ 等）输入不会引发崩溃且字面量原样输出")
    public void testRenderWithRegexSpecialCharacters() {
        String template = "价格：{{price}}，路径：{{path}}，表达式：{{regex}}。";

        Map<String, String> userVars = new HashMap<>();
        userVars.put("price", "$100.00 and $2"); // 包含 $1, $2
        userVars.put("path", "C:\\Users\\admin\\file.txt"); // 包含反斜杠
        userVars.put("regex", "(?<id>\\d+)"); // 正则字符

        String rendered = PromptVariableTool.render(template, userVars, null);

        assertEquals("价格：$100.00 and $2，路径：C:\\Users\\admin\\file.txt，表达式：(?<id>\\d+)。", rendered);
    }

    @Test
    @DisplayName("4. 验证防二次递归注入（用户输入含 {{other}} 不会被二次替换）")
    public void testAntiRecursiveInjection() {
        String template = "Hello {{user}}! Token: {{secret:123}}";

        Map<String, String> userVars = new HashMap<>();
        userVars.put("user", "{{secret}}"); // 恶意尝试利用变量展开注入

        String rendered = PromptVariableTool.render(template, userVars, null);

        // {{secret}} 应被视作纯字面量字符串输出，而不是被第二次替换为 123
        assertEquals("Hello {{secret}}! Token: 123", rendered);
    }

    @Test
    @DisplayName("5. 验证超长变量值安全截断（防超大文本打爆 Token 预算）")
    public void testValueLengthTruncation() {
        String template = "内容：{{data}}";

        String superLongText = "A".repeat(3000); // 3000 字符
        Map<String, String> userVars = new HashMap<>();
        userVars.put("data", superLongText);

        Map<String, String> resolved = new HashMap<>();
        String rendered = PromptVariableTool.render(template, userVars, resolved);

        assertEquals(PromptVariableTool.MAX_VARIABLE_VALUE_LENGTH, resolved.get("data").length());
        assertTrue(rendered.startsWith("内容：AAA"));
        assertEquals("内容：".length() + PromptVariableTool.MAX_VARIABLE_VALUE_LENGTH, rendered.length());
    }

    @Test
    @DisplayName("6. 验证变量提取上限保护（最多提取 20 个）")
    public void testMaxVariableCountLimit() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            sb.append("{{var_").append(i).append(":def").append(i).append("}} ");
        }

        List<PromptVariableTool.VariableDef> defs = PromptVariableTool.extractVariables(sb.toString());
        assertEquals(PromptVariableTool.MAX_VARIABLE_COUNT, defs.size());
        assertEquals("var_1", defs.get(0).getName());
        assertEquals("var_20", defs.get(19).getName());
    }
}
