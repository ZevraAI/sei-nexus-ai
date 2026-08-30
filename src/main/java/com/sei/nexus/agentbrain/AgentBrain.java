package com.sei.nexus.agentbrain;

import com.sei.nexus.agentrunner.ZevraAgent;
import com.sei.nexus.semantic.BusinessLanguageResolver;
import com.sei.nexus.semantic.ResolvedQuestion;
import com.sei.nexus.semanticmodel.ColumnValueDomain;
import com.sei.nexus.semanticmodel.BusinessAttribute;
import com.sei.nexus.semanticmodel.BusinessObject;
import com.sei.nexus.semanticmodel.EnterpriseSemanticAssembler;
import com.sei.nexus.semanticmodel.PhysicalColumn;
import com.sei.nexus.semanticmodel.PhysicalTable;
import com.sei.nexus.semanticmodel.SemanticModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The agent business-reasoning layer (ADR-0003 semantic model, Phase 1B) and, from Phase 2 of
 * the Unified Answer Engine, the single owner of business reasoning for <em>every</em> execution
 * experience.
 *
 * <p>AgentBrain reasons over the canonical semantic model only — {@link BusinessObject},
 * {@link BusinessAttribute}, and relationships — obtained from the
 * {@link EnterpriseSemanticAssembler}. It never depends on persistence entities
 * ({@code DataObject}/{@code DataColumn}/repositories). It resolves the request against the
 * approved business objects, decides the execution scope, ranks objects by relevance for
 * grounding, and produces a {@link ResolvedBusinessModel}. It does not compile the contract,
 * execute SQL, perform governance, enforce, or build prompts.
 *
 * <p><b>Phase 2 — Semantic Foundation.</b> When the scope carries business domains, AgentBrain
 * also consults {@link BusinessLanguageResolver}: the resulting business-language resolutions,
 * expansion tokens, and literal candidates travel on the {@link ResolvedBusinessModel}, and the
 * canonical expansion tokens join the keywords used to rank objects — so grounding is selected as
 * if the user had spoken canonically. A scope with no domains (today's autonomous agents) skips
 * resolution entirely and behaves byte-identically to Phase 1.
 *
 * <p><b>Phase 3 — business scope.</b> The approved execution surface must represent the same
 * business scope the experience is grounded in, so AgentBrain derives it: a domain-bearing scope
 * (conversational) resolves to those domains narrowed to the approved connections, preserving
 * domain boundaries; a domain-free scope (autonomous agents) resolves to the agent's connections.
 * The question ranks objects for grounding — it never narrows what the caller may execute.
 */
@Service
public class AgentBrain {

    private final EnterpriseSemanticAssembler assembler;
    private final BusinessLanguageResolver    resolver;
    // Concept-Scoped Metadata Narrowing: nullable, same dual-constructor pattern already used by
    // EnterpriseSemanticAssembler/BusinessObjectBatchAnalyzer for an additive collaborator — every
    // existing test constructs AgentBrain via the 2-arg constructor below, so conceptResolver is
    // null for all of them and this feature is a complete no-op, byte-identical to before it
    // existed (see assembleBusinessScope).
    private final ConceptScopedMetadataResolver conceptResolver;

    /** Backward-compatible convenience (tests, and any caller that predates this feature):
     *  concept-scoped narrowing is inactive — {@code assembleBusinessScope} always falls back to
     *  the existing full assembly, exactly as before this feature existed. */
    public AgentBrain(EnterpriseSemanticAssembler assembler,
                      BusinessLanguageResolver resolver) {
        this(assembler, resolver, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentBrain(EnterpriseSemanticAssembler assembler,
                      BusinessLanguageResolver resolver,
                      ConceptScopedMetadataResolver conceptResolver) {
        this.assembler       = assembler;
        this.resolver        = resolver;
        this.conceptResolver = conceptResolver;
    }

    /**
     * Autonomous-agent scope: the agent's connections, no business domains — so no
     * business-language resolution is performed, exactly as before Phase 2.
     */
    public ResolvedBusinessModel resolve(ZevraAgent agent, String question) {
        return resolve(agent.id(), agent.connectionKeys(), List.of(), question);
    }

    /**
     * Resolves a question for any execution scope.
     *
     * @param agentId        identity recorded on the resolved model
     * @param connectionKeys the approved execution surface to assemble
     * @param domainKeys     business domains in scope; empty ⇒ no Semantic Foundation resolution
     */
    public ResolvedBusinessModel resolve(String agentId, List<String> connectionKeys,
                                         List<String> domainKeys, String question) {
        return resolve(agentId, connectionKeys, domainKeys, question, null);
    }

    /**
     * Same as {@link #resolve(String, List, List, String)}, additionally threading a {@code
     * conversationId} through to {@link ConceptScopedMetadataResolver}'s Stage 1 so the File
     * Search path (when enabled) can chain to the tenant's own prior turn in this conversation —
     * see {@link ConceptScopedMetadataResolver#resolveObjectKeys(String, String, String)}. Purely
     * additive: {@code null} (or a null {@link #conceptResolver}) reproduces the 4-arg overload's
     * behavior exactly.
     */
    public ResolvedBusinessModel resolve(String agentId, List<String> connectionKeys,
                                         List<String> domainKeys, String question, String conversationId) {
        return resolve(agentId, connectionKeys, domainKeys, question, conversationId, null);
    }

    /**
     * Same as {@link #resolve(String, List, List, String, String)}, additionally requesting the
     * Decision Router absorption: when {@code memoryAvailable} is non-null, the combined
     * Persistent Knowledge / File Search LLM call is asked to also produce a routing decision
     * (see {@link ConceptScopedMetadataResolver#resolveObjectKeysWithRouting}), returned on
     * {@link ResolvedBusinessModel#routingDecision()}. Passing {@code null} (the 5-arg overload)
     * requests no routing at all — the exact prior call graph — so every existing caller
     * (autonomous agents, any test) is entirely unaffected.
     *
     * @param memoryAvailable the one Java-computed runtime fact the LLM cannot obtain itself —
     *                        whether document memory was found relevant for this question. Only
     *                        meaningful when non-null; ignored (and no routing requested) when
     *                        {@code null}.
     */
    public ResolvedBusinessModel resolve(String agentId, List<String> connectionKeys,
                                         List<String> domainKeys, String question, String conversationId,
                                         Boolean memoryAvailable) {
        // Business-language resolution (PRO-31/33) — deterministic, domain-scoped, and
        // annotate-never-substitute: the question text is never rewritten.
        ResolvedQuestion resolution = (domainKeys == null || domainKeys.isEmpty())
                ? ResolvedQuestion.empty(question == null ? "" : question)
                : resolver.resolve(question, domainKeys);

        Optional<SemanticModel> conceptScoped;
        Optional<ConceptScopedMetadataResolver.RoutingDecision> routingDecision;
        if (memoryAvailable == null) {
            conceptScoped = conceptScopedModel(connectionKeys, question, conversationId);
            routingDecision = Optional.empty();
        } else {
            ConceptScopedModelResult r = conceptScopedModelWithRouting(connectionKeys, question, conversationId, memoryAvailable);
            conceptScoped = r.model();
            routingDecision = r.routing();
        }
        SemanticModel model = conceptScoped.orElseGet(() -> assembleByFallback(connectionKeys, domainKeys));

        // Rank the resolved objects by relevance to the request (business reasoning) so
        // grounding leads with what the user most likely means — without narrowing the surface.
        // Canonical tokens from the resolutions join the keywords, so a resolved term selects
        // objects even when the surface form never became a keyword.
        Set<String> keywords = keywords(question);
        keywords.addAll(resolution.expandedTokens());
        List<BusinessObject> ranked = new ArrayList<>(model.objects());
        ranked.sort(Comparator.comparingInt((BusinessObject o) -> -relevance(o, keywords)));

        return new ResolvedBusinessModel(agentId, connectionKeys, question,
                ranked, model.objectTargets(), model.attributeTargets(),
                resolution, literalScopeOf(resolution), conceptScoped.isPresent(), routingDecision);
    }

    // ── Business scope (owned here from Phase 3) ───────────────────────────────

    /**
     * Determines the approved business surface for a scope. This is a business-reasoning
     * decision — the approved surface must represent the <em>same business scope</em> the
     * experience is grounded in, so it is derived here rather than in the assembler (a
     * selection primitive) or the Runtime (deterministic, and unaware of business scope).
     *
     * <p><b>domain_keys vs. physical metadata retrieval (Concept-Scoped Metadata Narrowing —
     * domain-key decoupling):</b> {@code domainKeys} is a namespace/partition key consumed
     * independently by {@link BusinessLanguageResolver}, vocabulary/entity context, semantic
     * learning, the knowledge graph, RAG, and provenance tagging — all of that is untouched and
     * still driven by {@code domainKeys} exactly as before (see {@link #resolve}, which passes
     * it to {@link #resolver} regardless of what this method does). It is NOT the criterion for
     * <em>which physical objects/columns to retrieve</em> — that decision now belongs to {@link
     * ConceptScopedMetadataResolver} whenever it can make it (an active Pack + a non-empty
     * tenant concept catalog on every in-scope connection), independent of whether {@code
     * domainKeys} happens to be empty or not. Concretely:
     *
     * <ul>
     *   <li><b>Concept-scoped narrowing applies</b> (any scope — domain-free or domain-bearing):
     *       {@link #conceptScopedModel} resolves the physical surface. {@code assembleByDomains}
     *       is never called in this case.</li>
     *   <li><b>Concept-scoped narrowing does not apply</b> (no resolver wired, no connections, no
     *       active Pack on any in-scope connection, no tenant concept catalog yet, or any
     *       failure — see {@link #conceptScopedModel}'s own fallback discipline): falls through
     *       to exactly the pre-existing behavior —
     *       <ul>
     *         <li><b>No business domains</b> (autonomous agents): the scope is the agent's
     *             connections. Unchanged from Phase 2.</li>
     *         <li><b>Business domains present</b> (conversational): the scope is those domains,
     *             narrowed to the approved connections. Domain boundaries are preserved — an
     *             object is never admitted merely because it shares a connection with an
     *             in-scope object.</li>
     *         <li><b>Stale-connection fallback</b>: if narrowing would empty the scope, the
     *             domain scope is kept. A stale connection key on the agent record must not
     *             silently blank the surface — the same rule the conversational grounding has
     *             always applied.</li>
     *       </ul>
     *   </li>
     * </ul>
     */
    /** The exact pre-concept-scoping assembly — reached whenever {@link #conceptScopedModel}
     *  is empty (see this method's own javadoc, retained above the method it now backs). {@link
     *  #resolve} calls {@link #conceptScopedModel} directly and falls back to this method so it
     *  can also record, alongside the model, whether concept-scoping actually produced it (see
     *  {@link ResolvedBusinessModel#conceptScoped()}). */
    private SemanticModel assembleByFallback(List<String> connectionKeys, List<String> domainKeys) {
        if (domainKeys == null || domainKeys.isEmpty()) {
            return assembler.assemble(connectionKeys);
        }
        SemanticModel byDomain = assembler.assembleByDomains(domainKeys);
        if (connectionKeys == null || connectionKeys.isEmpty()) {
            return byDomain;
        }
        SemanticModel narrowed = narrowToConnections(byDomain, connectionKeys);
        return narrowed.objects().isEmpty() ? byDomain : narrowed;
    }

    /**
     * Concept-Scoped Metadata Narrowing (upstream Agent Brain context reduction): when EVERY
     * connection in scope has an active Industry Pack with a non-empty tenant concept catalog,
     * a compact Stage 1 concept-selection LLM call decides which concepts are relevant to the
     * question, and Stage 2 resolves that selection to exactly the physical objects bound to it
     * — see {@link ConceptScopedMetadataResolver}. Absent for any reason (no resolver wired, no
     * connections, no active pack on ANY of them, no tenant concept catalog yet, or any failure)
     * ⇒ {@link Optional#empty()}, and the caller falls back to the full, unnarrowed assembly —
     * never a partial narrowing across a mixed set of connections in this version.
     *
     * <p>This is a deliberate, additive divergence from this class's own "ranking never narrows
     * the approved surface" principle (see this class's javadoc) — that principle governs the
     * keyword-ranking step below, which still never narrows. Concept-scoped narrowing is a
     * different, explicit mechanism: it only activates for a connection whose tenant has already
     * gone through Pack application + LLM concept classification, and even then the LLM (never
     * Java) decides what is relevant — the same non-negotiable ownership rule Apply Pack's own
     * classification path already enforces.
     */
    private Optional<SemanticModel> conceptScopedModel(List<String> connectionKeys, String question, String conversationId) {
        if (conceptResolver == null || connectionKeys == null || connectionKeys.isEmpty()) {
            return Optional.empty();
        }
        List<String> allObjectKeys = new ArrayList<>();
        for (String connectionKey : connectionKeys) {
            Optional<List<String>> objectKeys = conceptResolver.resolveObjectKeys(connectionKey, question, conversationId);
            if (objectKeys.isEmpty()) return Optional.empty();
            allObjectKeys.addAll(objectKeys.get());
        }
        if (allObjectKeys.isEmpty()) {
            // Every in-scope connection is concept-classified, and the LLM found none of the
            // available concepts relevant — a legitimate, honest "nothing applies" outcome.
            return Optional.of(new SemanticModel(List.of(), Map.of(), Map.of()));
        }
        return Optional.of(assembler.assembleByObjectKeys(allObjectKeys));
    }

    /** Combined semantic model + routing decision — see {@link #conceptScopedModelWithRouting}. */
    private record ConceptScopedModelResult(Optional<SemanticModel> model,
                                            Optional<ConceptScopedMetadataResolver.RoutingDecision> routing) {}

    /**
     * Same as {@link #conceptScopedModel}, additionally requesting the Decision Router
     * absorption's routing decision from the combined Persistent Knowledge call per connection
     * (see {@link ConceptScopedMetadataResolver#resolveObjectKeysWithRouting}).
     *
     * <p>Scope limitation, honestly disclosed rather than silently resolved: {@code
     * routingDecision} is a whole-REQUEST decision, but Stage 1 runs per CONNECTION. For the
     * common case (one connection in scope), this is exactly one combined call and one routing
     * decision. For a multi-connection scope, the FIRST connection's routing decision is used as
     * the request's routing decision — later connections' routing decisions (if they differ) are
     * discarded. This mirrors this class's existing all-or-nothing semantics for object keys
     * (any one connection failing empties the whole scope) rather than inventing a new merge
     * rule for a case that does not arise in this codebase's current agent/connection topology.
     */
    private ConceptScopedModelResult conceptScopedModelWithRouting(List<String> connectionKeys, String question,
                                                                    String conversationId, boolean memoryAvailable) {
        if (conceptResolver == null || connectionKeys == null || connectionKeys.isEmpty()) {
            return new ConceptScopedModelResult(Optional.empty(), Optional.empty());
        }
        List<String> allObjectKeys = new ArrayList<>();
        Optional<ConceptScopedMetadataResolver.RoutingDecision> routing = Optional.empty();
        for (String connectionKey : connectionKeys) {
            ConceptScopedMetadataResolver.CombinedResolution resolution =
                    conceptResolver.resolveObjectKeysWithRouting(connectionKey, question, conversationId, memoryAvailable);
            if (resolution.objectKeys().isEmpty()) {
                return new ConceptScopedModelResult(Optional.empty(), Optional.empty());
            }
            allObjectKeys.addAll(resolution.objectKeys().get());
            if (routing.isEmpty()) routing = resolution.routing();
        }
        if (allObjectKeys.isEmpty()) {
            return new ConceptScopedModelResult(Optional.of(new SemanticModel(List.of(), Map.of(), Map.of())), routing);
        }
        return new ConceptScopedModelResult(Optional.of(assembler.assembleByObjectKeys(allObjectKeys)), routing);
    }

    /** Restricts a domain scope to objects reachable through the approved connections. */
    private static SemanticModel narrowToConnections(SemanticModel model,
                                                     List<String> connectionKeys) {
        List<BusinessObject> kept = new ArrayList<>();
        Map<String, PhysicalTable>  objectTargets    = new LinkedHashMap<>();
        Map<String, PhysicalColumn> attributeTargets = new LinkedHashMap<>();
        for (BusinessObject object : model.objects()) {
            PhysicalTable target = model.objectTargets().get(object.objectKey());
            if (target == null || !connectionKeys.contains(target.connectionKey())) continue;
            kept.add(object);
            objectTargets.put(object.objectKey(), target);
            for (BusinessAttribute attribute : object.attributes()) {
                PhysicalColumn column = model.attributeTargets().get(attribute.attributeKey());
                if (column != null) attributeTargets.put(attribute.attributeKey(), column);
            }
        }
        return new SemanticModel(kept, objectTargets, attributeTargets);
    }

    // ── Semantic Foundation derivations (owned here from Phase 2) ──────────────

    /**
     * PRO-33: the literal validator's scope — every domain-bearing column the resolver found on
     * the entity-bound tables, keyed by qualified {@code table.column} and, when unambiguous, by
     * bare column name (SQL aliases hide the real table, so the bare key is the alias fallback).
     * Empty map ⇒ validation is a no-op (zero-cost).
     */
    public static Map<String, ColumnValueDomain> literalScopeOf(ResolvedQuestion resolved) {
        if (resolved == null || resolved.literalCandidates().isEmpty()) return Map.of();
        Map<String, ColumnValueDomain> scope = new HashMap<>();
        Set<String> ambiguousBare = new HashSet<>();
        for (ResolvedQuestion.LiteralCandidate c : resolved.literalCandidates()) {
            ColumnValueDomain info = new ColumnValueDomain(
                    c.table(), c.column(), c.authoritative(), c.values());
            scope.put(c.qualifiedColumn().toLowerCase(Locale.ROOT), info);
            String bare = c.column().toLowerCase(Locale.ROOT);
            ColumnValueDomain prior = scope.putIfAbsent(bare, info);
            if (prior != null && !prior.qualifiedColumn().equals(info.qualifiedColumn())) {
                ambiguousBare.add(bare);
            }
        }
        scope.keySet().removeAll(ambiguousBare);
        return scope;
    }

    // ── resolution helpers (over the semantic model only) ──────────────────────

    private int relevance(BusinessObject object, Set<String> keywords) {
        if (keywords.isEmpty()) return 0;
        StringBuilder hay = new StringBuilder(safe(object.businessName()));
        for (BusinessAttribute a : object.attributes()) {
            hay.append(' ').append(safe(a.businessName()));
        }
        String text = hay.toString().toLowerCase(Locale.ROOT);
        int score = 0;
        for (String kw : keywords) {
            if (text.contains(kw)) score += 3;
        }
        return score;
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private Set<String> keywords(String question) {
        if (question == null || question.isBlank()) return new HashSet<>();
        return Arrays.stream(question.toLowerCase(Locale.ROOT).split("[\\s,?!.;:]+"))
                .filter(w -> w.length() >= 3)
                .collect(Collectors.toCollection(HashSet::new));
    }
}
