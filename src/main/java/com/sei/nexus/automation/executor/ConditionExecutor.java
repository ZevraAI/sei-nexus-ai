package com.sei.nexus.automation.executor;

import com.sei.nexus.automation.ExecutionContext;
import com.sei.nexus.automation.StepExecutor;
import com.sei.nexus.automation.VariableResolver;
import com.sei.nexus.common.NexusException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * CONDITION node — evaluates a simple boolean expression and returns true/false.
 * The WorkflowExecutionEngine uses this result to follow the correct outgoing edge
 * (sourceHandle "true" or "false").
 *
 * Expected node config keys:
 *   leftValue   — {{variable}} or literal
 *   operator    — "eq" | "neq" | "gt" | "lt" | "gte" | "lte" | "contains" | "isEmpty" | "isNotEmpty"
 *   rightValue  — {{variable}} or literal (not needed for isEmpty / isNotEmpty)
 *
 * Output: Boolean
 */
@Component
public class ConditionExecutor implements StepExecutor {

    @Override
    public String nodeType() {
        return "CONDITION";
    }

    @Override
    public Object execute(String nodeId, Map<String, Object> config, ExecutionContext ctx) {
        String leftTemplate  = getString(config, "leftValue");
        String operator      = getString(config, "operator");
        String rightTemplate = getString(config, "rightValue");

        if (operator == null || operator.isBlank())
            throw new NexusException(HttpStatus.BAD_REQUEST, "CONDITION node '" + nodeId + "' missing operator");

        Object left  = leftTemplate  == null ? null : VariableResolver.resolve(leftTemplate, ctx);
        Object right = rightTemplate == null ? null : VariableResolver.resolve(rightTemplate, ctx);

        return evaluate(left, operator, right, nodeId);
    }

    private boolean evaluate(Object left, String operator, Object right, String nodeId) {
        return switch (operator.toLowerCase()) {
            case "isempty"    -> isEmpty(left);
            case "isnotempty" -> !isEmpty(left);
            case "eq"         -> compareStrings(left, right) == 0;
            case "neq"        -> compareStrings(left, right) != 0;
            case "contains"   -> left != null && right != null &&
                                 left.toString().toLowerCase().contains(right.toString().toLowerCase());
            case "gt"         -> toDouble(left) > toDouble(right);
            case "lt"         -> toDouble(left) < toDouble(right);
            case "gte"        -> toDouble(left) >= toDouble(right);
            case "lte"        -> toDouble(left) <= toDouble(right);
            default -> throw new NexusException(HttpStatus.BAD_REQUEST,
                    "CONDITION node '" + nodeId + "' unknown operator: " + operator);
        };
    }

    private boolean isEmpty(Object v) {
        if (v == null) return true;
        if (v instanceof String s) return s.isBlank();
        if (v instanceof java.util.Collection<?> c) return c.isEmpty();
        return false;
    }

    private int compareStrings(Object a, Object b) {
        String sa = a == null ? "" : a.toString();
        String sb = b == null ? "" : b.toString();
        return sa.compareToIgnoreCase(sb);
    }

    private double toDouble(Object v) {
        if (v == null) return 0;
        try { return Double.parseDouble(v.toString()); } catch (NumberFormatException e) { return 0; }
    }

    private String getString(Map<String, Object> config, String key) {
        Object v = config.get(key);
        return v == null ? null : v.toString();
    }
}
