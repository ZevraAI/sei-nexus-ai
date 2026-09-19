package com.sei.nexus.agentbrain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.onboarding.TenantSettingsRepository;
import com.sei.nexus.pack.IndustryPack;
import com.sei.nexus.pack.IndustryPackRepository;
import com.sei.nexus.pack.TenantPack;
import com.sei.nexus.semantic.BusinessEntity;
import com.sei.nexus.semantic.SemanticService;
import com.sei.nexus.tenant.Tenant;
import com.sei.nexus.tenant.TenantContext;
import com.sei.nexus.tenant.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Concept-Scoped Metadata Narrowing (upstream Agent Brain context reduction).
 *
 * <p>Two-stage retrieval, deliberately kept as thin orchestration over existing
 * services/repositories — no new persistence, no new AI mechanism:
 *
 * <ul>
 *   <li><b>Stage 1</b> — Persistent Knowledge / native OpenAI File Search (see {@link
 *       #selectConceptsViaPersistentKnowledge}/{@link
 *       #selectConceptsAndRoutingViaPersistentKnowledge}) is the ONE production Stage 1
 *       semantic-resolution implementation: the model retrieves and reasons over the tenant's
 *       own persistent business knowledge via {@code file_search} against its provisioned
 *       Vector Store, and returns which concept(s) — zero, one, or several — are relevant, plus
 *       (per {@link #CONCEPT_RESOLUTION_TYPE_RULES}) whether the resolution is a single clear
 *       concept, a genuine multi-concept span, or a disjunctive ambiguity between mutually
 *       exclusive concepts. Java never builds or sends a constructed catalog, and never chooses
 *       among concepts — it only validates the model's returned {@code concept_key}(s) against
 *       {@code usedConceptKeys} (this connection's actual, current concept usage in Postgres —
 *       see {@link SemanticService#findDistinctConceptKeysForConnection}) and discards anything
 *       not in that set. Physical table/column names are never part of this stage's context.
 *       There is no runtime choice between multiple Stage 1 implementations and no fallback to
 *       any other semantic mechanism — see {@link #resolveStage1SelectionInternal}.</li>
 *   <li><b>Stage 2</b> — resolves the LLM's validated concept_key selection to the physical
 *       object keys bound to them (via {@link
 *       SemanticService#findEntitiesByConnectionAndConcepts}), returning ALL matching objects —
 *       never one arbitrarily chosen when a concept binds to more than one physical object.</li>
 * </ul>
 *
 * <p>This class makes no semantic decision itself: the LLM decides which concepts are relevant
 * and whether the question is ambiguous between them; this class only sends the question,
 * validates the response against the tenant's actual concept usage, and retrieves the resulting
 * metadata.
 *
 * <p><b>Two distinct degradation modes, never conflated:</b> a public method returns {@link
 * Optional#empty()} (equivalently, {@link CombinedResolution#EMPTY}) only when concept-scoped
 * narrowing does not apply to this connection AT ALL — no active pack, or no tenant concept
 * catalog yet (no Business Entity on this connection has ever been LLM-classified) — so {@link
 * AgentBrain} falls back to its existing, unnarrowed assembly exactly as before this feature
 * existed. This is NOT the same as required Persistent Knowledge infrastructure (Vector Store /
 * File Search) being missing or failing when narrowing DOES apply — that condition throws a
 * {@link com.sei.nexus.common.NexusException} instead, making the failure visible rather than
 * silently degrading to an unnarrowed result.
 */
@Component
public class ConceptScopedMetadataResolver {

    private static final Logger log = LoggerFactory.getLogger(ConceptScopedMetadataResolver.class);

    private final IndustryPackRepository packRepository;
    private final SemanticService semanticService;
    private final AzureOpenAiClient aiClient;
    private final ObjectMapper objectMapper;
    // Persistent AI Knowledge V1, Stage 1 File Search infrastructure — both nullable (see the
    // 4-arg convenience constructor below): when null, Stage 1 cannot resolve a Vector Store id
    // for any connection, so any call that reaches Stage 1 (an active pack + tenant concept
    // catalog apply) throws NexusException — File Search is the sole Stage 1 implementation, so
    // there is no other mechanism left to "fall back" to. tenantSettingsRepository is also used,
    // independently, for previous_response_id conversation-chaining bookkeeping.
    private final TenantSettingsRepository tenantSettingsRepository;
    private final TenantRepository tenantRepository;

    @Autowired
    public ConceptScopedMetadataResolver(IndustryPackRepository packRepository,
                                         SemanticService semanticService,
                                         AzureOpenAiClient aiClient,
                                         ObjectMapper objectMapper,
                                         TenantSettingsRepository tenantSettingsRepository,
                                         TenantRepository tenantRepository) {
        this.packRepository  = packRepository;
        this.semanticService = semanticService;
        this.aiClient        = aiClient;
        this.objectMapper    = objectMapper;
        this.tenantSettingsRepository = tenantSettingsRepository;
        this.tenantRepository = tenantRepository;
    }

    /**
     * Convenience constructor (existing tests, and any caller not wiring the Persistent Knowledge
     * infrastructure). Since Persistent Knowledge / File Search is the sole Stage 1
     * implementation, a resolver built this way has no {@link TenantRepository} to resolve a
     * Vector Store id from — any call that reaches Stage 1 (an active pack + tenant concept
     * catalog apply for the connection) throws {@link com.sei.nexus.common.NexusException}.
     * Concept-scoped narrowing's own "does not apply at all" degradation (no active pack, no
     * tenant concept catalog) is unaffected and still returns {@link Optional#empty()}.
     */
    public ConceptScopedMetadataResolver(IndustryPackRepository packRepository,
                                         SemanticService semanticService,
                                         AzureOpenAiClient aiClient,
                                         ObjectMapper objectMapper) {
        this(packRepository, semanticService, aiClient, objectMapper, null, null);
    }

    /**
     * Stage 1 + Stage 2 for ONE connection.
     *
     * @return {@link Optional#empty()} when concept-scoped narrowing does not apply to this
     *         connection AT ALL (no active pack, no tenant concept catalog yet) — the caller MUST
     *         fall back to its existing full assembly for this connection. When present, the list
     *         is the exact, already-selected-and-resolved set of physical object keys to
     *         assemble — possibly empty, when the LLM legitimately found no available tenant
     *         concept relevant to the question. Required-infrastructure failure (missing Vector
     *         Store, File Search/OpenAI failure) is a distinct condition and is never absorbed
     *         into this return value — it propagates as a {@link com.sei.nexus.common.NexusException}.
     */
    public Optional<List<String>> resolveObjectKeys(String connectionKey, String question) {
        return resolveObjectKeys(connectionKey, question, null);
    }

    /**
     * Same as {@link #resolveObjectKeys(String, String)}, additionally threading a {@code
     * conversationId} through to Stage 1 so the File Search path (when enabled) can chain this
     * request to the tenant's own prior turn in the same conversation via OpenAI's {@code
     * previous_response_id} — see {@link #selectConceptsViaPersistentKnowledge}. {@code
     * conversationId} is never sent to OpenAI itself; it is only used locally as the lookup key
     * for the tenant's own stored response id. Passing {@code null} (or blank) is equivalent to
     * the 2-arg overload — no chaining is attempted, exactly as before this parameter existed.
     */
    public Optional<List<String>> resolveObjectKeys(String connectionKey, String question, String conversationId) {
        return resolveObjectKeysInternal(connectionKey, question, conversationId, false, false).objectKeys();
    }

    /**
     * Concept-Key Semantic Anchor design: the exact concept_key(s) Stage 1 selected for this
     * connection/question — the same Stage 1 run {@link #resolveObjectKeys} already performs,
     * exposing its concept identity instead of discarding it after Stage 2 resolves it to
     * physical object keys. {@link Optional#empty()} under every condition {@link
     * #resolveObjectKeys} already falls back on; otherwise the validated list Stage 1 returned,
     * unmodified — Java neither rederives nor filters it semantically here.
     */
    public Optional<List<String>> resolveConceptKeys(String connectionKey, String question, String conversationId) {
        return resolveObjectKeysInternal(connectionKey, question, conversationId, false, false).conceptKeys();
    }

    /**
     * One semantic value the combined Persistent Knowledge response can carry as its routing
     * decision — the exact same five-value contract the (now bypassable) Decision Router
     * produced, relayed verbatim from the LLM's own combined response. Java never constructs,
     * infers, or overrides this value — see {@link
     * #selectConceptsAndRoutingViaPersistentKnowledge} and its validation-only handling.
     */
    public record RoutingDecision(String type, String clarificationQuestion) {}

    /** Stage 1 + Stage 2 combined result, additionally carrying a request-level routing decision
     *  — see {@link #resolveObjectKeysWithRouting} — and the exact concept_key(s) Stage 1
     *  selected before Stage 2 resolved them to physical object keys (Concept-Key Semantic
     *  Anchor design — see {@link #resolveConceptKeys}). {@code conceptKeys} is {@link
     *  Optional#empty()} whenever {@code objectKeys} is (every fallback condition already
     *  documented on {@link #resolveObjectKeys}); otherwise it is the validated, unmodified list
     *  Stage 1 returned — Java never rederives, normalizes, or filters it semantically. */
    public record CombinedResolution(Optional<List<String>> objectKeys, Optional<RoutingDecision> routing,
                                     Optional<List<String>> conceptKeys,
                                     Optional<String> conceptAmbiguityClarification) {
        static final CombinedResolution EMPTY = new CombinedResolution(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * Same Stage 1 + Stage 2 resolution as {@link #resolveObjectKeys(String, String, String)},
     * additionally asking the SAME single Persistent Knowledge / File Search LLM call to also
     * produce the routing decision previously made by a separate Decision Router LLM call — see
     * {@code docs/ai/decision-router-absorption.md}. No additional LLM call is introduced: the
     * existing Stage 1 call's output contract is extended, not duplicated.
     *
     * @param memoryAvailable the one Java-computed runtime fact the LLM cannot obtain itself
     *                        (document memory is a separate subsystem from the tenant's
     *                        persistent-knowledge Vector Store, and is not retrievable via
     *                        {@code file_search}) — whether {@code
     *                        DocumentMemoryService.retrieveContext} found any relevant chunk for
     *                        this question. This is a plain fact handed to the LLM as input text,
     *                        exactly as the legacy Decision Router's context did; Java never
     *                        decides routing from it itself.
     * @return {@link CombinedResolution#EMPTY} only when concept-scoped narrowing does not apply
     *         to this connection AT ALL (no active pack, no tenant concept catalog yet) — a
     *         condition distinct from, and never conflated with, required-infrastructure failure
     *         (missing Vector Store, File Search/OpenAI failure), which instead propagates as a
     *         {@link NexusException} (see {@link #resolveStage1SelectionInternal}). {@code
     *         routing()} is {@link Optional#empty()} whenever the model's own combined response
     *         did not include a valid routing decision, in which case the caller (ChatService)
     *         MUST fall back to its own existing Decision Router call — exactly as before this
     *         method existed. This is the pre-existing Decision Router absorption fallback (a
     *         separate, unrelated concern this task does not change), never a Stage 1
     *         concept-selection fallback.
     */
    public CombinedResolution resolveObjectKeysWithRouting(String connectionKey, String question,
                                                            String conversationId, boolean memoryAvailable) {
        return resolveObjectKeysInternal(connectionKey, question, conversationId, true, memoryAvailable);
    }

    private CombinedResolution resolveObjectKeysInternal(String connectionKey, String question,
                                                          String conversationId, boolean includeRouting,
                                                          boolean memoryAvailable) {
        if (connectionKey == null || connectionKey.isBlank()) return CombinedResolution.EMPTY;
        try {
            TenantPack assignment = packRepository.findActivePackForConnection(connectionKey).orElse(null);
            if (assignment == null) return CombinedResolution.EMPTY;
            IndustryPack pack = packRepository.findPackById(assignment.packKey()).orElse(null);
            if (pack == null) return CombinedResolution.EMPTY;

            List<String> usedConceptKeys = semanticService.findDistinctConceptKeysForConnection(connectionKey);
            if (usedConceptKeys == null || usedConceptKeys.isEmpty()) return CombinedResolution.EMPTY;

            Stage1Selection sel = resolveStage1SelectionInternal(connectionKey, pack, usedConceptKeys, question,
                    conversationId, includeRouting, memoryAvailable);

            boolean ambiguous = sel.ambiguityClarification() != null && !sel.ambiguityClarification().isBlank();
            // Concept-Level Disjunctive Ambiguity design — CRITICAL DOWNSTREAM BOUNDARY: when
            // Stage 1 explicitly marked its own selection ambiguous, Stage 2 (resolving concept
            // keys to physical object keys) is never invoked at all — no object becomes approved
            // for an unresolved ambiguity. objectKeys is Optional.of(List.of()) (present, but
            // empty) rather than Optional.empty(), so the caller takes the SAME "legitimately no
            // relevant objects" branch it already has for the zero-concepts-selected case (never
            // falls back to a broader, unnarrowed assembly) — see AgentBrain#conceptScopedModel*.
            List<String> objectKeys = (ambiguous || sel.selected().isEmpty())
                    ? List.of()
                    : semanticService.findEntitiesByConnectionAndConcepts(connectionKey, sel.selected()).stream()
                            .map(BusinessEntity::primaryObjectKey)
                            .filter(k -> k != null && !k.isBlank())
                            .distinct()
                            .toList();
            return new CombinedResolution(Optional.of(objectKeys), Optional.ofNullable(sel.routing()),
                    Optional.of(sel.selected()),
                    ambiguous ? Optional.of(sel.ambiguityClarification()) : Optional.empty());
        } catch (NexusException e) {
            // Required Persistent Knowledge infrastructure (Vector Store / File Search) is
            // missing or failed — this is NOT the same condition as "concept-scoped narrowing
            // doesn't apply to this connection" (the generic catch below) and must never be
            // silently absorbed into it. The absence/failure of required semantic infrastructure
            // must be visible to the caller, never masked as an ordinary unnarrowed fallback.
            throw e;
        } catch (Exception e) {
            log.warn("Concept-scoped metadata resolution unavailable for connection '{}' "
                    + "(no active pack or no tenant concept catalog yet), falling back to full assembly: {}",
                    connectionKey, e.getMessage());
            return CombinedResolution.EMPTY;
        }
    }

    // ── Stage 1 dispatch — Persistent Knowledge / native OpenAI File Search (the sole Stage 1) ──

    /** A Stage 1 selection, optionally carrying a routing decision — see {@link
     *  #resolveStage1SelectionInternal}. {@code selected()} is never {@code null} — Persistent
     *  Knowledge / File Search is the sole Stage 1 implementation, so the legacy "no catalog to
     *  offer" sentinel no longer applies; a genuinely empty tenant concept catalog is instead
     *  filtered out earlier, in {@link #resolveObjectKeysInternal} (see {@code usedConceptKeys}).
     *  {@code routing()} is {@code null} for a caller that did not request routing. {@code
     *  ambiguityClarification()} (Concept-Level Disjunctive Ambiguity design) is non-null/non-blank
     *  only when the LLM explicitly marked its selection {@code resolutionType: "AMBIGUOUS"} — see
     *  {@link #CONCEPT_RESOLUTION_TYPE_RULES} — and is {@code null} for the non-combined,
     *  non-routing File Search path (see {@link #selectConceptsViaPersistentKnowledge}'s own
     *  javadoc for why that prompt does not carry this field). */
    private record Stage1Selection(List<String> selected, RoutingDecision routing, String ambiguityClarification) {}

    /**
     * Chooses and runs Stage 1 — Persistent Knowledge / native OpenAI File Search is the ONLY
     * production Stage 1 semantic-resolution implementation; there is no runtime choice and no
     * fallback to any other semantic mechanism. When {@code includeRouting} is true, also returns
     * the combined call's routing decision (see {@link
     * #selectConceptsAndRoutingViaPersistentKnowledge}).
     *
     * <p><b>Explicit failure, never silent degradation:</b> if this connection's tenant has no
     * provisioned Vector Store, or the File Search/OpenAI call itself fails, this method throws
     * {@link NexusException} ({@code 503 SERVICE_UNAVAILABLE}) rather than falling back to any
     * other concept-selection mechanism — the absence/failure of required Persistent Knowledge
     * infrastructure must be visible, never silently masked. The caller ({@link
     * #resolveObjectKeysInternal}) lets this propagate; it is caught only by the OUTER "does
     * concept-scoped narrowing apply to this connection at all" try/catch there, which explicitly
     * re-throws it rather than swallowing it into the unrelated, pre-existing "narrowing doesn't
     * apply" degradation (no active pack, no tenant concept catalog) — those remain two distinct
     * conditions, never conflated.
     *
     * @param includeRouting when true, calls {@link #selectConceptsAndRoutingViaPersistentKnowledge}
     *                       (the combined concept+routing contract) instead of {@link
     *                       #selectConceptsViaPersistentKnowledge} (concept-keys only) — the two
     *                       call different system prompts/schemas, never both, so a caller that
     *                       does not need routing never pays for or receives it.
     */
    private Stage1Selection resolveStage1SelectionInternal(String connectionKey, IndustryPack pack,
                                                            List<String> usedConceptKeys, String question,
                                                            String conversationId, boolean includeRouting,
                                                            boolean memoryAvailable) {
        String vectorStoreId = currentTenantVectorStoreId();
        if (vectorStoreId == null || vectorStoreId.isBlank()) {
            throw new NexusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Persistent Knowledge Stage 1 requires a provisioned Vector Store for this tenant, "
                            + "but none is available for connection '" + connectionKey + "'.");
        }
        try {
            if (includeRouting) {
                Stage1CombinedResult r = selectConceptsAndRoutingViaPersistentKnowledge(
                        vectorStoreId, usedConceptKeys, question, conversationId, memoryAvailable);
                return new Stage1Selection(r.conceptKeys(), r.routing(), r.ambiguityClarification());
            }
            return new Stage1Selection(
                    selectConceptsViaPersistentKnowledge(vectorStoreId, usedConceptKeys, question, conversationId),
                    null, null);
        } catch (NexusException e) {
            throw e;
        } catch (Exception e) {
            throw new NexusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Persistent Knowledge Stage 1 (File Search) failed for connection '" + connectionKey
                            + "': " + e.getMessage());
        }
    }

    /**
     * The current tenant's own persistent-knowledge Vector Store id, resolved strictly from
     * {@link TenantContext}'s schema — never from any caller-supplied value — so a tenant can
     * never reach another tenant's Vector Store through this path. {@code null} when the current
     * schema is the shared {@code public} schema (no tenant context), when the resolver was
     * built via the 4-arg convenience constructor, or when the tenant has not yet been
     * provisioned with a Vector Store — {@link #resolveStage1SelectionInternal} turns a {@code
     * null} here into an explicit failure; it is never treated as "narrowing doesn't apply."
     */
    private String currentTenantVectorStoreId() {
        if (tenantRepository == null) return null;
        try {
            String schema = TenantContext.getSchema();
            if (TenantContext.PUBLIC_SCHEMA.equals(schema)) return null;
            return tenantRepository.findBySchemaName(schema).map(Tenant::aiKnowledgeVectorStoreId).orElse(null);
        } catch (Exception e) {
            log.warn("Could not resolve the tenant's Vector Store id for connection resolution: {}", e.getMessage());
            return null;
        }
    }

    // ── Stage 1 — File Search concept selection field shape ────────────────────

    private static final String METADATA_REQUEST_FIELD_SCHEMA = """
            {"metadataRequest": {"conceptKeys": ["<concept_key>", "..."]}}""";

    /** Shared rules text for the {@code resolutionType}/{@code conceptClarificationQuestion}
     *  fields — appended verbatim to the combined Persistent Knowledge + routing prompt ({@link
     *  #PERSISTENT_KNOWLEDGE_WITH_ROUTING_SYSTEM_PROMPT}), the canonical production Stage 1
     *  contract. Deliberately NOT added to the non-combined, non-routing File Search prompt
     *  ({@link #PERSISTENT_KNOWLEDGE_SYSTEM_PROMPT}) or its parser — see that prompt's own javadoc
     *  for why. This is the sole mechanism by which Agent Brain — never Java — decides whether a
     *  question is a single clear concept, a genuine multi-concept span, or a disjunctive
     *  ambiguity between mutually exclusive concepts. */
    private static final String CONCEPT_RESOLUTION_TYPE_RULES = """
            - Additionally decide how the selected concepts relate to the question, using
              "resolutionType":
                - "SINGLE": exactly one concept is clearly established as what the question means.
                - "MULTI_SPAN": the question genuinely requires more than one concept TOGETHER
                  (e.g. a comparison or a combined metric spanning concepts) — conceptKeys lists
                  every concept required.
                - "AMBIGUOUS": the wording could plausibly mean any ONE of two or more DIFFERENT,
                  mutually-exclusive concepts, and nothing in the question or prior conversation
                  establishes which one the user means. List every plausible candidate in
                  conceptKeys and set "conceptClarificationQuestion" to a specific, concept-level
                  question asking the user to choose between them (e.g. "Do you mean purchase
                  orders or sales orders?") — never guess which one is meant.
            - "conceptClarificationQuestion" MUST be a non-empty, specific question when
              resolutionType is "AMBIGUOUS", and an empty string for every other resolutionType.
            - Only use "AMBIGUOUS" when the candidates are genuinely DIFFERENT, non-overlapping
              business concepts the question could equally mean — never for a question that
              merely spans multiple concepts together (that is "MULTI_SPAN"), and never merely
              because a term could be interpreted several ways WITHIN one already-clear concept
              (that is a value-level ambiguity, handled separately, later, and not your concern
              here).
            - When conceptKeys is empty (no listed concept is relevant at all), resolutionType
              MUST be "SINGLE" and conceptClarificationQuestion MUST be empty — "AMBIGUOUS" means
              genuinely plausible candidates exist, never "nothing matched."
            """;

    // ── Stage 1 — File Search concept selection (Persistent AI Knowledge V1) ───────────────────

    private static final String PERSISTENT_KNOWLEDGE_SYSTEM_PROMPT = """
            You are the semantic reasoning layer of an enterprise data platform. You are given a
            user's business question. Use the file_search tool to retrieve this tenant's
            persistent business knowledge and decide which business concept(s) — zero, one, or
            several — are relevant to answering the question, using each retrieved concept's
            name/aliases/description/operational meaning. You have NOT been given any physical
            table or column names — do not guess at or assume any; reason only from what
            file_search actually retrieves.

            Respond with valid JSON only — no prose, no markdown fences — in exactly this shape:
            """ + METADATA_REQUEST_FIELD_SCHEMA + """


            Rules:
            - Every value in conceptKeys MUST be the exact concept_key of a business concept you
              actually retrieved via file_search — never invent one, and never use a table or
              column name as a concept_key.
            - Returning an empty conceptKeys array is correct and expected when file_search finds
              nothing relevant to the question — never include a concept just to produce a
              non-empty answer.
            - Select every concept genuinely relevant to the question, not only the single best
              match — a question may span more than one business concept.
            """;

    private static final String RESPONSE_ID_SETTING_KEY_PREFIX = "stage1_response_id:";

    /**
     * Persistent AI Knowledge V1 Stage 1, non-routing variant: the model retrieves and reasons
     * over the tenant's persistent knowledge itself via {@code file_search} — Java sends only the
     * question, never a constructed catalog. This is the entire point of this method; see {@link
     * com.sei.nexus.ai.AzureOpenAiClient#chatWithFileSearch}.
     *
     * <p><b>Why this remains a distinct method/prompt rather than being merged into {@link
     * #selectConceptsAndRoutingViaPersistentKnowledge}:</b> this is the Stage 1 call reached by
     * {@link #resolveObjectKeys}/{@link #resolveConceptKeys}, which {@code AgentBrain}'s
     * autonomous-agent call graph uses (the {@code memoryAvailable == null} overload — see {@code
     * AgentRunner}). Autonomous agents have no Decision Router / routing concept at all — there is
     * no {@code memoryAvailable} runtime fact and no routing decision to request — so forcing that
     * caller through the combined concept+routing contract would mean sending a meaningless
     * runtime fact and silently discarding an unused routing field on every call. Both this method
     * and the combined one are equally "the" Persistent Knowledge / File Search Stage 1
     * implementation for their respective callers — this is a distinct public contract (concept
     * resolution only), not a second, competing semantic-resolution mechanism, and not legacy.
     *
     * <p>Java's role here is deterministic only: it never decides which concept is relevant — it
     * only validates whatever concept_key(s) the model returns against {@code usedConceptKeys}
     * (the tenant's actual, current concept usage for this connection, from Postgres) and discards
     * anything not in that set. This is enforcement, not semantic resolution — Java does not parse
     * the retrieved filename to determine the concept; the model's own returned {@code
     * conceptKeys} field is the only signal consulted.
     *
     * <p>Conversation-aware chaining: when {@code conversationId} is non-blank and this resolver
     * has a {@link TenantSettingsRepository}, Zevra looks up the current tenant's own previously
     * stored OpenAI response id for that conversation (key {@code stage1_response_id:<id>}, in
     * the tenant's own schema — never a value supplied by the client, and never shared across
     * tenants or conversations) and passes it as {@code previous_response_id}, letting the model
     * resolve follow-up references ("only the submitted ones") without Java resending any prior
     * turn's text. If the chained call fails for any reason (including an expired/invalid
     * previous response id), exactly one fresh, non-chained retry is attempted before giving up —
     * never an unbounded retry, and never a failure surfaced to the user solely because of a
     * stale previous response id. Whichever call succeeds, its new response id is persisted,
     * replacing whatever was stored before.
     */
    private List<String> selectConceptsViaPersistentKnowledge(String vectorStoreId, List<String> usedConceptKeys,
                                                                String question, String conversationId) {
        String previousResponseId = loadPreviousResponseId(conversationId);
        boolean chained = previousResponseId != null;
        com.sei.nexus.ai.AzureOpenAiClient.FileSearchResult result;
        try {
            com.sei.nexus.ai.LlmCallTag.set("STAGE1_FILE_SEARCH_CONCEPT_SELECTION");
            result = aiClient.chatWithFileSearch(vectorStoreId, PERSISTENT_KNOWLEDGE_SYSTEM_PROMPT, question, previousResponseId);
        } catch (Exception e) {
            if (!chained) {
                log.warn("File Search concept-selection call failed for vector store '{}': {}", vectorStoreId, e.getMessage());
                throw e; // propagates to the caller, which surfaces an explicit failure — no fallback
            }
            log.warn("Chained File Search concept-selection call failed for vector store '{}' "
                    + "(previous response id may be stale/expired); retrying once, fresh: {}",
                    vectorStoreId, e.getMessage());
            try {
                com.sei.nexus.ai.LlmCallTag.set("STAGE1_FILE_SEARCH_CONCEPT_SELECTION");
                result = aiClient.chatWithFileSearch(vectorStoreId, PERSISTENT_KNOWLEDGE_SYSTEM_PROMPT, question, null);
                chained = false;
            } catch (Exception retryEx) {
                log.warn("Fresh (non-chained) File Search concept-selection retry also failed for vector store '{}': {}",
                        vectorStoreId, retryEx.getMessage());
                throw retryEx; // propagates to the caller, which surfaces an explicit failure — no fallback
            }
        }
        storePreviousResponseId(conversationId, result.responseId());
        log.info("STAGE1_CONVERSATION_CHAIN conversationId={} chained={} previousResponseIdPresent={} newResponseIdPresent={}",
                conversationId, chained, previousResponseId != null, result.responseId() != null);
        return validateAgainstUsedConceptKeys(parseSelection(result.text()), usedConceptKeys);
    }

    // ── Stage 1 — combined concept selection + routing decision (Decision Router absorption) ───

    /** The five routing values, unchanged in meaning from the legacy Decision Router's own
     *  {@code type} enum — see {@code docs/ai/decision-router-absorption.md}. Exposed as a
     *  constant so the same list backs both the JSON-schema {@code enum} constraint below and
     *  Java's own defensive validation of the model's returned value. */
    private static final List<String> ROUTING_TYPES = List.of(
            "ANSWER_FROM_MEMORY", "QUERY_LIVE_DATA", "HYBRID_DOC_AND_DATA", "ASK_CLARIFICATION", "KNOWLEDGE_GAP");

    private static final String COMBINED_FIELD_SCHEMA = """
            {"metadataRequest": {"conceptKeys": ["<concept_key>", "..."],
                                  "resolutionType": "SINGLE|MULTI_SPAN|AMBIGUOUS",
                                  "conceptClarificationQuestion": ""},
             "routing": {"type": "ANSWER_FROM_MEMORY|QUERY_LIVE_DATA|HYBRID_DOC_AND_DATA|ASK_CLARIFICATION|KNOWLEDGE_GAP",
                         "clarificationQuestion": ""}}""";

    /**
     * Decision Router absorption: the same Persistent Knowledge / File Search LLM call that
     * resolves business concepts also decides the routing type previously produced by a separate
     * Decision Router LLM call — see {@code docs/ai/decision-router-absorption.md}. The five
     * routing values and their meaning are unchanged from the legacy Decision Router's own
     * prompt; simplified only where File Search + {@code previous_response_id} conversation
     * chaining already make a rule redundant (e.g. the legacy prompt's own resolutions/literal-
     * candidates rules, which belong to the physical-schema stage this LLM never sees).
     *
     * <p>Java supplies exactly one runtime fact this LLM cannot obtain itself — {@code
     * memoryAvailable} — as plain input text alongside the question, never as something Java
     * itself reasons from. Every other legacy Decision Router context section (full physical
     * schema, knowledge graph, findings/anomalies, resolutions, literal candidates, prior-
     * execution presence, conversation history text) is deliberately NOT reproduced here — see
     * the design doc for why each was judged unnecessary for the routing decision specifically.
     */
    private static final String PERSISTENT_KNOWLEDGE_WITH_ROUTING_SYSTEM_PROMPT = """
            You are the semantic reasoning layer of an enterprise data platform, responsible for
            TWO related jobs on the same question.

            JOB A — CONCEPT RESOLUTION: use the file_search tool to retrieve this tenant's
            persistent business knowledge and decide which business concept(s) — zero, one, or
            several — are relevant to answering the question, using each retrieved concept's
            name/aliases/description/operational meaning. You have NOT been given any physical
            table or column names — do not guess at or assume any; reason only from what
            file_search actually retrieves.

            JOB B — ROUTING: decide the best answer mode for this question. Choose exactly one:
            - QUERY_LIVE_DATA: the question needs fresh data retrieved and executed against this
              tenant's live systems — including EVERY follow-up question in this conversation,
              whether it asks for a different filter, metric, entity, subset, or more detail than
              before. You do not decide whether prior evidence already answers a follow-up — a
              separate downstream stage decides that from the actual evidence, using the
              conversation context you have via previous_response_id. When in doubt, choose this.
            - ANSWER_FROM_MEMORY: only when the "Document memory available" runtime fact below is
              true AND no live data retrieval is needed to answer this question.
            - HYBRID_DOC_AND_DATA: the question genuinely needs both document memory AND live
              data; only valid when the runtime fact says memory is available.
            - ASK_CLARIFICATION: ONLY when the question is completely ambiguous and the
              conversation context available to you (via previous_response_id) does not resolve
              the ambiguity.
            - KNOWLEDGE_GAP: only when file_search finds no relevant concept for this question AND
              the "Document memory available" runtime fact below is false.

            You will be given a short RUNTIME FACTS section after the question — facts about this
            request that you cannot retrieve yourself. Use them ONLY for the routing decision in
            Job B; never for concept resolution in Job A.

            Respond with valid JSON only — no prose, no markdown fences — in exactly this shape:
            """ + COMBINED_FIELD_SCHEMA + """


            Rules:
            - Every value in conceptKeys MUST be the exact concept_key of a business concept you
              actually retrieved via file_search — never invent one, and never use a table or
              column name as a concept_key.
            - Returning an empty conceptKeys array is correct and expected when file_search finds
              nothing relevant to the question — never include a concept just to produce a
              non-empty answer.
            - Select every concept genuinely relevant to the question, not only the single best
              match — a question may span more than one business concept.
            - clarificationQuestion must be a specific, non-empty question when routing.type is
              ASK_CLARIFICATION, and an empty string for every other routing.type.
            """ + CONCEPT_RESOLUTION_TYPE_RULES;

    private static final Map<String, Object> COMBINED_STAGE1_JSON_SCHEMA = buildCombinedJsonSchema();

    /** Strict-mode JSON Schema (every property required, {@code additionalProperties:false} at
     *  every object level — OpenAI's own requirement for {@code strict:true}) enforcing the
     *  combined contract at the API level, rather than relying on prose alone — the routing
     *  {@code type} field is constrained to exactly the five allowed values via a JSON Schema
     *  {@code enum}, something the legacy Decision Router's plain {@code chat()} call never had. */
    private static Map<String, Object> buildCombinedJsonSchema() {
        Map<String, Object> conceptKeysArray = Map.of("type", "array", "items", Map.of("type", "string"));
        Map<String, Object> resolutionType = Map.of(
                "type", "string", "enum", List.of("SINGLE", "MULTI_SPAN", "AMBIGUOUS"));
        Map<String, Object> metadataRequest = Map.of(
                "type", "object",
                "properties", Map.of(
                        "conceptKeys", conceptKeysArray,
                        "resolutionType", resolutionType,
                        "conceptClarificationQuestion", Map.of("type", "string")),
                "required", List.of("conceptKeys", "resolutionType", "conceptClarificationQuestion"),
                "additionalProperties", false);
        Map<String, Object> routingType = Map.of("type", "string", "enum", ROUTING_TYPES);
        Map<String, Object> routing = Map.of(
                "type", "object",
                "properties", Map.of("type", routingType, "clarificationQuestion", Map.of("type", "string")),
                "required", List.of("type", "clarificationQuestion"),
                "additionalProperties", false);
        return Map.of(
                "type", "object",
                "properties", Map.of("metadataRequest", metadataRequest, "routing", routing),
                "required", List.of("metadataRequest", "routing"),
                "additionalProperties", false);
    }

    /** The raw Stage 1 output before Stage 2 resolves {@code conceptKeys} to physical object
     *  keys — {@link #resolveObjectKeysInternal} performs that resolution afterward, identically
     *  to the non-combined path. {@code ambiguityClarification} (Concept-Level Disjunctive
     *  Ambiguity design) is non-null/non-blank only when the LLM explicitly marked its selection
     *  {@code resolutionType: "AMBIGUOUS"} — see {@link #CONCEPT_RESOLUTION_TYPE_RULES}. */
    private record Stage1CombinedResult(List<String> conceptKeys, RoutingDecision routing,
                                        String ambiguityClarification) {}

    /**
     * Same request/response chaining discipline as {@link #selectConceptsViaPersistentKnowledge}
     * (previous_response_id lookup, fallback-to-fresh-on-chained-failure, new response id
     * persisted on success) — duplicated rather than shared because the two call different
     * system prompts and JSON schemas (concept-keys-only vs. combined), and because keeping them
     * as independent methods means a defect in one prompt's parsing can never silently affect
     * the other's.
     */
    private Stage1CombinedResult selectConceptsAndRoutingViaPersistentKnowledge(
            String vectorStoreId, List<String> usedConceptKeys, String question, String conversationId,
            boolean memoryAvailable) {
        String questionWithRuntimeFacts = question + "\n\nRuntime facts (for the routing decision "
                + "only, never for concept resolution):\n- Document memory available for this "
                + "question: " + (memoryAvailable ? "true" : "false");

        String previousResponseId = loadPreviousResponseId(conversationId);
        boolean chained = previousResponseId != null;
        com.sei.nexus.ai.AzureOpenAiClient.FileSearchResult result;
        try {
            com.sei.nexus.ai.LlmCallTag.set("STAGE1_FILE_SEARCH_CONCEPT_AND_ROUTING");
            // Phase 1 explicit prompt caching: identical request shape, additionally attaching
            // prompt_cache_key="zevra:stage1-concept-and-routing:v1" (a pure cache-routing hint).
            result = aiClient.chatWithFileSearchForConceptAndRouting(vectorStoreId,
                    PERSISTENT_KNOWLEDGE_WITH_ROUTING_SYSTEM_PROMPT,
                    questionWithRuntimeFacts, previousResponseId, COMBINED_STAGE1_JSON_SCHEMA);
        } catch (Exception e) {
            if (!chained) {
                log.warn("Combined File Search concept+routing call failed for vector store '{}': {}",
                        vectorStoreId, e.getMessage());
                throw e; // propagates to the caller, which surfaces an explicit failure — no fallback
            }
            log.warn("Chained combined File Search call failed for vector store '{}' (previous "
                    + "response id may be stale/expired); retrying once, fresh: {}",
                    vectorStoreId, e.getMessage());
            try {
                com.sei.nexus.ai.LlmCallTag.set("STAGE1_FILE_SEARCH_CONCEPT_AND_ROUTING");
                result = aiClient.chatWithFileSearchForConceptAndRouting(vectorStoreId,
                        PERSISTENT_KNOWLEDGE_WITH_ROUTING_SYSTEM_PROMPT,
                        questionWithRuntimeFacts, null, COMBINED_STAGE1_JSON_SCHEMA);
                chained = false;
            } catch (Exception retryEx) {
                log.warn("Fresh (non-chained) combined File Search retry also failed for vector store '{}': {}",
                        vectorStoreId, retryEx.getMessage());
                throw retryEx; // propagates to the caller, which surfaces an explicit failure — no fallback
            }
        }
        storePreviousResponseId(conversationId, result.responseId());
        log.info("STAGE1_CONVERSATION_CHAIN conversationId={} chained={} previousResponseIdPresent={} "
                        + "newResponseIdPresent={} routingIncluded=true",
                conversationId, chained, previousResponseId != null, result.responseId() != null);

        List<String> conceptKeys = validateAgainstUsedConceptKeys(parseSelection(result.text()), usedConceptKeys);
        RoutingDecision routing = parseRouting(result.text());
        String ambiguity = parseConceptClarification(result.text());
        return new Stage1CombinedResult(conceptKeys, routing, ambiguity);
    }

    /** Java validates the model's own {@code routing.type} against the exact five-value contract
     *  — deterministic validation, never a semantic decision: an invalid/missing type is
     *  discarded (routing treated as absent, caller falls back to Decision Router for this
     *  request) rather than guessed at or defaulted to a specific type. */
    @SuppressWarnings("unchecked")
    private RoutingDecision parseRouting(String json) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(extractJson(json), new TypeReference<>() {});
            Object routingObj = parsed.get("routing");
            if (!(routingObj instanceof Map<?, ?> r)) return null;
            Object typeObj = r.get("type");
            if (!(typeObj instanceof String type) || !ROUTING_TYPES.contains(type)) {
                log.warn("Discarding invalid/missing routing.type from combined Stage 1 response");
                return null;
            }
            Object cq = r.get("clarificationQuestion");
            String clarificationQuestion = cq != null ? String.valueOf(cq) : "";
            return new RoutingDecision(type, clarificationQuestion);
        } catch (Exception e) {
            log.warn("Failed to parse routing decision from combined Stage 1 response: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Concept-Level Disjunctive Ambiguity design: reads {@code metadataRequest.resolutionType}/
     * {@code metadataRequest.conceptClarificationQuestion} from the combined Persistent Knowledge
     * + routing Stage 1 response (see {@link #CONCEPT_RESOLUTION_TYPE_RULES}).
     *
     * <p>Deterministic validation only, exactly like {@link #parseRouting}: the LLM's own
     * {@code resolutionType} value is the only signal consulted. A response is treated as
     * non-ambiguous (returns {@code null}) whenever {@code resolutionType} is missing or not
     * exactly {@code "AMBIGUOUS"} (byte-identical to a Stage 1 response predating this field —
     * the zero-cost backward-compatibility guarantee), or when it claims {@code "AMBIGUOUS"} but
     * supplies no actual clarification question — a malformed/degenerate signal is discarded
     * rather than guessed at, never surfaced as a fabricated clarification. Java never invents,
     * upgrades, or downgrades this value; it only accepts or discards the model's own answer.
     */
    private String parseConceptClarification(String json) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(extractJson(json), new TypeReference<>() {});
            Object metadataRequestObj = parsed.get("metadataRequest");
            if (!(metadataRequestObj instanceof Map<?, ?> mr)) return null;
            Object typeObj = mr.get("resolutionType");
            if (!(typeObj instanceof String resolutionType) || !"AMBIGUOUS".equals(resolutionType)) {
                return null;
            }
            Object cq = mr.get("conceptClarificationQuestion");
            String clarificationQuestion = cq != null ? String.valueOf(cq).trim() : "";
            if (clarificationQuestion.isEmpty()) {
                log.warn("Discarding AMBIGUOUS resolutionType with no conceptClarificationQuestion "
                        + "from Stage 1 response — malformed signal, never fabricated by Java");
                return null;
            }
            return clarificationQuestion;
        } catch (Exception e) {
            log.warn("Failed to parse concept resolution type from Stage 1 response: {}", e.getMessage());
            return null;
        }
    }

    /** {@code null} when there is no conversation id, no {@link TenantSettingsRepository} (the
     *  4-arg convenience constructor), no stored value yet, or the lookup fails for any reason —
     *  every case correctly results in a fresh (non-chained) call. */
    private String loadPreviousResponseId(String conversationId) {
        if (conversationId == null || conversationId.isBlank() || tenantSettingsRepository == null) return null;
        try {
            return tenantSettingsRepository.get(RESPONSE_ID_SETTING_KEY_PREFIX + conversationId).orElse(null);
        } catch (Exception e) {
            log.warn("Could not read the stored Stage 1 response id for this conversation, starting fresh: {}", e.getMessage());
            return null;
        }
    }

    /** No-op when there is no conversation id, no {@link TenantSettingsRepository}, or no new
     *  response id to store (OpenAI response parsing failure) — a missed persist simply means the
     *  next turn in this conversation starts fresh instead of chaining, never an error. */
    private void storePreviousResponseId(String conversationId, String newResponseId) {
        if (conversationId == null || conversationId.isBlank() || tenantSettingsRepository == null) return;
        if (newResponseId == null || newResponseId.isBlank()) return;
        try {
            tenantSettingsRepository.set(RESPONSE_ID_SETTING_KEY_PREFIX + conversationId, newResponseId);
        } catch (Exception e) {
            log.warn("Could not persist the new Stage 1 response id for this conversation: {}", e.getMessage());
        }
    }

    /** Java validates the LLM's own answer against the tenant's actual, current concept usage for
     *  this connection (from Postgres) — it never chooses, scores, ranks, or infers a concept
     *  itself. A concept_key the model invented (not in that set) is dropped, never persisted or
     *  acted on. There is no Java-rendered catalog to validate against in the File Search path —
     *  the model retrieves its own context via {@code file_search}. */
    private List<String> validateAgainstUsedConceptKeys(List<String> candidateKeys, List<String> usedConceptKeys) {
        Set<String> valid = new HashSet<>(usedConceptKeys);
        List<String> result = new ArrayList<>();
        for (String key : candidateKeys) {
            if (valid.contains(key)) {
                result.add(key);
            } else {
                log.warn("Discarding invalid/invented conceptKey '{}' from File Search Stage 1 — "
                        + "not in this connection's actual concept usage", key);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<String> parseSelection(String json) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(extractJson(json), new TypeReference<>() {});
            Object metadataRequest = parsed.get("metadataRequest");
            if (!(metadataRequest instanceof Map<?, ?> mr)) return List.of();
            Object keys = mr.get("conceptKeys");
            if (!(keys instanceof List<?> list)) return List.of();
            List<String> result = new ArrayList<>();
            for (Object o : list) {
                if (o != null) result.add(String.valueOf(o));
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse concept-selection response: {}", e.getMessage());
            return List.of();
        }
    }

    private String extractJson(String text) {
        if (text == null) return "{}";
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return (start >= 0 && end > start) ? text.substring(start, end + 1) : text;
    }

}
