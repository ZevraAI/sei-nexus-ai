package com.sei.nexus.automation.executor;

import com.sei.nexus.automation.ExecutionContext;
import com.sei.nexus.automation.StepExecutor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * TRIGGER node — no processing needed.
 * The webhook payload is already in ctx["trigger"] before the engine starts.
 * This executor simply echoes the trigger payload so it appears in the step trace.
 */
@Component
public class TriggerExecutor implements StepExecutor {

    @Override
    public String nodeType() {
        return "TRIGGER";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(String nodeId, Map<String, Object> config, ExecutionContext ctx) {
        Object trigger = ctx.get("trigger");
        return trigger instanceof Map ? trigger : Map.of("received", true);
    }
}
