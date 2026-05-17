package com.sei.nexus.connection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;

@Service
public class ConnectionTestService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionTestService.class);

    private static final int JDBC_TIMEOUT_SECONDS = 10;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final ConnectionRepository connectionRepository;

    public ConnectionTestService(ConnectionRepository connectionRepository) {
        this.connectionRepository = connectionRepository;
    }

    /**
     * Immutable result of a connection test.
     */
    public record TestResult(boolean success, String message) {}

    /**
     * Tests the supplied connection and persists the outcome to the database.
     *
     * <p>Supports three connection types:
     * <ul>
     *   <li>POSTGRES — connects via JDBC and runs {@code SELECT 1}</li>
     *   <li>ORACLE  — connects via JDBC and runs {@code SELECT 1 FROM DUAL}</li>
     *   <li>REST_API — issues an HTTP GET to the instance URL and checks for a non-5xx response</li>
     * </ul>
     * </p>
     */
    public TestResult test(NexusConnection conn) {
        TestResult result;
        try {
            result = switch (conn.connectionType()) {
                case "POSTGRES" -> testJdbc(conn, "SELECT 1");
                case "ORACLE"   -> testJdbc(conn, "SELECT 1 FROM DUAL");
                case "REST_API" -> testRestApi(conn);
                default -> new TestResult(false,
                        "Unknown connection type: " + conn.connectionType());
            };
        } catch (Exception ex) {
            log.warn("Unexpected error testing connection {}: {}", conn.connectionKey(), ex.getMessage());
            result = new TestResult(false, "Unexpected error: " + ex.getMessage());
        }

        String status = result.success() ? "SUCCESS" : "FAILED";
        connectionRepository.updateTestResult(conn.connectionKey(), status, result.message());

        return result;
    }

    // ---------------------------------------------------------------------------
    // JDBC test
    // ---------------------------------------------------------------------------

    private TestResult testJdbc(NexusConnection conn, String probeSql) {
        // PRODUCTION NOTE: encryptedSecret should be decrypted here via Vault.
        // Currently stored as plaintext.
        String secret = conn.encryptedSecret();

        try (Connection c = DriverManager.getConnection(conn.jdbcUrl(), conn.username(), secret)) {
            c.setNetworkTimeout(java.util.concurrent.Executors.newSingleThreadExecutor(),
                    JDBC_TIMEOUT_SECONDS * 1000);
            try (Statement stmt = c.createStatement()) {
                stmt.setQueryTimeout(JDBC_TIMEOUT_SECONDS);
                try (ResultSet rs = stmt.executeQuery(probeSql)) {
                    if (rs.next()) {
                        return new TestResult(true, "Connection successful");
                    }
                    return new TestResult(false, "Probe query returned no rows");
                }
            }
        } catch (Exception ex) {
            log.debug("JDBC test failed for {}: {}", conn.connectionKey(), ex.getMessage());
            return new TestResult(false, sanitise(ex.getMessage()));
        }
    }

    // ---------------------------------------------------------------------------
    // REST API test
    // ---------------------------------------------------------------------------

    private TestResult testRestApi(NexusConnection conn) {
        String baseUrl = conn.instanceUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return new TestResult(false, "instanceUrl is not configured");
        }

        // Try a standard ServiceNow-style health endpoint first; fall back to the base URL.
        String probeUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;

        // Append the well-known ServiceNow table endpoint if nothing more specific is set
        String targetUrl = probeUrl + "/api/now/table/sys_user?sysparm_limit=1";

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();

        try {
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

            int code = response.statusCode();
            if (code < 500) {
                return new TestResult(true, "HTTP " + code + " — reachable");
            }
            return new TestResult(false, "Server returned HTTP " + code);

        } catch (Exception ex) {
            // Try the bare base URL as a fallback
            try {
                HttpRequest fallback = HttpRequest.newBuilder()
                        .uri(URI.create(probeUrl))
                        .timeout(HTTP_TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(fallback,
                        HttpResponse.BodyHandlers.ofString());
                int code = response.statusCode();
                if (code < 500) {
                    return new TestResult(true, "HTTP " + code + " — reachable (base URL)");
                }
                return new TestResult(false, "Server returned HTTP " + code);
            } catch (Exception fallbackEx) {
                log.debug("REST API test failed for {}: {}", conn.connectionKey(), fallbackEx.getMessage());
                return new TestResult(false, sanitise(fallbackEx.getMessage()));
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /** Return a user-friendly, actionable error message from a raw JDBC/HTTP exception. */
    private String sanitise(String message) {
        if (message == null) return "Unknown error";
        String m = message.toLowerCase();
        if (m.contains("connection attempt failed") || m.contains("timed out") || m.contains("timeout")) {
            return "Host unreachable or connection timed out — verify the hostname and port in the JDBC URL are correct and the database is accepting remote connections.";
        }
        if (m.contains("connection refused")) {
            return "Connection refused — the database is not listening on the specified host/port. Check that the host, port, and firewall rules are correct.";
        }
        if (m.contains("password") || m.contains("authentication") || m.contains("pg_hba")) {
            return "Authentication failed — check the username and password.";
        }
        if (m.contains("database") && (m.contains("does not exist") || m.contains("not found"))) {
            return "Database not found — verify the database name at the end of the JDBC URL.";
        }
        if (m.contains("unknown host") || m.contains("nodename nor servname")) {
            return "Unknown hostname — the hostname in the JDBC URL cannot be resolved. Check for typos.";
        }
        if (m.contains("ssl") || m.contains("certificate")) {
            return "SSL/TLS error — try adding ?sslmode=require or ?sslmode=disable to the JDBC URL.";
        }
        return message.length() > 256 ? message.substring(0, 256) + "…" : message;
    }
}
