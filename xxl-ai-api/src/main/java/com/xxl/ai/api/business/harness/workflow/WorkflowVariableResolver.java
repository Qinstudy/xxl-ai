package com.xxl.ai.api.business.harness.workflow;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作流变量插值与静态条件表达式解析器（无第三方脚本引擎沙箱依赖，纯净安全）
 *
 * @author xuxueli 2026-11-12
 */
public class WorkflowVariableResolver {

    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{([a-zA-Z0-9_]+)\\.([a-zA-Z0-9_]+)\\}\\}");

    /**
     * 解析字符串模板中的变量插值
     *
     * @param template 模板字符串，如 "用户提问: {{start.query}}, 背景: {{retrieval_1.context}}"
     * @param ctx      工作流上下文
     * @return 渲染后的字符串
     */
    public static String resolveString(String template, WorkflowContext ctx) {
        if (template == null || template.isEmpty()) {
            return template;
        }

        Matcher matcher = VAR_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String nodeId = matcher.group(1);
            String propertyKey = matcher.group(2);
            Object value = ctx.getNodePropertyValue(nodeId, propertyKey);
            String replacement = value != null ? Matcher.quoteReplacement(String.valueOf(value)) : "";
            matcher.appendReplacement(sb, replacement);
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 递归解析任意 Object（支持 String, List, Map 变量注入）
     */
    @SuppressWarnings("unchecked")
    public static Object resolveObject(Object obj, WorkflowContext ctx) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof String str) {
            // 如果恰好纯粹是一个变量引用 {{node.prop}}，尽量返回其原始对象类型
            Matcher m = VAR_PATTERN.matcher(str);
            if (m.matches()) {
                String nodeId = m.group(1);
                String propertyKey = m.group(2);
                Object val = ctx.getNodePropertyValue(nodeId, propertyKey);
                return val != null ? val : "";
            }
            return resolveString(str, ctx);
        }
        if (obj instanceof List<?> list) {
            List<Object> resolvedList = new ArrayList<>();
            for (Object item : list) {
                resolvedList.add(resolveObject(item, ctx));
            }
            return resolvedList;
        }
        if (obj instanceof Map<?, ?> map) {
            Map<String, Object> resolvedMap = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                resolvedMap.put(key, resolveObject(entry.getValue(), ctx));
            }
            return resolvedMap;
        }
        return obj;
    }

    /**
     * 纯声明式断言求值（支持 EQUALS, CONTAINS, GREATER_THAN 等）
     *
     * @param leftValue  左操作数值（已完成变量替换）
     * @param operator   操作符
     * @param rightValue 目标比对值
     * @return 是否满足条件
     */
    public static boolean evaluateCondition(Object leftValue, String operator, Object rightValue) {
        if (operator == null) {
            return false;
        }
        String op = operator.trim().toUpperCase();

        // 空值断言
        if ("IS_EMPTY".equals(op)) {
            if (leftValue == null) return true;
            if (leftValue instanceof String s) return s.trim().isEmpty();
            if (leftValue instanceof Collection<?> c) return c.isEmpty();
            if (leftValue instanceof Map<?, ?> m) return m.isEmpty();
            return false;
        }
        if ("IS_NOT_EMPTY".equals(op)) {
            return !evaluateCondition(leftValue, "IS_EMPTY", null);
        }

        String leftStr = leftValue != null ? String.valueOf(leftValue).trim() : "";
        String rightStr = rightValue != null ? String.valueOf(rightValue).trim() : "";

        switch (op) {
            case "EQUALS":
            case "==":
                return leftStr.equalsIgnoreCase(rightStr);
            case "NOT_EQUALS":
            case "!=":
                return !leftStr.equalsIgnoreCase(rightStr);
            case "CONTAINS":
                return leftStr.contains(rightStr);
            case "NOT_CONTAINS":
                return !leftStr.contains(rightStr);
            case "GREATER_THAN":
            case ">":
                return compareNumbers(leftValue, rightValue) > 0;
            case "LESS_THAN":
            case "<":
                return compareNumbers(leftValue, rightValue) < 0;
            case "GREATER_THAN_OR_EQUALS":
            case ">=":
                return compareNumbers(leftValue, rightValue) >= 0;
            case "LESS_THAN_OR_EQUALS":
            case "<=":
                return compareNumbers(leftValue, rightValue) <= 0;
            default:
                return false;
        }
    }

    private static int compareNumbers(Object left, Object right) {
        try {
            double l = Double.parseDouble(String.valueOf(left).trim());
            double r = Double.parseDouble(String.valueOf(right).trim());
            return Double.compare(l, r);
        } catch (Exception e) {
            return String.valueOf(left).compareTo(String.valueOf(right));
        }
    }
}
