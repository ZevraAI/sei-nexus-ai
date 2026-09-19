package com.sei.nexus.agentbrain;

import com.sei.nexus.semantic.ResolvedQuestion;
import com.sei.nexus.semanticmodel.ColumnValueDomain;
import com.sei.nexus.semanticmodel.BusinessObject;
import com.sei.nexus.semanticmodel.PhysicalColumn;
import com.sei.nexus.semanticmodel.PhysicalTable;
import com.sei.nexus.semanticmodel.SemanticModel;

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
        Optional<ConceptScopedMetadataResolver.RoutingDecision> routingDecision,
        // Execution-authorization scope, kept SEPARATE from `objects` (Concept-Scoped Metadata
        // Narrowing's prompt-rendering selection). `objects`/`objectTargets`/`attributeTargets`
        // above control what gets rendered in detail to the model — a token-budget/relevance
        // concern this class's own javadoc already says must "never narrow what the caller may
        // execute." `executionScope`, when present, is the full, deterministic, domain/connection
        // -scoped object set — identical to what `objects` would already be had narrowing never
        // run (see AgentBrain#resolve) — and is what ExecutionContractBuilder must compile
        // approvedAssets from, so execution authorization never depends on which concepts an LLM
        // call happened to select for THIS question's prompt. Optional.empty() for every caller
        // that predates this field (every pre-existing constructor overload below), in which case
        // ExecutionContractBuilder falls back to `objects` — byte-identical to before this field
        // existed.
        Optional<SemanticModel> executionScope,
        // Concept-Key Semantic Anchor design: the exact concept_key(s) Stage 1 selected for this
        // request (see ConceptScopedMetadataResolver#resolveConceptKeys / AgentBrain's
        // conceptScopedModel*), carried through verbatim — never rederived, normalized, or
        // filtered semantically here. This is DETERMINISTIC SCOPING IDENTITY ONLY: it exists so a
        // downstream, exact-key lookup (LearnedMappingRepository#findPromotedByConceptKeys) can
        // retrieve concept-scoped learned-knowledge evidence; it is never itself a semantic
        // decision, and nothing in this class or its callers may use it to choose or apply a
        // learning. Empty for every pre-existing constructor overload below (no concept-scoped
        // narrowing occurred, or narrowing ran through a path that predates this field) —
        // reproducing prior behavior byte for byte.
        List<String> resolvedConceptKeys,
        // Concept-Level Disjunctive Ambiguity design: present, carrying a concept-level
        // clarification question authored verbatim by Agent Brain (Stage 1), only when Stage 1
        // explicitly marked its own concept selection "AMBIGUOUS" — the question could plausibly
        // mean any ONE of two or more DIFFERENT, mutually-exclusive business concepts, and nothing
        // resolves which one (see AgentBrain#conceptScopedModelWithRouting and
        // ConceptScopedMetadataResolver's CONCEPT_RESOLUTION_TYPE_RULES). Java never constructs or
        // infers this text — it is relayed verbatim from the LLM's own response, exactly like
        // {@link #routingDecision}. When present, {@code objects}/{@code objectTargets}/{@code
        // attributeTargets}/{@code executionScope} are ALL empty — no physical object is ever
        // approved for an unresolved ambiguity — and the caller (ChatService) MUST terminate the
        // request into a clarification response without compiling an ExecutionContract or
        // retrieving physical metadata. Empty for every pre-existing constructor overload below
        // (no ambiguity signal existed before this field), reproducing prior behavior byte for byte.
        Optional<String> conceptAmbiguityClarification
) {
    public ResolvedBusinessModel {
        connectionKeys   = List.copyOf(connectionKeys);
        objects          = List.copyOf(objects);
        objectTargets    = Map.copyOf(objectTargets);
        attributeTargets = Map.copyOf(attributeTargets);
        if (resolution == null)   resolution   = ResolvedQuestion.empty(question);
        literalScope = literalScope == null ? Map.of() : Map.copyOf(literalScope);
        routingDecision = routingDecision == null ? Optional.empty() : routingDecision;
        executionScope  = executionScope  == null ? Optional.empty() : executionScope;
        resolvedConceptKeys = resolvedConceptKeys == null ? List.of() : List.copyOf(resolvedConceptKeys);
        conceptAmbiguityClarification = conceptAmbiguityClarification == null
                ? Optional.empty() : conceptAmbiguityClarification;
    }

    /** Pre-existing 12-arg shape (Concept-Key Semantic Anchor design, no ambiguity signal) —
     *  {@code conceptAmbiguityClarification} defaults to empty, exactly the fallback behavior
     *  every caller of this overload has always exhibited. */
    public ResolvedBusinessModel(String agentId, List<String> connectionKeys, String question,
                                 List<BusinessObject> objects,
                                 Map<String, PhysicalTable> objectTargets,
                                 Map<String, PhysicalColumn> attributeTargets,
                                 ResolvedQuestion resolution,
                                 Map<String, ColumnValueDomain> literalScope,
                                 boolean conceptScoped,
                                 Optional<ConceptScopedMetadataResolver.RoutingDecision> routingDecision,
                                 Optional<SemanticModel> executionScope,
                                 List<String> resolvedConceptKeys) {
        this(agentId, connectionKeys, question, objects, objectTargets, attributeTargets,
                resolution, literalScope, conceptScoped, routingDecision, executionScope,
                resolvedConceptKeys, Optional.empty());
    }

    /** Pre-existing 11-arg shape (Decision Router absorption + separate execution-authorization
     *  scope, no concept-key carry-through) — {@code resolvedConceptKeys} defaults to empty,
     *  exactly the fallback behavior every caller of this overload has always exhibited. */
    public ResolvedBusinessModel(String agentId, List<String> connectionKeys, String question,
                                 List<BusinessObject> objects,
                                 Map<String, PhysicalTable> objectTargets,
                                 Map<String, PhysicalColumn> attributeTargets,
                                 ResolvedQuestion resolution,
                                 Map<String, ColumnValueDomain> literalScope,
                                 boolean conceptScoped,
                                 Optional<ConceptScopedMetadataResolver.RoutingDecision> routingDecision,
                                 Optional<SemanticModel> executionScope) {
        this(agentId, connectionKeys, question, objects, objectTargets, attributeTargets,
                resolution, literalScope, conceptScoped, routingDecision, executionScope, List.of());
    }

    /** Pre-existing 10-arg shape (Decision Router absorption, no separate execution-authorization
     *  scope) — {@code executionScope} defaults to empty, so {@link ExecutionContractBuilder}
     *  falls back to {@code objects}, exactly as before this field existed. */
    public ResolvedBusinessModel(String agentId, List<String> connectionKeys, String question,
                                 List<BusinessObject> objects,
                                 Map<String, PhysicalTable> objectTargets,
                                 Map<String, PhysicalColumn> attributeTargets,
                                 ResolvedQuestion resolution,
                                 Map<String, ColumnValueDomain> literalScope,
                                 boolean conceptScoped,
                                 Optional<ConceptScopedMetadataResolver.RoutingDecision> routingDecision) {
        this(agentId, connectionKeys, question, objects, objectTargets, attributeTargets,
                resolution, literalScope, conceptScoped, routingDecision, Optional.empty(), List.of());
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
                resolution, literalScope, conceptScoped, Optional.empty(), Optional.empty());
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
