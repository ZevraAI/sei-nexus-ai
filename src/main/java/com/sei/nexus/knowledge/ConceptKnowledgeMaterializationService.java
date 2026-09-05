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

    /**
     * One concept's provenance-qualified identity — never derived from a physical table/column
     * name. Package-visible (not {@code private}) so {@link ConceptKnowledgeSynchronizationService}
     * — same package, reusing this class's projection logic rather than duplicating it — can
     * consume the exact same authoritative units this materializer itself works from.
     */
    record ConceptUnit(String connectionKey, String packKey, ConceptEntry entry) {
        String uid() { return connectionKey + "::" + packKey + "::" + entry.conceptKey(); }
    }

    /** Mirrors {@code ConceptScopedMetadataResolver.ConceptCatalogEntry} — same fields, same source. */
    record ConceptEntry(String conceptKey, String name, List<String> aliases,
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
        String fileId = uploadAndAttach(vectorStoreId, unit);
        return new ConceptResult(unit.uid(), fileId, false);
    }

    /**
     * Uploads this unit's current Concept Knowledge JSON and attaches it to the Vector Store,
     * carrying the deterministic identity attributes plus {@code content_hash}/{@code
     * projection_version} for {@link ConceptKnowledgeSynchronizationService}'s change detection.
     * Package-visible so the synchronization service reuses this exact upload path for both a
     * brand-new concept and a changed one (delete-old-then-call-this, never a second, divergent
     * upload implementation).
     */
    String uploadAndAttach(String vectorStoreId, ConceptUnit unit) {
        byte[] json = buildConceptKnowledgeJson(unit);
        String filename = deterministicFilename(unit);

        String fileId = aiClient.uploadFile(json, filename, "application/json");

        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("concept_uid", unit.uid());
        attributes.put("concept_key", unit.entry().conceptKey());
        attributes.put("knowledge_type", "business-concept");
        attributes.put("pack_key", unit.packKey());
        attributes.put("connection_key", unit.connectionKey());
        attributes.put("content_hash", contentHash(unit));
        attributes.put("projection_version", PROJECTION_VERSION);
        aiClient.attachFileToVectorStore(vectorStoreId, fileId, attributes);
        return fileId;
    }

    /** Current shape version of the Concept Knowledge document/attribute contract itself (not the
     *  concept's own content) — bumped only if the document/attribute SHAPE changes, never per
     *  concept edit. Lets a future reconciliation distinguish "content changed" from "the whole
     *  projection format changed" if that's ever needed; unused by anything today beyond being
     *  stamped on every file. */
    static final String PROJECTION_VERSION = "1";

    /**
     * Deterministic SHA-256 content hash over exactly the fields that constitute this concept's
     * authoritative meaning ({@code conceptKey/name/aliases/description/operationalMeaning/
     * packKey/connectionKey}) — deliberately excluding {@code generated_at} (a timestamp, not
     * content) so re-deriving the same Postgres+Pack state always produces the same hash,
     * regardless of when it's computed. This is the missing piece that lets {@link
     * ConceptKnowledgeSynchronizationService} tell "unchanged, skip" apart from "content changed,
     * replace" — the identity key ({@link ConceptUnit#uid()}) alone cannot do this, since it does
     * not change when only descriptive text changes.
     */
    String contentHash(ConceptUnit unit) {
        String canonical = String.join("",
                nullToEmpty(unit.entry().conceptKey()),
                nullToEmpty(unit.entry().name()),
                String.join(",", unit.entry().aliases() != null ? unit.entry().aliases() : List.of()),
                nullToEmpty(unit.entry().description()),
                nullToEmpty(unit.entry().operationalMeaning()),
                nullToEmpty(unit.packKey()),
                nullToEmpty(unit.connectionKey()));
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every JVM — this branch is unreachable in practice.
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "SHA-256 unavailable: " + e.getMessage());
        }
    }

    private String nullToEmpty(String s) { return s == null ? "" : s; }

    /**
     * The structured representation itself — the SAME fields {@code
     * ConceptScopedMetadataResolver} already sends the concept-selection LLM
     * (concept_key/name/aliases/description/operational_meaning), plus the provenance (pack,
     * connection) needed to preserve which physical representation this knowledge belongs to, and
     * a generation timestamp. No speculative fields, no physical column metadata beyond what's
     * already here (there is none — this is concept-level knowledge only).
     */
    byte[] buildConceptKnowledgeJson(ConceptUnit unit) {
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

    String deterministicFilename(ConceptUnit unit) {
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
                units.add(new ConceptUnit(connectionKey, tenantPack.packKey(), entry));
            }
        }
        return units;
    }

    /**
     * AI Knowledge Vector Store sync watermark support: the concept-level Business Entity rows
     * whose {@code created_at}/{@code updated_at} is after {@code since} — i.e. changed since the
     * tenant's last successful synchronization. Package-visible so {@link
     * ConceptKnowledgeSynchronizationService} can build its "pending changes" status without a
     * second, divergent connection/pack iteration — mirrors {@link #collectConceptUnits()}'s exact
     * pack/connection scoping (same {@code findAppliedPacks}/{@code connectionKey} loop), swapping
     * the concept-key-membership query for the timestamp one.
     *
     * <p><b>Scope note:</b> this reports concept-level (Business Entity) changes only, since that
     * is the only granularity {@link #collectConceptUnits()} itself ever materializes — a
     * column-only edit (a {@code nexus_data_column} change with no corresponding Business Entity
     * update) is not visible here, and is not something this sync feature detects at all today (see
     * this feature's own report). Deliberately Postgres-only: no OpenAI/Vector Store call.
     */
    List<PendingConceptChange> findChangedConceptEntities(Instant since) {
        List<PendingConceptChange> changes = new ArrayList<>();
        for (TenantPack tenantPack : packRepository.findAppliedPacks()) {
            String connectionKey = tenantPack.connectionKey();
            if (connectionKey == null || connectionKey.isBlank()) continue;
            for (com.sei.nexus.semantic.BusinessEntity entity :
                    semanticService.findEntitiesChangedAfterForConnection(connectionKey, since)) {
                Instant changedAt = entity.updatedAt() != null ? entity.updatedAt() : entity.createdAt();
                changes.add(new PendingConceptChange(connectionKey, entity.conceptKey(),
                        entity.entityName(), changedAt));
            }
        }
        return changes;
    }

    /** One concept-level metadata change observed after a given watermark — see {@link
     *  #findChangedConceptEntities}. Package-visible for the same reason as {@link ConceptUnit}. */
    record PendingConceptChange(String connectionKey, String conceptKey, String entityName, Instant changedAt) {}
}
