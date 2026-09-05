package com.sei.nexus.semantic;

import com.sei.nexus.common.NexusException;
import com.sei.nexus.knowledge.ConceptKnowledgeMaterializationService;
import com.sei.nexus.knowledge.ConceptKnowledgeSynchronizationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Minimal, focused coverage of the concept-classification endpoints added to {@link
 * SemanticController} — promote's optional concept assignment, the dedicated {@code /concept}
 * backfill endpoint, and {@code /demote}. Hand-rolled fakes throughout (no Mockito, no Spring
 * context, no DB) — every dependency this controller doesn't exercise in these scenarios (
 * {@link SemanticRepository}, {@code EnterpriseMapService}, {@link RelationshipDiscoveryService},
 * {@code MetadataRegistrationService}) is passed as {@code null}, exactly like other
 * hand-rolled-fake tests in this codebase pass unused JDBC/collaborator dependencies as null —
 * the controller constructor only assigns fields, it never touches them.
 */
class SemanticControllerTest {

    static class FakeLearnedMappingRepository extends LearnedMappingRepository {
        final Map<String, LearnedMapping> byKey = new LinkedHashMap<>();
        final List<String> assignedConceptKeys = new ArrayList<>();
        final List<String> demoted = new ArrayList<>();
        FakeLearnedMappingRepository() { super(null); }
        void seed(LearnedMapping m) { byKey.put(m.mappingKey(), m); }
        @Override public Optional<LearnedMapping> findByKey(String mappingKey) {
            return Optional.ofNullable(byKey.get(mappingKey));
        }
        @Override public void markPromoted(String mappingKey) {
            LearnedMapping m = byKey.get(mappingKey);
            if (m != null) byKey.put(mappingKey, withPromoted(m, true));
        }
        @Override public void markDemoted(String mappingKey) {
            demoted.add(mappingKey);
            LearnedMapping m = byKey.get(mappingKey);
            if (m != null) byKey.put(mappingKey, withPromoted(m, false));
        }
        @Override public void assignConceptKey(String mappingKey, String conceptKey) {
            assignedConceptKeys.add(mappingKey + "=" + conceptKey);
            LearnedMapping m = byKey.get(mappingKey);
            if (m != null) byKey.put(mappingKey, withConceptKey(m, conceptKey));
        }
        private LearnedMapping withPromoted(LearnedMapping m, boolean promoted) {
            return new LearnedMapping(m.mappingKey(), m.domainKey(), m.businessTerm(), m.sqlPattern(),
                    m.sourceRunKey(), m.source(), m.confidence(), m.useCount(), m.lastUsedAt(),
                    promoted, m.createdAt(), m.updatedAt(), m.conceptKey());
        }
        private LearnedMapping withConceptKey(LearnedMapping m, String conceptKey) {
            return new LearnedMapping(m.mappingKey(), m.domainKey(), m.businessTerm(), m.sqlPattern(),
                    m.sourceRunKey(), m.source(), m.confidence(), m.useCount(), m.lastUsedAt(),
                    m.promoted(), m.createdAt(), m.updatedAt(), conceptKey);
        }
    }

    static class FakeSemanticService extends SemanticService {
        FakeSemanticService() { super(null, null, null); }
        @Override public OperationalVocabulary createTerm(Map<String, Object> body) {
            return null; // return value unused by SemanticController's promote path
        }
    }

    static class FakeMaterializationService extends ConceptKnowledgeMaterializationService {
        List<Map<String, String>> catalog = new ArrayList<>();
        FakeMaterializationService() { super(null, null, null, null, null, null); }
        @Override public List<Map<String, String>> listConceptCatalog() { return catalog; }
    }

    static class FakeSynchronizationService extends ConceptKnowledgeSynchronizationService {
        int triggerAsyncCalls = 0;
        FakeSynchronizationService() { super(null, null, null, null, null); }
        @Override public void triggerAsync() { triggerAsyncCalls++; }
    }

    private LearnedMapping mapping(String key, boolean promoted, String conceptKey) {
        return new LearnedMapping(key, "PLATFORM", "open", "status IN ('open')", "run-1",
                "QUERY_SUCCESS", 0.8, 10, Instant.now(), promoted, Instant.now(), Instant.now(), conceptKey);
    }

    private SemanticController controller(FakeLearnedMappingRepository mappingRepo,
                                           FakeMaterializationService materializationService,
                                           FakeSynchronizationService syncService) {
        return new SemanticController(new FakeSemanticService(), null, null, null,
                mappingRepo, null, syncService, materializationService);
    }

