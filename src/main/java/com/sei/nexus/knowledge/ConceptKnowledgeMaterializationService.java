package com.sei.nexus.knowledge;

import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.pack.IndustryPack;
import com.sei.nexus.pack.IndustryPackRepository;
import com.sei.nexus.pack.PackEntity;
import com.sei.nexus.pack.TenantPack;
import com.sei.nexus.semantic.LearnedMapping;
import com.sei.nexus.semantic.LearnedMappingRepository;
import com.sei.nexus.semantic.SemanticService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The authoritative projection of a tenant's business concepts, and the writer of their retrieval
 * index.
 *
 * <p>Takes the concept metadata Zevra already holds — name, aliases, description, operational
 * meaning from the applied Industry Pack(s), plus the tenant's promoted learned knowledge — as one
 * {@link ConceptUnit} per (connection, pack, concept), renders each as a single {@link
 * #retrievalDocument retrieval document}, embeds that text once, and stores it in {@code
 * nexus_concept_embedding} via {@link ConceptEmbeddingRepository}. That one text is what is
 * embedded AND what the Stage 1 prompt later shows the model, so the model reads exactly what was
 * matched.
 *
 * <p><b>PostgreSQL is the single source of truth.</b> The index is derived state: re-running {@link
 * ConceptKnowledgeSynchronizationService} re-derives it from the Pack and learned-mapping tables
 * every time, and only re-embeds a concept whose {@link #contentHash} (or {@link
 * #PROJECTION_VERSION}) changed. There is no external store to keep in step.
 *
 * <p>This class performs no relevance judgement: it projects, hashes, embeds, stores.
 */
@Service
public class ConceptKnowledgeMaterializationService {

    private static final Logger log = LoggerFactory.getLogger(ConceptKnowledgeMaterializationService.class);

    /** Shape version of {@link #retrievalDocument}. Bump it to force every concept to be re-embedded
     *  (the sync treats a stored row with a different version as changed). "2": the document is now
     *  the text Stage 1 shows the model, not a separate JSON file. */
    static final String PROJECTION_VERSION = "2";

    private final IndustryPackRepository   packRepository;
    private final SemanticService          semanticService;
    private final AzureOpenAiClient        aiClient;
    private final LearnedMappingRepository learnedMappingRepository;
    private final ConceptEmbeddingRepository embeddingRepository;

    public ConceptKnowledgeMaterializationService(IndustryPackRepository packRepository,
                                                   SemanticService semanticService,
                                                   AzureOpenAiClient aiClient,
                                                   LearnedMappingRepository learnedMappingRepository,
                                                   ConceptEmbeddingRepository embeddingRepository) {
        this.packRepository           = packRepository;
        this.semanticService          = semanticService;
        this.aiClient                 = aiClient;
        this.learnedMappingRepository = learnedMappingRepository;
        this.embeddingRepository      = embeddingRepository;
    }

    /**
     * One concept's provenance-qualified identity — never derived from a physical table/column
     * name. Package-visible so {@link ConceptKnowledgeSynchronizationService} consumes the exact
     * same authoritative units this class works from.
     */
    record ConceptUnit(String connectionKey, String packKey, ConceptEntry entry,
                        List<LearnedMapping> learnedKnowledge) {
        String uid() { return connectionKey + "::" + packKey + "::" + entry.conceptKey(); }
    }

    record ConceptEntry(String conceptKey, String name, List<String> aliases,
                         String description, String operationalMeaning) {}

    /**
     * Embeds one concept's retrieval document and writes (or replaces) its row. Used for both a
     * brand-new concept and a changed one — the upsert makes create and update the same operation.
     */
    void embedAndStore(ConceptUnit unit) {
        String doc = retrievalDocument(unit);
        com.sei.nexus.ai.LlmCallTag.set("CONCEPT_INDEX_EMBEDDING");
        float[] embedding = aiClient.embed(doc).embedding();
        embeddingRepository.upsert(unit.uid(), unit.connectionKey(), unit.packKey(),
                unit.entry().conceptKey(), contentHash(unit), PROJECTION_VERSION, doc, embedding);
    }

    /**
     * Deterministic SHA-256 content hash over exactly the fields that constitute this concept's
     * authoritative meaning ({@code conceptKey/name/aliases/description/operationalMeaning/
     * packKey/connectionKey}), plus its promoted, classified learned knowledge ({@code
     * mappingKey/businessTerm/sqlPattern/confidence} per entry, sorted by {@code mappingKey}).
     * Promoting, editing or demoting a learning therefore changes the hash exactly like editing the
     * concept's own metadata does. This is what lets the sync tell "unchanged, skip" from "content
     * changed, re-embed" — the identity key ({@link ConceptUnit#uid()}) alone cannot.
     */
    String contentHash(ConceptUnit unit) {
        String learnedCanonical = unit.learnedKnowledge() == null ? "" : unit.learnedKnowledge().stream()
                .sorted(Comparator.comparing(LearnedMapping::mappingKey))
                .map(m -> nullToEmpty(m.businessTerm()) + nullToEmpty(m.sqlPattern()) + m.confidence())
                .collect(Collectors.joining());
        String canonical = String.join("",
                nullToEmpty(unit.entry().conceptKey()),
                nullToEmpty(unit.entry().name()),
                String.join(",", unit.entry().aliases() != null ? unit.entry().aliases() : List.of()),
                nullToEmpty(unit.entry().description()),
                nullToEmpty(unit.entry().operationalMeaning()),
                nullToEmpty(unit.packKey()),
                nullToEmpty(unit.connectionKey()),
                learnedCanonical);
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every JVM — unreachable in practice.
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR, "SHA-256 unavailable: " + e.getMessage());
        }
    }

    private String nullToEmpty(String s) { return s == null ? "" : s; }

    /**
     * The one text that represents a concept: it is what gets embedded, and it is what the Stage 1
     * prompt shows the model. The concept key comes first so the model can copy it verbatim; the
     * aliases are the words people actually use for the concept; the description and operational
     * meaning carry the "this is NOT that, see X" disambiguation. Promoted learned knowledge —
     * business terms the tenant's users taught Zevra, each with the binding it resolved to — follows,
     * sorted by mapping key so identical inputs always give identical text. No timestamps, nothing
     * that varies between runs, so a re-render never looks like a change.
     */
    String retrievalDocument(ConceptUnit unit) {
        ConceptEntry e = unit.entry();
        StringBuilder sb = new StringBuilder();
        sb.append(e.conceptKey()).append(" — ").append(nullToEmpty(e.name()));
        if (e.aliases() != null && !e.aliases().isEmpty()) {
            sb.append(" (also called: ").append(String.join(", ", e.aliases())).append(")");
        }
        sb.append(". ");
        if (e.description() != null && !e.description().isBlank()) sb.append(e.description().strip()).append(' ');
        if (e.operationalMeaning() != null && !e.operationalMeaning().isBlank()) {
            sb.append(e.operationalMeaning().strip());
        }
        if (unit.learnedKnowledge() != null && !unit.learnedKnowledge().isEmpty()) {
            String learned = unit.learnedKnowledge().stream()
                    .sorted(Comparator.comparing(LearnedMapping::mappingKey))
                    .map(m -> "\"" + nullToEmpty(m.businessTerm()) + "\" means " + nullToEmpty(m.sqlPattern()))
                    .collect(Collectors.joining("; "));
            sb.append(" Business terms learned for this concept: ").append(learned).append('.');
        }
        return sb.toString().strip();
    }

    /**
     * Enumerates every concept currently in this tenant's concept catalog, across every ACTIVE
     * connection/Pack assignment: the Pack's entities intersected with the concept_keys actually
     * used on that connection. A {@link TenantPack} with no {@code connectionKey}
     * (pre-Global-Pack-Foundation data) is skipped — it cannot be concept-scoped.
     */
    List<ConceptUnit> collectConceptUnits() {
        List<ConceptUnit> units = new ArrayList<>();
        for (TenantPack tenantPack : packRepository.findAppliedPacks()) {
            String connectionKey = tenantPack.connectionKey();
            if (connectionKey == null || connectionKey.isBlank()) {
                log.debug("Skipping pack '{}' — no connection_key (pre-Global-Pack-Foundation assignment)",
                        tenantPack.packKey());
                continue;
            }
            IndustryPack pack = packRepository.findPackById(tenantPack.packKey()).orElse(null);
            if (pack == null || pack.entities() == null) continue;

            List<String> usedConceptKeys = semanticService.findDistinctConceptKeysForConnection(connectionKey);
            if (usedConceptKeys == null || usedConceptKeys.isEmpty()) continue;
            Set<String> used = new HashSet<>(usedConceptKeys);

            for (PackEntity e : pack.entities()) {
                if (e.conceptKey() == null || e.conceptKey().isBlank()) continue;
                if (!used.contains(e.conceptKey())) continue;
                ConceptEntry entry = new ConceptEntry(e.conceptKey(), e.name(),
                        e.aliases() != null ? e.aliases() : List.of(), e.description(), e.operationalMeaning());
                List<LearnedMapping> learnedKnowledge =
                        learnedMappingRepository.findPromotedByConceptKey(e.conceptKey());
                units.add(new ConceptUnit(connectionKey, tenantPack.packKey(), entry, learnedKnowledge));
            }
        }
        return units;
    }

    /**
     * Read-only, admin-facing concept catalog — powers the Learnings panel's concept picker (an
     * admin choosing which concept a learning belongs to via {@code SemanticController}'s
     * promote/concept endpoints). Not part of any runtime Chat/Planner path: it exists so a human
     * can see and choose a valid {@code concept_key}, built by deduplicating {@link
     * #collectConceptUnits()} (per connection/pack, so the same concept can legitimately repeat
     * across connections) down to one row per distinct {@code conceptKey}.
     */
    public List<Map<String, String>> listConceptCatalog() {
        Map<String, String> byConceptKey = new LinkedHashMap<>();
        for (ConceptUnit unit : collectConceptUnits()) {
            byConceptKey.putIfAbsent(unit.entry().conceptKey(), unit.entry().name());
        }
        return byConceptKey.entrySet().stream()
                .map(en -> {
                    Map<String, String> row = new LinkedHashMap<String, String>();
                    row.put("conceptKey", en.getKey());
                    row.put("name", en.getValue());
                    return row;
                })
                .sorted(Comparator.comparing(row -> row.get("name"), Comparator.nullsLast(String::compareTo)))
                .toList();
    }
}
