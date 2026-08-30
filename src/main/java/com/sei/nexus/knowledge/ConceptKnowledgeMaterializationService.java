package com.sei.nexus.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.pack.IndustryPack;
import com.sei.nexus.pack.IndustryPackRepository;
import com.sei.nexus.pack.PackEntity;
import com.sei.nexus.pack.TenantPack;
import com.sei.nexus.semantic.SemanticService;
import com.sei.nexus.tenant.Tenant;
import com.sei.nexus.tenant.TenantContext;
import com.sei.nexus.tenant.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 2A of the Persistent Tenant Knowledge migration — Concept Knowledge Materialization.
 *
 * <p>Takes the SAME concept metadata {@link com.sei.nexus.agentbrain.ConceptScopedMetadataResolver}
 * already sends to the concept-selection LLM (concept_key, name, aliases, description,
 * operational_meaning — see that class's {@code ConceptCatalogEntry} and {@code
 * tenantConceptCatalog}, which this class deliberately mirrors rather than depends on, since that
 * class must remain byte-identical) and materializes it as small, disposable JSON documents
 * uploaded into the tenant's existing Phase 1 Vector Store — one document per concept, per its
 * physical (pack, connection) binding.
 *
 * <p><strong>PostgreSQL remains the source of truth.</strong> The JSON built here exists only as
 * an in-memory {@code byte[]} for the duration of one upload HTTP call — it is never written to
 * disk and never persisted anywhere in Zevra; the durable artifact this method produces is the
 * OpenAI Vector Store file, not the JSON itself. Re-running this method re-derives the same JSON
 * from Postgres every time.
 *
 * <p><strong>Not wired into Chat.</strong> Nothing here is called by {@code ChatService}, {@code
 * AgentBrain}, or {@code ConceptScopedMetadataResolver}. This is a standalone knowledge-ingestion
 * operation; the concept-selection LLM continues to run exactly as before, sourced from Postgres.
 */
@Service
public class ConceptKnowledgeMaterializationService {

    private static final Logger log = LoggerFactory.getLogger(ConceptKnowledgeMaterializationService.class);

    private final TenantRepository       tenantRepository;
    private final IndustryPackRepository packRepository;
    private final SemanticService        semanticService;
    private final AzureOpenAiClient      aiClient;
    private final ObjectMapper           objectMapper;

    public ConceptKnowledgeMaterializationService(TenantRepository tenantRepository,
                                                   IndustryPackRepository packRepository,
                                                   SemanticService semanticService,
                                                   AzureOpenAiClient aiClient,
                                                   ObjectMapper objectMapper) {
        this.tenantRepository = tenantRepository;
        this.packRepository   = packRepository;
        this.semanticService  = semanticService;
        this.aiClient         = aiClient;
        this.objectMapper     = objectMapper;
    }

    /** One concept's provenance-qualified identity — never derived from a physical table/column name. */
    private record ConceptUnit(String connectionKey, String packKey, ConceptEntry entry) {
        String uid() { return connectionKey + "::" + packKey + "::" + entry.conceptKey(); }
    }

    /** Mirrors {@code ConceptScopedMetadataResolver.ConceptCatalogEntry} — same fields, same source. */
    private record ConceptEntry(String conceptKey, String name, List<String> aliases,
                                 String description, String operationalMeaning) {}

    public record ConceptResult(String conceptUid, String fileId, boolean skippedAlreadyPresent) {}

    public record MaterializationResult(String tenantSlug, String vectorStoreId,
                                         List<ConceptResult> materialized,
                                         List<String> failures) {}

