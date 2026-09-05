package com.sei.nexus.semantic;

import com.sei.nexus.auth.UserAccount;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.enterprise.EnterpriseMapService;
import com.sei.nexus.knowledge.ConceptKnowledgeMaterializationService;
import com.sei.nexus.knowledge.ConceptKnowledgeSynchronizationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/semantic")
public class SemanticController {

    private final SemanticService              service;
    private final SemanticRepository           repository;
    private final EnterpriseMapService         enterpriseMapService;
    private final RelationshipDiscoveryService discoveryService;
    private final LearnedMappingRepository     learnedMappingRepository;
    private final com.sei.nexus.onboarding.MetadataRegistrationService metadataRegistration;
    private final ConceptKnowledgeSynchronizationService  conceptKnowledgeSynchronizationService;
    private final ConceptKnowledgeMaterializationService  conceptKnowledgeMaterializationService;

    public SemanticController(SemanticService service, SemanticRepository repository,
                               EnterpriseMapService enterpriseMapService,
                               RelationshipDiscoveryService discoveryService,
                               LearnedMappingRepository learnedMappingRepository,
                               com.sei.nexus.onboarding.MetadataRegistrationService metadataRegistration,
                               ConceptKnowledgeSynchronizationService conceptKnowledgeSynchronizationService,
                               ConceptKnowledgeMaterializationService conceptKnowledgeMaterializationService) {
        this.service                 = service;
        this.repository              = repository;
        this.enterpriseMapService    = enterpriseMapService;
        this.discoveryService        = discoveryService;
        this.learnedMappingRepository = learnedMappingRepository;
        this.metadataRegistration    = metadataRegistration;
        this.conceptKnowledgeSynchronizationService = conceptKnowledgeSynchronizationService;
        this.conceptKnowledgeMaterializationService = conceptKnowledgeMaterializationService;
    }

    // -------------------------------------------------------------------------
    // Business Entities
    // -------------------------------------------------------------------------

    /**
     * GET /semantic/entities?domainKey=
     */
    @GetMapping("/entities")
    public ResponseEntity<List<BusinessEntity>> listEntities(@RequestParam String domainKey) {
        return ResponseEntity.ok(repository.findEntitiesByDomain(domainKey));
    }

    /**
     * POST /semantic/entities
     * Creates or updates a business entity.
     */
    @PostMapping("/entities")
    public ResponseEntity<BusinessEntity> createEntity(@RequestBody Map<String, Object> body) {
        UserAccount user = currentUser();
        BusinessEntity entity = service.createOrUpdateEntity(body, user.email());
        return ResponseEntity.status(HttpStatus.OK).body(entity);
    }