    @Test
    void promoteRejectsAnUnknownConceptKeyWith400() {
        FakeLearnedMappingRepository mappingRepo = new FakeLearnedMappingRepository();
        mappingRepo.seed(mapping("lmap-1", false, null));
        FakeMaterializationService materializationService = new FakeMaterializationService();
        materializationService.catalog.add(Map.of("conceptKey", "purchase-order", "name", "Purchase Order"));
        SemanticController controller = controller(mappingRepo, materializationService, new FakeSynchronizationService());

        NexusException ex = assertThrows(NexusException.class, () -> controller.promoteLearning(
                "lmap-1", Map.of("conceptKey", "no-such-concept")));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void promoteAcceptsAValidConceptKeyAndTriggersSync() {
        FakeLearnedMappingRepository mappingRepo = new FakeLearnedMappingRepository();
        mappingRepo.seed(mapping("lmap-1", false, null));
        FakeMaterializationService materializationService = new FakeMaterializationService();
        materializationService.catalog.add(Map.of("conceptKey", "purchase-order", "name", "Purchase Order"));
        FakeSynchronizationService syncService = new FakeSynchronizationService();
        SemanticController controller = controller(mappingRepo, materializationService, syncService);

        ResponseEntity<Map<String, Object>> response = controller.promoteLearning(
                "lmap-1", Map.of("conceptKey", "purchase-order"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("purchase-order", response.getBody().get("concept_key"));
        assertEquals(List.of("lmap-1=purchase-order"), mappingRepo.assignedConceptKeys);
        assertTrue(mappingRepo.byKey.get("lmap-1").promoted());
        assertEquals(1, syncService.triggerAsyncCalls);
    }

    @Test
    void promoteWithNoConceptKeyStillPromotesAndTriggersSyncWithoutClassifying() {
        FakeLearnedMappingRepository mappingRepo = new FakeLearnedMappingRepository();
        mappingRepo.seed(mapping("lmap-1", false, null));
        FakeSynchronizationService syncService = new FakeSynchronizationService();
        SemanticController controller = controller(mappingRepo, new FakeMaterializationService(), syncService);

        ResponseEntity<Map<String, Object>> response = controller.promoteLearning("lmap-1", null);

        assertTrue(mappingRepo.byKey.get("lmap-1").promoted());
        assertNull(mappingRepo.byKey.get("lmap-1").conceptKey());
        assertTrue(mappingRepo.assignedConceptKeys.isEmpty());
        assertEquals(1, syncService.triggerAsyncCalls);
        assertEquals("", response.getBody().get("concept_key"));
    }

    @Test
    void assignConceptRejectsAnUnknownConceptKeyWith400() {
        FakeLearnedMappingRepository mappingRepo = new FakeLearnedMappingRepository();
        mappingRepo.seed(mapping("lmap-1", true, null));
        FakeMaterializationService materializationService = new FakeMaterializationService();
        SemanticController controller = controller(mappingRepo, materializationService, new FakeSynchronizationService());

        NexusException ex = assertThrows(NexusException.class, () -> controller.assignConcept(
                "lmap-1", Map.of("conceptKey", "unknown")));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void assignConceptBackfillsThePreExistingPromotedMappingAndTriggersSync() {
        FakeLearnedMappingRepository mappingRepo = new FakeLearnedMappingRepository();
        mappingRepo.seed(mapping("lmap-open", true, null)); // the pre-existing "open" -> PO status mapping
        FakeMaterializationService materializationService = new FakeMaterializationService();
        materializationService.catalog.add(Map.of("conceptKey", "purchase-order", "name", "Purchase Order"));
        FakeSynchronizationService syncService = new FakeSynchronizationService();
        SemanticController controller = controller(mappingRepo, materializationService, syncService);

        ResponseEntity<Map<String, Object>> response = controller.assignConcept(
                "lmap-open", Map.of("conceptKey", "purchase-order"));

        assertEquals("purchase-order", response.getBody().get("concept_key"));
        assertEquals(1, syncService.triggerAsyncCalls, "a promoted mapping's classification must trigger a sync");
    }

    @Test
    void assignConceptOnAnUnpromotedMappingDoesNotTriggerSync() {
        FakeLearnedMappingRepository mappingRepo = new FakeLearnedMappingRepository();
        mappingRepo.seed(mapping("lmap-1", false, null));
        FakeMaterializationService materializationService = new FakeMaterializationService();
        materializationService.catalog.add(Map.of("conceptKey", "purchase-order", "name", "Purchase Order"));
        FakeSynchronizationService syncService = new FakeSynchronizationService();
        SemanticController controller = controller(mappingRepo, materializationService, syncService);

        controller.assignConcept("lmap-1", Map.of("conceptKey", "purchase-order"));

        assertEquals(0, syncService.triggerAsyncCalls, "nothing to project for an unpromoted mapping yet");
    }

    @Test
    void demoteCallsMarkDemotedAndTriggersSync() {
        FakeLearnedMappingRepository mappingRepo = new FakeLearnedMappingRepository();
        mappingRepo.seed(mapping("lmap-1", true, "purchase-order"));
        FakeSynchronizationService syncService = new FakeSynchronizationService();
        SemanticController controller = controller(mappingRepo, new FakeMaterializationService(), syncService);

        ResponseEntity<Map<String, Object>> response = controller.demoteLearning("lmap-1");

        assertEquals(List.of("lmap-1"), mappingRepo.demoted);
        assertFalse(mappingRepo.byKey.get("lmap-1").promoted());
        assertEquals(1, syncService.triggerAsyncCalls);
        assertEquals(false, response.getBody().get("promoted"));
    }
}
