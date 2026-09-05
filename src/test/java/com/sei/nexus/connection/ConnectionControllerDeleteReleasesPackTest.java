package com.sei.nexus.connection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.enterprise.DataObject;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import com.sei.nexus.knowledge.ConceptKnowledgeSynchronizationService;
import com.sei.nexus.pack.*;
import com.sei.nexus.prompt.BusinessObjectBatchAnalyzer;
import com.sei.nexus.semantic.SemanticService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Industry Pack Required At Connection Creation — a necessary consequence: since every
 * connection now always has an ACTIVE Pack from creation, deleting a connection (e.g. the
 * onboarding wizard's existing create → test → delete-on-failure flow) must release it too, or
 * the pack_key would remain permanently "already applied" tenant-wide with no connection left
 * to remove it from. This reuses the EXISTING {@link IndustryPackService#removePack} — no
 * Pack-removal logic changed, only invoked from a new call site ({@link
 * ConnectionController#archiveConnection}). Same hand-rolled-fakes convention as {@link
 * ConnectionServiceTest}.
 */
class ConnectionControllerDeleteReleasesPackTest {

    static class FakeConnectionRepository extends ConnectionRepository {
        final Map<String, NexusConnection> connections = new LinkedHashMap<>();
        final List<String> archivedKeys = new ArrayList<>();
        FakeConnectionRepository() { super(null); }
        @Override public Optional<NexusConnection> findByKey(String connectionKey) {
            return Optional.ofNullable(connections.get(connectionKey));
        }
        @Override public void save(NexusConnection conn) { connections.put(conn.connectionKey(), conn); }
        @Override public List<String> findDependents(String connectionKey) { return List.of(); }
        @Override public void archive(String connectionKey) {
            archivedKeys.add(connectionKey);
            connections.remove(connectionKey);
        }
    }

    static class FakeEnterpriseMapRepository extends EnterpriseMapRepository {
        FakeEnterpriseMapRepository() { super(null); }
        @Override public List<DataObject> findDataObjectsByConnection(String connectionKey) { return List.of(); }
    }

    static class FakeSemanticService extends SemanticService {
        FakeSemanticService() { super(null, null, null); }
    }

    static class FakeIndustryPackRepository extends IndustryPackRepository {
        final Map<String, IndustryPack> catalogue = new LinkedHashMap<>();
        final Map<String, TenantPack> tenantPacksByPackKey = new LinkedHashMap<>();
        FakeIndustryPackRepository() { super(null, new ObjectMapper()); }
        @Override public Optional<IndustryPack> findPackById(String packId) { return Optional.ofNullable(catalogue.get(packId)); }
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
        @Override public Map<String, Map<String, Object>> analyzeBatch(String connectionKey, String schemaName,
                String domainKey, List<String> tableNames) { return Map.of(); }
    }

    private static PackEntity packEntity(String name) {
        return new PackEntity(name, List.of(), List.of(), List.of(), "desc", "meaning", null, null);
    }

    private static IndustryPack pack(String packId) {
        return new IndustryPack(packId, "RETAIL", "Test Pack", "v1", "desc",
                List.of(packEntity("Product")), List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null, null);
    }

    private FakeConnectionRepository connectionRepository;
    private FakeIndustryPackRepository packRepository;
    private IndustryPackService packService;
    private ConnectionService connectionService;
    private ConnectionController controller;

    @BeforeEach
    void setUp() {
        connectionRepository = new FakeConnectionRepository();
        packRepository = new FakeIndustryPackRepository();
        packRepository.catalogue.put("retail-v1", pack("retail-v1"));

        packService = new IndustryPackService(packRepository, new PackEntityMapper(null, new ObjectMapper()),
                new PackRecommendationService(packRepository), new FakeSemanticService(),
                new FakeEnterpriseMapRepository(), connectionRepository, new FakeBusinessObjectBatchAnalyzer(),
                new ConceptKnowledgeSynchronizationService(null, null, null, null) {
                    @Override public void triggerAsync() { }
                });
        connectionService = new ConnectionService(connectionRepository, packService);
        controller = new ConnectionController(connectionRepository, null, null, connectionService,
                packRepository, packService);
    }

    @Test
    void deletingAConnectionReleasesItsActivePackSoItCanBeAppliedAgain() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Temp"); body.put("connectionType", "POSTGRES");
        body.put("jdbcUrl", "jdbc:postgresql://h:5432/d"); body.put("packKey", "retail-v1");
        NexusConnection created = connectionService.createConnection(body, "u@x.com");
        assertTrue(packRepository.findActivePackForConnection(created.connectionKey()).isPresent());

        controller.archiveConnection(created.connectionKey());

        assertTrue(connectionRepository.archivedKeys.contains(created.connectionKey()));
        assertTrue(packRepository.findActivePackForConnection(created.connectionKey()).isEmpty(),
                "the pack association must be released when its connection is deleted");
        assertTrue(packRepository.findAppliedPack("retail-v1").isEmpty(),
                "retail-v1 must no longer be 'already applied' tenant-wide — it must be re-applicable elsewhere");

        // Proves the fix actually matters: re-applying the same pack to a NEW connection must
        // now succeed rather than being permanently blocked by an orphaned row.
        Map<String, Object> body2 = new LinkedHashMap<>(body);
        body2.remove("connectionKey");
        NexusConnection second = connectionService.createConnection(body2, "u@x.com");
        assertEquals("retail-v1", packRepository.findActivePackForConnection(second.connectionKey()).orElseThrow().packKey());
    }
}
