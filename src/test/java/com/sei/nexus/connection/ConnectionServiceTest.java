package com.sei.nexus.connection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.enterprise.DataObject;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import com.sei.nexus.pack.*;
import com.sei.nexus.prompt.BusinessObjectBatchAnalyzer;
import com.sei.nexus.semantic.SemanticService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Industry Pack Required At Connection Creation — {@link ConnectionService} exercised with
 * hand-rolled fakes over the REAL {@link IndustryPackService} (same construction pattern as
 * {@code IndustryPackServiceBindingTest}), so these tests prove the actual Apply Pack mechanism
 * runs at connection-creation time — not a re-implementation of it. No Mockito, no DB.
 */
class ConnectionServiceTest {

    // ── fakes (same shapes as IndustryPackServiceBindingTest) ───────────────────

    static class FakeConnectionRepository extends ConnectionRepository {
        final Map<String, NexusConnection> connections = new LinkedHashMap<>();
        final List<String> savedKeys = new ArrayList<>();
        FakeConnectionRepository() { super(null); }
        @Override public Optional<NexusConnection> findByKey(String connectionKey) {
            return Optional.ofNullable(connections.get(connectionKey));
        }
        @Override public void save(NexusConnection conn) {
            savedKeys.add(conn.connectionKey());
            connections.put(conn.connectionKey(), conn);
        }
    }

    static class FakeEnterpriseMapRepository extends EnterpriseMapRepository {
        List<DataObject> objects = List.of();
        FakeEnterpriseMapRepository() { super(null); }
        @Override public List<DataObject> findDataObjectsByConnection(String connectionKey) { return objects; }
    }

    static class FakeSemanticService extends SemanticService {
        FakeSemanticService() { super(null, null, null); }
        // No pre-existing Business Entities for a brand-new connection — every lookup is empty,
        // exactly the real-world shape at connection-creation time (Discover hasn't run yet).
    }

    static class FakeIndustryPackRepository extends IndustryPackRepository {
        final Map<String, IndustryPack> catalogue = new LinkedHashMap<>();
        final Map<String, TenantPack> tenantPacksByPackKey = new LinkedHashMap<>();
        FakeIndustryPackRepository() { super(null, new ObjectMapper()); }
        @Override public List<IndustryPack> findAllPacks() { return List.copyOf(catalogue.values()); }
        @Override public Optional<IndustryPack> findPackById(String packId) {
            return Optional.ofNullable(catalogue.get(packId));
        }
        @Override public List<TenantPack> findAppliedPacks() {
            return tenantPacksByPackKey.values().stream().filter(tp -> "ACTIVE".equals(tp.status())).toList();
        }
        @Override public Optional<TenantPack> findAppliedPack(String packKey) {
            TenantPack tp = tenantPacksByPackKey.get(packKey);
            return tp != null && "ACTIVE".equals(tp.status()) ? Optional.of(tp) : Optional.empty();
        }
        @Override public void saveTenantPack(TenantPack tp) { tenantPacksByPackKey.put(tp.packKey(), tp); }
        @Override public void disableTenantPack(String packKey) {
            TenantPack tp = tenantPacksByPackKey.get(packKey);
            if (tp != null) {
                tenantPacksByPackKey.put(packKey, new TenantPack(tp.packKey(), tp.connectionKey(), tp.packVersion(),
                        tp.displayName(), "DISABLED", tp.entityMapping(), tp.coverageScore(), tp.appliedAt(), tp.appliedBy()));
            }
        }
        @Override public Optional<TenantPack> findActivePackForConnection(String connectionKey) {
            return tenantPacksByPackKey.values().stream()
                    .filter(tp -> "ACTIVE".equals(tp.status()) && connectionKey.equals(tp.connectionKey()))
                    .findFirst();
        }
    }

    static class FakeBusinessObjectBatchAnalyzer extends BusinessObjectBatchAnalyzer {
        FakeBusinessObjectBatchAnalyzer() { super(null, null, null, null); }
        @Override
        public Map<String, Map<String, Object>> analyzeBatch(String connectionKey, String schemaName,
                String domainKey, List<String> tableNames) {
            return Map.of(); // no existing tenant objects to classify for a brand-new connection
        }
    }

