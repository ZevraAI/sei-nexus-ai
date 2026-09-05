package com.sei.nexus.knowledge;

import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.onboarding.TenantSettingsRepository;
import com.sei.nexus.tenant.Tenant;
import com.sei.nexus.tenant.TenantContext;
import com.sei.nexus.tenant.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converges the tenant's OpenAI Vector Store Concept Knowledge projection to whatever Postgres
 * (+ the static Industry Pack catalogue) currently, authoritatively says it should be.
 *
 * <p><b>Postgres remains authoritative; the Vector Store is disposable derived state.</b> This
 * service never reads FROM the Vector Store to decide anything about business meaning — it only
 * reads the Vector Store's own file list to compare identity/content against the authoritative
 * projection {@link ConceptKnowledgeMaterializationService#collectConceptUnits()} already
 * computes, then creates/replaces/deletes Vector Store documents to match. It performs no
 * semantic reasoning of its own (no LLM call anywhere in this class).
 *
 * <p>Reuses, rather than duplicates: {@link ConceptKnowledgeMaterializationService}'s existing
 * projection logic ({@code collectConceptUnits}), document-building ({@code
 * buildConceptKnowledgeJson}/{@code uploadAndAttach}), and content hashing ({@code contentHash}).
 * This class adds only the missing piece — comparison against current Vector Store state, and the
 * create/update/delete/no-op decision that comparison implies — plus the operational surface
 * (async triggers, status telemetry, concurrency guard, reconciliation) needed to make that safe
 * to run automatically.
 *
 * <p><b>Tenant scoping</b>: every public entry point here resolves the tenant strictly from the
 * calling thread's {@link TenantContext} (fail-closed via {@link TenantContext#getSchemaStrict()})
 * — never from a caller-supplied tenant id/slug/vector-store-id. This mirrors the exact discipline
 * {@code TenantContextPropagatingTaskDecorator} already established for {@code
 * SemanticLearningService}'s async methods, reused verbatim for {@link #conceptSyncExecutor} (see
 * {@link ConceptSyncAsyncConfig}).
 */
@Service
public class ConceptKnowledgeSynchronizationService {

    private static final Logger log = LoggerFactory.getLogger(ConceptKnowledgeSynchronizationService.class);

    private static final String KEY_LAST_STARTED_AT   = "concept_sync_last_started_at";
    private static final String KEY_LAST_COMPLETED_AT = "concept_sync_last_completed_at";
    private static final String KEY_LAST_STATUS       = "concept_sync_last_status";
    private static final String KEY_LAST_ERROR        = "concept_sync_last_error";
    // Sync watermark (AI Knowledge Vector Store sync enhancement): unlike KEY_LAST_COMPLETED_AT
    // (stamped on every completion regardless of outcome), this is set ONLY when a synchronize()
    // run finishes with zero failures — see the "never advance past a failed synchronization"
    // rule this feature's report documents. A partial run (some concepts failed) leaves this
    // untouched, so findMetadataChangedAfter-style pending-change detection keeps including every
    // concept changed since the last fully-clean run, not just the ones that failed.
    private static final String KEY_LAST_SUCCESSFUL_AT = "concept_sync_last_successful_at";

    private final TenantRepository                      tenantRepository;
    private final AzureOpenAiClient                      aiClient;
    private final ConceptKnowledgeMaterializationService materializationService;
    private final TenantSettingsRepository               tenantSettingsRepository;

    /**
     * Per-tenant-schema concurrency guard. Application-level only — sufficient for a
     * single-instance deployment (confirmed the assumption every other async/scheduled mechanism
     * in this codebase already makes, e.g. {@code SemanticLearningService}'s executor). A
     * multi-instance deployment would need a durable, cross-instance lock instead; documented as a
     * known limitation rather than solved here, since nothing in this codebase today proves
     * multi-instance deployment is in play (see the Risks section of this feature's own report).
     */
    private final Set<String> inProgress = ConcurrentHashMap.newKeySet();

    public ConceptKnowledgeSynchronizationService(TenantRepository tenantRepository,
                                                   AzureOpenAiClient aiClient,
                                                   ConceptKnowledgeMaterializationService materializationService,
                                                   TenantSettingsRepository tenantSettingsRepository) {
        this.tenantRepository        = tenantRepository;
        this.aiClient                = aiClient;
        this.materializationService  = materializationService;
        this.tenantSettingsRepository = tenantSettingsRepository;
    }

