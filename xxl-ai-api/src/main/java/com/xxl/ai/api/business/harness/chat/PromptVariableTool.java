package com.xxl.ai.api.business.harness.chat;

import com.xxl.tool.core.StringTool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt 动态变量解析与安全渲染工具（[FEAT-010]）
 *
 * @author xxl-ai 2026-11-10
 */
public final class PromptVariableTool {

    /* 匹配 {{varName}} 或 {{varName:defaultValue}}，支持中英文、数字、下划线、连字符，最长 50 字符 */
    private static final Pattern VARIABLE_PATTERN = Pattern.compile(
            "\\{\\{\\s*([a-zA-Z0-9_\\-\\u4e00-\\u9fa5]{1,50})(?:\\s*:\\s*([^}]*?))?\\s*\\}\\}"
    );

    /* 单个 Agent 最多支持提取的变量槽位数 */
    public static final int MAX_VARIABLE_COUNT = 20;

    /* 单个变量值的最大字符长度（超出截断，防超长文本注入） */
    public static final int MAX_VARIABLE_VALUE_LENGTH = 2000;

    private PromptVariableTool() {
    }

    /**
     * 变量槽位定义
     */
    public static class VariableDef {
        private final String name;
        private final String defaultValue;

        public VariableDef(String name, String defaultValue) {
            this.name = name;
            this.defaultValue = defaultValue != null ? defaultValue.trim() : "";
        }

        public String getName() {
            return name;
        }

        public String getDefaultValue() {
            return defaultValue;
        }
    }

    /**
     * 从系统提示词模板中提取变量槽位定义（有序去重，最多 MAX_VARIABLE_COUNT 个）
     */
    public static List<VariableDef> extractVariables(String promptTemplate) {
        if (StringTool.isBlank(promptTemplate)) {
            return Collections.emptyList();
        }
        Matcher matcher = VARIABLE_PATTERN.matcher(promptTemplate);
        Map<String, VariableDef> uniqueDefs = new LinkedHashMap<>();
        while (matcher.find() && uniqueDefs.size() < MAX_VARIABLE_COUNT) {
            String name = matcher.group(1).trim();
            String defaultVal = matcher.group(2) != null ? matcher.group(2).trim() : "";
            if (!uniqueDefs.containsKey(name)) {
                uniqueDefs.put(name, new VariableDef(name, defaultVal));
            }
        }
        return new ArrayList<>(uniqueDefs.values());
    }

    /**
     * 单遍安全替换模板中的变量槽位
     *
     * @param promptTemplate 系统提示词模板
     * @param userVariables  用户传入的变量键值对（可空）
     * @param resolvedOut    输出参数：记录最终生效的变量键值快照（可空）
     * @return 渲染后的系统提示词
     */
    public static String render(String promptTemplate,
                                Map<String, String> userVariables,
                                Map<String, String> resolvedOut) {
        if (StringTool.isBlank(promptTemplate)) {
            return promptTemplate;
        }
        Matcher matcher = VARIABLE_PATTERN.matcher(promptTemplate);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String name = matcher.group(1).trim();
            String defaultVal = matcher.group(2) != null ? matcher.group(2).trim() : "";

            String finalVal = defaultVal;
            if (userVariables != null && userVariables.containsKey(name)) {
                String userVal = userVariables.get(name);
                if (userVal != null) {
                    userVal = userVal.trim();
                    if (!userVal.isEmpty()) {
                        finalVal = userVal;
                    }
                }
            }

            // 安全截断最大长度
            if (finalVal != null && finalVal.length() > MAX_VARIABLE_VALUE_LENGTH) {
                finalVal = finalVal.substring(0, MAX_VARIABLE_VALUE_LENGTH);
            }

            String safeVal = finalVal != null ? finalVal : "";
            if (resolvedOut != null) {
                resolvedOut.put(name, safeVal);
            }

            // 使用 quoteReplacement 防止 $1 或 \ 字符触发 Matcher 正则分组引用越界异常
            matcher.appendReplacement(sb, Matcher.quoteReplacement(safeVal));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
