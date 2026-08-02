package com.sei.nexus.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The immutable, factual record of <b>what actually executed</b> (Execution Continuity).
 *
 * <p>Produced only by {@link GovernedSqlRuntime} after a successful execution. It carries two
 * kinds of information, and nothing else:
 * <ul>
 *   <li><b>Execution facts</b> the runtime observed (identity, lineage pointer, connection,
 *       timing, governance outcome, result shape/reference, SQL pointer for audit); and</li>
 *   <li><b>A semantic snapshot copied verbatim from the {@link com.sei.nexus.agentbrain.ExecutionContract}</b>
 *       (contract id, retrieval target(s), business object/attribute bindings, approved assets).</li>
 * </ul>
 *
 * <p><b>Invariant (frozen architecture):</b> an ExecutionReference contains only intrinsic facts
 * of one execution. It never contains comparisons, deltas, lineage <i>facts</i>, intent, or any
 * derived interpretation. Runtime copies the semantic snapshot — it never derives it — and never
 * compares one execution to another. It answers exactly one question: <i>what actually executed?</i>
 *
 * <p>{@code parentExecutionId} is supplied by AgentBrain/orchestration and recorded verbatim;
 * Runtime never decides lineage.
 */
public record ExecutionReference(
        // ── Execution facts (produced by Runtime) ──
        String executionId,
        String parentExecutionId,            // nullable — supplied, never decided by Runtime
        String conversationId,               // nullable
        String runKey,
        String connectionKey,
        Instant startedAt,
        Instant completedAt,
        long executionMs,
        String governanceOutcome,            // factual route/classification, e.g. "EXECUTE_SYNC"
        int rowCount,
        List<String> resultColumns,
        String resultJson,                   // self-contained result reference (audit/debug + drill-down)
        String sqlReference,                 // audit only — the governed SQL

        // ── Semantic snapshot (copied verbatim from ExecutionContract; never derived) ──
        String contractId,
        String semanticHash,
        List<String> retrievalTargets,               // schema.table, copied from object bindings
        Map<String, String> businessObjectBindings,  // objectKey    -> schema.table
        Map<String, String> businessAttributeBindings, // attributeKey -> schema.table.column
        List<String> approvedAssets                  // connectionKey:canonicalIdentifier
) {
    public ExecutionReference {
        resultColumns             = resultColumns == null ? List.of() : List.copyOf(resultColumns);
        retrievalTargets          = retrievalTargets == null ? List.of() : List.copyOf(retrievalTargets);
        businessObjectBindings    = businessObjectBindings == null ? Map.of() : Map.copyOf(businessObjectBindings);
        businessAttributeBindings = businessAttributeBindings == null ? Map.of() : Map.copyOf(businessAttributeBindings);
        approvedAssets            = approvedAssets == null ? List.of() : List.copyOf(approvedAssets);
    }
}
