package com.sei.nexus.chat;

import com.sei.nexus.auth.UserAccount;
import com.sei.nexus.common.Keys;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.knowledge.KnowledgeGap;
import com.sei.nexus.knowledge.KnowledgeGapRepository;
import com.sei.nexus.query.QueryExecution;
import com.sei.nexus.query.QueryExecutionRepository;
import com.sei.nexus.run.RunRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
public class ChatController {

    private final ChatService chatService;
    private final RunRepository runRepository;
    private final QueryExecutionRepository queryExecutionRepository;
    private final KnowledgeGapRepository knowledgeGapRepository;
    private final JdbcTemplate jdbc;

    public ChatController(ChatService chatService,
                          RunRepository runRepository,
                          QueryExecutionRepository queryExecutionRepository,
                          KnowledgeGapRepository knowledgeGapRepository,
                          JdbcTemplate jdbc) {
        this.chatService = chatService;
        this.runRepository = runRepository;
        this.queryExecutionRepository = queryExecutionRepository;
        this.knowledgeGapRepository = knowledgeGapRepository;
        this.jdbc = jdbc;
    }

    // ── POST /chat/ask ────────────────────────────────────────────────────────

    @PostMapping("/chat/ask")
    public ResponseEntity<ChatResponse> ask(@RequestBody ChatRequest request) {
        String userEmail = currentUserEmail();
        ChatResponse response = chatService.ask(request, userEmail);
        return ResponseEntity.ok(response);
    }

    // ── POST /chat/runs/{runKey}/feedback ─────────────────────────────────────

    @PostMapping("/chat/runs/{runKey}/feedback")
    public ResponseEntity<Map<String, String>> feedback(
            @PathVariable String runKey,
            @RequestBody Map<String, Object> body) {
        String userEmail = currentUserEmail();
        String rating = (String) body.get("rating");
        String comment = (String) body.getOrDefault("comment", "");

        if (rating == null || rating.isBlank()) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "rating is required");
        }

        // Verify run belongs to this user
        runRepository.findByKey(runKey)
                .filter(r -> userEmail.equals(r.userEmail()))
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "Run not found or not accessible: " + runKey));

        // Save evidence
        String evidenceKey = Keys.uniqueKey("ev");
        runRepository.saveEvidence(evidenceKey, runKey, "FEEDBACK",
                "{\"rating\":\"" + rating + "\",\"comment\":" + jsonString(comment) + "}");

        // If KNOWLEDGE_GAP rating, create a knowledge gap record
        if ("KNOWLEDGE_GAP".equalsIgnoreCase(rating)) {
            String gapKey = Keys.uniqueKey("gap");
            KnowledgeGap gap = new KnowledgeGap(gapKey, null, "USER_FEEDBACK_GAP", runKey,
                    comment.isBlank() ? "User flagged knowledge gap" : comment,
                    "User rated answer as knowledge gap.",
                    null, "OPEN", null, null, null, null);
            knowledgeGapRepository.save(gap);
        }

        return ResponseEntity.ok(Map.of("run_key", runKey, "rating", rating, "status", "recorded"));
    }

    // ── GET /chat/conversations ───────────────────────────────────────────────

    @GetMapping("/chat/conversations")
    public ResponseEntity<List<Map<String, Object>>> listConversations() {
        String userEmail = currentUserEmail();
        List<Map<String, Object>> conversations = runRepository.findConversations(userEmail, 3);
        return ResponseEntity.ok(conversations);
    }

    // ── GET /chat/conversations/{conversationId} ──────────────────────────────

    @GetMapping("/chat/conversations/{conversationId}")
    public ResponseEntity<List<com.sei.nexus.run.NexusRun>> getConversation(
            @PathVariable String conversationId) {
        return ResponseEntity.ok(runRepository.findConversationRuns(conversationId, 50));
    }

    // ── DELETE /chat/conversations/{conversationId} ───────────────────────────

    @DeleteMapping("/chat/conversations/{conversationId}")
    public ResponseEntity<Map<String, String>> deleteConversation(
            @PathVariable String conversationId) {
        String userEmail = currentUserEmail();
        // Soft delete: mark runs as deleted status, or use hard delete restricted to user
        int deleted = jdbc.update(
                "DELETE FROM nexus_run WHERE conversation_id = ? AND user_email = ?",
                conversationId, userEmail);
        return ResponseEntity.ok(Map.of(
                "conversation_id", conversationId,
                "runs_deleted", String.valueOf(deleted)));
    }

    // ── PATCH /chat/conversations/{conversationId}/pin ────────────────────────

    @PatchMapping("/chat/conversations/{conversationId}/pin")
    public ResponseEntity<Map<String, Object>> pinConversation(
            @PathVariable String conversationId,
            @RequestBody Map<String, Object> body) {
        String userEmail = currentUserEmail();
        Boolean pinned = (Boolean) body.get("pinned");
        if (pinned == null) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "pinned (boolean) is required");
        }
        if (Boolean.TRUE.equals(pinned)) {
            jdbc.update(
                    "INSERT INTO nexus_conversation_pin (conversation_id, user_email, pinned_at) " +
                    "VALUES (?, ?, NOW()) ON CONFLICT (conversation_id, user_email) DO NOTHING",
                    conversationId, userEmail);
        } else {
            jdbc.update(
                    "DELETE FROM nexus_conversation_pin WHERE conversation_id = ? AND user_email = ?",
                    conversationId, userEmail);
        }
        return ResponseEntity.ok(Map.of(
                "conversation_id", conversationId,
                "pinned", pinned));
    }

    // ── GET /chat/async/{executionKey} ────────────────────────────────────────

    @GetMapping("/chat/async/{executionKey}")
    public ResponseEntity<QueryExecution> getAsyncExecution(
            @PathVariable String executionKey) {
        String userEmail = currentUserEmail();
        QueryExecution qe = queryExecutionRepository.findByKey(executionKey)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "Execution not found: " + executionKey));

        // Verify the run belongs to the current user
        runRepository.findByKey(qe.runKey())
                .filter(r -> userEmail.equals(r.userEmail()))
                .orElseThrow(() -> new NexusException(HttpStatus.FORBIDDEN,
                        "Access denied to execution: " + executionKey));

        return ResponseEntity.ok(qe);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String currentUserEmail() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof UserAccount ua) return ua.email();
        return principal.toString();
    }

    private String jsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
