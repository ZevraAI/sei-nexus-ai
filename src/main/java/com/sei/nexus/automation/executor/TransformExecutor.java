package com.sei.nexus.automation.executor;

import com.sei.nexus.automation.ExecutionContext;
import com.sei.nexus.automation.StepExecutor;
import com.sei.nexus.automation.VariableResolver;
import com.sei.nexus.common.NexusException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TRANSFORM node — maps/extracts fields from a prior node's output into a new shape.
 *
 * Expected node config keys:
 *   mappings — List of { "key": "outputField", "value": "{{variable.path}}" }
 *
 * Output: Map<String, Object> — the assembled output object
 */
@Component
public class TransformExecutor implements StepExecutor {

    @Override
    public String nodeType() {
        return "TRANSFORM";
    }

    @Override
    public Object execute(String nodeId, Map<String, Object> config, ExecutionContext ctx) {
        Object rawMappings = config.get("mappings");
        if (rawMappings == null)
            throw new NexusException(HttpStatus.BAD_REQUEST, "TRANSFORM node '" + nodeId + "' missing mappings");

        if (!(rawMappings instanceof List<?> list))
            throw new NexusException(HttpStatus.BAD_REQUEST, "TRANSFORM node '" + nodeId + "' mappings must be a list");

        Map<String, Object> output = new LinkedHashMap<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> mapping)) continue;
            String key      = String.valueOf(mapping.get("key"));
            String template = String.valueOf(mapping.get("value"));
            output.put(key, VariableResolver.resolve(template, ctx));
        }
        return output;
    }
}
