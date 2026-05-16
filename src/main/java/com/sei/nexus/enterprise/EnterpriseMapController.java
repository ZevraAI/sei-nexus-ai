package com.sei.nexus.enterprise;

import com.sei.nexus.auth.UserAccount;
import com.sei.nexus.common.NexusException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/enterprise-map")
public class EnterpriseMapController {

    private final EnterpriseMapService service;
    private final EnterpriseMapRepository repository;

    public EnterpriseMapController(EnterpriseMapService service,
                                    EnterpriseMapRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    // -------------------------------------------------------------------------
    // Data Objects
    // -------------------------------------------------------------------------

    /**
     * GET /enterprise-map/objects?domainKey=
     */
    @GetMapping("/objects")
    public ResponseEntity<List<DataObject>> listObjects(@RequestParam String domainKey) {
        return ResponseEntity.ok(repository.findDataObjectsByDomain(domainKey));
    }

    /**
     * POST /enterprise-map/objects
     * Creates or updates a data object.
     */
    @PostMapping("/objects")
    public ResponseEntity<DataObject> createObject(@RequestBody Map<String, Object> body) {
        UserAccount user = currentUser();
        DataObject created = service.createOrUpdateObject(body, user.email());
        return ResponseEntity.status(HttpStatus.OK).body(created);
    }

    /**
     * POST /enterprise-map/objects/{objectKey}/scan
     * Triggers a re-scan of the object's columns from the live database.
     */
    @PostMapping("/objects/{objectKey}/scan")
    public ResponseEntity<DataObject> scanObject(@PathVariable String objectKey) {
        DataObject scanned = service.scanObject(objectKey);
        return ResponseEntity.ok(scanned);
    }

    /**
     * GET /enterprise-map/objects/{objectKey}/versions
     */
    @GetMapping("/objects/{objectKey}/versions")
    public ResponseEntity<List<Map<String, Object>>> listVersions(@PathVariable String objectKey) {
        return ResponseEntity.ok(repository.findVersionsByObject(objectKey));
    }

    /**
     * POST /enterprise-map/objects/{objectKey}/versions/{versionNo}/rollback
     */
    @PostMapping("/objects/{objectKey}/versions/{versionNo}/rollback")
    public ResponseEntity<DataObject> rollback(@PathVariable String objectKey,
                                                @PathVariable int versionNo) {
        DataObject rolled = service.rollback(objectKey, versionNo);
        return ResponseEntity.ok(rolled);
    }

    /**
     * DELETE /enterprise-map/objects/{objectKey}
     * Soft-deletes (archives) the data object.
     */
    @DeleteMapping("/objects/{objectKey}")
    public ResponseEntity<Void> archiveObject(@PathVariable String objectKey) {
        repository.findDataObjectByKey(objectKey)
            .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                "Data object not found: " + objectKey));
        repository.archiveDataObject(objectKey);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // Columns
    // -------------------------------------------------------------------------

    /**
     * GET /enterprise-map/objects/{objectKey}/columns
     */
    @GetMapping("/objects/{objectKey}/columns")
    public ResponseEntity<List<DataColumn>> listColumns(@PathVariable String objectKey) {
        return ResponseEntity.ok(repository.findColumnsByObject(objectKey));
    }

    /**
     * PATCH /enterprise-map/objects/{objectKey}/columns/{columnName}
     * Updates metadata for a specific column.
     */
    @PatchMapping("/objects/{objectKey}/columns/{columnName}")
    public ResponseEntity<Void> updateColumn(@PathVariable String objectKey,
                                              @PathVariable String columnName,
                                              @RequestBody Map<String, Object> body) {
        String businessMeaning = (String) body.getOrDefault("businessMeaning", "");
        boolean isIdentifier = Boolean.TRUE.equals(body.get("isIdentifier"));
        boolean isStatus     = Boolean.TRUE.equals(body.get("isStatus"));
        boolean isError      = Boolean.TRUE.equals(body.get("isError"));
        boolean isSensitive  = Boolean.TRUE.equals(body.get("isSensitive"));
        boolean isFilterable = Boolean.TRUE.equals(body.get("isFilterable"));

        repository.updateColumn(objectKey, columnName, businessMeaning,
            isIdentifier, isStatus, isError, isSensitive, isFilterable);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // Catalog
    // -------------------------------------------------------------------------

    /**
     * GET /enterprise-map/catalog/tables?connectionKey=&schemaName=&query=
     */
    @GetMapping("/catalog/tables")
    public ResponseEntity<List<Map<String, Object>>> searchCatalog(
            @RequestParam String connectionKey,
            @RequestParam(required = false) String schemaName,
            @RequestParam(required = false) String query) {
        List<Map<String, Object>> tables = service.searchCatalog(connectionKey, schemaName, query);
        return ResponseEntity.ok(tables);
    }

    // -------------------------------------------------------------------------
    // Operational Notes
    // -------------------------------------------------------------------------

    /**
     * GET /enterprise-map/notes?domainKey=
     */
    @GetMapping("/notes")
    public ResponseEntity<List<OperationalNote>> listNotes(@RequestParam String domainKey) {
        return ResponseEntity.ok(repository.findNotesByDomain(domainKey));
    }

    /**
     * POST /enterprise-map/notes
     */
    @PostMapping("/notes")
    public ResponseEntity<OperationalNote> createNote(@RequestBody Map<String, Object> body) {
        UserAccount user = currentUser();
        String domainKey  = required(body, "domainKey");
        String title      = required(body, "title");
        String noteText   = required(body, "noteText");
        String noteKey    = "note-" + com.sei.nexus.common.Keys.uniqueKey("note");

        OperationalNote note = new OperationalNote(
            noteKey,
            domainKey,
            (String) body.get("entityName"),
            (String) body.get("objectKey"),
            title,
            noteText,
            (String) body.get("tags"),
            "ACTIVE",
            user.email(),
            java.time.Instant.now(),
            java.time.Instant.now());

        repository.saveNote(note);
        return ResponseEntity.status(HttpStatus.OK).body(note);
    }

    /**
     * DELETE /enterprise-map/notes/{noteKey}
     */
    @DeleteMapping("/notes/{noteKey}")
    public ResponseEntity<Void> archiveNote(@PathVariable String noteKey) {
        repository.archiveNote(noteKey);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // Onboarding
    // -------------------------------------------------------------------------

    /**
     * POST /enterprise-map/onboarding/analyze
     * Analyzes a set of tables using AI to suggest enterprise map configuration.
     */
    @PostMapping("/onboarding/analyze")
    public ResponseEntity<Map<String, Object>> analyzeOnboarding(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = service.analyzeForOnboarding(body);
        return ResponseEntity.ok(result);
    }

    /**
     * POST /enterprise-map/onboarding/simulate
     * Simulates a prompt against a draft data object to validate SQL generation quality.
     */
    @PostMapping("/onboarding/simulate")
    public ResponseEntity<Map<String, Object>> simulateOnboarding(@RequestBody Map<String, Object> body) {
        String prompt = required(body, "prompt");
        @SuppressWarnings("unchecked")
        Map<String, Object> draft = (Map<String, Object>) body.get("draft");
        if (draft == null) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "draft is required");
        }
        Map<String, Object> result = service.simulate(prompt, draft);
        return ResponseEntity.ok(result);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UserAccount currentUser() {
        return (UserAccount) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private String required(Map<String, Object> body, String field) {
        Object val = body.get(field);
        if (val == null || val.toString().isBlank()) {
            throw new NexusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return val.toString();
    }
}
