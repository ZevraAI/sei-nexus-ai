package com.sei.nexus.reasoning;

import com.sei.nexus.agentbrain.ExecutionContract;
import com.sei.nexus.agentbrain.PromptAssembler;
import com.sei.nexus.agentbrain.PromptContext;
import com.sei.nexus.agentbrain.PromptContextBuilder;
import com.sei.nexus.semanticmodel.BusinessObject;
import com.sei.nexus.semanticmodel.EnterpriseSemanticAssembler;
import com.sei.nexus.semanticmodel.PhysicalTable;
import com.sei.nexus.semanticmodel.SemanticModel;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * Missing-Column Metadata Request (additive extension to the Reasoning Planner loop): the
 * runtime side of {@link ReasoningPlanner.StepPlan#metadataRequest()}.
 *
 * <p>This class performs exactly the two things Java is allowed to do for a metadata request,
 * and nothing else:
 * <ol>
 *   <li>Validate that the requested object exists — by exact, case-insensitive identity match
 *       (physical table, schema-qualified table, or business name) against authoritative
 *       metadata: first the request's already-resolved {@link ExecutionContract} (the common,
 *       fast-path case), and, when not found there, the wider Enterprise Map catalog for the
 *       SAME connections already approved for this investigation (see {@link #resolveColumns}
 *       for why the second step exists and why it never widens access). No fuzzy matching, no
 *       ranking, no interpretation of the user's question anywhere in this class.</li>
 *   <li>Retrieve that object's authoritative column detail via the existing, unbounded
 *       single-object rendering ({@link PromptAssembler#assembleObject}) and return it (or
 *       {@link Optional#empty()} when the object exists nowhere in that authoritative universe)
 *       — {@link ReasoningEngine} decides what to do with either outcome.</li>
 * </ol>
 *
 * <p>What this class deliberately never does: interpret the user's original question, search or
 * rank columns/objects by name similarity, map a business term to a physical column/object, or
 * substitute one for another. Which object to request is entirely Agent Brain's (the LLM's)
 * decision; this class only confirms the LLM's own answer against real metadata and relays it.
 */
@Component
public class ColumnMetadataRequestHandler {

    private final PromptContextBuilder         contextBuilder;
    private final PromptAssembler              assembler;
    private final EnterpriseSemanticAssembler  enterpriseAssembler;

    public ColumnMetadataRequestHandler(PromptContextBuilder contextBuilder, PromptAssembler assembler,
                                        EnterpriseSemanticAssembler enterpriseAssembler) {
        this.contextBuilder      = contextBuilder;
        this.assembler           = assembler;
        this.enterpriseAssembler = enterpriseAssembler;
    }

    /**
     * @param contract        The compiled execution contract for this request. Its {@link
     *                        ExecutionContract#semanticView()} is checked first (the common case
     *                        — the object is already part of this request's resolved/narrowed
     *                        scope). When the object is not found there, its {@link
     *                        ExecutionContract#connectionKeys()} — the connections already
     *                        approved for this investigation, unrelated to which objects on them
     *                        happen to be resolved for this particular question — are used to
     *                        look the object up in the full Enterprise Map catalog for those same
     *                        connections. This closes the circular dependency where an object
     *                        Agent Brain has not yet caused to be resolved (e.g. Concept-Scoped
     *                        Metadata Narrowing did not select it) could never be requested,
     *                        because requesting it was itself gated on it already being resolved.
     *                        The fallback never queries a connection beyond what {@code contract}
     *                        already approved — it only widens WHICH of those connections' known
     *                        objects can be looked up by exact identity, never WHICH connections.
     *                        Never {@code null} at the call site (see {@link ReasoningEngine}); a
     *                        defensive {@code null}/empty check still returns empty rather than
     *                        throwing.
     * @param requestedObject The {@code object} field of the planner's {@code requires_metadata}
     *                        response — expected to be a physical table name (bare or
     *                        schema-qualified) or business name copied verbatim from context
     *                        already shown to the model. Relayed verbatim into an exact-match
     *                        lookup; Java never interprets, tokenizes, or reasons about it.
     * @return The object's full, unbounded column detail, or {@link Optional#empty()} when
     *         {@code requestedObject} does not exactly match any object either already resolved
     *         for this request or known to the Enterprise Map for its approved connections — i.e.
     *         the request names something that does not exist in Zevra's authoritative metadata
     *         for this investigation, and must be rejected, never guessed at or substituted.
     */
    public Optional<String> resolveColumns(ExecutionContract contract, String requestedObject) {
        if (contract == null || requestedObject == null || requestedObject.isBlank()) {
            return Optional.empty();
        }
        String needle = requestedObject.trim();

        PromptContext resolved = contextBuilder.build(contract);
        for (PromptContext.PromptObject o : resolved.objects()) {
            if (matches(o, needle)) {
                return Optional.of(assembler.assembleObject(o));
            }
        }

        // Not yet compiled into this request's resolved scope — fall back to the authoritative
        // Enterprise Map catalog for the same already-approved connections. Still a pure exact-
        // identity lookup, never a search: every candidate object is compared the same way as
        // above, and the first (only) exact match wins.
        if (enterpriseAssembler != null && contract.connectionKeys() != null
                && !contract.connectionKeys().isEmpty()) {
            SemanticModel catalog = enterpriseAssembler.assemble(contract.connectionKeys());
            for (BusinessObject object : catalog.objects()) {
                PhysicalTable table = catalog.objectTargets().get(object.objectKey());
                PromptContext.PromptObject candidate =
                        contextBuilder.buildObject(object, table, catalog.attributeTargets());
                if (matches(candidate, needle)) {
                    return Optional.of(assembler.assembleObject(candidate));
                }
            }
        }

        return Optional.empty();
    }

    /** Exact (case-insensitive) match only — bare table, schema-qualified table, or business name. */
    private static boolean matches(PromptContext.PromptObject o, String needle) {
        String bareTable = o.physicalTable();
        if (equalsIgnoreCase(bareTable, needle)) return true;
        if (o.schema() != null && !o.schema().isBlank()
                && equalsIgnoreCase(o.schema().trim() + "." + bareTable, needle)) {
            return true;
        }
        return equalsIgnoreCase(o.businessName(), needle);
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a != null && b != null && a.trim().toLowerCase(Locale.ROOT).equals(b.trim().toLowerCase(Locale.ROOT));
    }
}
