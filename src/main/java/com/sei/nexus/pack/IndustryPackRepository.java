package com.sei.nexus.pack;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Two responsibilities:
 *
 * <ol>
 *   <li><b>Pack catalogue</b> — loads all {@code *.json} files from
 *       {@code classpath:/industry-packs/} at startup into an in-memory map.
 *       No database involved; pack definitions update with each deployment.</li>
 *   <li><b>Tenant packs</b> — reads / writes {@code nexus_tenant_pack} to track
 *       which packs a tenant has applied and their entity mappings.</li>
 * </ol>
 */
@Repository
public class IndustryPackRepository {

    private static final Logger log = LoggerFactory.getLogger(IndustryPackRepository.class);

    private final JdbcTemplate  jdbc;
    private final ObjectMapper  objectMapper;

    /** In-memory catalogue: pack_id → IndustryPack. */
    private final Map<String, IndustryPack> catalogue = new LinkedHashMap<>();

    public IndustryPackRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc         = jdbc;
        this.objectMapper = objectMapper;
    }

    // ── Pack catalogue (classpath) ────────────────────────────────────────────

    @PostConstruct
    public void loadPacksFromClasspath() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:/industry-packs/*.json");
            for (Resource r : resources) {
                try (InputStream is = r.getInputStream()) {
                    IndustryPack pack = objectMapper.readValue(is, IndustryPack.class);
                    catalogue.put(pack.packId(), pack);
                    log.info("Industry pack loaded: {} ({})", pack.displayName(), pack.packId());
                } catch (Exception e) {
                    log.warn("Failed to load industry pack from {}: {}", r.getFilename(), e.getMessage());
                }
            }
            log.info("Industry pack catalogue ready: {} packs", catalogue.size());
        } catch (Exception e) {
            log.error("Failed to scan industry-packs directory: {}", e.getMessage(), e);
        }
    }

    public List<IndustryPack> findAllPacks() {
        return List.copyOf(catalogue.values());
    }

    public Optional<IndustryPack> findPackById(String packId) {
        return Optional.ofNullable(catalogue.get(packId));
    }

    // ── Tenant pack (DB) ──────────────────────────────────────────────────────

    public void saveTenantPack(TenantPack tp) {
        String mappingJson = toJson(tp.entityMapping());
        jdbc.update("""
                INSERT INTO nexus_tenant_pack
                    (pack_key, pack_version, display_name, status,
                     mapping_json, coverage_score, applied_at, applied_by)
                VALUES (?,?,?,?,?::jsonb,?,NOW(),?)
                ON CONFLICT (pack_key) DO UPDATE SET
                    pack_version   = EXCLUDED.pack_version,
                    display_name   = EXCLUDED.display_name,
                    status         = EXCLUDED.status,
                    mapping_json   = EXCLUDED.mapping_json,
                    coverage_score = EXCLUDED.coverage_score,
                    applied_by     = EXCLUDED.applied_by
                """,
                tp.packKey(), tp.packVersion(), tp.displayName(), tp.status(),
                mappingJson, tp.coverageScore(), tp.appliedBy());
    }

    public List<TenantPack> findAppliedPacks() {
        return jdbc.query(
                "SELECT * FROM nexus_tenant_pack WHERE status = 'ACTIVE' ORDER BY applied_at DESC",
                tenantPackMapper());
    }

    public Optional<TenantPack> findAppliedPack(String packKey) {
        List<TenantPack> rows = jdbc.query(
                "SELECT * FROM nexus_tenant_pack WHERE pack_key = ?", tenantPackMapper(), packKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void disableTenantPack(String packKey) {
        jdbc.update("UPDATE nexus_tenant_pack SET status = 'DISABLED' WHERE pack_key = ?", packKey);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private RowMapper<TenantPack> tenantPackMapper() {
        return (rs, i) -> {
            Map<String, String> mapping = Collections.emptyMap();
            try {
                String json = rs.getString("mapping_json");
                if (json != null && !json.isBlank() && !json.equals("{}")) {
                    mapping = objectMapper.readValue(json, Map.class);
                }
            } catch (Exception ignored) {}
            double coverage = rs.getObject("coverage_score") != null ? rs.getDouble("coverage_score") : 0.0;
            Timestamp ts = rs.getTimestamp("applied_at");
            return new TenantPack(
                    rs.getString("pack_key"),
                    rs.getString("pack_version"),
                    rs.getString("display_name"),
                    rs.getString("status"),
                    mapping,
                    coverage,
                    ts != null ? ts.toInstant() : Instant.now(),
                    rs.getString("applied_by"));
        };
    }

    private String toJson(Object obj) {
        try {
            return obj != null ? objectMapper.writeValueAsString(obj) : "{}";
        } catch (Exception e) {
            return "{}";
        }
    }
}