    // ── Public result/status shapes ──────────────────────────────────────────────

    // CHANGES_PENDING/FAILED are the AI Knowledge Vector Store sync enhancement's naming (formerly
    // OUT_OF_SYNC/SYNC_FAILED) — renamed to match this feature's exact required states (status
    // derived from actual sync state + the watermark below, never guessed by the frontend).
    public enum Status { NOT_INITIALIZED, SYNCING, IN_SYNC, CHANGES_PENDING, FAILED }

    public record SyncResult(Status status, int createdCount, int updatedCount, int deletedCount,
                              int unchangedCount, int failedCount, Instant startedAt,
                              Instant completedAt, String errorMessage) {}

    /** One concept-level metadata change not yet reflected in the last successful synchronization —
     *  UI-facing shape (see {@link ConceptKnowledgeMaterializationService.PendingConceptChange}). */
    public record PendingChange(String type, String name, String changedAt) {}

    public record StatusReport(Status status, int totalConcepts, int pendingChangeCount,
                                List<PendingChange> pendingChanges, boolean vectorStoreConnected,
                                boolean syncInProgress, String lastSuccessfulSync,
                                String lastSyncStartedAt, String lastSyncCompletedAt,
                                String lastSyncStatus, String lastSyncError) {}

    // ── Manual / automatic trigger (same implementation, both async) ────────────

    /**
     * Fire-and-forget trigger for both automatic (Pack apply/remove) and manual (Admin "Sync Now")
     * callers — deliberately the SAME method, so there is exactly one synchronization
     * implementation regardless of who asked for it. Must be called while the caller's own thread
     * still has the correct {@link TenantContext} set (e.g. from the request/servlet-filter chain)
     * — {@link ConceptSyncAsyncConfig}'s {@code TenantContextPropagatingTaskDecorator} captures it
     * at submission time, exactly like {@code SemanticLearningService}'s executor already does.
     */
    @Async("conceptSyncExecutor")
    public void triggerAsync() {
        synchronize();
    }

    /**
     * The synchronous convergence itself — resolves the tenant strictly from the calling thread's
     * {@link TenantContext}, diffs the authoritative projection against the Vector Store's current
     * Zevra-managed files, and creates/replaces/deletes to converge. Safe to call directly
     * (reconciliation, tests) or via {@link #triggerAsync()} (production triggers).
     */
    public SyncResult synchronize() {
        String schema;
        try {
            schema = TenantContext.getSchemaStrict();
        } catch (IllegalStateException e) {
            log.warn("Concept knowledge synchronization skipped — no tenant context on the "
                    + "calling thread; refusing to run against the shared 'public' schema: {}", e.getMessage());
            return new SyncResult(Status.FAILED, 0, 0, 0, 0, 0, null, null,
                    "No tenant context available");
        }

        if (!inProgress.add(schema)) {
            log.debug("Concept knowledge synchronization already in progress for schema '{}' — skipping", schema);
            return new SyncResult(Status.SYNCING, 0, 0, 0, 0, 0, null, null, null);
        }

        Instant startedAt = Instant.now();
        tenantSettingsRepository.set(KEY_LAST_STARTED_AT, startedAt.toString());
        log.info("CONCEPT_SYNC start tenant_schema={}", schema);

        try {
            Tenant tenant = tenantRepository.findBySchemaName(schema)
                    .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND, "Tenant not found for schema: " + schema));
            String vectorStoreId = tenant.aiKnowledgeVectorStoreId();
            if (vectorStoreId == null || vectorStoreId.isBlank()) {
                String msg = "Tenant has no AI knowledge store provisioned yet";
                recordCompletion(startedAt, "FAILED", msg);
                return new SyncResult(Status.FAILED, 0, 0, 0, 0, 0, startedAt, Instant.now(), msg);
            }

            Diff diff = computeDiff(vectorStoreId);

            int created = 0, updated = 0, deleted = 0, failed = 0;
            List<String> failures = new ArrayList<>();

            for (ConceptKnowledgeMaterializationService.ConceptUnit unit : diff.toCreate()) {
                try {
                    materializationService.uploadAndAttach(vectorStoreId, unit);
                    created++;
                    log.info("CONCEPT_SYNC action=CREATE tenant_schema={} connection={} pack={} concept={}",
                            schema, unit.connectionKey(), unit.packKey(), unit.entry().conceptKey());
                } catch (Exception e) {
                    failed++;
                    failures.add("create " + unit.uid() + ": " + e.getMessage());
                    log.warn("CONCEPT_SYNC action=CREATE_FAILED tenant_schema={} concept_uid={} error={}",
                            schema, unit.uid(), e.getMessage());
                }
            }

