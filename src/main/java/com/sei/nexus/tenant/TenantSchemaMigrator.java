package com.sei.nexus.tenant;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Applies any pending Flyway migrations to every existing tenant schema on
 * application startup.
 *
 * <p>New migrations are normally only applied when a new tenant is provisioned.
 * This component closes that gap: when a migration file is added after tenants
 * already exist, those schemas are brought up to date on the next server start.
 *
 * <p>Uses the raw {@link HikariDataSource} (not the tenant-aware wrapper) so
 * Flyway controls the {@code search_path} directly via its {@code .schemas()}
 * setting, bypassing {@link TenantContext}.
 */
@Component
public class TenantSchemaMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TenantSchemaMigrator.class);

    private final TenantRepository tenantRepository;
    private final HikariDataSource rawDataSource;

    public TenantSchemaMigrator(TenantRepository tenantRepository,
                                 @Qualifier("rawDataSource") HikariDataSource rawDataSource) {
        this.tenantRepository = tenantRepository;
        this.rawDataSource    = rawDataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Tenant> tenants;
        try {
            tenants = tenantRepository.findAll();
        } catch (Exception e) {
            log.error("TenantSchemaMigrator: could not load tenant list — skipping: {}", e.getMessage());
            return;
        }

        int migratedCount = 0;
        for (Tenant tenant : tenants) {
            String schema = tenant.schemaName();
            if ("public".equals(schema))          continue;
            if ("DEPROVISIONED".equals(tenant.status())) continue;

            try {
                int applied = Flyway.configure()
                        .dataSource(rawDataSource)
                        .schemas(schema)
                        .locations("classpath:db/migration")
                        .baselineOnMigrate(true)
                        .baselineVersion("0")
                        .outOfOrder(false)
                        .validateOnMigrate(false)
                        .target(MigrationVersion.LATEST)
                        .load()
                        .migrate()
                        .migrationsExecuted;

                if (applied > 0) {
                    log.info("Applied {} pending migration(s) to tenant schema '{}'", applied, schema);
                    migratedCount++;
                }
            } catch (Exception e) {
                log.error("Failed to apply migrations to tenant schema '{}': {}", schema, e.getMessage());
            }
        }

        if (migratedCount == 0) {
            log.info("TenantSchemaMigrator: all tenant schemas are up to date");
        }
    }
}
