package com.sei.nexus.semantic;

import com.sei.nexus.auth.UserAccount;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.enterprise.EnterpriseMapService;
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

    public SemanticController(SemanticService service, SemanticRepository repository,
                               EnterpriseMapService enterpriseMapService,
                               RelationshipDiscoveryService discoveryService,
                               LearnedMappingRepository learnedMappingRepository) {
        this.service                 = service;
        this.repository              = repository;
        this.enterpriseMapService    = enterpriseMapService;
        this.discoveryService        = discoveryService;
        this.learnedMappingRepository = learnedMappingRepository;
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
     * POST /semantic/learnings/{mappingKey}/promote
     * Manually promote a learned mapping to formal vocabulary immediately,
     * without waiting for the nightly scheduler threshold.
     */
    @PostMapping("/learnings/{mappingKey}/promote")
    public ResponseEntity<Map<String, Object>> promoteLearning(@PathVariable String mappingKey) {
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
        return ResponseEntity.ok(Map.of(
                "mapping_key", mappingKey,
                "promoted",    true,
                "term",        m.businessTerm()));
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
        return r;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UserAccount currentUser() {
        return (UserAccount) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
