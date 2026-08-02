package com.sei.nexus.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Brings every existing tenant schema up to the latest Flyway version at
 * application startup.
 *
 * <p>Background (PRO-10 incident): tenant schemas are migrated by
 * {@link TenantProvisioningService} only at creation time, so migrations added
 * afterwards never reached existing tenants. This stayed invisible while
 * migrations only created new tables — the {@code search_path = tenant, public}
 * fallback resolved the missing tables in {@code public} — but V034's ALTER of
 * {@code nexus_data_column} made the drift fatal: code selecting the new
 * columns failed against unmigrated tenant schemas
 * ("bad SQL grammar [SELECT * FROM nexus_data_column …]").
 *
 * <p>Behavior:
 * <ul>
 *   <li>Runs once at startup, before other {@link ApplicationRunner}s
 *       ({@code @Order(0)}), after Spring Boot's own Flyway has migrated
 *       {@code public}.</li>
 *   <li>Migrates every registered tenant schema except {@code public},
 *       regardless of status — a suspended tenant must not resume onto a
 *       stale schema.</li>
 *   <li>Per-schema isolation: one failing schema is logged and skipped so a
 *       single broken tenant cannot prevent the platform (or other tenants)
 *       from starting.</li>
 * </ul>
 */
@Component
@Order(0)
public class TenantSchemaMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TenantSchemaMigrator.class);

    private final TenantRepository           tenantRepository;
    private final TenantProvisioningService  provisioningService;

    public TenantSchemaMigrator(TenantRepository tenantRepository,
                                TenantProvisioningService provisioningService) {
        this.tenantRepository    = tenantRepository;
        this.provisioningService = provisioningService;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Tenant> tenants;
        try {
            tenants = tenantRepository.findAll();
        } catch (Exception e) {
            log.error("Tenant schema catch-up skipped — could not load tenant registry: {}",
                    e.getMessage());
            return;
        }

        int migrated = 0;
        int failed   = 0;
        for (Tenant tenant : tenants) {
            String schema = tenant.schemaName();
            if (schema == null || schema.isBlank() || TenantContext.PUBLIC_SCHEMA.equals(schema)) {
                continue; // public is migrated by Spring Boot's own Flyway at startup
            }
            try {
                provisioningService.migrateSchemaToLatest(schema);
                migrated++;
            } catch (Exception e) {
                failed++;
                log.error("Tenant schema catch-up FAILED for '{}' (tenant '{}'): {}",
                        schema, tenant.slug(), e.getMessage());
            }
        }
        log.info("Tenant schema catch-up complete: {} schema(s) checked/migrated, {} failed",
                migrated, failed);
    }
}