            for (var change : diff.toUpdate()) {
                try {
                    // Detach-then-delete-then-reupload. Detaching from THIS vector store is what
                    // actually removes the stale entry from listVectorStoreFiles/file_search —
                    // confirmed against the real OpenAI API that deleteFile alone does not do
                    // this (it only deletes the underlying File object; the vector store's file
                    // list keeps a now-dangling entry for it). No in-place update API exists for a
                    // vector-store file, so detach+delete+reupload is the only convergence path.
                    aiClient.detachFileFromVectorStore(vectorStoreId, change.existingFileId());
                    aiClient.deleteFile(change.existingFileId());
                    materializationService.uploadAndAttach(vectorStoreId, change.unit());
                    updated++;
                    log.info("CONCEPT_SYNC action=UPDATE tenant_schema={} connection={} pack={} concept={}",
                            schema, change.unit().connectionKey(), change.unit().packKey(), change.unit().entry().conceptKey());
                } catch (Exception e) {
                    failed++;
                    failures.add("update " + change.unit().uid() + ": " + e.getMessage());
                    log.warn("CONCEPT_SYNC action=UPDATE_FAILED tenant_schema={} concept_uid={} error={}",
                            schema, change.unit().uid(), e.getMessage());
                }
            }

            for (String staleFileId : diff.toDelete()) {
                try {
                    aiClient.detachFileFromVectorStore(vectorStoreId, staleFileId);
                    aiClient.deleteFile(staleFileId);
                    deleted++;
                    log.info("CONCEPT_SYNC action=DELETE tenant_schema={} file_id={}", schema, staleFileId);
                } catch (Exception e) {
                    failed++;
                    failures.add("delete " + staleFileId + ": " + e.getMessage());
                    log.warn("CONCEPT_SYNC action=DELETE_FAILED tenant_schema={} file_id={} error={}",
                            schema, staleFileId, e.getMessage());
                }
            }

            Instant completedAt = Instant.now();
            String outcome = failed == 0 ? "SUCCESS" : (created + updated + deleted + diff.unchangedCount() > failed ? "PARTIAL" : "FAILED");
            String errorMessage = failures.isEmpty() ? null : String.join("; ", failures);
            recordCompletion(startedAt, outcome, errorMessage);
            // Correctness rule: the watermark advances ONLY on a fully-clean run (failed == 0).
            // A PARTIAL run (some concepts succeeded, some failed) must never claim "everything
            // through now is synchronized" — leaving this untouched means the next status()/retry
            // still sees every concept changed since the last fully-clean run as pending, the
            // failed ones included, never a false IN_SYNC.
            if (failed == 0) {
                tenantSettingsRepository.set(KEY_LAST_SUCCESSFUL_AT, completedAt.toString());
            }

            log.info("CONCEPT_SYNC summary tenant_schema={} candidates={} created={} updated={} deleted={} "
                            + "unchanged={} failed={} watermarkAdvanced={}",
                    schema, created + updated + deleted + diff.unchangedCount(), created, updated, deleted,
                    diff.unchangedCount(), failed, failed == 0);

