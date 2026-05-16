package com.sei.nexus.auth;

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
public class AuthRepository {

    private static final String FIND_BY_EMAIL =
            "SELECT email, display_name, password_hash, role, status, created_at, updated_at " +
            "FROM nexus_user_account WHERE email = ?";

    private static final String INSERT_USER =
            "INSERT INTO nexus_user_account (email, display_name, password_hash, role, status, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, NOW(), NOW())";

    private static final String INSERT_SESSION =
            "INSERT INTO nexus_user_session (session_key, user_email, session_token_hash, expires_at, created_at) " +
            "VALUES (?, ?, ?, ?, NOW())";

    private static final String FIND_SESSION_BY_TOKEN_HASH =
            "SELECT session_key, user_email, session_token_hash, expires_at, created_at " +
            "FROM nexus_user_session WHERE session_token_hash = ? AND expires_at > NOW()";

    private static final String DELETE_SESSION_BY_TOKEN =
            "DELETE FROM nexus_user_session WHERE session_token_hash = ?";

    private static final String DELETE_EXPIRED_SESSIONS =
            "DELETE FROM nexus_user_session WHERE expires_at <= NOW()";

    private static final String COUNT_USERS =
            "SELECT COUNT(*) FROM nexus_user_account";

    private final JdbcTemplate jdbc;

    public AuthRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> findByEmail(String email) {
        List<UserAccount> results = jdbc.query(FIND_BY_EMAIL, userAccountMapper(), email);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public void create(UserAccount account) {
        jdbc.update(INSERT_USER,
                account.email(),
                account.displayName(),
                account.passwordHash(),
                account.role(),
                account.status());
    }

    public void createSession(UserSession session) {
        Timestamp expiresAt = Timestamp.from(session.expiresAt().toInstant());
        jdbc.update(INSERT_SESSION,
                session.sessionKey(),
                session.userEmail(),
                session.sessionTokenHash(),
                expiresAt);
    }

    public Optional<UserSession> findSessionByTokenHash(String hash) {
        List<UserSession> results = jdbc.query(FIND_SESSION_BY_TOKEN_HASH, userSessionMapper(), hash);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public void deleteSession(String tokenHash) {
        jdbc.update(DELETE_SESSION_BY_TOKEN, tokenHash);
    }

    public int deleteExpiredSessions() {
        return jdbc.update(DELETE_EXPIRED_SESSIONS);
    }

    public int countUsers() {
        Integer count = jdbc.queryForObject(COUNT_USERS, Integer.class);
        return count != null ? count : 0;
    }

    private RowMapper<UserAccount> userAccountMapper() {
        return (rs, rowNum) -> new UserAccount(
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("password_hash"),
                rs.getString("role"),
                rs.getString("status"),
                toOffsetDateTime(rs, "created_at"),
                toOffsetDateTime(rs, "updated_at")
        );
    }

    private RowMapper<UserSession> userSessionMapper() {
        return (rs, rowNum) -> new UserSession(
                rs.getString("session_key"),
                rs.getString("user_email"),
                rs.getString("session_token_hash"),
                toOffsetDateTime(rs, "expires_at"),
                toOffsetDateTime(rs, "created_at")
        );
    }

    private OffsetDateTime toOffsetDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts != null ? ts.toInstant().atOffset(ZoneOffset.UTC) : null;
    }
}
