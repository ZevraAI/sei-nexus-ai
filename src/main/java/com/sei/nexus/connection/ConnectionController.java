package com.sei.nexus.connection;

import com.sei.nexus.auth.UserAccount;
import com.sei.nexus.common.Keys;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.pack.IndustryPackRepository;
import com.sei.nexus.pack.IndustryPackService;
import com.sei.nexus.sql.DynamicSqlService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
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
    private final ConnectionService connectionService;
    private final IndustryPackRepository packRepository;
    private final IndustryPackService packService;

    public ConnectionController(ConnectionRepository connectionRepository,
                                 ConnectionTestService connectionTestService,
                                 DynamicSqlService dynamicSqlService,
                                 ConnectionService connectionService,
                                 IndustryPackRepository packRepository,
                                 IndustryPackService packService) {
        this.connectionRepository = connectionRepository;
        this.connectionTestService = connectionTestService;
        this.dynamicSqlService = dynamicSqlService;
        this.connectionService = connectionService;
        this.packRepository = packRepository;
        this.packService = packService;
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
     * jdbcUrl, instanceUrl, username, secret, allowedSchemas, allowedTables, readOnly,
     * <b>packKey</b> (required for a NEW connection only — see below), domainKey (optional,
     * defaults to the tenant's PLATFORM domain).
     *
     * <p><b>Industry Pack Required At Connection Creation:</b> when this request creates a
     * genuinely NEW connection (no {@code connectionKey} supplied, or one that does not already
     * exist), {@code packKey} is required and validated — see {@link
     * ConnectionService#createConnection}. Editing an EXISTING connection through this same
     * upsert endpoint is completely unaffected: no Pack is required, none is touched, and the
     * behavior is byte-identical to before this feature existed.
     */
    @PostMapping
    public ResponseEntity<NexusConnection> upsertConnection(
            @RequestBody Map<String, Object> body) {

        Object requestedKey = body.get("connectionKey");
        boolean isNewConnection = requestedKey == null || requestedKey.toString().isBlank()
                || connectionRepository.findByKey(requestedKey.toString()).isEmpty();

        NexusConnection saved = isNewConnection
                ? connectionService.createConnection(body, currentUserEmail())
                : upsertExisting(requestedKey.toString(), body);

        return ResponseEntity.status(HttpStatus.OK).body(redactSecret(saved));
    }

    /** The pre-existing upsert path, unchanged — editing a connection that already exists never
     *  requires or touches an Industry Pack. */
    private NexusConnection upsertExisting(String connectionKey, Map<String, Object> body) {
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

        return connectionRepository.findByKey(connectionKey)
                .orElseThrow(() -> new NexusException(HttpStatus.INTERNAL_SERVER_ERROR, "Save failed unexpectedly"));
    }

    private String currentUserEmail() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof UserAccount u) return u.email();
        throw new NexusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
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
     * Deletes the connection — blocked if any data objects or agents reference it.
     *
     * <p>Industry Pack Required At Connection Creation: since every connection now always
     * carries an ACTIVE Industry Pack from the moment it's created, deleting a connection must
     * release that Pack too — otherwise the pack_key would remain permanently "already applied"
     * tenant-wide (see {@code IndustryPackService#applyPack}'s own guard) with no connection left
     * to remove it from, silently blocking that Pack from ever being applied again. This reuses
     * the EXISTING {@link IndustryPackService#removePack} exactly as the Packs UI's own Remove
     * action does — no Pack-removal logic is changed, only invoked from this new call site. Most
     * relevant for the onboarding wizard's existing create → test → delete-on-failure flow, which
     * would otherwise always leak an orphaned {@code nexus_tenant_pack} row now that Pack
     * selection is mandatory at creation.
     */
    @DeleteMapping("/{connectionKey}")
    public ResponseEntity<Void> archiveConnection(@PathVariable String connectionKey) {
        connectionRepository.findByKey(connectionKey)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND, "Connection not found: " + connectionKey));

        List<String> dependents = connectionRepository.findDependents(connectionKey);
        if (!dependents.isEmpty()) {
            throw new NexusException(HttpStatus.CONFLICT,
                    "Cannot delete connection '" + connectionKey + "' — it is still used by: " +
                    String.join(", ", dependents) + ". Remove those dependencies first or re-assign them to another connection.");
        }

        packRepository.findActivePackForConnection(connectionKey).ifPresent(tenantPack ->
                packService.removePack(tenantPack.packKey()));

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
