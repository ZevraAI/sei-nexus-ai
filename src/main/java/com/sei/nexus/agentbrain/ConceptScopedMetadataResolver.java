package com.sei.nexus.agentbrain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.knowledge.ConceptEmbeddingRepository;
import com.sei.nexus.knowledge.ConceptRetrievalService;
import com.sei.nexus.onboarding.TenantSettingsRepository;
import com.sei.nexus.pack.IndustryPack;
import com.sei.nexus.pack.IndustryPackRepository;
import com.sei.nexus.pack.TenantPack;
import com.sei.nexus.semantic.BusinessEntity;
import com.sei.nexus.semantic.SemanticService;
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
import java.util.stream.Collectors;

/**
 * Concept-Scoped Metadata Narrowing (upstream Agent Brain context reduction).
 *
 * <p>Two-stage retrieval, deliberately kept as thin orchestration over existing services — one
 * production path, no alternatives:
 *
 * <ul>
 *   <li><b>Stage 1</b> — decides which business concept(s) the question is about, and which answer
 *       mode it needs. Zevra retrieves the tenant's most similar concepts from its own Postgres
 *       index ({@link ConceptRetrievalService}: the question is embedded and compared with each
 *       concept's embedded retrieval text, plus the concepts selected for the previous question in
 *       the same conversation), shows those candidates to the LLM in one ordinary call, and the LLM
 *       decides which of them are relevant — zero, one, or several — and (per {@link
 *       #CONCEPT_RESOLUTION_TYPE_RULES}) whether the resolution is a single clear concept, a genuine
 *       multi-concept span, or a disjunctive ambiguity. Retrieval only narrows the candidate list by
 *       vector similarity; it never decides relevance and never interprets the question. Java
 *       validates the model's returned {@code concept_key}(s) against {@code usedConceptKeys} (this
 *       connection's actual, current concept usage — see {@link
 *       SemanticService#findDistinctConceptKeysForConnection}) and discards anything not in that
 *       set. Physical table/column names are never part of this stage's context.</li>
 *   <li><b>Stage 2</b> — resolves the validated concept_key selection to the physical object keys
 *       bound to them (via {@link SemanticService#findEntitiesByConnectionAndConcepts}), returning
 *       ALL matching objects — never one arbitrarily chosen when a concept binds to more than one.</li>
 * </ul>
 *
 * <p>This class makes no semantic decision itself: the LLM decides which concepts are relevant and
 * whether the question is ambiguous between them; this class only retrieves candidates, sends them,
 * validates the response against the tenant's actual concept usage, and retrieves the resulting
 * metadata.
 *
 * <p><b>Failures are loud.</b> If the concept index for the connection has not been built, or the
 * Stage 1 call fails, a {@link NexusException} is thrown — never a silent degradation to an
 * unnarrowed result. The one case that returns {@link CombinedResolution#EMPTY} is a connection to
 * which concept-scoped narrowing does not apply at all (no active Pack, or no concept catalog yet),
 * so {@link AgentBrain} assembles that connection's full surface, exactly as before this feature.
 */
@Component
public class ConceptScopedMetadataResolver {

    private static final Logger log = LoggerFactory.getLogger(ConceptScopedMetadataResolver.class);

    private final IndustryPackRepository packRepository;
    private final SemanticService semanticService;
    private final AzureOpenAiClient aiClient;
    private final ObjectMapper objectMapper;
    private final TenantSettingsRepository tenantSettingsRepository;
    private final ConceptRetrievalService retrievalService;

    @Autowired
    public ConceptScopedMetadataResolver(IndustryPackRepository packRepository,
                                         SemanticService semanticService,
                                         AzureOpenAiClient aiClient,
                                         ObjectMapper objectMapper,
                                         TenantSettingsRepository tenantSettingsRepository,
                                         ConceptRetrievalService retrievalService) {
        this.packRepository           = packRepository;
        this.semanticService          = semanticService;
        this.aiClient                 = aiClient;
        this.objectMapper             = objectMapper;
        this.tenantSettingsRepository = tenantSettingsRepository;
        this.retrievalService         = retrievalService;
    }

