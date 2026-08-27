package com.sei.nexus.connection;

import com.sei.nexus.common.Keys;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.pack.IndustryPackService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Industry Pack Required At Connection Creation: establishes the architectural invariant that
 * "Industry Pack is part of Connection identity/context" — every NEWLY created connection must
 * carry a validated {@code packKey} and end up with an ACTIVE {@code nexus_tenant_pack} row, in
 * the same request that creates it. Existing connections (edited via the same upsert endpoint)
 * are completely untouched by this class — see {@link ConnectionController#upsertConnection},
 * which only calls here for a genuinely NEW connection.
 *
 * <p>Deliberately thin: reuses the EXISTING Industry Pack retrieval/application mechanism
 * ({@link IndustryPackService#getPack}/{@link IndustryPackService#applyPack}) verbatim — no new
 * Pack model, no duplicate Pack API, no second concept-classification path. This class's only
 * job is sequencing: validate the Pack first (before any write), persist the connection, then
 * apply the Pack — and, via {@link Transactional}, ensure a Pack-application failure never
 * leaves a partially-created connection behind (the "smallest safe change" for atomicity this
 * codebase already uses elsewhere, e.g. {@code EnterpriseMapService}'s existing
 * {@code @Transactional} methods — no new transaction-management mechanism introduced).
 */
@Service
public class ConnectionService {

    // A new tenant has no business domain of its own yet at connection-creation time (Discover
    // hasn't run) — PLATFORM is the one domain every tenant schema is guaranteed to have,
    // seeded by V007__knowledge_graph.sql for every tenant schema. Only used for the Pack's
    // vocabulary rows' domain_key; never used to infer or choose the Pack itself.
    private static final String DEFAULT_DOMAIN_KEY = "PLATFORM";

    private final ConnectionRepository connectionRepository;
    private final IndustryPackService  packService;

    public ConnectionService(ConnectionRepository connectionRepository, IndustryPackService packService) {
        this.connectionRepository = connectionRepository;
        this.packService          = packService;
    }

    /**
     * Creates a brand-new connection with its required Industry Pack, atomically.
     *
     * <ul>
     *   <li>{@code packKey} missing/blank → 400, nothing is written.</li>
     *   <li>{@code packKey} does not resolve to a known, loadable Pack → 404 (via {@link
     *       IndustryPackService#getPack}, the exact existing lookup — never a second Pack
     *       lookup mechanism), nothing is written.</li>
     *   <li>Pack validated → the connection is saved, then {@link
     *       IndustryPackService#applyPack} is called for it. Both happen inside one
     *       {@code @Transactional} boundary: if {@code applyPack} fails for any reason (e.g. a
     *       genuine race where the pack became unavailable between validation and apply), the
     *       connection insert rolls back too — never a connection left behind with no Pack.</li>
     * </ul>
     *
     * <p>Never infers a default Pack, never silently picks Retail — the caller (the UI's
     * required-selector, enforced again here server-side) must always supply {@code packKey}
     * explicitly.
     */
    @Transactional
    public NexusConnection createConnection(Map<String, Object> body, String appliedBy) {
        String packKey = str(body, "packKey");
        if (packKey == null || packKey.isBlank()) {
            throw new NexusException(HttpStatus.BAD_REQUEST,
                    "packKey is required — every new connection must have an Industry Pack selected.");
        }
        // Reuses the EXISTING Pack lookup exactly as Apply Pack itself does — throws 404 if the
        // requested pack doesn't exist/isn't loadable. No new Pack model, no duplicate API.
        packService.getPack(packKey);

        String connectionKey = str(body, "connectionKey");
        if (connectionKey == null || connectionKey.isBlank()) {
            connectionKey = Keys.uniqueKey("conn");
        }
        String name = requireString(body, "name");
        String connectionType = requireString(body, "connectionType");
        if (!List.of("POSTGRES", "ORACLE", "REST_API").contains(connectionType)) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "connectionType must be POSTGRES, ORACLE, or REST_API");
        }
        boolean readOnly = Boolean.parseBoolean(String.valueOf(body.getOrDefault("readOnly", "true")));

        NexusConnection conn = new NexusConnection(
                connectionKey,
                name,
                connectionType,
                (String) body.get("usageDescription"),
                (String) body.get("jdbcUrl"),
                (String) body.get("instanceUrl"),
                (String) body.get("username"),
                (String) body.get("secret"),
                (String) body.get("allowedSchemas"),
                (String) body.get("allowedTables"),
                readOnly,
                null, null, null,                       // test fields set by the test endpoint
                "ACTIVE",
                Instant.now(),
                Instant.now());
        connectionRepository.save(conn);

        String domainKey = str(body, "domainKey");
        if (domainKey == null || domainKey.isBlank()) domainKey = DEFAULT_DOMAIN_KEY;

        // Establishes connection + ACTIVE Pack together — see class javadoc. This is exactly the
        // existing Apply Pack path (associate pack_key, LLM-classify existing entities — none
        // yet for a brand-new connection, so a no-op there — create vocabulary, save the
        // nexus_tenant_pack row as ACTIVE); nothing about concept classification is duplicated
        // or reimplemented here.
        packService.applyPack(packKey, domainKey, connectionKey, appliedBy);

        return connectionRepository.findByKey(connectionKey)
                .orElseThrow(() -> new NexusException(HttpStatus.INTERNAL_SERVER_ERROR, "Save failed unexpectedly"));
    }

    private String requireString(Map<String, Object> body, String field) {
        Object val = body.get(field);
        if (val == null || val.toString().isBlank()) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "Field '" + field + "' is required");
        }
        return val.toString();
    }

    private String str(Map<String, Object> body, String field) {
        Object v = body.get(field);
        return v == null ? null : v.toString();
    }
}
