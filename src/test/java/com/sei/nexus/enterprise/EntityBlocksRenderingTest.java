package com.sei.nexus.enterprise;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.connection.ConnectionRepository;
import com.sei.nexus.connection.NexusConnection;
import com.sei.nexus.sql.DynamicSqlService;
import com.sei.nexus.sql.SqlSafetyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PRO-19 — EnterpriseMapService.operationalContext must additionally expose its
 * per-object rendered sections as discrete blocks ("entityBlocks"), while
 * remaining a question-agnostic renderer: same output for any question, blocks
 * in renderer order, and their concatenation byte-identical to entityContext.
 */
class EntityBlocksRenderingTest {

    static class FakeRepository extends EnterpriseMapRepository {
        List<DataObject> objectsByDomain = List.of();
        Map<String, List<DataColumn>> columnsByObject = new HashMap<>();

        FakeRepository() { super(null); }

        @Override public List<DataObject> findDataObjectsByDomain(String domainKey) {
            return objectsByDomain;
        }
        @Override public List<DataColumn> findColumnsByObject(String key) {
            return columnsByObject.getOrDefault(key, List.of());
        }
        @Override public List<OperationalNote> findNotesByDomain(String domainKey) {
            return List.of();
        }
        @Override public Optional<ValueDomain> findValueDomainByKey(String key) {
            return Optional.empty();
        }
    }

    static class FakeConnectionRepository extends ConnectionRepository {
        FakeConnectionRepository() { super(null); }
        @Override public Optional<NexusConnection> findByKey(String key) {
            return Optional.of(new NexusConnection(key, "Retail DB", "POSTGRES", "",
                    "jdbc:postgresql://x/db", null, "u", "s", "", "", true,
                    null, null, null, "ACTIVE", Instant.now(), Instant.now()));
        }
    }

    static class FakeDynamicSql extends DynamicSqlService {
        FakeDynamicSql() { super(null); }
    }

    private FakeRepository repository;
    private EnterpriseMapService service;

    private static DataObject object(String key, String table, String business) {
        return new DataObject(key, "PLATFORM", business, "conn-1",
                "retail_core", table, business, business + " master data",
                "", "status", "", "", "", "", "",
                500, false, "SCANNED", 1, Instant.now(), Instant.now());
    }

    private static DataColumn column(String objectKey, String name) {
        return new DataColumn("col-" + objectKey + "-" + name, objectKey, name, "varchar",
                false, "", false, false, false, false, false,
                null, null, "INFERRED", Instant.now(), Instant.now());
    }

    @BeforeEach
    void setUp() {
        repository = new FakeRepository();
        service = new EnterpriseMapService(repository, new FakeConnectionRepository(),
                new FakeDynamicSql(), new SqlSafetyService(), null, new ObjectMapper(), null);

        repository.objectsByDomain = List.of(
                object("obj-regions", "regions", "Regions"),
                object("obj-stores", "stores", "Stores"));
        repository.columnsByObject.put("obj-regions",
                List.of(column("obj-regions", "id"), column("obj-regions", "name")));
        repository.columnsByObject.put("obj-stores",
                List.of(column("obj-stores", "id"), column("obj-stores", "state_province")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void entityBlocksArePerObjectAndConcatenateToEntityContext() {
        Map<String, Object> ctx = service.operationalContext(
                List.of("PLATFORM"), List.of("conn-1"), "show me stores");

        Map<String, String> blocks = (Map<String, String>) ctx.get("entityBlocks");
        String entityContext = (String) ctx.get("entityContext");

        assertEquals(List.of("obj-regions", "obj-stores"), new ArrayList<>(blocks.keySet()),
                "blocks keyed by object_key, in renderer order");
        assertTrue(blocks.get("obj-stores").startsWith("Table: retail_core.stores (Stores)"));
        assertTrue(blocks.get("obj-stores").contains("state_province"));
        assertEquals(entityContext, String.join("", blocks.values()),
                "concatenating blocks in map order must reproduce entityContext exactly");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rendererStaysQuestionAgnostic() {
        Map<String, Object> a = service.operationalContext(
                List.of("PLATFORM"), List.of("conn-1"), "show me stores");
        Map<String, Object> b = service.operationalContext(
                List.of("PLATFORM"), List.of("conn-1"), "completely different question about invoices");

        assertEquals(a.get("entityContext"), b.get("entityContext"),
                "renderer output must not depend on the question");
        assertEquals(new ArrayList<>(((Map<String, String>) a.get("entityBlocks")).values()),
                new ArrayList<>(((Map<String, String>) b.get("entityBlocks")).values()),
                "block order must not depend on the question — selection is ChatService's job");
    }
}
