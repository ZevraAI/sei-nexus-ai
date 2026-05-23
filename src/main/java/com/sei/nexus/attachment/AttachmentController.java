package com.sei.nexus.attachment;

import com.sei.nexus.auth.UserAccount;
import com.sei.nexus.common.NexusException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints for chat attachment upload.
 *
 * <p>Files are uploaded before or during a conversation. The returned
 * {@code attachment_key} is then passed with {@code POST /chat/ask} so the
 * extracted content is included in the AI context.
 *
 * <p>Supported types: images (JPEG/PNG/WebP/GIF/BMP), spreadsheets (CSV/XLSX/XLS),
 * documents (PDF/DOCX/PPTX), and plain text (TXT/MD/JSON/XML).
 */
@RestController
@RequestMapping("/chat/attachments")
public class AttachmentController {

    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024; // 20 MB

    private final AttachmentProcessingService processingService;
    private final ChatAttachmentRepository    repository;

    public AttachmentController(AttachmentProcessingService processingService,
                                 ChatAttachmentRepository repository) {
        this.processingService = processingService;
        this.repository        = repository;
    }

    /**
     * POST /chat/attachments
     * Upload a file (multipart/form-data). Returns attachment metadata and
     * a preview suitable for display in the chat UI.
     *
     * Form fields:
     *   file           — the uploaded file (required)
     *   conversationId — the conversation this attachment belongs to (optional)
     *
     * Response:
     * {
     *   "attachment_key":    "att-abc123",
     *   "file_name":         "invoice_march.pdf",
     *   "attachment_type":   "DOCUMENT",
     *   "file_size_bytes":   24576,
     *   "summary":           "Document: invoice_march.pdf — 38 lines extracted",
     *   "thumbnail_base64":  "...",   // only for images
     *   "expires_at":        "2026-05-20T09:00:00Z"
     * }
     */
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "conversationId", required = false) String conversationId) {

        if (file == null || file.isEmpty()) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "File must not be empty");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new NexusException(HttpStatus.BAD_REQUEST,
                    "File exceeds the 20 MB limit (" + (file.getSize() / 1_048_576) + " MB uploaded)");
        }

        String userEmail = currentUserEmail();
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not read uploaded file: " + e.getMessage());
        }

        ChatAttachment attachment = processingService.process(
                file.getOriginalFilename(),
                file.getContentType(),
                bytes,
                conversationId,
                userEmail);

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(attachment));
    }

    /**
     * GET /chat/attachments/{attachmentKey}
     * Retrieve metadata for a previously uploaded attachment.
     * The extracted_text field is omitted from this response (it is only
     * used server-side in the LLM context).
     */
    @GetMapping("/{attachmentKey}")
    public ResponseEntity<Map<String, Object>> getAttachment(
            @PathVariable String attachmentKey) {
        ChatAttachment att = repository.findByKey(attachmentKey)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "Attachment not found: " + attachmentKey));
        return ResponseEntity.ok(toResponse(att));
    }

    /**
     * GET /chat/attachments?conversationId=
     * Lists all non-expired attachments for a conversation.
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listByConversation(
            @RequestParam String conversationId) {
        List<Map<String, Object>> list = repository.findByConversation(conversationId)
                .stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(list);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> toResponse(ChatAttachment a) {
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("attachment_key",    a.attachmentKey());
        map.put("file_name",         a.fileName());
        map.put("attachment_type",   a.attachmentType());
        map.put("mime_type",         a.mimeType());
        map.put("file_size_bytes",   a.fileSizeBytes());
        map.put("summary",           a.summary());
        map.put("thumbnail_base64",  a.thumbnailBase64()); // null for non-images
        map.put("expires_at",        a.expiresAt());
        return map;
    }

    private String currentUserEmail() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof UserAccount u) return u.email();
        throw new NexusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
    }
}
