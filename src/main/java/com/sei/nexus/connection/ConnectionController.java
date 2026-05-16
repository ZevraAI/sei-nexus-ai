package com.sei.nexus.connection;

import com.sei.nexus.common.Keys;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.sql.DynamicSqlService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST endpoints for managing governed data-source connections.
 * Base path: /api/v1/connections (context-path set in application.yml)
 *
 * <p>The secret field is never returned to API callers.</p>
 */
@RestController
@RequestMapping("/connections")
public class ConnectionController {

    private final ConnectionRepository connectionRepository;
    private final ConnectionTestService connectionTestService;
    private final DynamicSqlService dynamicSqlService;

    public ConnectionController(ConnectionRepository connectionRepository,
                                 ConnectionTestService connectionTestService,
                                 DynamicSqlService dynamicSqlService) {
        this.connectionRepository = connectionRepository;
        this.connectionTestService = connectionTestService;
        this.dynamicSqlService = dynamicSqlService;
    }

    /**
     * GET /connections
     * Lists all active connections.  The encryptedSecret field is redacted.
     */
    @GetMapping
    public ResponseEntity<List<NexusConnection>> listConnections() {
        List<NexusConnection> connections = connectionRepository.findAll()
                .stream()
                .map(this::redactSecret)
                .collect(Collectors.toList());
        return ResponseEntity.ok(connections);
    }

    /**
     * POST /connections
     * Creates or updates a connection (upsert by connectionKey).
     * If connectionKey is absent in the request body, a new one is generated.
     *
     * <p>Request body fields:
     * connectionKey (optional), name, connectionType, usageDescription,
     * jdbcUrl, instanceUrl, username, secret, allowedSchemas, allowedTables, readOnly</p>
     */
    @PostMapping
    public ResponseEntity<NexusConnection> upsertConnection(
            @RequestBody Map<String, Object> body) {

        String connectionKey = (String) body.getOrDefault("connectionKey",
                Keys.uniqueKey("conn"));
        String name = requireString(body, "name");
        String connectionType = requireString(body, "connectionType");

        if (!List.of("POSTGRES", "ORACLE", "REST_API").contains(connectionType)) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "connectionType must be POSTGRES, ORACLE, or REST_API");
        }

        boolean readOnly = Boolean.parseBoolean(
                String.valueOf(body.getOrDefault("readOnly", "true")));

        String newSecret = (String) body.get("secret");
        String secretToStore = (newSecret != null && !newSecret.isBlank())
                ? newSecret
                : connectionRepository.findByKey(connectionKey)
                        .map(NexusConnection::encryptedSecret)
                        .orElse(null);

        NexusConnection conn = new NexusConnection(
                connectionKey,
                name,
                connectionType,
                (String) body.get("usageDescription"),
                (String) body.get("jdbcUrl"),
                (String) body.get("instanceUrl"),
                (String) body.get("username"),
                secretToStore,
                (String) body.get("allowedSchemas"),
                (String) body.get("allowedTables"),
                readOnly,
                null, null, null,                       // test fields set by test endpoint
                "ACTIVE",
                Instant.now(),
                Instant.now());

        connectionRepository.save(conn);

        // Return without the secret
        NexusConnection saved = connectionRepository.findByKey(connectionKey)
                .map(this::redactSecret)
                .orElseThrow(() -> new NexusException(HttpStatus.INTERNAL_SERVER_ERROR, "Save failed unexpectedly"));

        return ResponseEntity.status(HttpStatus.OK).body(saved);
    }

    /**
     * POST /connections/{connectionKey}/test
     * Tests the connection and persists the result.
     */
    @PostMapping("/{connectionKey}/test")
    public ResponseEntity<ConnectionTestService.TestResult> testConnection(
            @PathVariable String connectionKey) {

        NexusConnection conn = connectionRepository.findByKey(connectionKey)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND, "Connection not found: " + connectionKey));

        ConnectionTestService.TestResult result = connectionTestService.test(conn);
        return ResponseEntity.ok(result);
    }

    /**
     * GET /connections/{connectionKey}/catalog?schema=public&query=
     * Lists tables available in the connected database for a given schema.
     * Used by the Semantic Layer discovery wizard.
     */
    @GetMapping("/{connectionKey}/catalog")
    public ResponseEntity<List<Map<String, Object>>> catalog(
            @PathVariable String connectionKey,
            @RequestParam(defaultValue = "public") String schema,
            @RequestParam(required = false, defaultValue = "") String query) {
        connectionRepository.findByKey(connectionKey)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "Connection not found: " + connectionKey));
        List<Map<String, Object>> tables = dynamicSqlService.listTables(connectionKey, schema, query);
        return ResponseEntity.ok(tables);
    }

    /**
     * DELETE /connections/{connectionKey}
     * Archives the connection (soft delete).
     */
    @DeleteMapping("/{connectionKey}")
    public ResponseEntity<Void> archiveConnection(@PathVariable String connectionKey) {
        connectionRepository.findByKey(connectionKey)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND, "Connection not found: " + connectionKey));
        connectionRepository.archive(connectionKey);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private String requireString(Map<String, Object> body, String field) {
        Object val = body.get(field);
        if (val == null || val.toString().isBlank()) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "Field '" + field + "' is required");
        }
        return val.toString();
    }

    /**
     * Returns a copy of the connection with the secret replaced by a redaction marker.
     */
    private NexusConnection redactSecret(NexusConnection c) {
        return new NexusConnection(
                c.connectionKey(), c.name(), c.connectionType(), c.usageDescription(),
                c.jdbcUrl(), c.instanceUrl(), c.username(),
                "***REDACTED***",
                c.allowedSchemas(), c.allowedTables(), c.readOnly(),
                c.lastTestStatus(), c.lastTestMessage(), c.lastTestedAt(),
                c.status(), c.createdAt(), c.updatedAt());
    }
}
