package com.sei.nexus.governance;

import com.sei.nexus.common.Keys;
import com.sei.nexus.tenant.TenantContext;
import com.sei.nexus.tenant.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes compliance audit events asynchronously after every query execution,
 * and runs a nightly retention purge across all tenant schemas.
 *
 * <h3>Retention policy</h3>
 * <p>Configured via {@code nexus.retention.audit-log-days} (default 90).
 * Set to {@code 0} to keep audit events indefinitely.
 * The purge runs daily at 02:15 local server time across every active
 * tenant schema, using the same multi-tenant loop used by BaselineService.
 */
@Service
public class GovernanceAuditService {

    private static final Logger log = LoggerFactory.getLogger(GovernanceAuditService.class);

    @Value("${nexus.retention.audit-log-days:90}")
    private int auditLogRetentionDays;

    private final AuditEventRepository auditEventRepository;
    private final TenantRepository     tenantRepository;
    private final JdbcTemplate         jdbc;

    public GovernanceAuditService(AuditEventRepository auditEventRepository,
                                  TenantRepository tenantRepository,
                                  JdbcTemplate jdbc) {
        this.auditEventRepository = auditEventRepository;
        this.tenantRepository     = tenantRepository;
        this.jdbc                 = jdbc;
    }

    // ── Event recording ───────────────────────────────────────────────────────

    /**
     * Persist an audit event derived from the supplied {@link AuditContext}.
     * Called fire-and-forget via {@code @Async} — exceptions are swallowed to
     * prevent audit failures from surfacing to users.
     */
    @Async
    public void record(AuditContext ctx, boolean blocked) {
        try {
            String eventType = ctx.deriveEventType(blocked);
            AuditEvent event = ctx.toEvent(eventType);

            AuditEvent withKey = new AuditEvent(
                    Keys.uniqueKey("audit"),
                    event.eventType(),
                    event.userEmail(), event.userRole(),
                    event.runKey(), event.connectionKey(),
                    event.objectKeys(),
                    event.columnsAccessed(), event.columnsMasked(),
                    event.rlsPoliciesApplied(),
                    event.contractsChecked(), event.contractsViolated(),
                    event.originalSql(), event.executedSql(),
                    event.rowCountReturned(), event.rowsFilteredByRls(),
                    event.executionMs(), event.ipAddress(),
                    Instant.now());

            auditEventRepository.save(withKey);
        } catch (Exception e) {
            log.error("Failed to write audit event: {}", e.getMessage(), e);
        }
    }

    // ── Retention purge ───────────────────────────────────────────────────────

    /**
     * Nightly job (02:15) that deletes audit events older than
     * {@code nexus.retention.audit-log-days} across every active tenant schema.
     *
     * <p>Runs at a quiet off-peak time. Each tenant schema is processed in
     * isolation so a slow schema doesn't block others. Setting
     * {@code audit-log-days=0} disables the purge entirely.
     */
    @Scheduled(cron = "0 15 2 * * *")
    public void purgeExpiredAuditEvents() {
        if (auditLogRetentionDays <= 0) {
            log.debug("Audit log retention purge disabled (audit-log-days={})", auditLogRetentionDays);
            return;
        }

        // Build the list of schemas to purge: public workspace + all active tenants
        List<String> schemas = new ArrayList<>();
        schemas.add(TenantContext.PUBLIC_SCHEMA);
        try {
            tenantRepository.findAll().stream()
                    .filter(t -> "ACTIVE".equals(t.status()))
                    .map(t -> t.schemaName())
                    .forEach(schemas::add);
        } catch (Exception e) {
            log.warn("Could not load tenant list for audit purge: {}", e.getMessage());
        }

        int totalDeleted = 0;
        for (String schema : schemas) {
            TenantContext.set(schema);
            try {
                int deleted = jdbc.update(
                        "DELETE FROM nexus_audit_event " +
                        "WHERE created_at < NOW() - INTERVAL '" + auditLogRetentionDays + " days'");
                if (deleted > 0) {
                    log.info("Audit purge: deleted {} event(s) older than {} days from schema '{}'",
                            deleted, auditLogRetentionDays, schema);
                    totalDeleted += deleted;
                }
            } catch (Exception e) {
                log.warn("Audit purge failed for schema '{}': {}", schema, e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }

        if (totalDeleted > 0) {
            log.info("Audit purge complete: {} total event(s) deleted across {} schema(s)",
                    totalDeleted, schemas.size());
        }
    }
}
