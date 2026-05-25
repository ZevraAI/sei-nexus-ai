package com.sei.nexus.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.automation.WorkflowGraph.WorkflowEdge;
import com.sei.nexus.automation.WorkflowGraph.WorkflowNode;
import com.sei.nexus.common.NexusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Traverses a WorkflowGraph as a DAG and executes each node in topological order.
 * CONDITION nodes fork the traversal path based on the boolean result.
 * All step results are accumulated into StepTrace records for full observability.
 */
@Component
public class WorkflowExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutionEngine.class);
    private static final int MAX_NODES = 50;

    private final Map<String, StepExecutor> executors;
    private final ObjectMapper mapper;

    public WorkflowExecutionEngine(List<StepExecutor> executorList, ObjectMapper mapper) {
        this.executors = executorList.stream()
                .collect(Collectors.toMap(StepExecutor::nodeType, Function.identity()));
        this.mapper = mapper;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * @param graphJson      raw JSONB workflow graph from the database
     * @param triggerPayload the webhook/manual trigger payload
     * @return ExecutionResult containing all step traces and the final output
     */
    public ExecutionResult execute(String graphJson, Map<String, Object> triggerPayload) {
        WorkflowGraph graph;
        try {
            graph = mapper.readValue(graphJson, WorkflowGraph.class);
        } catch (Exception e) {
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Invalid workflow graph JSON: " + e.getMessage());
        }

        ExecutionContext ctx = new ExecutionContext();
        ctx.set("trigger", triggerPayload);

        Map<String, WorkflowNode> nodeMap = graph.nodes().stream()
                .collect(Collectors.toMap(WorkflowNode::id, Function.identity()));

        List<StepTrace> traces = new ArrayList<>();
        Object finalOutput = null;

        // Find the TRIGGER node — it is always the entry point
        WorkflowNode startNode = graph.nodes().stream()
                .filter(n -> "TRIGGER".equals(n.type()))
                .findFirst()
                .orElseThrow(() -> new NexusException(HttpStatus.BAD_REQUEST,
                        "Workflow has no TRIGGER node"));

        String currentNodeId = startNode.id();
        int steps = 0;

        while (currentNodeId != null && steps++ < MAX_NODES) {
            WorkflowNode node = nodeMap.get(currentNodeId);
            if (node == null) break;

            StepTrace trace = executeNode(node, ctx);
            traces.add(trace);

            if ("FAILED".equals(trace.status())) {
                return new ExecutionResult("FAILED", traces, null, trace.errorMessage());
            }

            // Advance to next node
            if ("RESPONSE".equals(node.type())) {
                finalOutput = trace.output();
                break;
            }

            if ("CONDITION".equals(node.type())) {
                boolean branch = Boolean.TRUE.equals(trace.output());
                currentNodeId = nextNodeId(graph.edges(), currentNodeId, branch ? "true" : "false");
            } else {
                currentNodeId = nextNodeId(graph.edges(), currentNodeId, null);
            }

            ctx.set("__lastOutput", trace.output());
        }

        return new ExecutionResult("SUCCESS", traces, finalOutput, null);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private StepTrace executeNode(WorkflowNode node, ExecutionContext ctx) {
        StepExecutor executor = executors.get(node.type());
        if (executor == null) {
            return failTrace(node, "No executor registered for node type: " + node.type(), 0);
        }

        Map<String, Object> config = node.data() != null ? node.data() : Map.of();
        String label = config.containsKey("label") ? config.get("label").toString() : node.type();

        Map<String, Object> inputSnapshot = snapshotInputs(config, ctx);
        long start = System.currentTimeMillis();

        try {
            Object output = executor.execute(node.id(), config, ctx);
            long durationMs = System.currentTimeMillis() - start;

            ctx.set("nodes." + node.id(), output);

            String sqlExecuted = config.containsKey("sql")
                    ? resolveForTrace(config.get("sql").toString(), ctx)
                    : null;

            return new StepTrace(node.id(), node.type(), label, "SUCCESS",
                    inputSnapshot, output, sqlExecuted, null, durationMs);

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - start;
            log.error("Node {} ({}) failed: {}", node.id(), node.type(), e.getMessage(), e);
            return failTrace(node, e.getMessage(), durationMs);
        }
    }

    private StepTrace failTrace(WorkflowNode node, String error, long durationMs) {
        String label = node.data() != null && node.data().containsKey("label")
                ? node.data().get("label").toString() : node.type();
        return new StepTrace(node.id(), node.type(), label, "FAILED",
                Map.of(), null, null, error, durationMs);
    }

    private String nextNodeId(List<WorkflowEdge> edges, String sourceId, String sourceHandle) {
        for (WorkflowEdge edge : edges) {
            if (!sourceId.equals(edge.source())) continue;
            if (sourceHandle == null || sourceHandle.equals(edge.sourceHandle())) {
                return edge.target();
            }
        }
        return null;
    }

    private Map<String, Object> snapshotInputs(Map<String, Object> config, ExecutionContext ctx) {
        Map<String, Object> snap = new HashMap<>();
        config.forEach((k, v) -> {
            if (v instanceof String s && s.contains("{{")) {
                Object resolved = com.sei.nexus.automation.VariableResolver.resolve(s, ctx);
                snap.put(k, resolved);
            } else {
                snap.put(k, v);
            }
        });
        return snap;
    }

    private String resolveForTrace(String template, ExecutionContext ctx) {
        try {
            return com.sei.nexus.automation.VariableResolver.resolve(template, ctx).toString();
        } catch (Exception e) {
            return template;
        }
    }

    // ── Result record ─────────────────────────────────────────────────────────

    public record ExecutionResult(
            String status,
            List<StepTrace> stepTraces,
            Object finalOutput,
            String errorMessage
    ) {}
}
