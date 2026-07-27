package com.sei.nexus.enterprise;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Tenant-owned persistence for {@link BusinessValue} and {@link BusinessValueMapping}. Plain
 * JdbcTemplate, matching {@link EnterpriseMapRepository}; the tenant search_path keeps every row
 * isolated. Mappings reference physical values by natural key {@code (value_domain_key,
 * physical_value)} — no physical value is stored here.
 */
@Repository
public class BusinessValueRepository {

    private final JdbcTemplate jdbc;

    public BusinessValueRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Business Value ──────────────────────────────────────────────────────────

    public void saveBusinessValue(BusinessValue v) {
        jdbc.update("""
                INSERT INTO nexus_business_value
                    (business_value_key, business_attribute_key, name, description, source,
                     confidence, approval_status, created_by, approved_by, approved_at,
                     created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?, NOW(), NOW())
                ON CONFLICT (business_value_key) DO UPDATE SET
                    business_attribute_key = EXCLUDED.business_attribute_key,
                    name            = EXCLUDED.name,
                    description     = EXCLUDED.description,
                    source          = EXCLUDED.source,
                    confidence      = EXCLUDED.confidence,
                    approval_status = EXCLUDED.approval_status,
                    approved_by     = EXCLUDED.approved_by,
                    approved_at     = EXCLUDED.approved_at,
                    updated_at      = NOW()
                """,
                v.businessValueKey(), v.businessAttributeKey(), v.name(), v.description(), v.source(),
                v.confidence(), v.approvalStatus(), v.createdBy(), v.approvedBy(), ts(v.approvedAt()));
    }

    public Optional<BusinessValue> findBusinessValue(String key) {
        List<BusinessValue> rows = jdbc.query(
                "SELECT * FROM nexus_business_value WHERE business_value_key = ?", BV_MAPPER, key);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** All concepts scoped to a business attribute — the set duplicate-detection checks against. */
    public List<BusinessValue> findBusinessValuesByAttribute(String businessAttributeKey) {
        return jdbc.query(
                "SELECT * FROM nexus_business_value WHERE business_attribute_key = ? ORDER BY name",
                BV_MAPPER, businessAttributeKey);
    }

    public void approveBusinessValue(String key, String approvedBy) {
        jdbc.update("""
                UPDATE nexus_business_value
                   SET approval_status='APPROVED', approved_by=?, approved_at=NOW(), updated_at=NOW()
                 WHERE business_value_key=?
                """, approvedBy, key);
    }

    // ── Business Value Mapping ──────────────────────────────────────────────────

    /**
     * Upserts a mapping by its natural key {@code (value_domain_key, physical_value)} so re-mapping a
     * physical value updates its concept rather than violating the uniqueness constraint. The
     * constraint still guarantees exactly one Business Value per physical value.
     */
    public void saveMapping(BusinessValueMapping m) {
        jdbc.update("""
                INSERT INTO nexus_business_value_mapping
                    (mapping_key, value_domain_key, physical_value, business_value_key, source,
                     confidence, approval_status, cross_application, created_by, approved_by,
                     approved_at, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?, NOW(), NOW())
                ON CONFLICT (value_domain_key, physical_value) DO UPDATE SET
                    business_value_key = EXCLUDED.business_value_key,
                    source            = EXCLUDED.source,
                    confidence        = EXCLUDED.confidence,
                    approval_status   = EXCLUDED.approval_status,
                    cross_application = EXCLUDED.cross_application,
                    approved_by       = EXCLUDED.approved_by,
                    approved_at       = EXCLUDED.approved_at,
                    updated_at        = NOW()
                """,
                m.mappingKey(), m.valueDomainKey(), m.physicalValue(), m.businessValueKey(), m.source(),
                m.confidence(), m.approvalStatus(), m.crossApplication(), m.createdBy(), m.approvedBy(),
                ts(m.approvedAt()));
    }

    /** The existing mapping for one physical value, if any — used for conflict detection. */
    public Optional<BusinessValueMapping> findMapping(String valueDomainKey, String physicalValue) {
        List<BusinessValueMapping> rows = jdbc.query(
                "SELECT * FROM nexus_business_value_mapping WHERE value_domain_key=? AND physical_value=?",
                MAP_MAPPER, valueDomainKey, physicalValue);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Approved mappings for a domain — the set the assembler projects into the prompt. */
    public List<BusinessValueMapping> findApprovedMappingsByDomain(String valueDomainKey) {
        return jdbc.query("""
                SELECT * FROM nexus_business_value_mapping
                 WHERE value_domain_key=? AND approval_status='APPROVED'
                """, MAP_MAPPER, valueDomainKey);
    }

    public List<BusinessValueMapping> findMappingsByBusinessValue(String businessValueKey) {
        return jdbc.query(
                "SELECT * FROM nexus_business_value_mapping WHERE business_value_key=?",
                MAP_MAPPER, businessValueKey);
    }

    public void approveMapping(String mappingKey, String approvedBy) {
        jdbc.update("""
                UPDATE nexus_business_value_mapping
                   SET approval_status='APPROVED', approved_by=?, approved_at=NOW(), updated_at=NOW()
                 WHERE mapping_key=?
                """, approvedBy, mappingKey);
    }

    // ── mappers / helpers ───────────────────────────────────────────────────────

    private static Timestamp ts(Instant i) { return i == null ? null : Timestamp.from(i); }
    private static Instant inst(Timestamp t) { return t == null ? null : t.toInstant(); }
    private static Double dbl(ResultSet rs, String col) throws SQLException {
        double d = rs.getDouble(col); return rs.wasNull() ? null : d;
    }

    private static final RowMapper<BusinessValue> BV_MAPPER = (ResultSet rs, int n) -> new BusinessValue(
            rs.getString("business_value_key"), rs.getString("business_attribute_key"),
            rs.getString("name"), rs.getString("description"), rs.getString("source"),
            dbl(rs, "confidence"), rs.getString("approval_status"), rs.getString("created_by"),
            rs.getString("approved_by"), inst(rs.getTimestamp("approved_at")),
            inst(rs.getTimestamp("created_at")), inst(rs.getTimestamp("updated_at")));

    private static final RowMapper<BusinessValueMapping> MAP_MAPPER = (ResultSet rs, int n) -> new BusinessValueMapping(
            rs.getString("mapping_key"), rs.getString("value_domain_key"), rs.getString("physical_value"),
            rs.getString("business_value_key"), rs.getString("source"), dbl(rs, "confidence"),
            rs.getString("approval_status"), rs.getBoolean("cross_application"), rs.getString("created_by"),
            rs.getString("approved_by"), inst(rs.getTimestamp("approved_at")),
            inst(rs.getTimestamp("created_at")), inst(rs.getTimestamp("updated_at")));
}
