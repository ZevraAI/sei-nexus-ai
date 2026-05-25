package com.sei.nexus.automation;

import java.util.Map;

/**
 * Strategy interface — one implementation per node type.
 * Implementations are Spring beans; WorkflowExecutionEngine resolves them by nodeType string.
 */
public interface StepExecutor {

    /** The node type string this executor handles (e.g. "TRIGGER", "DB_QUERY"). */
    String nodeType();

    /**
     * Execute the node and return its output value.
     * The output is stored in ctx under "nodes.<nodeId>" and in the StepTrace.
     *
     * @param nodeId  unique node ID from the graph
     * @param config  the node's data map from the graph (user-configured fields)
     * @param ctx     mutable execution context — read prior outputs, write this node's output
     * @return        the node's output — may be a String, List, Map, Boolean, etc.
     * @throws Exception on any unrecoverable step failure
     */
    Object execute(String nodeId, Map<String, Object> config, ExecutionContext ctx) throws Exception;
}
