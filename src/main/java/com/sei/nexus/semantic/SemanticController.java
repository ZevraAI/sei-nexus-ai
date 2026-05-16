package com.sei.nexus.semantic;

import com.sei.nexus.auth.UserAccount;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.enterprise.EnterpriseMapService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/semantic")
public class SemanticController {

    private final SemanticService service;
    private final SemanticRepository repository;
    private final EnterpriseMapService enterpriseMapService;

    public SemanticController(SemanticService service, SemanticRepository repository,
                               EnterpriseMapService enterpriseMapService) {
        this.service = service;
        this.repository = repository;
        this.enterpriseMapService = enterpriseMapService;
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

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UserAccount currentUser() {
        return (UserAccount) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
