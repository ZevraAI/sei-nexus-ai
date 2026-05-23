package com.sei.nexus.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.common.Keys;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class DataContractRepository {

    private final JdbcTemplate  jdbc;
    private final ObjectMapper  objectMapper;

    public DataContractRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc         = jdbc;
        this.objectMapper = objectMapper;
    }

    public DataContract save(DataContract c) {
        String key = c.contractKey() != null ? c.contractKey() : Keys.uniqueKey("dcon");
        String configJson = toJson(c.ruleConfig());
        jdbc.update("""
                INSERT INTO nexus_data_contract
                    (contract_key, contract_name, object_key, rule_type,
                     rule_config, enforcement, is_active, created_by, created_at, updated_at)
                VALUES (?,?,?,?,?::jsonb,?,?,?,NOW(),NOW())
                ON CONFLICT (contract_key) DO UPDATE SET
                    contract_name = EXCLUDED.contract_name,
                    object_key    = EXCLUDED.object_key,
                    rule_type     = EXCLUDED.rule_type,
                    rule_config   = EXCLUDED.rule_config,
                    enforcement   = EXCLUDED.enforcement,
                    is_active     = EXCLUDED.is_active,
                    updated_at    = NOW()
                """,
                key, c.contractName(), c.objectKey(), c.ruleType(),
                configJson, c.enforcement(), c.isActive(), c.createdBy());
        return findByKey(key).orElseThrow();
    }

    public Optional<DataContract> findByKey(String contractKey) {
        List<DataContract> rows = jdbc.query(
                "SELECT * FROM nexus_data_contract WHERE contract_key = ?", mapper(), contractKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<DataContract> findAll() {
        return jdbc.query(
                "SELECT * FROM nexus_data_contract ORDER BY object_key, contract_name",
                mapper());
    }

    /** Returns active contracts for the given object keys — called before every query step. */
    public List<DataContract> findActiveByObjectKeys(List<String> objectKeys) {
        if (objectKeys == null || objectKeys.isEmpty()) return List.of();
        String placeholders = objectKeys.stream().map(k -> "?")
                .collect(java.util.stream.Collectors.joining(","));
        List<Object> params = new java.util.ArrayList<>(objectKeys);
        params.add(true);
        return jdbc.query(
                "SELECT * FROM nexus_data_contract WHERE object_key IN (" + placeholders + ") AND is_active = ?",
                mapper(), params.toArray());
    }

    public void delete(String contractKey) {
        jdbc.update("DELETE FROM nexus_data_contract WHERE contract_key = ?", contractKey);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private RowMapper<DataContract> mapper() {
        return (rs, i) -> new DataContract(
                rs.getString("contract_key"),
                rs.getString("contract_name"),
                rs.getString("object_key"),
                rs.getString("rule_type"),
                parseJson(rs.getString("rule_config")),
                rs.getString("enforcement"),
                rs.getBoolean("is_active"),
                rs.getString("created_by"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json != null ? json : "{}");
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private String toJson(JsonNode node) {
        try {
            return node != null ? objectMapper.writeValueAsString(node) : "{}";
        } catch (Exception e) {
            return "{}";
        }
    }

    private Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : Instant.now();
    }
}