    /**
     * DELETE /semantic/entities/{entityKey}
     * Archives the entity (soft delete).
     */
    @DeleteMapping("/entities/{entityKey}")
    public ResponseEntity<Void> archiveEntity(@PathVariable String entityKey) {
        repository.findEntityByKey(entityKey)
            .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                "Business entity not found: " + entityKey));
        repository.archiveEntity(entityKey);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // Lifecycle States
    // -------------------------------------------------------------------------

    /**
     * GET /semantic/entities/{entityKey}/lifecycle
     */
    @GetMapping("/entities/{entityKey}/lifecycle")
    public ResponseEntity<List<EntityLifecycleState>> listLifecycle(@PathVariable String entityKey) {
        return ResponseEntity.ok(repository.findLifecycleByEntity(entityKey));
    }

    /**
     * POST /semantic/entities/{entityKey}/lifecycle
     */
    @PostMapping("/entities/{entityKey}/lifecycle")
    public ResponseEntity<EntityLifecycleState> addLifecycleState(@PathVariable String entityKey,
                                                                    @RequestBody Map<String, Object> body) {
        EntityLifecycleState state = service.addLifecycleState(entityKey, body);
        return ResponseEntity.status(HttpStatus.OK).body(state);
    }

    // -------------------------------------------------------------------------
    // Relationships
    // -------------------------------------------------------------------------

    /**
     * GET /semantic/entities/{entityKey}/relationships
     */
    @GetMapping("/entities/{entityKey}/relationships")
    public ResponseEntity<List<EntityRelationship>> listRelationships(@PathVariable String entityKey) {
        return ResponseEntity.ok(repository.findRelationshipsByEntity(entityKey));
    }

    /**
     * POST /semantic/entities/{entityKey}/relationships
     */
    @PostMapping("/entities/{entityKey}/relationships")
    public ResponseEntity<EntityRelationship> addRelationship(@PathVariable String entityKey,
                                                               @RequestBody Map<String, Object> body) {
        EntityRelationship rel = service.addRelationship(entityKey, body);
        return ResponseEntity.status(HttpStatus.OK).body(rel);
    }

    // -------------------------------------------------------------------------
    // Operational Vocabulary
    // -------------------------------------------------------------------------

    /**
     * GET /semantic/vocabulary?domainKey=
     */
    @GetMapping("/vocabulary")
    public ResponseEntity<List<OperationalVocabulary>> listVocabulary(@RequestParam String domainKey) {
        return ResponseEntity.ok(repository.findTermsByDomain(domainKey));
    }

    /**
     * POST /semantic/vocabulary
     */
    @PostMapping("/vocabulary")
    public ResponseEntity<OperationalVocabulary> createTerm(@RequestBody Map<String, Object> body) {
        OperationalVocabulary term = service.createTerm(body);
        return ResponseEntity.status(HttpStatus.OK).body(term);
    }

    // -------------------------------------------------------------------------
    // Entity Data Mappings
    // -------------------------------------------------------------------------

    /**
     * POST /semantic/entities/{entityKey}/mappings
     */
    @PostMapping("/entities/{entityKey}/mappings")
    public ResponseEntity<EntityDataMapping> addMapping(@PathVariable String entityKey,
                                                         @RequestBody Map<String, Object> body) {
        EntityDataMapping mapping = service.addMapping(entityKey, body);
        return ResponseEntity.status(HttpStatus.OK).body(mapping);
    }

    /**
     * GET /semantic/entities/{entityKey}/mappings
     */
    @GetMapping("/entities/{entityKey}/mappings")
    public ResponseEntity<List<EntityDataMapping>> listMappings(@PathVariable String entityKey) {
        return ResponseEntity.ok(repository.findMappingsByEntity(entityKey));
    }

    // -------------------------------------------------------------------------
    // Discovery — AI-powered schema → semantic layer
    // -------------------------------------------------------------------------

    /**
     * POST /semantic/discover
     *
     * <p>Accepts a connection key, schema name, and list of table names.
     * For each table, reads the live schema from the database, sends it to the AI,
     * and returns structured suggestions for entities, vocabulary terms, and
     * relationship hints — ready for the user to review and approve in the UI.
     *
     * <p>Request body:
     * { "connectionKey": "local-postgres", "domainKey": "PLATFORM",
     *   "schemaName": "public", "tableNames": ["lgs_supplier", "lgs_shipment"] }
     *
     * <p>Response: the raw onboarding analysis map from EnterpriseMapService,
     * which contains a "tables" array, each entry having:
     * entityName, purpose, vocabularySuggestions, relationshipHints, columns, etc.
     */
    @PostMapping("/discover")
    public ResponseEntity<Map<String, Object>> discover(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = enterpriseMapService.analyzeForOnboarding(body);
        return ResponseEntity.ok(result);
    }

    /**
     * POST /semantic/discover/apply
     *
     * <p>Persists the approved drafts from the discovery review step by executing
     * the canonical Metadata Registration Pipeline (PRO-21) — the same pipeline
     * the Onboarding Wizard runs: data object + column scan + value domains +
     * version snapshot, entity linked via primary_object_key, vocabulary linked
     * via entity_key, then batch relationship discovery. Bootstrap operations
     * (suggested questions, default agent, completion flag) are wizard-only and
     * deliberately not executed here.
     *
     * <p>Request body mirrors /onboarding/apply:
     * { "connectionKey": "...", "schemaName": "...", "domainKey": "...",
     *   "entities": [ { "approved": true, "tableName": "...", ... , "vocabulary": [...] } ] }
     */
    @PostMapping("/discover/apply")
    public ResponseEntity<Map<String, Object>> discoverApply(@RequestBody Map<String, Object> body) {
        UserAccount user = currentUser();
        var result = metadataRegistration.register(body, user.email());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data_objects_created",      result.objectsCreated());
        response.put("entities_created",          result.entitiesCreated());
        response.put("vocab_terms_created",       result.vocabCreated());
        response.put("relationships_discovered",  result.relationshipsDiscovered());
        response.put("failures",                  result.failures());
        return ResponseEntity.ok(response);
    }

    /**
     * POST /semantic/discover-relationships
     * Automatically discovers entity relationships from the connected database
     * using foreign key constraints and column-name heuristics.
     * Safe to call multiple times — idempotent.
     *
     * Request: { "connectionKey": "...", "schemaName": "public", "domainKey": "PLATFORM" }
     * Response: { "relationships_created": 12, "connection_key": "...", "domain_key": "..." }
     */
    @PostMapping("/discover-relationships")
    public ResponseEntity<Map<String, Object>> discoverRelationships(@RequestBody Map<String, Object> body) {
        String connectionKey = requireStr(body, "connectionKey");
        String schemaName    = (String) body.getOrDefault("schemaName", "public");
        String domainKey     = requireStr(body, "domainKey");

        int created = discoveryService.discoverAndPersist(connectionKey, schemaName, domainKey);
        return ResponseEntity.ok(Map.of(
                "relationships_created", created,
                "connection_key",        connectionKey,
                "schema_name",           schemaName,
                "domain_key",            domainKey));
    }

    private String requireStr(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank())
            throw new NexusException(HttpStatus.BAD_REQUEST, key + " is required");
        return v.toString();
    }

    // -------------------------------------------------------------------------
    // Learned Mappings  (Phase 3 — Semantic Learning)
    // -------------------------------------------------------------------------

    /**
     * GET /semantic/learnings?domainKey=
     * Lists learned term → SQL pattern mappings for the Learnings panel.
     */
    @GetMapping("/learnings")
    public ResponseEntity<List<Map<String, Object>>> listLearnings(
            @RequestParam(required = false) String domainKey) {
        List<LearnedMapping> mappings = learnedMappingRepository.findForDomain(domainKey);
        List<Map<String, Object>> result = mappings.stream().map(this::toLearningMap).toList();
        return ResponseEntity.ok(result);
    }

    /**
     * PATCH /semantic/learnings/{mappingKey}
     * Admin can update the sql_pattern or confidence of a learned mapping.
     * Send {"sqlPattern":"..."} and/or {"confidence": 0.9} in the request body.
     */
    @PatchMapping("/learnings/{mappingKey}")
    public ResponseEntity<Map<String, Object>> updateLearning(
            @PathVariable String mappingKey,
            @RequestBody Map<String, Object> body) {
        String sqlPattern = (String) body.get("sqlPattern");
        Double confidence = body.containsKey("confidence")
                ? ((Number) body.get("confidence")).doubleValue() : null;
        learnedMappingRepository.update(mappingKey, sqlPattern, confidence);
        return learnedMappingRepository.findByKey(mappingKey)
                .map(m -> ResponseEntity.ok(toLearningMap(m)))
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "Learned mapping not found: " + mappingKey));
    }

    /**
     * GET /semantic/concepts
     * Read-only concept catalog — powers the Learnings panel's concept picker. See {@link
     * ConceptKnowledgeMaterializationService#listConceptCatalog()}: NOT part of the runtime Chat
     * path, purely so an admin can see and choose a valid concept_key.
     */
    @GetMapping("/concepts")
    public ResponseEntity<List<Map<String, String>>> listConcepts() {
        return ResponseEntity.ok(conceptKnowledgeMaterializationService.listConceptCatalog());
    }

    /**
     * POST /semantic/learnings/{mappingKey}/promote
     * Manually promote a learned mapping to formal vocabulary immediately,
     * without waiting for the nightly scheduler threshold.
     *
     * <p>Optionally accepts {"conceptKey": "..."} in the request body to classify the mapping into
     * a concept at the same time it's promoted — the classification is validated against {@link
     * ConceptKnowledgeMaterializationService#listConceptCatalog()} first, since an unknown
     * concept_key would silently make the learning unprojectable forever. Promotion alone (no
     * conceptKey) still leaves the mapping excluded from Vector Store projection until an admin
     * classifies it separately via {@link #assignConcept}, exactly as before this endpoint accepted
     * a body at all.
     */
    @PostMapping("/learnings/{mappingKey}/promote")
    public ResponseEntity<Map<String, Object>> promoteLearning(
            @PathVariable String mappingKey,
            @RequestBody(required = false) Map<String, Object> body) {
        LearnedMapping m = learnedMappingRepository.findByKey(mappingKey)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "Learned mapping not found: " + mappingKey));
        service.createTerm(Map.of(
                "domainKey",     m.domainKey() != null ? m.domainKey() : "",
                "term",          m.businessTerm(),
                "definition",    "Promoted from team learning: maps to — " + m.sqlPattern(),
                "sql_equivalent", m.sqlPattern(),
                "status",        "ACTIVE"));
        learnedMappingRepository.markPromoted(mappingKey);

        String conceptKey = body != null ? asNonBlank(body.get("conceptKey")) : null;
        if (conceptKey != null) {
            validateConceptKey(conceptKey);
            learnedMappingRepository.assignConceptKey(mappingKey, conceptKey);
        }

        // Fire-and-forget, same pattern as the existing Pack apply/remove triggers — the HTTP
        // response never waits on the OpenAI Vector Store round-trip.
        conceptKnowledgeSynchronizationService.triggerAsync();

        return ResponseEntity.ok(Map.of(
                "mapping_key", mappingKey,
                "promoted",    true,
                "term",        m.businessTerm(),
                "concept_key", conceptKey != null ? conceptKey : ""));
    }

    /**
     * POST /semantic/learnings/{mappingKey}/concept
     * Assigns (or reassigns) a learning's concept_key — the backfill path for a learning that was
     * promoted before this classification existed (e.g. the pre-existing "open" → PO status
     * mapping), and the general way to (re)classify any learning independent of promotion. Body:
     * {"conceptKey": "..."}, required and validated against {@link
     * ConceptKnowledgeMaterializationService#listConceptCatalog()} — never inferred (see {@link
     * LearnedMappingRepository#assignConceptKey}).
     */
    @PostMapping("/learnings/{mappingKey}/concept")
    public ResponseEntity<Map<String, Object>> assignConcept(
            @PathVariable String mappingKey,
            @RequestBody Map<String, Object> body) {
        String conceptKey = requireStr(body, "conceptKey");
        validateConceptKey(conceptKey);
        LearnedMapping m = learnedMappingRepository.findByKey(mappingKey)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "Learned mapping not found: " + mappingKey));
        learnedMappingRepository.assignConceptKey(mappingKey, conceptKey);
        if (m.promoted()) {
            // Only a promoted mapping is ever eligible for projection — classifying an
            // unpromoted one has nothing to sync yet.
            conceptKnowledgeSynchronizationService.triggerAsync();
        }
        return learnedMappingRepository.findByKey(mappingKey)
                .map(u -> ResponseEntity.ok(toLearningMap(u)))
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "Learned mapping not found: " + mappingKey));
    }

    /**
     * POST /semantic/learnings/{mappingKey}/demote
     * Demotes a promoted mapping — {@link LearnedMappingRepository#markDemoted}, distinct from the
     * hard {@link #deleteLearning} below: the row and its history stay in Postgres, only
     * {@code promoted} flips off. Triggers a sync so the next convergence removes it from its
     * concept's Vector Store projection (it no longer matches {@code findPromotedByConceptKey}).
     */
    @PostMapping("/learnings/{mappingKey}/demote")
    public ResponseEntity<Map<String, Object>> demoteLearning(@PathVariable String mappingKey) {
        learnedMappingRepository.markDemoted(mappingKey);
        conceptKnowledgeSynchronizationService.triggerAsync();
        return learnedMappingRepository.findByKey(mappingKey)
                .map(u -> ResponseEntity.ok(toLearningMap(u)))
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "Learned mapping not found: " + mappingKey));
    }

    /**
     * DELETE /semantic/learnings/{mappingKey}
     * Reject and delete a learned mapping.
     */
    @DeleteMapping("/learnings/{mappingKey}")
    public ResponseEntity<Void> deleteLearning(@PathVariable String mappingKey) {
        learnedMappingRepository.delete(mappingKey);
        return ResponseEntity.noContent().build();
    }

    /** Validates a caller-supplied concept_key against the admin-facing concept catalog — used by
     *  both /promote (optional) and /concept (required) so an unknown concept can never be
     *  assigned, which would silently make a learning unprojectable forever. */
    private void validateConceptKey(String conceptKey) {
        boolean known = conceptKnowledgeMaterializationService.listConceptCatalog().stream()
                .anyMatch(c -> conceptKey.equals(c.get("conceptKey")));
        if (!known) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "Unknown concept key: " + conceptKey);
        }
    }

    private String asNonBlank(Object value) {
        if (value == null) return null;
        String s = value.toString();
        return s.isBlank() ? null : s;
    }

    private Map<String, Object> toLearningMap(LearnedMapping m) {
        var r = new LinkedHashMap<String, Object>();
        r.put("mapping_key",   m.mappingKey());
        r.put("domain_key",    m.domainKey());
        r.put("business_term", m.businessTerm());
        r.put("sql_pattern",   m.sqlPattern());
        r.put("source",        m.source());
        r.put("confidence",    m.confidence());
        r.put("use_count",     m.useCount());
        r.put("last_used_at",  m.lastUsedAt() != null ? m.lastUsedAt().toString() : null);
        r.put("promoted",      m.promoted());
        r.put("created_at",    m.createdAt() != null ? m.createdAt().toString() : null);
        r.put("concept_key",   m.conceptKey());
        return r;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UserAccount currentUser() {
        return (UserAccount) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
