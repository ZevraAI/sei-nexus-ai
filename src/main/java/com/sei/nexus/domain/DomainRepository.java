package com.sei.nexus.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class DomainRepository {

    private static final String FIND_ALL_ACTIVE =
            "SELECT domain_key, name, description, owner_team, owner_email, status, created_at, updated_at " +
            "FROM nexus_domain WHERE status = 'ACTIVE' ORDER BY name";

    private static final String FIND_BY_KEY =
            "SELECT domain_key, name, description, owner_team, owner_email, status, created_at, updated_at " +
            "FROM nexus_domain WHERE domain_key = ?";

    private static final String UPSERT_DOMAIN =
            "INSERT INTO nexus_domain (domain_key, name, description, owner_team, owner_email, status, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW()) " +
            "ON CONFLICT (domain_key) DO UPDATE SET " +
            "name = EXCLUDED.name, description = EXCLUDED.description, " +
            "owner_team = EXCLUDED.owner_team, owner_email = EXCLUDED.owner_email, " +
            "status = EXCLUDED.status, updated_at = NOW()";

    private static final String ARCHIVE_DOMAIN =
            "UPDATE nexus_domain SET status = 'ARCHIVED', updated_at = NOW() WHERE domain_key = ?";

    private final JdbcTemplate jdbc;

    public DomainRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Domain> findAll() {
        return jdbc.query(FIND_ALL_ACTIVE, domainMapper());
    }

    public Optional<Domain> findByKey(String domainKey) {
        List<Domain> results = jdbc.query(FIND_BY_KEY, domainMapper(), domainKey);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public void save(Domain domain) {
        jdbc.update(UPSERT_DOMAIN,
                domain.domainKey(),
                domain.name(),
                domain.description(),
                domain.ownerTeam(),
                domain.ownerEmail(),
                domain.status() != null ? domain.status() : "ACTIVE");
    }

    public int archive(String domainKey) {
        return jdbc.update(ARCHIVE_DOMAIN, domainKey);
    }

    private RowMapper<Domain> domainMapper() {
        return (rs, rowNum) -> new Domain(
                rs.getString("domain_key"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("owner_team"),
                rs.getString("owner_email"),
                rs.getString("status"),
                toOffsetDateTime(rs, "created_at"),
                toOffsetDateTime(rs, "updated_at")
        );
    }

    private OffsetDateTime toOffsetDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts != null ? ts.toInstant().atOffset(ZoneOffset.UTC) : null;
    }
}
