package com.sei.nexus.automation;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {{variable.path}} template expressions against an ExecutionContext.
 *
 * Examples:
 *   "{{trigger.order_id}}"           → value of context["trigger"]["order_id"]
 *   "{{nodes.step1.rows[0].status}}" → first row's status from node step1 output
 *   "Order {{trigger.order_id}} done" → mixed literal + variable string
 */
public class VariableResolver {

    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{([^}]+)}}");

    /**
     * Resolve all {{…}} placeholders in the input string.
     * Returns the original string if no placeholders are found.
     * If the entire string is a single placeholder its resolved value is returned
     * as-is (may be non-String); otherwise the result is always a String.
     */
    public static Object resolve(String template, ExecutionContext ctx) {
        if (template == null) return null;

        Matcher m = TEMPLATE.matcher(template);
        if (!m.find()) return template;

        // Single whole-string placeholder → return raw resolved value
        String full = template.trim();
        if (full.startsWith("{{") && full.endsWith("}}") &&
                full.indexOf("{{", 1) == -1) {
            String path = full.substring(2, full.length() - 2).trim();
            return resolvePath(path, ctx);
        }

        // Mixed literal + placeholders → stringify everything
        return m.replaceAll(match -> {
            Object val = resolvePath(match.group(1).trim(), ctx);
            return val == null ? "" : val.toString();
        });
    }

    /**
     * Resolve a dot-path expression against the execution context.
     * Supports:  trigger.field, nodes.nodeId.field, nodes.nodeId.rows[0].col
     */
    @SuppressWarnings("unchecked")
    private static Object resolvePath(String path, ExecutionContext ctx) {
        String[] parts = path.split("\\.");
        if (parts.length == 0) return null;

        // First segment is always a top-level key in the context
        Object current = ctx.get(parts[0]);
        if (current == null) return null;

        for (int i = 1; i < parts.length; i++) {
            if (current == null) return null;
            String part = parts[i];

            // Array index access: fieldName[n]
            int bracketIdx = part.indexOf('[');
            if (bracketIdx >= 0) {
                String fieldName = part.substring(0, bracketIdx);
                int arrayIndex = Integer.parseInt(part.substring(bracketIdx + 1, part.indexOf(']')));

                if (!fieldName.isEmpty() && current instanceof Map) {
                    current = ((Map<String, Object>) current).get(fieldName);
                }
                if (current instanceof List) {
                    List<?> list = (List<?>) current;
                    current = (arrayIndex >= 0 && arrayIndex < list.size()) ? list.get(arrayIndex) : null;
                } else {
                    return null;
                }
            } else {
                if (current instanceof Map) {
                    current = ((Map<String, Object>) current).get(part);
                } else {
                    return null;
                }
            }
        }
        return current;
    }
}
