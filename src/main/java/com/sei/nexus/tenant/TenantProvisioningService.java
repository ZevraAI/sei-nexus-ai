package com.sei.nexus.tenant;

import com.sei.nexus.common.NexusException;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Handles the full lifecycle of tenant provisioning and deprovisioning.
 *
 * <p>When a new tenant is created:
 * <ol>
 *   <li>The tenant record is written to {@code public.nexus_tenant}.</li>
 *   <li>A new PostgreSQL schema is created: {@code tenant_{slug}}.</li>
 *   <li>Flyway runs all migrations (V001–current) against the new schema,
 *       giving the tenant a fresh, fully-migrated set of tables.</li>
 *   <li>The initial admin user is created in the new schema.</li>
 *   <li>A default domain ({@code PLATFORM}) is seeded in the new schema.</li>
 * </ol>
 *
 * <p><strong>Schema naming:</strong> {@code tenant_} + sanitised slug.
 * Only lowercase alphanumeric characters and underscores are allowed.
 * The {@code public} schema is reserved for the default (development) tenant.
 */
@Service
public class TenantProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(TenantProvisioningService.class);

    // Schema names: tenant_ prefix + up to 40 chars of alphanumeric/underscore
    private static final Pattern VALID_SLUG    = Pattern.compile("^[a-z0-9][a-z0-9-]{1,62}$");
    private static final Pattern SAFE_SCHEMA   = Pattern.compile("^[a-zA-Z0-9_]{1,63}$");
    private static final String  SCHEMA_PREFIX = "tenant_";

    private final TenantRepository tenantRepository;
    private final DataSource       rawDataSource;   // unwrapped — bypasses tenant routing

    @Value("${nexus.security.jwt-secret}")
    private String jwtSecret;

    @Value("${nexus.security.session-expiry-hours:24}")
    private int sessionExpiryHours;

    public TenantProvisioningService(TenantRepository tenantRepository,
                                      DataSource rawDataSource) {
        this.tenantRepository = tenantRepository;
        this.rawDataSource    = rawDataSource;
    }

    // ── Provisioning ──────────────────────────────────────────────────────────

    /**
     * Provisions a new tenant end-to-end:
     * registry record → PostgreSQL schema → Flyway migrations → seed admin user.
     *
     * @param slug          URL-safe tenant identifier, e.g. {@code "acme-corp"}
     * @param name          Human-readable tenant name, e.g. {@code "Acme Corporation"}
     * @param plan          Subscription plan: TRIAL | STANDARD | PROFESSIONAL | ENTERPRISE
     * @param contactEmail  Billing/admin contact email
     * @param maxUsers      Maximum number of user accounts allowed
     * @param adminEmail    Email address for the first admin account
     * @param adminPassword Plain-text password for the first admin account (hashed before storage)
     * @return the newly created {@link Tenant}
     */
    public Tenant provision(String slug, String name, String plan,
                             String contactEmail, int maxUsers,
                             String adminEmail, String adminPassword) {

        validateSlug(slug);
        validatePlan(plan);
        validateEmail(adminEmail);
        validatePassword(adminPassword);

        if (tenantRepository.findBySlug(slug).isPresent()) {
            throw new NexusException(HttpStatus.CONFLICT, "Tenant slug already exists: " + slug);
        }

        String schemaName = SCHEMA_PREFIX + slug.replace('-', '_').toLowerCase();
        if (!SAFE_SCHEMA.matcher(schemaName).matches()) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "Derived schema name is invalid: " + schemaName);
        }

        log.info("Provisioning tenant '{}' → schema '{}'", slug, schemaName);

        // 1. Write tenant record
        Tenant tenant = new Tenant(
                UUID.randomUUID(), slug, name, schemaName, plan,
                "ACTIVE", contactEmail, maxUsers, Instant.now(), Instant.now());
        tenantRepository.save(tenant);

        // 2. Create PostgreSQL schema
        createSchema(schemaName);

        // 3. Phase 1: run migrations up to V006 (table structure only, no domain-dependent seeds)
        try {
            runMigrationsUpTo(schemaName, "006");
        } catch (Exception ex) {
            log.error("Phase-1 migration failed for tenant '{}', rolling back schema", slug, ex);
            dropSchema(schemaName);
            tenantRepository.updateStatus(slug, "DEPROVISIONED");
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Tenant provisioning failed during phase-1 migration: " + ex.getMessage());
        }

        // 4. Seed PLATFORM domain + admin user (must happen before V007 which references PLATFORM)
        try {
            seedTenantData(schemaName, slug, name, adminEmail, adminPassword);
        } catch (Exception ex) {
            log.error("Seed failed for tenant '{}': {}", slug, ex.getMessage(), ex);
            dropSchema(schemaName);
            tenantRepository.updateStatus(slug, "DEPROVISIONED");
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Tenant data seeding failed: " + ex.getMessage());
        }

        // 5. Phase 2: run V007 onwards — domain now exists so FK constraints are satisfied
        try {
            runMigrationsFrom(schemaName, "7");
        } catch (Exception ex) {
            log.error("Phase-2 migration failed for tenant '{}': {}", slug, ex.getMessage(), ex);
            dropSchema(schemaName);
            tenantRepository.updateStatus(slug, "DEPROVISIONED");
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Tenant provisioning failed during phase-2 migration: " + ex.getMessage());
        }

        log.info("Tenant '{}' provisioned successfully (schema: {})", slug, schemaName);
        return tenantRepository.findBySlug(slug).orElse(tenant);
    }

    // ── Deprovisioning ────────────────────────────────────────────────────────

    /**
     * Soft-suspends a tenant (retains data). Use {@link #deprovision} for full removal.
     */
    public void suspend(String slug) {
        requireTenant(slug);
        tenantRepository.updateStatus(slug, "SUSPENDED");
        log.info("Tenant '{}' suspended", slug);
    }

    /**
     * Permanently deprovisions a tenant: drops their schema and marks the
     * registry record as DEPROVISIONED. This is irreversible.
     */
    public void deprovision(String slug) {
        Tenant tenant = requireTenant(slug);
        if ("public".equals(tenant.schemaName())) {
            throw new NexusException(HttpStatus.FORBIDDEN,
                    "The default tenant cannot be deprovisioned");
        }
        tenantRepository.updateStatus(slug, "DEPROVISIONED");
        dropSchema(tenant.schemaName());
        log.warn("Tenant '{}' deprovisioned — schema '{}' dropped", slug, tenant.schemaName());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void createSchema(String schemaName) {
        log.debug("Creating schema '{}'", schemaName);
        try (Connection conn = rawDataSource.getConnection();
             Statement  stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS \"" + schemaName + "\"");
        } catch (SQLException ex) {
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to create schema '" + schemaName + "': " + ex.getMessage());
        }
    }

    private void dropSchema(String schemaName) {
        log.warn("Dropping schema '{}'", schemaName);
        try (Connection conn = rawDataSource.getConnection();
             Statement  stmt = conn.createStatement()) {
            stmt.execute("DROP SCHEMA IF EXISTS \"" + schemaName + "\" CASCADE");
        } catch (SQLException ex) {
            log.error("Failed to drop schema '{}': {}", schemaName, ex.getMessage());
        }
    }

    /** Phase 1: run Flyway up to (and including) the specified version. */
    private void runMigrationsUpTo(String schemaName, String targetVersion) {
        log.debug("Flyway phase-1: schema='{}' target='{}'", schemaName, targetVersion);
        flywayFor(schemaName).target(targetVersion).load().migrate();
    }

    /** Phase 2: run all Flyway migrations from the given version onwards. */
    private void runMigrationsFrom(String schemaName, String fromVersion) {
        log.debug("Flyway phase-2: schema='{}' from='{}'", schemaName, fromVersion);
        // Flyway always applies only pending migrations, so "latest" naturally
        // picks up everything after the phase-1 checkpoint.
        flywayFor(schemaName)
                .target(org.flywaydb.core.api.MigrationVersion.LATEST)
                .load()
                .migrate();
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration flywayFor(String schemaName) {
        return Flyway.configure()
                .dataSource(rawDataSource)
                .schemas(schemaName)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .outOfOrder(false)
                .validateOnMigrate(false);   // skip checksum re-validation on phase-2
    }

    /**
     * Inserts the default PLATFORM domain and the first admin user into the
     * newly-created tenant schema.
     */
    private void seedTenantData(String schemaName, String slug, String tenantName,
                                  String adminEmail, String adminPassword) throws SQLException {
        String passwordHash = BCrypt.hashpw(adminPassword, BCrypt.gensalt(12));

        try (Connection conn = rawDataSource.getConnection();
             Statement  stmt = conn.createStatement()) {

            stmt.execute("SET search_path TO \"" + schemaName + "\", public");

            // Default domain
            stmt.execute(String.format("""
                    INSERT INTO nexus_domain (domain_key, name, description, owner_team, status)
                    VALUES ('PLATFORM', '%s', 'Default platform domain for %s', 'Platform Team', 'ACTIVE')
                    ON CONFLICT (domain_key) DO NOTHING
                    """, escSql(tenantName + " Platform"), escSql(tenantName)));

            // Admin user
            stmt.execute(String.format("""
                    INSERT INTO nexus_user_account
                        (email, display_name, password_hash, role, status, created_at, updated_at)
                    VALUES ('%s', 'Tenant Administrator', '%s', 'ADMIN', 'ACTIVE', NOW(), NOW())
                    ON CONFLICT (email) DO NOTHING
                    """, escSql(adminEmail), escSql(passwordHash)));

            stmt.execute("RESET search_path");
        }
    }

    private Tenant requireTenant(String slug) {
        return tenantRepository.findBySlug(slug)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "Tenant not found: " + slug));
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private void validateSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "Tenant slug is required");
        }
        if (!VALID_SLUG.matcher(slug).matches()) {
            throw new NexusException(HttpStatus.BAD_REQUEST,
                    "Tenant slug must be 2–63 lowercase alphanumeric characters or hyphens, " +
                    "and must start with a letter or digit");
        }
        if ("public".equals(slug) || "default".equals(slug)) {
            throw new NexusException(HttpStatus.BAD_REQUEST,
                    "'" + slug + "' is a reserved slug");
        }
    }

    private void validatePlan(String plan) {
        if (!java.util.Set.of("TRIAL", "STANDARD", "PROFESSIONAL", "ENTERPRISE").contains(plan)) {
            throw new NexusException(HttpStatus.BAD_REQUEST,
                    "Invalid plan. Must be one of: TRIAL, STANDARD, PROFESSIONAL, ENTERPRISE");
        }
    }

    private void validateEmail(String email) {
        if (email == null || !email.contains("@")) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "Invalid admin email address");
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8) {
            throw new NexusException(HttpStatus.BAD_REQUEST,
                    "Admin password must be at least 8 characters");
        }
    }

    /** Minimal SQL string escaping — single quotes only. Schema and email values are validated separately. */
    private String escSql(String value) {
        return value.replace("'", "''");
    }
}