    /**
     * One semantic value the Stage 1 response can carry as its routing decision — the five-value
     * contract the (now absorbed) Decision Router produced, relayed verbatim from the LLM's own
     * response. Java never constructs, infers, or overrides this value.
     */
    public record RoutingDecision(String type, String clarificationQuestion) {}

    /** Stage 1 + Stage 2 combined result: the physical object keys to assemble, the routing
     *  decision, the exact concept_key(s) Stage 1 selected, and — only when the LLM explicitly
     *  marked its selection {@code resolutionType: "AMBIGUOUS"} — its clarification question.
     *  {@code objectKeys} is {@link Optional#empty()} only when narrowing does not apply to the
     *  connection at all (see the class Javadoc); otherwise it is the already-selected-and-resolved
     *  set, possibly empty when the LLM found no relevant concept. */
    public record CombinedResolution(Optional<List<String>> objectKeys, Optional<RoutingDecision> routing,
                                     Optional<List<String>> conceptKeys,
                                     Optional<String> conceptAmbiguityClarification) {
        static final CombinedResolution EMPTY = new CombinedResolution(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * Stage 1 + Stage 2 for ONE connection — the single entry point for every caller (conversational
     * Chat and autonomous agents alike).
     *
     * @param conversationId used only to carry the previous question and its concepts into a
     *                       follow-up ("only the submitted ones"); {@code null}/blank for a first or
     *                       stand-alone question
     * @param memoryAvailable the one Java-computed runtime fact the LLM cannot obtain itself —
     *                        whether document memory was found relevant for this question; handed to
     *                        the LLM as plain input text, never something Java reasons from
     */
    public CombinedResolution resolveObjectKeysWithRouting(String connectionKey, String question,
                                                            String conversationId, boolean memoryAvailable) {
        if (connectionKey == null || connectionKey.isBlank()) return CombinedResolution.EMPTY;

        TenantPack assignment = packRepository.findActivePackForConnection(connectionKey).orElse(null);
        if (assignment == null) return CombinedResolution.EMPTY;
        IndustryPack pack = packRepository.findPackById(assignment.packKey()).orElse(null);
        if (pack == null) return CombinedResolution.EMPTY;

        List<String> usedConceptKeys = semanticService.findDistinctConceptKeysForConnection(connectionKey);
        if (usedConceptKeys == null || usedConceptKeys.isEmpty()) return CombinedResolution.EMPTY;

        Stage1Selection sel = selectConceptsAndRouting(connectionKey, usedConceptKeys, question,
                conversationId, memoryAvailable);

        boolean ambiguous = sel.ambiguityClarification() != null && !sel.ambiguityClarification().isBlank();
        // Concept-Level Disjunctive Ambiguity — CRITICAL DOWNSTREAM BOUNDARY: when Stage 1 explicitly
        // marked its own selection ambiguous, Stage 2 is never invoked at all — no object becomes
        // approved for an unresolved ambiguity. objectKeys is Optional.of(List.of()) (present, but
        // empty) so the caller takes the same "legitimately no relevant objects" branch it has for
        // the zero-concepts-selected case, never a broader unnarrowed assembly.
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
    }

    // ── Stage 1 ─────────────────────────────────────────────────────────────────

    /** A Stage 1 selection with its routing decision. {@code selected()} is never {@code null}.
     *  {@code ambiguityClarification()} is non-null/non-blank only when the LLM explicitly marked its
     *  selection {@code resolutionType: "AMBIGUOUS"}. */
    private record Stage1Selection(List<String> selected, RoutingDecision routing, String ambiguityClarification) {}

    /** The previous question of a conversation and the concepts it selected, as Stage 1 saved them. */
    record ConversationState(String question, List<String> conceptKeys) {
        static final ConversationState NONE = new ConversationState(null, List.of());
    }

    private static final String STATE_KEY_PREFIX = "stage1_state:";

    private Stage1Selection selectConceptsAndRouting(String connectionKey, List<String> usedConceptKeys,
                                                      String question, String conversationId,
                                                      boolean memoryAvailable) {
        ConversationState prior = loadConversationState(conversationId);
        // A follow-up ("which of those are overdue?") says nothing about its subject on its own, so
        // retrieval is run over the previous question plus this one, and the previous turn's concepts
        // are always carried in as candidates. Both are mechanical text/key operations: Java does not
        // read either question, the model resolves what "those" means.
        String retrievalQuery = prior.question() == null ? question : prior.question() + "\n" + question;

        List<ConceptEmbeddingRepository.Candidate> candidates =
                retrievalService.retrieve(connectionKey, retrievalQuery, prior.conceptKeys());
        String prompt = buildStage1Prompt(question, prior.question(), candidates, memoryAvailable);

        Stage1Selection chosen = callStage1(connectionKey, prompt, usedConceptKeys);

        // Self-contradiction check. A response that selects NO concept yet routes to a mode that needs
        // data from a concept (or that selects a concept yet says "no relevant knowledge") is
        // internally inconsistent — and acting on it leaves the planner with an empty schema. Java does
        // not pick a concept; it hands the MODEL that observation, shows it a wider candidate list and
        // the tenant's concept menu, and lets the model re-decide, exactly once.
        if (chosen.ambiguityClarification() == null
                && responseContradictsItself(chosen.selected(), chosen.routing())) {
            boolean gap = "KNOWLEDGE_GAP".equals(chosen.routing().type());
            String observation;
            if (chosen.selected().isEmpty()) {
                observation = "\n\nSYSTEM OBSERVATION (not from the user): your previous response "
                        + "selected no business concept and routed this question to " + chosen.routing().type()
                        + (gap
                            ? ", concluding this tenant has no relevant knowledge. Candidate retrieval can "
                              + "miss a concept that is present."
                            : ", which needs data belonging to at least one concept.")
                        + " This tenant's concept_keys are: " + String.join(", ", usedConceptKeys)
                        + ". More candidate concepts are listed above than before. Re-check the question "
                        + "against them; if one or more genuinely apply, select them. If none genuinely "
                        + "applies, keep conceptKeys empty and set routing.type to the value that honestly "
                        + "fits (for example ASK_CLARIFICATION or KNOWLEDGE_GAP). Respond in the same JSON shape.";
            } else {
                // A concept WAS selected, yet the routing says there is no relevant knowledge: the two
                // statements conflict. Hand the conflict back and let the model resolve it.
                observation = "\n\nSYSTEM OBSERVATION (not from the user): your previous response selected "
                        + "the business concept(s) " + String.join(", ", chosen.selected()) + " but routed this "
                        + "question to KNOWLEDGE_GAP, which states that this tenant has no relevant "
                        + "knowledge. Those two statements conflict. Decide which is right: if the "
                        + "selected concept(s) bear on the question, keep them and set routing.type to the "
                        + "value that fits (for example QUERY_LIVE_DATA); if none genuinely applies, set "
                        + "conceptKeys to empty and keep KNOWLEDGE_GAP. Respond in the same JSON shape.";
            }
            log.info("STAGE1_RECONSIDER reason={} routing={}",
                    chosen.selected().isEmpty() ? "NO_CONCEPT_SELECTED" : "CONCEPT_SELECTED_BUT_KNOWLEDGE_GAP",
                    chosen.routing().type());
            List<ConceptEmbeddingRepository.Candidate> wider =
                    retrievalService.retrieveWider(connectionKey, retrievalQuery, prior.conceptKeys());
            String widerPrompt = buildStage1Prompt(question, prior.question(), wider, memoryAvailable) + observation;
            try {
                Stage1Selection second = callStage1(connectionKey, widerPrompt, usedConceptKeys);
                // Take the model's second answer only if it is a usable decision of its own.
                if (second.routing() != null) {
                    log.info("STAGE1_RECONSIDER outcome=ACCEPTED conceptKeys={} routing={}",
                            second.selected().size(), second.routing().type());
                    chosen = second;
                }
            } catch (Exception e) {
                log.warn("Stage 1 reconsideration call failed; keeping the first response: {}", e.getMessage());
            }
        }

        // The previous turn's concepts are kept when this turn selected none (e.g. a clarification
        // question), so the NEXT follow-up still has something to resolve against.
        saveConversationState(conversationId, question,
                chosen.selected().isEmpty() ? prior.conceptKeys() : chosen.selected());
        return chosen;
    }

    private Stage1Selection callStage1(String connectionKey, String prompt, List<String> usedConceptKeys) {
        String raw;
        try {
            com.sei.nexus.ai.LlmCallTag.set("STAGE1_CONCEPT_AND_ROUTING");
            raw = aiClient.respondForStage1(List.of(ChatMessage.user(prompt)), STAGE1_SYSTEM_PROMPT,
                    "stage1_concept_and_routing", COMBINED_STAGE1_JSON_SCHEMA);
        } catch (NexusException e) {
            throw e;
        } catch (Exception e) {
            throw new NexusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Stage 1 concept selection failed for connection '" + connectionKey + "': " + e.getMessage());
        }
        List<String> conceptKeys = validateAgainstUsedConceptKeys(parseSelection(raw), usedConceptKeys);
        RoutingDecision routing = parseRouting(raw);
        String ambiguity = parseConceptClarification(raw);
        // Observability only: what Stage 1 decided (concept keys are tenant catalog identifiers, not
        // user text) — without it a "no data / knowledge gap" answer cannot be traced to its cause.
        log.info("STAGE1_DECISION conceptKeys={} routing={} ambiguous={}",
                conceptKeys, routing == null ? null : routing.type(), ambiguity != null);
        return new Stage1Selection(conceptKeys, routing, ambiguity);
    }

    /**
     * The user message of the Stage 1 call: the candidate concepts (each exactly as it was embedded),
     * the previous question when this is a follow-up, the question, and the one runtime fact. A pure
     * function of its inputs. It contains no physical table or column name and no instruction that
     * depends on what the question says.
     */
    static String buildStage1Prompt(String question, String priorQuestion,
                                    List<ConceptEmbeddingRepository.Candidate> candidates,
                                    boolean memoryAvailable) {
        StringBuilder sb = new StringBuilder();
        sb.append("CANDIDATE CONCEPTS — this tenant's business concepts most similar to the question")
          .append(priorQuestion == null ? "" : " (plus those selected for the previous question)")
          .append(". Each line starts with the concept_key to use:\n");
        for (ConceptEmbeddingRepository.Candidate c : candidates) {
            sb.append("- ").append(c.docText()).append('\n');
        }
        if (priorQuestion != null) {
            sb.append("\nPREVIOUS QUESTION IN THIS CONVERSATION:\n").append(priorQuestion).append('\n');
        }
        sb.append("\nQUESTION:\n").append(question).append('\n');
        sb.append("\nRuntime facts (for the routing decision only, never for concept resolution):\n")
          .append("- Document memory available for this question: ").append(memoryAvailable ? "true" : "false");
        return sb.toString();
    }

    // ── Conversation state (previous question + its concepts) ───────────────────

    private ConversationState loadConversationState(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return ConversationState.NONE;
        try {
            Optional<String> stored = tenantSettingsRepository.get(STATE_KEY_PREFIX + conversationId);
            if (stored.isEmpty()) return ConversationState.NONE;
            Map<String, Object> m = objectMapper.readValue(stored.get(), new TypeReference<>() {});
            Object q = m.get("question");
            List<String> concepts = new ArrayList<>();
            if (m.get("concepts") instanceof List<?> l) for (Object o : l) if (o != null) concepts.add(String.valueOf(o));
            return new ConversationState(q instanceof String s && !s.isBlank() ? s : null, List.copyOf(concepts));
        } catch (Exception e) {
            log.warn("Could not read the Stage 1 conversation state, treating this as a first question: {}", e.getMessage());
            return ConversationState.NONE;
        }
    }

    private void saveConversationState(String conversationId, String question, List<String> conceptKeys) {
        if (conversationId == null || conversationId.isBlank()) return;
        try {
            tenantSettingsRepository.set(STATE_KEY_PREFIX + conversationId,
                    objectMapper.writeValueAsString(Map.of("question", question, "concepts", conceptKeys)));
        } catch (Exception e) {
            // A missed save only means the next turn is treated as a first question — never an error.
            log.warn("Could not persist the Stage 1 conversation state: {}", e.getMessage());
        }
    }
    /** Shared rules text for the {@code resolutionType}/{@code conceptClarificationQuestion}
     *  fields — appended verbatim to the combined Persistent Knowledge + routing prompt ({@link
     *  #PERSISTENT_KNOWLEDGE_WITH_ROUTING_SYSTEM_PROMPT}), the canonical production Stage 1
     *  contract. Part of the one Stage 1 prompt
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

    /** Shared rule, appended to both Stage 1 prompts: select the concept that OWNS an identifier the
     *  user quotes, not only the concept holding the details they ask about. Domain-neutral by
     *  construction — it names no industry, concept or field. */
    private static final String IDENTIFIER_OWNER_RULE = """
            - Select concepts to IDENTIFY as well as to DESCRIBE. When the question refers to a
              record by a business identifier (a number, code or name) and asks for details that
              live on a related concept (for example its line items, components or events), select
              BOTH the concept that owns that identifier AND the concept that holds the details —
              the details alone cannot be looked up by an identifier they do not carry.
            """;

    /** Companion to {@link #IDENTIFIER_OWNER_RULE}: a breakdown needs the label owner too. */
    private static final String BREAKDOWN_OWNER_RULE = """
            - Select concepts to LABEL a breakdown. When the question groups, lists or compares
              results by an entity ("by X", "per X", "for each X"), also select the
              concept(s) that own that entity's descriptive name, alongside the concept that holds
              the measure — the measure's own table may carry only a reference key, which is not
              readable on its own. If the entity could be one of several concepts, select each.
            """;


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
     * The Stage 1 system prompt: one call decides BOTH which business concept(s) the question is
     * about and the answer mode — the routing that used to be a separate Decision Router LLM call
     * (see {@code docs/ai/decision-router-absorption.md}). The five routing values and their meaning
     * are unchanged from that router.
     *
     * <p>Java supplies exactly one runtime fact this LLM cannot obtain itself — {@code
     * memoryAvailable} — as plain input text, never as something Java itself reasons from.
     */
    private static final String STAGE1_SYSTEM_PROMPT = """
            You are the semantic reasoning layer of an enterprise data platform, responsible for
            TWO related jobs on the same question.

            JOB A — CONCEPT RESOLUTION: the message lists CANDIDATE CONCEPTS — this tenant's business
            concepts most similar to the question, each with its name, aliases, description and
            operational meaning. Decide which of them — zero, one, or several — are relevant to
            answering the question. You have NOT been given any physical table or column names — do
            not guess at or assume any; reason only from the candidate concepts shown.

            JOB B — ROUTING: decide the best answer mode for this question. Choose exactly one:
            - QUERY_LIVE_DATA: the question needs fresh data retrieved and executed against this
              tenant's live systems — including EVERY follow-up question in this conversation,
              whether it asks for a different filter, metric, entity, subset, or more detail than
              before. You do not decide whether prior evidence already answers a follow-up — a
              separate downstream stage decides that from the actual evidence. When in doubt,
              choose this.
            - ANSWER_FROM_MEMORY: only when the "Document memory available" runtime fact below is
              true AND no live data retrieval is needed to answer this question.
            - HYBRID_DOC_AND_DATA: the question genuinely needs both document memory AND live
              data; only valid when the runtime fact says memory is available.
            - ASK_CLARIFICATION: ONLY when the question is completely ambiguous and the PREVIOUS
              QUESTION (when one is shown) does not resolve the ambiguity.
            - KNOWLEDGE_GAP: only when none of the candidate concepts is relevant to this question
              AND the "Document memory available" runtime fact below is false.

            When the QUESTION is a follow-up that only makes sense together with the PREVIOUS
            QUESTION (it refers to "those", "them", "the same", or narrows an earlier request), resolve
            it against the previous question and the concepts that were relevant to it.

            You will be given a short RUNTIME FACTS section after the question — facts about this
            request that you cannot determine yourself. Use them ONLY for the routing decision in
            Job B; never for concept resolution in Job A.

            Respond with valid JSON only — no prose, no markdown fences — in exactly this shape:
            """ + COMBINED_FIELD_SCHEMA + """


            Rules:
            - Every value in conceptKeys MUST be the exact concept_key of a candidate concept shown
              to you — never invent one, and never use a table or column name as a concept_key.
            - Returning an empty conceptKeys array is correct and expected when none of the
              candidate concepts is relevant to the question — never include a concept just to
              produce a non-empty answer.
            - Select every concept genuinely relevant to the question, not only the single best
              match — a question may span more than one business concept.
            - clarificationQuestion must be a specific, non-empty question when routing.type is
              ASK_CLARIFICATION, and an empty string for every other routing.type.
            """ + IDENTIFIER_OWNER_RULE + BREAKDOWN_OWNER_RULE + CONCEPT_RESOLUTION_TYPE_RULES;
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


    /** True when the model's own concept selection and routing are in tension:
     *  <ul>
     *    <li>NO concept selected, yet routed to a data route (a data route needs a concept) or to
     *        KNOWLEDGE_GAP ("nothing relevant exists" — exactly what a retrieval miss looks like);</li>
     *    <li>a concept WAS selected, yet routed to KNOWLEDGE_GAP (found it, and also "no knowledge").</li>
     *  </ul>
     *  ASK_CLARIFICATION / ANSWER_FROM_MEMORY with no concept, and any data route with a concept, are
     *  coherent and left alone. Java only detects the conflict; the model resolves it. */
    private static boolean responseContradictsItself(List<String> conceptKeys, RoutingDecision routing) {
        if (routing == null) return false;
        String t = routing.type();
        if (conceptKeys.isEmpty()) {
            return "QUERY_LIVE_DATA".equals(t) || "HYBRID_DOC_AND_DATA".equals(t) || "KNOWLEDGE_GAP".equals(t);
        }
        return "KNOWLEDGE_GAP".equals(t);
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

    /** Java validates the LLM's own answer against the tenant's actual, current concept usage for
     *  this connection (from Postgres) — it never chooses, scores, ranks, or infers a concept
     *  itself. A concept_key the model invented (not in that set) is dropped, never persisted or
     *  acted on. The candidate list the model chose from is only a retrieved subset, so validation is against the full set —
     *  of this connection's concepts. */
    private List<String> validateAgainstUsedConceptKeys(List<String> candidateKeys, List<String> usedConceptKeys) {
        Set<String> valid = new HashSet<>(usedConceptKeys);
        List<String> result = new ArrayList<>();
        for (String key : candidateKeys) {
            if (valid.contains(key)) {
                result.add(key);
            } else {
                log.warn("Discarding invalid/invented conceptKey '{}' from Stage 1 — "
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
