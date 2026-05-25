package com.sei.nexus.automation;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Mutable variable store threaded through an entire workflow execution.
 * Each node reads its resolved inputs from here and writes its output back.
 *
 * Key convention:  "trigger"          → the raw trigger payload Map
 *                  "nodes.<nodeId>"   → the output produced by that node
 */
public class ExecutionContext {

    private final Map<String, Object> vars = new HashMap<>();

    public void set(String key, Object value) {
        vars.put(key, value);
    }

    public Object get(String key) {
        return vars.get(key);
    }

    public boolean has(String key) {
        return vars.containsKey(key);
    }

    /** Snapshot of all variables — used for step trace input logging. */
    public Map<String, Object> snapshot() {
        return Collections.unmodifiableMap(vars);
    }
}
