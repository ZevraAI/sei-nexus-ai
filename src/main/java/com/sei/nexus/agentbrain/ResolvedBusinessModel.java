package com.sei.nexus.agentbrain;

import com.sei.nexus.semantic.ResolvedQuestion;
import com.sei.nexus.semanticmodel.ColumnValueDomain;
import com.sei.nexus.semanticmodel.BusinessObject;
import com.sei.nexus.semanticmodel.PhysicalColumn;
import com.sei.nexus.semanticmodel.PhysicalTable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AgentBrain's business-reasoning output (ADR-0003 semantic model, Phase 1B): the resolved
 * semantic scope (canonical {@link BusinessObject}s, ranked by relevance) plus the execution-plane
 * raw material for those objects/attributes, carried through from the assembler so
 * {@link ExecutionContractBuilder} can compile the bindings. AgentBrain reasons over the semantic
 * objects; the physical targets are pass-through (AgentBrain does not interpret them).
 *
 * <p><b>Unified Answer Engine, Phase 2.</b> The model additionally carries the Semantic
 * Foundation signals AgentBrain resolved for this question — the {@link ResolvedQuestion}
 * (business-language resolutions, expansion tokens, literal candidates) and the derived literal
 * validation scope. Both are empty for a scope with no business domains, which reproduces the
 * pre-Phase-2 behaviour byte for byte.
 */
public record ResolvedBusinessModel(
        String                      agentId,
        List<String>                connectionKeys,
        String                      question,
        List<BusinessObject>        objects,
        Map<String, PhysicalTable>  objectTargets,
        Map<String, PhysicalColumn> attributeTargets,
        ResolvedQuestion            resolution,
        Map<String, ColumnValueDomain> literalScope,
        // Concept-Scoped Metadata Narrowing — downstream context boundary: true only when
        // AgentBrain's Stage-1/Stage-2 concept-scoped resolution actually produced this model
        // (see AgentBrain#conceptScopedModel). When true, objectTargets().keySet() is the
        // AUTHORITATIVE, Stage-2-resolved physical scope for this request — every downstream
        // context channel (not just the physical-schema block PromptAssembler already renders)
        // must restrict itself to these object keys rather than falling back to a broader,
        // domain-wide retrieval. False for every pre-existing fallback path (no active pack, no
        // tenant concept catalog, resolver unavailable, or narrowing inapplicable) — those
        // callers are unaffected and keep their exact prior behavior.
        boolean conceptScoped,
        // Decision Router absorption (Persistent Knowledge combined concept+routing call):
        // present only when the caller explicitly requested routing (AgentBrain#resolve's
        // memoryAvailable-carrying overload) AND the File Search Stage 1 path actually produced
        // one for at least one in-scope connection — see AgentBrain#conceptScopedModelWithRouting.
        // Empty for every pre-existing caller/overload (autonomous agents, the legacy
        // conversationId-free/-bearing overloads, the legacy catalog-in-prompt fallback), which
        // must keep calling ChatService's own getLlmDecision() exactly as before. Java never
        // constructs or infers this value itself — it is relayed verbatim from the LLM's own
        // combined response.
        Optional<ConceptScopedMetadataResolver.RoutingDecision> routingDecision
) {
    public ResolvedBusinessModel {
        connectionKeys   = List.copyOf(connectionKeys);
        objects          = List.copyOf(objects);
        objectTargets    = Map.copyOf(objectTargets);
        attributeTargets = Map.copyOf(attributeTargets);
        if (resolution == null)   resolution   = ResolvedQuestion.empty(question);
        literalScope = literalScope == null ? Map.of() : Map.copyOf(literalScope);
        routingDecision = routingDecision == null ? Optional.empty() : routingDecision;
    }

    /** Pre-existing 9-arg shape (Concept-Scoped Metadata Narrowing, no routing absorption) —
     *  every caller that predates Decision Router absorption reaches this overload, so
     *  {@code routingDecision} is always {@link Optional#empty()} for them. */
    public ResolvedBusinessModel(String agentId, List<String> connectionKeys, String question,
                                 List<BusinessObject> objects,
                                 Map<String, PhysicalTable> objectTargets,
                                 Map<String, PhysicalColumn> attributeTargets,
                                 ResolvedQuestion resolution,
                                 Map<String, ColumnValueDomain> literalScope,
                                 boolean conceptScoped) {
        this(agentId, connectionKeys, question, objects, objectTargets, attributeTargets,
                resolution, literalScope, conceptScoped, Optional.empty());
    }

    /** Pre-existing Semantic Foundation shape, concept-scoping unknown/inapplicable — defaults
     *  to {@code false}, exactly the fallback-path behavior every caller of this overload has
     *  always exhibited. */
    public ResolvedBusinessModel(String agentId, List<String> connectionKeys, String question,
                                 List<BusinessObject> objects,
                                 Map<String, PhysicalTable> objectTargets,
                                 Map<String, PhysicalColumn> attributeTargets,
                                 ResolvedQuestion resolution,
                                 Map<String, ColumnValueDomain> literalScope) {
        this(agentId, connectionKeys, question, objects, objectTargets, attributeTargets,
                resolution, literalScope, false, Optional.empty());
    }

    /** A model with no Semantic Foundation enrichment (a scope with no business domains). */
    public ResolvedBusinessModel(String agentId, List<String> connectionKeys, String question,
                                 List<BusinessObject> objects,
                                 Map<String, PhysicalTable> objectTargets,
                                 Map<String, PhysicalColumn> attributeTargets) {
        this(agentId, connectionKeys, question, objects, objectTargets, attributeTargets,
                ResolvedQuestion.empty(question), Map.of());
    }
}
