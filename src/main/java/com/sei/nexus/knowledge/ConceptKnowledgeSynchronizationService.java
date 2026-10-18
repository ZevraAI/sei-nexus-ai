package com.sei.nexus.knowledge;

import com.sei.nexus.tenant.Tenant;
import com.sei.nexus.tenant.TenantContext;
import com.sei.nexus.tenant.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converges the tenant's concept retrieval index ({@code nexus_concept_embedding}) to whatever
 * Postgres (+ the static Industry Pack catalogue) currently, authoritatively says it should be.
 *
 * <p><b>Postgres remains authoritative; the index is derived state.</b> The only thing this
 * service compares is the index's stored fingerprint (content hash + projection version) against
 * the authoritative projection {@link ConceptKnowledgeMaterializationService#collectConceptUnits()}
 * computes, then embeds-and-stores what is new or changed and deletes what no longer exists. An
 * unchanged concept costs nothing: no embedding call, no write.
 *
 * <p><b>Exactly one synchronization implementation.</b> Every trigger — Pack apply/remove, object
 * onboarding, learning promotion, the nightly reconciliation and the startup reconciliation —
 * ends in {@link #synchronize()}. There is no manual sync.
 *
 * <p><b>Tenant scoping</b>: every entry point resolves the tenant strictly from the calling
 * thread's {@link TenantContext} (fail-closed via {@link TenantContext#getSchemaStrict()}), never
 * from a caller-supplied id. See {@link ConceptSyncAsyncConfig} for how the context reaches the
 * async executor.
 */
@Service
public class ConceptKnowledgeSynchronizationService {

    private static final Logger log = LoggerFactory.getLogger(ConceptKnowledgeSynchronizationService.class);

    private final TenantRepository                       tenantRepository;
    private final ConceptKnowledgeMaterializationService materializationService;
    private final ConceptEmbeddingRepository             embeddingRepository;

    /**
     * Per-tenant-schema concurrency guard. Application-level only — sufficient for a
     * single-instance deployment (the assumption every other async/scheduled mechanism in this
     * codebase already makes). A multi-instance deployment would need a durable cross-instance lock.
     */
    private final Set<String> inProgress = ConcurrentHashMap.newKeySet();

    public ConceptKnowledgeSynchronizationService(TenantRepository tenantRepository,
                                                   ConceptKnowledgeMaterializationService materializationService,
                                                   ConceptEmbeddingRepository embeddingRepository) {
        this.tenantRepository         = tenantRepository;
        this.materializationService   = materializationService;
        this.embeddingRepository      = embeddingRepository;
    }

    // ── Public result/status shapes ──────────────────────────────────────────────

    public enum Status { SYNCING, IN_SYNC, FAILED }

    public record SyncResult(Status status, int createdCount, int updatedCount, int deletedCount,
                              int unchangedCount, int failedCount, Instant startedAt,
                              Instant completedAt, String errorMessage) {}

    // ── Triggers (all end in synchronize()) ──────────────────────────────────────

    /**
     * Fire-and-forget trigger used by every automatic caller (Pack apply/remove, object onboarding,
     * learning promotion). Must be called while the caller's
     * own thread still has the correct {@link TenantContext}; {@link ConceptSyncAsyncConfig}'s
     * decorator captures it at submission time.
     */
    @Async("conceptSyncExecutor")
    public void triggerAsync() {
        synchronize();
    }

    /**
     * The synchronous convergence itself — resolves the tenant from the calling thread's {@link
     * TenantContext}, diffs the authoritative projection against the index, and embeds/replaces/
     * deletes to converge. Safe to call directly (reconciliation, tests) or via {@link
     * #triggerAsync()}.
     */
    public SyncResult synchronize() {
        String schema;
        try {
            schema = TenantContext.getSchemaStrict();
        } catch (IllegalStateException e) {
            log.warn("Concept index synchronization skipped — no tenant context on the calling "
                    + "thread; refusing to run against the shared 'public' schema: {}", e.getMessage());
            return new SyncResult(Status.FAILED, 0, 0, 0, 0, 0, null, null, "No tenant context available");
        }

        if (!inProgress.add(schema)) {
            log.debug("Concept index synchronization already in progress for schema '{}' — skipping", schema);
            return new SyncResult(Status.SYNCING, 0, 0, 0, 0, 0, null, null, null);
        }

        Instant startedAt = Instant.now();
        log.info("CONCEPT_SYNC start tenant_schema={}", schema);

        try {
            Diff diff = computeDiff();

            int created = 0, updated = 0, deleted = 0, failed = 0;
            List<String> failures = new ArrayList<>();

            for (ConceptKnowledgeMaterializationService.ConceptUnit unit : diff.toCreate()) {
                try {
                    materializationService.embedAndStore(unit);
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

            for (ConceptKnowledgeMaterializationService.ConceptUnit unit : diff.toUpdate()) {
                try {
                    // Same upsert as create: the row is replaced in place, so there is never a window
                    // in which a changed concept has no row.
                    materializationService.embedAndStore(unit);
                    updated++;
                    log.info("CONCEPT_SYNC action=UPDATE tenant_schema={} connection={} pack={} concept={}",
                            schema, unit.connectionKey(), unit.packKey(), unit.entry().conceptKey());
                } catch (Exception e) {
                    failed++;
                    failures.add("update " + unit.uid() + ": " + e.getMessage());
                    log.warn("CONCEPT_SYNC action=UPDATE_FAILED tenant_schema={} concept_uid={} error={}",
                            schema, unit.uid(), e.getMessage());
                }
            }

            for (String staleUid : diff.toDelete()) {
                try {
                    embeddingRepository.deleteByUid(staleUid);
                    deleted++;
                    log.info("CONCEPT_SYNC action=DELETE tenant_schema={} concept_uid={}", schema, staleUid);
                } catch (Exception e) {
                    failed++;
                    failures.add("delete " + staleUid + ": " + e.getMessage());
                    log.warn("CONCEPT_SYNC action=DELETE_FAILED tenant_schema={} concept_uid={} error={}",
                            schema, staleUid, e.getMessage());
                }
            }

            Instant completedAt = Instant.now();
            String errorMessage = failures.isEmpty() ? null : String.join("; ", failures);
            log.info("CONCEPT_SYNC summary tenant_schema={} candidates={} created={} updated={} deleted={} "
                            + "unchanged={} failed={}",
                    schema, created + updated + deleted + diff.unchangedCount(), created, updated, deleted,
                    diff.unchangedCount(), failed);

            Status resultStatus = failed == 0 ? Status.IN_SYNC : Status.FAILED;
            return new SyncResult(resultStatus, created, updated, deleted, diff.unchangedCount(), failed,
                    startedAt, completedAt, errorMessage);
        } catch (Exception e) {
            log.warn("CONCEPT_SYNC failed entirely for tenant_schema={}: {}", schema, e.getMessage());
            return new SyncResult(Status.FAILED, 0, 0, 0, 0, 0, startedAt, Instant.now(), e.getMessage());
        } finally {
            inProgress.remove(schema);
        }
    }

    // ── Diff computation ─────────────────────────────────────────────────────────

    private record Diff(List<ConceptKnowledgeMaterializationService.ConceptUnit> toCreate,
                         List<ConceptKnowledgeMaterializationService.ConceptUnit> toUpdate,
                         List<String> toDelete,
                         int unchangedCount) {}

    private Diff computeDiff() {
        List<ConceptKnowledgeMaterializationService.ConceptUnit> authoritative =
                materializationService.collectConceptUnits();
        Map<String, String> stored = embeddingRepository.findFingerprintsByUid();

        List<ConceptKnowledgeMaterializationService.ConceptUnit> toCreate = new ArrayList<>();
        List<ConceptKnowledgeMaterializationService.ConceptUnit> toUpdate = new ArrayList<>();
        Set<String> authoritativeUids = new HashSet<>();
        int unchanged = 0;

        for (ConceptKnowledgeMaterializationService.ConceptUnit unit : authoritative) {
            String uid = unit.uid();
            authoritativeUids.add(uid);
            String existing = stored.get(uid);
            if (existing == null) {
                toCreate.add(unit);
                continue;
            }
            String current = materializationService.contentHash(unit) + "|"
                    + ConceptKnowledgeMaterializationService.PROJECTION_VERSION;
            if (existing.equals(current)) {
                unchanged++;
            } else {
                toUpdate.add(unit);
            }
        }

        List<String> toDelete = new ArrayList<>();
        for (String uid : stored.keySet()) {
            if (!authoritativeUids.contains(uid)) toDelete.add(uid);
        }
        return new Diff(toCreate, toUpdate, toDelete, unchanged);
    }

    // ── Reconciliation ───────────────────────────────────────────────────────────

    /**
     * Brings every tenant's index up to date once the application has started. This is what builds
     * the index for tenants that existed before it did, and what repairs any drift that accumulated
     * while the application was down (most upstream mutation paths are best-effort, so a trigger
     * could in principle be missed). Runs on its own thread so startup is never delayed — NOT on
     * {@code conceptSyncExecutor}, whose decorator (correctly) refuses a task submitted without a
     * tenant context, and startup has none; {@link #reconcileAllTenants()} establishes each tenant's
     * context itself. An already-converged tenant costs only Postgres reads.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        log.info("CONCEPT_SYNC_RECONCILE startup begin");
        Thread t = new Thread(this::reconcileAllTenants, "concept-index-startup-reconcile");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Nightly reconciliation — a safety net behind the triggers. Iterates every ACTIVE tenant
     * sequentially on this scheduler's own thread (the same direct {@code TenantContext.set/clear}
     * pattern {@code SemanticLearningService.runMaintenanceAcrossTenants} uses).
     */
    @Scheduled(cron = "0 15 3 * * *")
    public void reconcileAllTenants() {
        List<Tenant> tenants;
        try {
            tenants = tenantRepository.findAll();
        } catch (Exception e) {
            log.warn("Concept index reconciliation could not load tenant list: {}", e.getMessage());
            return;
        }
        for (Tenant tenant : tenants) {
            if (!"ACTIVE".equals(tenant.status())) continue;
            // The seeded default/platform tenant (slug 'default', schema_name 'public') is the shared
            // platform schema, never a real customer tenant — must never be synchronized as one.
            if ("public".equals(tenant.schemaName())) continue;
            TenantContext.set(tenant.schemaName());
            log.info("CONCEPT_SYNC_RECONCILE start tenant={}", tenant.slug());
            try {
                SyncResult result = synchronize();
                log.info("CONCEPT_SYNC_RECONCILE complete tenant={} status={} created={} updated={} deleted={} "
                                + "unchanged={} failed={}",
                        tenant.slug(), result.status(), result.createdCount(), result.updatedCount(),
                        result.deletedCount(), result.unchangedCount(), result.failedCount());
            } catch (Exception e) {
                log.warn("CONCEPT_SYNC_RECONCILE failed tenant={} error={}", tenant.slug(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }
}