            Status resultStatus = failed == 0 ? Status.IN_SYNC : Status.FAILED;
            return new SyncResult(resultStatus, created, updated, deleted, diff.unchangedCount(), failed,
                    startedAt, completedAt, errorMessage);
        } catch (Exception e) {
            recordCompletion(startedAt, "FAILED", e.getMessage());
            log.warn("CONCEPT_SYNC failed entirely for tenant_schema={}: {}", schema, e.getMessage());
            return new SyncResult(Status.FAILED, 0, 0, 0, 0, 0, startedAt, Instant.now(), e.getMessage());
        } finally {
            inProgress.remove(schema);
        }
    }

    private void recordCompletion(Instant startedAt, String status, String error) {
        tenantSettingsRepository.set(KEY_LAST_COMPLETED_AT, Instant.now().toString());
        tenantSettingsRepository.set(KEY_LAST_STATUS, status);
        if (error != null) {
            tenantSettingsRepository.set(KEY_LAST_ERROR, error);
        } else {
            tenantSettingsRepository.delete(KEY_LAST_ERROR);
        }
    }

    // ── Read-only status (no mutation, no OpenAI upload/delete calls) ───────────

    /**
     * Computes current status from the sync watermark against Postgres metadata timestamps —
     * deliberately Postgres-only (no {@code listVectorStoreFiles}/OpenAI call at all), per this
     * feature's own core design decision: the authoritative comparison for "is anything pending"
     * is {@code metadata.updated_at > lastSuccessfulSync}, not a live Vector Store diff. The
     * heavyweight OpenAI-backed comparison ({@link #computeDiff}) remains exactly as it was, used
     * only where it always was — inside {@link #synchronize()} itself, to decide what to actually
     * upload/delete. Resolves the tenant from the calling thread's {@link TenantContext} exactly
     * like {@link #synchronize()} does.
     */
    public StatusReport status() {
        String schema = TenantContext.getSchemaStrict();
        Tenant tenant = tenantRepository.findBySchemaName(schema)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND, "Tenant not found for schema: " + schema));

        String lastStartedAt    = tenantSettingsRepository.get(KEY_LAST_STARTED_AT).orElse(null);
        String lastCompletedAt  = tenantSettingsRepository.get(KEY_LAST_COMPLETED_AT).orElse(null);
        String lastStatus       = tenantSettingsRepository.get(KEY_LAST_STATUS).orElse(null);
        String lastError        = tenantSettingsRepository.get(KEY_LAST_ERROR).orElse(null);
        String lastSuccessfulAt = tenantSettingsRepository.get(KEY_LAST_SUCCESSFUL_AT).orElse(null);

        String vectorStoreId = tenant.aiKnowledgeVectorStoreId();
        boolean vectorStoreConnected = vectorStoreId != null && !vectorStoreId.isBlank();
        boolean syncing = inProgress.contains(schema);

        if (!vectorStoreConnected) {
            return new StatusReport(Status.NOT_INITIALIZED, 0, 0, List.of(), false, syncing,
                    lastSuccessfulAt, lastStartedAt, lastCompletedAt, lastStatus, lastError);
        }

        int totalConcepts = materializationService.collectConceptUnits().size();

        if (syncing) {
            return new StatusReport(Status.SYNCING, totalConcepts, -1, List.of(), true, true,
                    lastSuccessfulAt, lastStartedAt, lastCompletedAt, lastStatus, lastError);
        }

        // Never synced successfully yet: Instant.EPOCH as the watermark means every concept's own
        // created_at/updated_at is trivially "after" it, so the same real per-concept query below
        // naturally lists every one of them individually — no separate synthetic placeholder
        // branch needed (an earlier version of this method used one; it hid exactly the per-object
        // detail this feature exists to show, so it's gone).
        Instant watermark = lastSuccessfulAt != null ? Instant.parse(lastSuccessfulAt) : Instant.EPOCH;
        List<PendingChange> pendingChanges = materializationService.findChangedConceptEntities(watermark).stream()
                .map(c -> new PendingChange("Business Object", c.entityName(), c.changedAt().toString()))
                .toList();

        Status status;
        if ("FAILED".equals(lastStatus) || "PARTIAL".equals(lastStatus)) {
            // A failed/partial run must keep surfacing as FAILED even if this watermark-based
            // check alone would otherwise read as clean — never a false IN_SYNC after a failure.
            status = Status.FAILED;
        } else if (!pendingChanges.isEmpty()) {
            status = Status.CHANGES_PENDING;
        } else {
            status = Status.IN_SYNC;
        }

        return new StatusReport(status, totalConcepts, pendingChanges.size(), pendingChanges,
                true, false, lastSuccessfulAt, lastStartedAt, lastCompletedAt, lastStatus, lastError);
    }

    // ── Diff computation — shared by synchronize() and status() ─────────────────

    private record PendingUpdate(ConceptKnowledgeMaterializationService.ConceptUnit unit, String existingFileId) {}

    private record Diff(List<ConceptKnowledgeMaterializationService.ConceptUnit> toCreate,
                         List<PendingUpdate> toUpdate,
                         List<String> toDelete,
                         int unchangedCount,
                         int existingManagedCount) {}

    /** Only files this service itself materializes are ever considered for deletion/replacement —
     *  a Vector Store file with a different {@code knowledge_type} (or none at all) is left
     *  strictly alone, regardless of what it is. */
    private static final String MANAGED_KNOWLEDGE_TYPE = "business-concept";

    private Diff computeDiff(String vectorStoreId) {
        List<ConceptKnowledgeMaterializationService.ConceptUnit> authoritative =
                materializationService.collectConceptUnits();

        Map<String, AzureOpenAiClient.VectorStoreFileRef> existingByUid = new HashMap<>();
        for (AzureOpenAiClient.VectorStoreFileRef ref : aiClient.listVectorStoreFiles(vectorStoreId)) {
            Map<String, String> attrs = ref.attributes();
            if (attrs == null) continue;
            if (!MANAGED_KNOWLEDGE_TYPE.equals(attrs.get("knowledge_type"))) continue; // not ours — never touch
            String uid = attrs.get("concept_uid");
            if (uid != null) existingByUid.put(uid, ref);
        }

        List<ConceptKnowledgeMaterializationService.ConceptUnit> toCreate = new ArrayList<>();
        List<PendingUpdate> toUpdate = new ArrayList<>();
        int unchanged = 0;
        Set<String> authoritativeUids = new HashSet<>();

        for (ConceptKnowledgeMaterializationService.ConceptUnit unit : authoritative) {
            String uid = unit.uid();
            authoritativeUids.add(uid);
            AzureOpenAiClient.VectorStoreFileRef existing = existingByUid.get(uid);
            if (existing == null) {
                toCreate.add(unit);
                continue;
            }
            String existingHash = existing.attributes() != null ? existing.attributes().get("content_hash") : null;
            String currentHash = materializationService.contentHash(unit);
            if (existingHash == null || !existingHash.equals(currentHash)) {
                toUpdate.add(new PendingUpdate(unit, existing.fileId()));
            } else {
                unchanged++;
            }
        }

        List<String> toDelete = new ArrayList<>();
        for (Map.Entry<String, AzureOpenAiClient.VectorStoreFileRef> entry : existingByUid.entrySet()) {
            if (!authoritativeUids.contains(entry.getKey())) {
                toDelete.add(entry.getValue().fileId());
            }
        }

        return new Diff(toCreate, toUpdate, toDelete, unchanged, existingByUid.size());
    }

    // ── Reconciliation — low-frequency safety net, not the primary mechanism ────

    /**
     * Nightly reconciliation, alongside {@code SemanticLearningService}'s own maintenance cron —
     * a safety net for the (already-existing, unrelated-to-this-feature) fact that most upstream
     * mutation paths in this codebase are non-transactional/best-effort, so a trigger call could
     * in principle be missed. Iterates every ACTIVE tenant sequentially on this scheduler's own
     * thread (same direct {@code TenantContext.set/clear} pattern {@code
     * SemanticLearningService.runMaintenanceAcrossTenants} already uses — no executor hand-off, so
     * no decorator is needed here). Only synchronizes when {@link #computeDiff} finds actual
     * drift — an already-converged tenant costs one {@code listVectorStoreFiles} call and zero
     * OpenAI uploads/deletes.
     */
    @Scheduled(cron = "0 15 3 * * *")
    public void reconcileAllTenants() {
        List<Tenant> tenants;
        try {
            tenants = tenantRepository.findAll();
        } catch (Exception e) {
            log.warn("Concept knowledge reconciliation could not load tenant list: {}", e.getMessage());
            return;
        }
        for (Tenant tenant : tenants) {
            if (!"ACTIVE".equals(tenant.status())) continue;
            // The seeded default/platform tenant (slug 'default', schema_name 'public' —
            // V008__tenant_registry.sql) is an ACTIVE row like any other, but is the shared
            // platform schema, never a real customer tenant — must never be synchronized as one.
            if ("public".equals(tenant.schemaName())) continue;
            if (tenant.aiKnowledgeVectorStoreId() == null || tenant.aiKnowledgeVectorStoreId().isBlank()) continue;
            TenantContext.set(tenant.schemaName());
            log.info("CONCEPT_SYNC_RECONCILE start tenant={}", tenant.slug());
            try {
                SyncResult result = synchronize();
                log.info("CONCEPT_SYNC_RECONCILE complete tenant={} status={} created={} updated={} deleted={} "
                                + "unchanged={} failed={} watermark={}",
                        tenant.slug(), result.status(), result.createdCount(), result.updatedCount(),
                        result.deletedCount(), result.unchangedCount(), result.failedCount(),
                        tenantSettingsRepository.get(KEY_LAST_SUCCESSFUL_AT).orElse(null));
            } catch (Exception e) {
                log.warn("CONCEPT_SYNC_RECONCILE failed tenant={} error={}", tenant.slug(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }
}
