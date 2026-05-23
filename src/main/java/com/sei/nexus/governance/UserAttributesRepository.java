package com.sei.nexus.governance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes the {@code attributes} JSONB column on {@code nexus_user_account}.
 *
 * <p>Attributes are arbitrary key-value pairs set by an admin for a user — e.g.
 * {@code {"region":"NORTH","department":"Finance","cost_centre":"CC42"}}.
 * They are used by {@link RowLevelSecurityService} to resolve RLS filter templates.
 */
@Repository
public class UserAttributesRepository {

    private static final Logger log = LoggerFactory.getLogger(UserAttributesRepository.class);
    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public UserAttributesRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc         = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Returns the user's attribute map, or an empty map if none are set or on error. */
    public Map<String, String> getAttributes(String userEmail) {
        List<String> rows = jdbc.query(
                "SELECT attributes::TEXT FROM nexus_user_account WHERE email = ?",
                (rs, i) -> rs.getString(1), userEmail);
        if (rows.isEmpty() || rows.get(0) == null) return Collections.emptyMap();
        try {
            return objectMapper.readValue(rows.get(0), MAP_TYPE);
        } catch (Exception e) {
            log.warn("Failed to parse attributes for user {}: {}", userEmail, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** Merges the provided attributes into the user's existing attribute map. */
    public void setAttributes(String userEmail, Map<String, String> attributes) {
        try {
            String json = objectMapper.writeValueAsString(attributes);
            jdbc.update(
                    "UPDATE nexus_user_account SET attributes = ?::jsonb WHERE email = ?",
                    json, userEmail);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save user attributes for " + userEmail, e);
        }
    }

    /** Returns the role of the user — used by masking and RLS exemption checks. */
    public String getRole(String userEmail) {
        List<String> rows = jdbc.query(
                "SELECT role FROM nexus_user_account WHERE email = ?",
                (rs, i) -> rs.getString("role"), userEmail);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