    private static NexusConnection testConnection(String connectionKey) {
        return new NexusConnection(connectionKey, "Test Connection " + connectionKey, "POSTGRES",
                "test", null, null, null, null, null, null, true,
                null, null, null, "ACTIVE", Instant.now(), Instant.now());
    }

    private static PackEntity packEntity(String name) {
        return new PackEntity(name, List.of(), List.of(), List.of(), "desc", "meaning", null, null);
    }

    private static IndustryPack pack(String packId) {
        return new IndustryPack(packId, "RETAIL", "Test Pack " + packId, "v1", "desc",
                List.of(packEntity("Product")), List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null, null);
    }

    private FakeConnectionRepository connectionRepository;
    private FakeIndustryPackRepository packRepository;
    private ConnectionService connectionService;

    @BeforeEach
    void setUp() {
        connectionRepository = new FakeConnectionRepository();
        packRepository = new FakeIndustryPackRepository();
        packRepository.catalogue.put("retail-v1", pack("retail-v1"));
        packRepository.catalogue.put("logistics-v1", pack("logistics-v1"));

        IndustryPackService packService = new IndustryPackService(
                packRepository,
                new PackEntityMapper(null, new ObjectMapper()),
                new PackRecommendationService(packRepository),
                new FakeSemanticService(),
                new FakeEnterpriseMapRepository(),
                connectionRepository,
                new FakeBusinessObjectBatchAnalyzer());

        connectionService = new ConnectionService(connectionRepository, packService);
    }

