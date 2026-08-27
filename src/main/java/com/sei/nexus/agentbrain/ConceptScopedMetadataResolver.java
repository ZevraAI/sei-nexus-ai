package com.sei.nexus.agentbrain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.pack.IndustryPack;
import com.sei.nexus.pack.IndustryPackRepository;
import com.sei.nexus.pack.PackEntity;
import com.sei.nexus.pack.TenantPack;
import com.sei.nexus.semantic.BusinessEntity;
import com.sei.nexus.semantic.SemanticService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public ConceptScopedMetadataResolver(IndustryPackRepository packRepository,
                                         SemanticService semanticService,
                                         AzureOpenAiClient aiClient,
                                         ObjectMapper objectMapper) {
        this.packRepository  = packRepository;
        this.semanticService = semanticService;
        this.aiClient        = aiClient;
        this.objectMapper    = objectMapper;
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
        if (connectionKey == null || connectionKey.isBlank()) return Optional.empty();
        try {
            TenantPack assignment = packRepository.findActivePackForConnection(connectionKey).orElse(null);
            if (assignment == null) return Optional.empty();
            IndustryPack pack = packRepository.findPackById(assignment.packKey()).orElse(null);
            if (pack == null) return Optional.empty();

            List<String> usedConceptKeys = semanticService.findDistinctConceptKeysForConnection(connectionKey);
            if (usedConceptKeys == null || usedConceptKeys.isEmpty()) return Optional.empty();

            List<ConceptCatalogEntry> catalog = tenantConceptCatalog(pack, usedConceptKeys);
            if (catalog.isEmpty()) return Optional.empty();

            List<String> selected = selectConceptsViaLlm(pack.packId(), catalog, question);

            List<String> objectKeys = selected.isEmpty()
                    ? List.of()
                    : semanticService.findEntitiesByConnectionAndConcepts(connectionKey, selected).stream()
                            .map(BusinessEntity::primaryObjectKey)
                            .filter(k -> k != null && !k.isBlank())
                            .distinct()
                            .toList();
            return Optional.of(objectKeys);
        } catch (Exception e) {
            log.warn("Concept-scoped metadata resolution unavailable for connection '{}', "
                    + "falling back to full assembly: {}", connectionKey, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Stage 1 content: the active Pack's concepts, INTERSECTED with the concept_keys actually
     * present on this connection (never the Pack's full catalogue, and never a Business Entity
     * whose concept_key is still NULL — an unclassified object is simply not part of any tenant
     * concept catalog yet, never guessed at here).
     */
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

    /** Governance only, never semantic decision-making: makes the LLM call, then discards any
     *  concept_key it returns that was not actually offered — this method decides nothing about
     *  relevance itself, it only accepts or rejects the model's own answer, exactly like {@code
     *  BusinessObjectBatchAnalyzer#applyConceptResolution}'s identical discipline. */
    private List<String> selectConceptsViaLlm(String packKey, List<ConceptCatalogEntry> catalog, String question) {
        String userMessage = renderCatalog(packKey, catalog, question);
        String response;
        try {
            response = aiClient.chatWithJson(List.of(ChatMessage.user(userMessage)), METADATA_REQUEST_SYSTEM_PROMPT);
        } catch (Exception e) {
            log.warn("Concept-selection LLM call failed for pack '{}': {}", packKey, e.getMessage());
            return List.of();
        }
        return validateSelection(parseSelection(response), catalog);
    }

    /** Renders the Stage 1 context — question + compact concept catalog ONLY. Deliberately never
     *  includes physical table names, column names, or full Business Entity/Data Object metadata
     *  — that is exactly the context-explosion this feature exists to prevent. */
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
