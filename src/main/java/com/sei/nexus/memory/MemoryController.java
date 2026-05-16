package com.sei.nexus.memory;

import com.sei.nexus.common.NexusException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints for knowledge-base document management.
 * Base path: /api/v1/memory (context-path set in application.yml)
 */
@RestController
@RequestMapping("/memory")
public class MemoryController {

    private final DocumentMemoryService documentMemoryService;

    public MemoryController(DocumentMemoryService documentMemoryService) {
        this.documentMemoryService = documentMemoryService;
    }

    /**
     * GET /memory/documents?domainKey={domainKey}
     * Lists documents for the given domain.
     */
    @GetMapping("/documents")
    public ResponseEntity<List<KnowledgeDocument>> listDocuments(
            @RequestParam String domainKey) {
        List<KnowledgeDocument> docs = documentMemoryService.getDocuments(domainKey);
        return ResponseEntity.ok(docs);
    }

    /**
     * POST /memory/documents  (multipart/form-data)
     * Uploads a document and triggers async indexing.
     * Form fields: file, domainKey, title, tags (optional)
     */
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<KnowledgeDocument> uploadDocument(
            @RequestPart("file") MultipartFile file,
            @RequestPart("domainKey") String domainKey,
            @RequestPart("title") String title,
            @RequestPart(value = "tags", required = false) String tags,
            Authentication authentication) {

        String userEmail = resolveEmail(authentication);
        KnowledgeDocument doc = documentMemoryService.uploadDocument(
                file, domainKey, title, tags, userEmail);
        return ResponseEntity.status(HttpStatus.CREATED).body(doc);
    }

    /**
     * PATCH /memory/documents/{documentKey}
     * Updates document metadata: domainKey, title, tags.
     * Request body: { "domainKey": "...", "title": "...", "tags": "..." }
     */
    @PatchMapping("/documents/{documentKey}")
    public ResponseEntity<KnowledgeDocument> updateDocument(
            @PathVariable String documentKey,
            @RequestBody Map<String, String> body) {

        String domainKey = body.get("domainKey");
        String title = body.get("title");
        String tags = body.get("tags");

        if (domainKey == null || domainKey.isBlank()) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "domainKey is required");
        }
        if (title == null || title.isBlank()) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "title is required");
        }

        KnowledgeDocument updated = documentMemoryService.updateDocumentMeta(
                documentKey, domainKey, title, tags);
        return ResponseEntity.ok(updated);
    }

    /**
     * DELETE /memory/documents/{documentKey}
     * Soft-deletes (archives) a document and its chunks.
     */
    @DeleteMapping("/documents/{documentKey}")
    public ResponseEntity<Void> archiveDocument(@PathVariable String documentKey) {
        documentMemoryService.archiveDocument(documentKey);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private String resolveEmail(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            return "anonymous";
        }
        Object principal = authentication.getPrincipal();
        // The auth filter sets a com.sei.nexus.auth.UserAccount record as principal
        try {
            return (String) principal.getClass().getMethod("email").invoke(principal);
        } catch (Exception e) {
            return authentication.getName();
        }
    }
}