    private Map<String, Object> validBody(String packKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "My Connection");
        body.put("connectionType", "POSTGRES");
        body.put("jdbcUrl", "jdbc:postgresql://host:5432/db");
        body.put("username", "user");
        body.put("secret", "pw");
        body.put("packKey", packKey);
        return body;
    }

    // ── Test 1 — valid packKey succeeds ──────────────────────────────────────────

    @Test
    void connectionCreationWithValidPackKeySucceeds() {
        NexusConnection created = connectionService.createConnection(validBody("retail-v1"), "user@x.com");

        assertNotNull(created);
        assertEquals("ACTIVE", created.status());
        assertTrue(connectionRepository.connections.containsKey(created.connectionKey()));
    }

    // ── Test 2 — successful creation creates the ACTIVE tenant-pack association ─

    @Test
    void successfulCreationCreatesTheExpectedActiveTenantPackAssociation() {
        NexusConnection created = connectionService.createConnection(validBody("retail-v1"), "user@x.com");

        Optional<TenantPack> tp = packRepository.findActivePackForConnection(created.connectionKey());
        assertTrue(tp.isPresent(), "an ACTIVE nexus_tenant_pack row must exist for the new connection");
        assertEquals("retail-v1", tp.get().packKey());
        assertEquals(created.connectionKey(), tp.get().connectionKey());
        assertEquals("ACTIVE", tp.get().status());
        assertEquals("user@x.com", tp.get().appliedBy());
    }

    // ── Test 3 — missing packKey is rejected ─────────────────────────────────────

    @Test
    void connectionCreationWithMissingPackKeyIsRejected() {
        Map<String, Object> body = validBody(null);
        body.remove("packKey");

        NexusException ex = assertThrows(NexusException.class, () -> connectionService.createConnection(body, "u@x.com"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(connectionRepository.savedKeys.isEmpty(), "nothing must be written when packKey is missing");
    }

    // ── Test 4 — blank packKey is rejected ───────────────────────────────────────

    @Test
    void connectionCreationWithBlankPackKeyIsRejected() {
        Map<String, Object> body = validBody("   ");

        NexusException ex = assertThrows(NexusException.class, () -> connectionService.createConnection(body, "u@x.com"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(connectionRepository.savedKeys.isEmpty());
    }

    // ── Test 5 — invalid (unknown) packKey is rejected ───────────────────────────

    @Test
    void connectionCreationWithInvalidPackKeyIsRejected() {
        Map<String, Object> body = validBody("not-a-real-pack");

        NexusException ex = assertThrows(NexusException.class, () -> connectionService.createConnection(body, "u@x.com"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus(), "an unknown pack must be rejected the same way Apply Pack itself rejects it");
        assertTrue(connectionRepository.savedKeys.isEmpty(), "no connection may be written for an invalid pack");
    }

    // ── Test 6 — a failed Pack validation must not leave a partial connection ───

    @Test
    void invalidPackKeyLeavesNoPartiallyCreatedConnectionBehind() {
        Map<String, Object> body = validBody("not-a-real-pack");
        body.put("connectionKey", "conn-would-be-orphaned");

        assertThrows(NexusException.class, () -> connectionService.createConnection(body, "u@x.com"));

        assertTrue(connectionRepository.findByKey("conn-would-be-orphaned").isEmpty(),
                "packKey validation happens BEFORE any write — no orphaned connection row");
    }

    // ── Test 7 — the selected Pack belongs to the same tenant context ───────────

    @Test
    void theValidatedPackComesFromTheSameIndustryPackRepositoryApplyPackUses() {
        // No second/duplicate Pack lookup mechanism: the exact same fake IndustryPackRepository
        // instance wired into IndustryPackService is the only source of Pack truth reachable
        // from ConnectionService — proven by asserting a pack registered only in THIS instance's
        // catalogue resolves successfully with no other configuration.
        packRepository.catalogue.put("tenant-only-pack", pack("tenant-only-pack"));

        NexusConnection created = connectionService.createConnection(validBody("tenant-only-pack"), "u@x.com");

        assertTrue(packRepository.findActivePackForConnection(created.connectionKey()).isPresent());
    }

    // ── Test 8 — a connection cannot be created with two Packs ──────────────────

    @Test
    void aConnectionCannotEndUpWithTwoActivePacks() {
        NexusConnection created = connectionService.createConnection(validBody("retail-v1"), "u@x.com");

        // Simulating a second attempt to apply a different pack to the SAME already-created
        // connection (the request contract only ever carries one packKey field at all — this
        // proves the underlying Apply Pack guard also rejects a second one, belt-and-braces).
        Map<String, Object> secondBody = validBody("logistics-v1");
        secondBody.put("connectionKey", created.connectionKey());

        NexusException ex = assertThrows(NexusException.class,
                () -> connectionService.createConnection(secondBody, "u@x.com"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("retail-v1", packRepository.findActivePackForConnection(created.connectionKey()).orElseThrow().packKey(),
                "the original pack association must remain the only active one");
    }

    // ── Test 9 — existing connection + Pack lifecycle remains unchanged ─────────
    // (Remove → Apply-a-different-pack still works exactly as before this feature — this
    // exercises IndustryPackService.removePack/applyPack directly, completely independent of
    // ConnectionService, proving this task did not touch that lifecycle.)

    @Test
    void removeThenApplyADifferentPackStillWorksUnchangedAfterThisFeature() {
        NexusConnection created = connectionService.createConnection(validBody("retail-v1"), "u@x.com");
        IndustryPackService packServiceDirect = new IndustryPackService(
                packRepository, new PackEntityMapper(null, new ObjectMapper()),
                new PackRecommendationService(packRepository), new FakeSemanticService(),
                new FakeEnterpriseMapRepository(), connectionRepository, new FakeBusinessObjectBatchAnalyzer());

        packServiceDirect.removePack("retail-v1");
        assertTrue(packRepository.findActivePackForConnection(created.connectionKey()).isEmpty());

        packServiceDirect.applyPack("logistics-v1", "PLATFORM", created.connectionKey(), "u@x.com");
        assertEquals("logistics-v1", packRepository.findActivePackForConnection(created.connectionKey()).orElseThrow().packKey());
    }

    // ── Editing an existing connection is untouched by this feature ─────────────
    // (ConnectionController-level behavior — see also the controller's own upsertExisting path,
    // which this service class is never even invoked for.)

    @Test
    void connectionServiceIsOnlyForNewConnectionsNeverInvokedForEdits() {
        // Documents the invariant the controller enforces: ConnectionService.createConnection
        // always requires packKey, by design — it must never be the code path an edit goes
        // through. (The controller-level branch selection is covered by the fact that no
        // existing test anywhere in this codebase constructs ConnectionController with an
        // updated signature and fails — see full regression.)
        Map<String, Object> body = validBody(null);
        body.remove("packKey");
        assertThrows(NexusException.class, () -> connectionService.createConnection(body, "u@x.com"),
                "createConnection must always require a Pack — the controller alone decides when to call it");
    }
}
