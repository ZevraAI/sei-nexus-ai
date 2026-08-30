package com.sei.nexus.agentbrain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.onboarding.TenantSettingsRepository;
import com.sei.nexus.pack.IndustryPack;
import com.sei.nexus.pack.IndustryPackRepository;
import com.sei.nexus.pack.PackEntity;
import com.sei.nexus.pack.TenantPack;
import com.sei.nexus.semantic.BusinessEntity;
import com.sei.nexus.semantic.SemanticService;
import com.sei.nexus.tenant.Tenant;
import com.sei.nexus.tenant.TenantContext;
import com.sei.nexus.tenant.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Concept-Scoped Metadata Narrowing (upstream Agent Brain context reduction).
 *
 * <p>Two-stage retrieval, deliberately kept as thin orchestration over existing
 * services/repositories — no new persistence, no new AI mechanism:
 *
 * <ul>
 *   <li><b>Stage 1</b> — {@link #resolveObjectKeys}: builds the tenant's compact concept
 *       catalog (the connection's active {@link IndustryPack}'s concepts, intersected with the
 *       {@code concept_key}s actually assigned to this connection's Business Entities — see
 *       {@link SemanticService#findDistinctConceptKeysForConnection}), then makes ONE LLM call
 *       asking which of those concepts (zero, one, or several) are relevant to the question.
 *       Physical table/column names are never part of this stage's context.</li>
 *   <li><b>Stage 2</b> — resolves the LLM's validated concept_key selection to the physical
 *       object keys bound to them (via {@link
 *       SemanticService#findEntitiesByConnectionAndConcepts}), returning ALL matching objects —
 *       never one arbitrarily chosen when a concept binds to more than one physical object.</li>
 * </ul>
 *
 * <p>Mirrors {@code BusinessObjectBatchAnalyzer}'s exact concept-catalog rendering shape and
 * acceptance-boundary discipline (never invent a key; validate, don't decide) — reused, not
 * duplicated as a second resolver mechanism. This class makes no semantic decision itself: the
 * LLM decides which concepts are relevant; this class only retrieves the catalog, sends it,
 * validates the response against the exact list offered, and retrieves the resulting metadata.
 *
 * <p>Every public method degrades to {@link Optional#empty()} on anything that isn't a clean,
 * confident Stage-1 result — no active pack, no tenant concept catalog yet (no Business Entity
 * on this connection has ever been LLM-classified), or any failure — so {@link AgentBrain} can
 * fall back to its existing, unnarrowed assembly exactly as it did before this feature existed.
 */
@Component
public class ConceptScopedMetadataResolver {

    private static final Logger log = LoggerFactory.getLogger(ConceptScopedMetadataResolver.class);

    private final IndustryPackRepository packRepository;
    private final SemanticService semanticService;
    private final AzureOpenAiClient aiClient;
    private final ObjectMapper objectMapper;
    // Persistent AI Knowledge V1, Stage 1 File Search integration — both nullable (see the
    // 4-arg convenience constructor below): when null, the File Search path is unconditionally
    // disabled and behavior is byte-identical to before this integration existed.
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
     * Backward-compatible convenience constructor (existing tests and any caller predating the
     * File Search Stage 1 integration). The File Search path is unconditionally disabled when
     * constructed this way — there is no {@link TenantSettingsRepository} to read the flag from
     * — so behavior is byte-identical to before this integration existed.
     */
    public ConceptScopedMetadataResolver(IndustryPackRepository packRepository,
                                         SemanticService semanticService,
                                         AzureOpenAiClient aiClient,
                                         ObjectMapper objectMapper) {
        this(packRepository, semanticService, aiClient, objectMapper, null, null);
    }

    /** One canonical concept offered to the LLM for SELECTION — the same shape {@code
     *  BusinessObjectBatchAnalyzer.ConceptInfo} offers for concept CLASSIFICATION; kept as a
     *  separate, private record here rather than a shared/exported type, since the two remain
     *  independent call sites with no coupling need (see that class's own javadoc on why its
     *  record is private too). */
    private record ConceptCatalogEntry(String conceptKey, String name, List<String> aliases,
                                       String description, String operationalMeaning) {}

    /**
     * Stage 1 + Stage 2 for ONE connection.
     *
     * @return {@link Optional#empty()} when concept-scoped narrowing does not apply to this
     *         connection (no active pack, no tenant concept catalog, or any failure) — the
     *         caller MUST fall back to its existing full assembly for this connection. When
     *         present, the list is the exact, already-selected-and-resolved set of physical
     *         object keys to assemble — possibly empty, when the LLM legitimately found no
     *         available tenant concept relevant to the question.
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
     * One semantic value the combined Persistent Knowledge response can carry as its routing
     * decision — the exact same five-value contract the (now bypassable) Decision Router
     * produced, relayed verbatim from the LLM's own combined response. Java never constructs,
     * infers, or overrides this value — see {@link
     * #selectConceptsAndRoutingViaPersistentKnowledge} and its validation-only handling.
     */
    public record RoutingDecision(String type, String clarificationQuestion) {}

    /** Stage 1 + Stage 2 combined result, additionally carrying a request-level routing decision
     *  — see {@link #resolveObjectKeysWithRouting}. */
    public record CombinedResolution(Optional<List<String>> objectKeys, Optional<RoutingDecision> routing) {
        static final CombinedResolution EMPTY = new CombinedResolution(Optional.empty(), Optional.empty());
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
     * @return {@link CombinedResolution#EMPTY} under every condition {@link #resolveObjectKeys}
     *         already falls back on (no active pack, no tenant concept catalog, resolver
     *         failure). {@code routing()} is additionally {@link Optional#empty()} whenever the
     *         File Search Stage 1 path does not apply (flag off, no Vector Store, or the
     *         combined call fails and the legacy catalog-in-prompt fallback runs) — the legacy
     *         prompt has no routing capability, so the caller (ChatService) MUST fall back to
     *         its own existing Decision Router call whenever routing is absent, exactly as
     *         before this method existed.
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
            if (sel.selected() == null) return CombinedResolution.EMPTY; // legacy "no catalog to offer" case, preserved verbatim

            List<String> objectKeys = sel.selected().isEmpty()
                    ? List.of()
                    : semanticService.findEntitiesByConnectionAndConcepts(connectionKey, sel.selected()).stream()
                            .map(BusinessEntity::primaryObjectKey)
                            .filter(k -> k != null && !k.isBlank())
                            .distinct()
                            .toList();
            return new CombinedResolution(Optional.of(objectKeys), Optional.ofNullable(sel.routing()));
        } catch (Exception e) {
            log.warn("Concept-scoped metadata resolution unavailable for connection '{}', "
                    + "falling back to full assembly: {}", connectionKey, e.getMessage());
            return CombinedResolution.EMPTY;
        }
    }

    // ── Stage 1 dispatch — File Search (new) vs. catalog-in-prompt (legacy, deprecated below) ──

    private static final String FILE_SEARCH_STAGE1_SETTING_KEY = "persistent_knowledge_stage1_enabled";

    /**
     * Chooses and runs Stage 1, returning the validated selected concept_keys.
     *
     * @return the validated selection (possibly empty — "found nothing relevant" is a valid
     *         result), or {@code null} when the legacy path determined narrowing does not apply
     *         at all (no catalog could be built) — the caller maps {@code null} to {@code
     *         Optional.empty()}, preserving the exact pre-existing semantics.
     */
    @SuppressWarnings("deprecation") // the legacy path is the intended fallback — see class javadoc on each deprecated method
    private List<String> resolveStage1Selection(String connectionKey, IndustryPack pack,
                                                 List<String> usedConceptKeys, String question, String conversationId) {
        return resolveStage1SelectionInternal(connectionKey, pack, usedConceptKeys, question, conversationId,
                false, false).selected();
    }

    /** A Stage 1 selection, optionally carrying a routing decision — see {@link
     *  #resolveStage1SelectionInternal}. {@code selected() == null} is the legacy "no catalog to
     *  offer" sentinel, preserved verbatim from {@link #resolveStage1Selection}'s original
     *  contract. {@code routing()} is always {@code null} for the legacy catalog-in-prompt path
     *  (it has no routing capability) and for a caller that did not request routing. */
    private record Stage1Selection(List<String> selected, RoutingDecision routing) {}

    /**
     * Chooses and runs Stage 1, returning the validated selected concept_keys and, when {@code
     * includeRouting} is true, the combined call's routing decision.
     *
     * @param includeRouting when true and the File Search path applies, calls {@link
     *                       #selectConceptsAndRoutingViaPersistentKnowledge} (the combined
     *                       concept+routing contract) instead of {@link
     *                       #selectConceptsViaPersistentKnowledge} (concept-keys only) — the two
     *                       call different system prompts/schemas, never both, so a caller that
     *                       does not need routing never pays for or receives it. Ignored for the
     *                       legacy catalog-in-prompt fallback, which has no routing capability
     *                       regardless of this flag — {@code routing()} is {@code null} whenever
     *                       that path runs.
     */
    private Stage1Selection resolveStage1SelectionInternal(String connectionKey, IndustryPack pack,
                                                            List<String> usedConceptKeys, String question,
                                                            String conversationId, boolean includeRouting,
                                                            boolean memoryAvailable) {
        if (fileSearchStage1Enabled()) {
            String vectorStoreId = currentTenantVectorStoreId();
            if (vectorStoreId != null && !vectorStoreId.isBlank()) {
                try {
                    if (includeRouting) {
                        Stage1CombinedResult r = selectConceptsAndRoutingViaPersistentKnowledge(
                                vectorStoreId, usedConceptKeys, question, conversationId, memoryAvailable);
                        return new Stage1Selection(r.conceptKeys(), r.routing());
                    }
                    return new Stage1Selection(
                            selectConceptsViaPersistentKnowledge(vectorStoreId, usedConceptKeys, question, conversationId),
                            null);
                } catch (Exception e) {
                    log.warn("File Search Stage 1 failed for connection '{}', falling back to the legacy "
                            + "catalog-in-prompt path for this call: {}", connectionKey, e.getMessage());
                    // fall through to the legacy path below — this call must still succeed
                }
            } else {
                log.debug("File Search Stage 1 is enabled but the tenant has no persistent knowledge "
                        + "Vector Store yet ({}); using the legacy catalog path", connectionKey);
            }
        }
        List<ConceptCatalogEntry> catalog = tenantConceptCatalog(pack, usedConceptKeys);
        if (catalog.isEmpty()) return new Stage1Selection(null, null);
        return new Stage1Selection(selectConceptsViaLlm(pack.packId(), catalog, question), null);
    }

    /** Reads the per-tenant feature flag. Defaults to {@code false} (legacy path) on any failure
     *  to read it, and when this resolver was built via the 4-arg convenience constructor. */
    private boolean fileSearchStage1Enabled() {
        if (tenantSettingsRepository == null) return false;
        try {
            return tenantSettingsRepository.isTrue(FILE_SEARCH_STAGE1_SETTING_KEY);
        } catch (Exception e) {
            log.warn("Could not read the File Search Stage 1 flag, defaulting to the legacy path: {}", e.getMessage());
            return false;
        }
    }

    /**
     * The current tenant's own persistent-knowledge Vector Store id, resolved strictly from
     * {@link TenantContext}'s schema — never from any caller-supplied value — so a tenant can
     * never reach another tenant's Vector Store through this path. {@code null} when the current
     * schema is the shared {@code public} schema (no tenant context), when the resolver was
     * built via the 4-arg convenience constructor, or when the tenant has not yet been
     * provisioned with a Vector Store (Phase 1) — all three cases correctly fall back to the
     * legacy path above.
     */
    private String currentTenantVectorStoreId() {
        if (tenantRepository == null) return null;
        try {
            String schema = TenantContext.getSchema();
            if (TenantContext.PUBLIC_SCHEMA.equals(schema)) return null;
            return tenantRepository.findBySchemaName(schema).map(Tenant::aiKnowledgeVectorStoreId).orElse(null);
        } catch (Exception e) {
            log.warn("Could not resolve the tenant's Vector Store id, defaulting to the legacy path: {}", e.getMessage());
            return null;
        }
    }

    /**
     * @deprecated Retained only as the fallback source for {@link #resolveStage1Selection} when
     * the Persistent AI Knowledge V1 File Search Stage 1 path ({@code
     * persistent_knowledge_stage1_enabled}) is disabled, unavailable for the current tenant, or
     * fails at runtime — see {@link #selectConceptsViaPersistentKnowledge} for the replacement. Not
     * removed and not to be extended with new functionality; it must remain exactly as-is for
     * safe rollback throughout the migration window. Candidate for retirement only after the
     * File Search path is validated in production for a meaningful tenant cohort (see
     * {@code docs/designs/ZEVRA_PERSISTENT_AI_KNOWLEDGE_V1_DESIGN.md} §19).
     *
     * <p>Stage 1 content: the active Pack's concepts, INTERSECTED with the concept_keys actually
     * present on this connection (never the Pack's full catalogue, and never a Business Entity
     * whose concept_key is still NULL — an unclassified object is simply not part of any tenant
     * concept catalog yet, never guessed at here).
     */
    @Deprecated
    private List<ConceptCatalogEntry> tenantConceptCatalog(IndustryPack pack, List<String> usedConceptKeys) {
        if (pack.entities() == null) return List.of();
        Set<String> used = new HashSet<>(usedConceptKeys);
        List<ConceptCatalogEntry> catalog = new ArrayList<>();
        for (PackEntity e : pack.entities()) {
            if (e.conceptKey() == null || e.conceptKey().isBlank()) continue;
            if (!used.contains(e.conceptKey())) continue;
            catalog.add(new ConceptCatalogEntry(e.conceptKey(), e.name(),
                    e.aliases() != null ? e.aliases() : List.of(), e.description(), e.operationalMeaning()));
        }
        return catalog;
    }

    // ── Stage 1 — LLM concept selection ─────────────────────────────────────────

    private static final String METADATA_REQUEST_FIELD_SCHEMA = """
            {"metadataRequest": {"conceptKeys": ["<concept_key>", "..."]}}""";

    private static final String METADATA_REQUEST_SYSTEM_PROMPT = """
            You are the semantic reasoning layer of an enterprise data platform. You are given a
            user's business question and a catalog of canonical business concepts actually
            available for this tenant's connection. Decide which of the listed concepts — zero,
            one, or several — are relevant to answering the question, using each concept's
            name/aliases/description/operational meaning. You have NOT been shown any physical
            table or column names at this stage — do not guess at or assume any.

            Respond with valid JSON only — no prose, no markdown fences — in exactly this shape:
            """ + METADATA_REQUEST_FIELD_SCHEMA + """


            Rules:
            - Every value in conceptKeys MUST be copied exactly from the concept_key values
              listed below — never invent one.
            - Returning an empty conceptKeys array is correct and expected when none of the
              listed concepts is actually relevant to the question — never include a concept
              just to produce a non-empty answer.
            - Select every concept genuinely relevant to the question, not only the single best
              match — a question may span more than one business concept.
            """;

    /**
     * @deprecated Replaced, when {@code persistent_knowledge_stage1_enabled} is on and the
     * tenant has a Vector Store, by {@link #selectConceptsViaPersistentKnowledge} — the Persistent AI
     * Knowledge V1 Stage 1 integration. Retained temporarily for production fallback/rollback
     * (called from {@link #resolveStage1Selection} whenever the new path is disabled,
     * unavailable, or fails). Do not add new functionality here; not removed in this change. See
     * {@code docs/designs/ZEVRA_PERSISTENT_AI_KNOWLEDGE_V1_DESIGN.md} §19 for the retirement
     * condition.
     *
     * <p>Governance only, never semantic decision-making: makes the LLM call, then discards any
     * concept_key it returns that was not actually offered — this method decides nothing about
     * relevance itself, it only accepts or rejects the model's own answer, exactly like {@code
     * BusinessObjectBatchAnalyzer#applyConceptResolution}'s identical discipline.
     */
    @Deprecated
    private List<String> selectConceptsViaLlm(String packKey, List<ConceptCatalogEntry> catalog, String question) {
        String userMessage = renderCatalog(packKey, catalog, question);
        String response;
        try {
            com.sei.nexus.ai.LlmCallTag.set("STAGE1_CONCEPT_SELECTION");
            response = aiClient.chatWithJson(List.of(ChatMessage.user(userMessage)), METADATA_REQUEST_SYSTEM_PROMPT);
        } catch (Exception e) {
            log.warn("Concept-selection LLM call failed for pack '{}': {}", packKey, e.getMessage());
            return List.of();
        }
        return validateSelection(parseSelection(response), catalog);
    }

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
     * Persistent AI Knowledge V1 Stage 1: the model retrieves and reasons over the tenant's
     * persistent knowledge itself via {@code file_search} — Java sends only the question, never
     * a constructed catalog. This is the entire point of this method; see {@link
     * com.sei.nexus.ai.AzureOpenAiClient#chatWithFileSearch}.
     *
     * <p>Java's role is unchanged in kind from the legacy path: it never decides which concept is
     * relevant — it only validates whatever concept_key(s) the model returns against {@code
     * usedConceptKeys} (the tenant's actual, current concept usage for this connection, from
     * Postgres) and discards anything not in that set. This is enforcement, not semantic
     * resolution — Java does not parse the retrieved filename to determine the concept; the
     * model's own returned {@code conceptKeys} field is the only signal consulted.
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
                throw e; // let the caller (resolveStage1Selection) fall back to the legacy path for this call
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
                throw retryEx; // let the caller fall back to the legacy path for this call
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
            {"metadataRequest": {"conceptKeys": ["<concept_key>", "..."]},
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
            """;

    private static final Map<String, Object> COMBINED_STAGE1_JSON_SCHEMA = buildCombinedJsonSchema();

    /** Strict-mode JSON Schema (every property required, {@code additionalProperties:false} at
     *  every object level — OpenAI's own requirement for {@code strict:true}) enforcing the
     *  combined contract at the API level, rather than relying on prose alone — the routing
     *  {@code type} field is constrained to exactly the five allowed values via a JSON Schema
     *  {@code enum}, something the legacy Decision Router's plain {@code chat()} call never had. */
    private static Map<String, Object> buildCombinedJsonSchema() {
        Map<String, Object> conceptKeysArray = Map.of("type", "array", "items", Map.of("type", "string"));
        Map<String, Object> metadataRequest = Map.of(
                "type", "object",
                "properties", Map.of("conceptKeys", conceptKeysArray),
                "required", List.of("conceptKeys"),
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
     *  to the non-combined path. */
    private record Stage1CombinedResult(List<String> conceptKeys, RoutingDecision routing) {}

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
            result = aiClient.chatWithFileSearch(vectorStoreId, PERSISTENT_KNOWLEDGE_WITH_ROUTING_SYSTEM_PROMPT,
                    questionWithRuntimeFacts, previousResponseId, COMBINED_STAGE1_JSON_SCHEMA);
        } catch (Exception e) {
            if (!chained) {
                log.warn("Combined File Search concept+routing call failed for vector store '{}': {}",
                        vectorStoreId, e.getMessage());
                throw e; // let the caller fall back to the legacy path for this call
            }
            log.warn("Chained combined File Search call failed for vector store '{}' (previous "
                    + "response id may be stale/expired); retrying once, fresh: {}",
                    vectorStoreId, e.getMessage());
            try {
                com.sei.nexus.ai.LlmCallTag.set("STAGE1_FILE_SEARCH_CONCEPT_AND_ROUTING");
                result = aiClient.chatWithFileSearch(vectorStoreId, PERSISTENT_KNOWLEDGE_WITH_ROUTING_SYSTEM_PROMPT,
                        questionWithRuntimeFacts, null, COMBINED_STAGE1_JSON_SCHEMA);
                chained = false;
            } catch (Exception retryEx) {
                log.warn("Fresh (non-chained) combined File Search retry also failed for vector store '{}': {}",
                        vectorStoreId, retryEx.getMessage());
                throw retryEx; // let the caller fall back to the legacy path for this call
            }
        }
        storePreviousResponseId(conversationId, result.responseId());
        log.info("STAGE1_CONVERSATION_CHAIN conversationId={} chained={} previousResponseIdPresent={} "
                        + "newResponseIdPresent={} routingIncluded=true",
                conversationId, chained, previousResponseId != null, result.responseId() != null);

        List<String> conceptKeys = validateAgainstUsedConceptKeys(parseSelection(result.text()), usedConceptKeys);
        RoutingDecision routing = parseRouting(result.text());
        return new Stage1CombinedResult(conceptKeys, routing);
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

    /** Same discipline as {@link #validateSelection}, adapted to a plain set of authoritative
     *  keys rather than a rendered catalog — there is no catalog in this path to validate against,
     *  only the tenant's actual Postgres-recorded concept usage. */
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

    /**
     * @deprecated Retained only as part of the legacy fallback path (see {@link
     * #tenantConceptCatalog}). Not removed, not extended.
     *
     * <p>Renders the Stage 1 context — question + compact concept catalog ONLY. Deliberately
     * never includes physical table names, column names, or full Business Entity/Data Object
     * metadata — that is exactly the context-explosion this feature exists to prevent.
     */
    @Deprecated
    private String renderCatalog(String packKey, List<ConceptCatalogEntry> catalog, String question) {
        StringBuilder sb = new StringBuilder();
        sb.append("User question: ").append(question == null ? "" : question).append("\n\n");
        sb.append("Industry Pack: ").append(packKey).append("\n");
        sb.append("Business concepts available for this tenant connection (physical table/column ")
          .append("names are intentionally not shown at this stage):\n");
        for (ConceptCatalogEntry c : catalog) {
            sb.append("  - concept_key: ").append(c.conceptKey()).append(" | name: ").append(c.name());
            if (!c.aliases().isEmpty()) {
                sb.append(" | aliases: ").append(String.join(", ", c.aliases()));
            }
            if (c.description() != null && !c.description().isBlank()) {
                sb.append(" | ").append(c.description());
            }
            if (c.operationalMeaning() != null && !c.operationalMeaning().isBlank()) {
                sb.append(" | operational meaning: ").append(c.operationalMeaning());
            }
            sb.append("\n");
        }
        return sb.toString();
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

    /** Java validates the LLM's own answer against the exact catalog it was offered — it never
     *  chooses, scores, ranks, or infers a concept itself. A concept_key the model invented (not
     *  in the offered list) is dropped, never persisted or acted on. */
    private List<String> validateSelection(List<String> candidateKeys, List<ConceptCatalogEntry> catalog) {
        Set<String> offered = catalog.stream().map(ConceptCatalogEntry::conceptKey).collect(Collectors.toSet());
        List<String> valid = new ArrayList<>();
        for (String key : candidateKeys) {
            if (offered.contains(key)) {
                valid.add(key);
            } else {
                log.warn("Discarding invalid/invented conceptKey '{}' — not offered in the tenant concept catalog", key);
            }
        }
        return valid;
    }
}