    /**
     * Materializes every concept currently in this tenant's concept catalog (across all its
     * ACTIVE connection/Pack assignments) into the tenant's existing Vector Store.
     *
     * <p>Fails fast (throws) for a precondition that makes the whole operation meaningless:
     * unknown tenant, or a tenant with no Vector Store yet (Phase 1 not provisioned — this method
     * never creates one). Per-concept failures (a single OpenAI upload/attach failure) are caught,
     * collected, and do not abort materialization of the remaining concepts — the same graceful-
     * degradation philosophy this codebase already uses for onboarding table analysis.
     *
     * @param tenantSlug the tenant to materialize concept knowledge for
     */
    public MaterializationResult materializeTenantConcepts(String tenantSlug) {
        Tenant tenant = tenantRepository.findBySlug(tenantSlug)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND, "Tenant not found: " + tenantSlug));

        String vectorStoreId = tenant.aiKnowledgeVectorStoreId();
        if (vectorStoreId == null || vectorStoreId.isBlank()) {
            throw new NexusException(HttpStatus.CONFLICT,
                    "Tenant '" + tenantSlug + "' has no AI knowledge store provisioned yet "
                            + "(ai_knowledge_vector_store_id is null) — Phase 2A never creates one; "
                            + "run Phase 1 provisioning first.");
        }

        TenantContext.set(tenant.schemaName());
        try {
            return materializeWithinTenantContext(tenantSlug, vectorStoreId);
        } finally {
            TenantContext.clear();
        }
    }

    private MaterializationResult materializeWithinTenantContext(String tenantSlug, String vectorStoreId) {
        List<ConceptUnit> units = collectConceptUnits();

        // Idempotency: one list call up front, then an in-memory lookup per concept — see
        // AzureOpenAiClient#listVectorStoreFiles javadoc for why this is deliberately not a
        // larger synchronization subsystem.
        Set<String> alreadyPresentUids = new HashSet<>();
        try {
            for (AzureOpenAiClient.VectorStoreFileRef ref : aiClient.listVectorStoreFiles(vectorStoreId)) {
                String uid = ref.attributes() != null ? ref.attributes().get("concept_uid") : null;
                if (uid != null) alreadyPresentUids.add(uid);
            }
        } catch (Exception e) {
            // Non-fatal: if listing fails, materialization proceeds without the idempotency check
            // (worst case, a duplicate concept document is uploaded — documented as a known
            // limitation, never a reason to abort a knowledge-ingestion operation entirely).
            log.warn("Could not list existing vector store files for tenant '{}' — idempotency check "
                    + "skipped for this run: {}", tenantSlug, e.getMessage());
        }

        List<ConceptResult> materialized = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (ConceptUnit unit : units) {
            String uid = unit.uid();
            if (alreadyPresentUids.contains(uid)) {
                materialized.add(new ConceptResult(uid, null, true));
                continue;
            }
            try {
                ConceptResult result = materializeOneConcept(vectorStoreId, unit);
                materialized.add(result);
            } catch (Exception e) {
                String reason = "concept '" + uid + "': " + e.getMessage();
                log.warn("Concept knowledge materialization failed for tenant '{}', {}", tenantSlug, reason);
                failures.add(reason);
            }
        }

        log.info("Concept knowledge materialization for tenant '{}': {} materialized, {} failed",
                tenantSlug, materialized.size(), failures.size());
        return new MaterializationResult(tenantSlug, vectorStoreId, materialized, failures);
    }

    /** Builds the JSON, uploads it as an in-memory byte[] (never a file), attaches it, returns the file id. */
    private ConceptResult materializeOneConcept(String vectorStoreId, ConceptUnit unit) {
        byte[] json = buildConceptKnowledgeJson(unit);
        String filename = deterministicFilename(unit);

        String fileId = aiClient.uploadFile(json, filename, "application/json");

        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("concept_uid", unit.uid());
        attributes.put("concept_key", unit.entry().conceptKey());
        attributes.put("knowledge_type", "business-concept");
        attributes.put("pack_key", unit.packKey());
        attributes.put("connection_key", unit.connectionKey());
        aiClient.attachFileToVectorStore(vectorStoreId, fileId, attributes);

        return new ConceptResult(unit.uid(), fileId, false);
    }

    /**
     * The structured representation itself — the SAME fields {@code
     * ConceptScopedMetadataResolver} already sends the concept-selection LLM
     * (concept_key/name/aliases/description/operational_meaning), plus the provenance (pack,
     * connection) needed to preserve which physical representation this knowledge belongs to, and
     * a generation timestamp. No speculative fields, no physical column metadata beyond what's
     * already here (there is none — this is concept-level knowledge only).
     */
    private byte[] buildConceptKnowledgeJson(ConceptUnit unit) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("concept_key", unit.entry().conceptKey());
        doc.put("name", unit.entry().name());
        doc.put("aliases", unit.entry().aliases() != null ? unit.entry().aliases() : List.of());
        doc.put("description", unit.entry().description());
        doc.put("operational_meaning", unit.entry().operationalMeaning());
        doc.put("pack", unit.packKey());
        doc.put("connection", unit.connectionKey());
        doc.put("generated_at", Instant.now().toString());
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc)
                    .getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to serialize concept knowledge for '" + unit.uid() + "': " + e.getMessage());
        }
    }

    private String deterministicFilename(ConceptUnit unit) {
        return "concept-" + sanitize(unit.connectionKey()) + "-" + sanitize(unit.packKey())
                + "-" + sanitize(unit.entry().conceptKey()) + ".json";
    }

    private String sanitize(String value) {
        return value == null ? "unknown" : value.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
    }

    /**
     * Enumerates every concept currently in this tenant's concept catalog, across every ACTIVE
     * connection/Pack assignment — mirrors {@code ConceptScopedMetadataResolver#tenantConceptCatalog}'s
     * exact intersection logic (Pack entities ∩ concept_keys actually used on that connection) for
     * one connection, applied here across ALL of the tenant's connections instead of one. A
     * {@link TenantPack} with no {@code connectionKey} (pre-Global-Pack-Foundation data) is
     * skipped — the same resolver this mirrors cannot concept-scope it either.
     */
    private List<ConceptUnit> collectConceptUnits() {
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
                units.add(new ConceptUnit(connectionKey, tenantPack.packKey(), entry));
            }
        }
        return units;
    }
}
