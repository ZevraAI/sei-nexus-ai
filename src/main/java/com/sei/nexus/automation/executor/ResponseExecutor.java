package com.sei.nexus.automation.executor;

import com.sei.nexus.automation.ExecutionContext;
import com.sei.nexus.automation.StepExecutor;
import com.sei.nexus.automation.VariableResolver;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RESPONSE node — assembles the final output returned to the caller.
 *
 * Expected node config keys:
 *   fields — List of { "key": "label", "value": "{{variable.path}}" }
 *            OR empty → returns the last node's output as-is
 *
 * Output: Map<String, Object> — the structured response payload
 */
@Component
public class ResponseExecutor implements StepExecutor {

    @Override
    public String nodeType() {
        return "RESPONSE";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(String nodeId, Map<String, Object> config, ExecutionContext ctx) {
        Object rawFields = config.get("fields");

        if (rawFields instanceof List<?> list && !list.isEmpty()) {
            Map<String, Object> output = new LinkedHashMap<>();
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?> field)) continue;
                String key      = String.valueOf(field.get("key"));
                String template = String.valueOf(field.get("value"));
                output.put(key, VariableResolver.resolve(template, ctx));
            }
            return output;
        }

        // No fields configured — surface the last contextual output
        Object lastOutput = ctx.get("__lastOutput");
        return lastOutput != null ? lastOutput : Map.of("status", "completed");
    }
}
