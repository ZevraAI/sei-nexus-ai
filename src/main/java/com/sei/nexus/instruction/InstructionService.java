package com.sei.nexus.instruction;

import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.LlmCallTag;
import com.sei.nexus.common.Keys;
import com.sei.nexus.common.NexusException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Lifecycle of standing instructions: a user submits (PENDING); an admin or domain owner approves
 * (embedding stored, optionally "always apply") or archives. Only APPROVED instructions are ever
 * read by a prompt.
 */
@Service
public class InstructionService {

    static final int MAX_TEXT_CHARS = 500;
    /** Cap on always-apply instructions: they are sent with EVERY question, so they stay few. */
    public static final int MAX_ALWAYS_APPLY = 5;

    private final InstructionRepository repository;
    private final AzureOpenAiClient aiClient;

    public InstructionService(InstructionRepository repository, AzureOpenAiClient aiClient) {
        this.repository = repository;
        this.aiClient = aiClient;
    }

    public Instruction submit(String text, String submittedBy) {
        String clean = validatedText(text);
        String id = Keys.uniqueKey("ins");
        repository.insert(id, clean, submittedBy);
        return repository.findById(id).orElseThrow();
    }

    public List<Instruction> list(String status) {
        return repository.findByStatus(status == null || status.isBlank()
                ? Instruction.PENDING : status.toUpperCase());
    }

    /** Approve, optionally with edited wording. Embeds the final text; fails (and does not approve) if that fails. */
    public Instruction approve(String id, String editedText, boolean alwaysApply, String reviewer) {
        Instruction current = repository.findById(id).orElseThrow(() ->
                new NexusException(HttpStatus.NOT_FOUND, "Instruction not found: " + id));
        if (Instruction.ARCHIVED.equals(current.status())) {
            throw new NexusException(HttpStatus.CONFLICT, "An archived instruction cannot be approved");
        }
        String text = editedText == null || editedText.isBlank()
                ? current.instructionText() : validatedText(editedText);
        boolean alreadyAlways = Instruction.APPROVED.equals(current.status()) && current.alwaysApply();
        if (alwaysApply && !alreadyAlways && repository.countApprovedAlways() >= MAX_ALWAYS_APPLY) {
            throw new NexusException(HttpStatus.CONFLICT, "At most " + MAX_ALWAYS_APPLY
                    + " instructions can be set to always apply; archive one first or approve this as a normal instruction");
        }
        float[] embedding;
        try {
            LlmCallTag.set("INSTRUCTION_EMBEDDING");
            embedding = aiClient.embed(text).embedding();
        } catch (Exception e) {
            throw new NexusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not embed the instruction, so it was not approved: " + e.getMessage());
        }
        repository.approve(id, text, alwaysApply, embedding, reviewer);
        return repository.findById(id).orElseThrow();
    }

    public void archive(String id, String reviewer) {
        if (repository.archive(id, reviewer) == 0) {
            throw new NexusException(HttpStatus.NOT_FOUND, "Instruction not found: " + id);
        }
    }

    static String validatedText(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "The instruction text is required");
        }
        if (t.length() > MAX_TEXT_CHARS) {
            throw new NexusException(HttpStatus.BAD_REQUEST,
                    "An instruction can be at most " + MAX_TEXT_CHARS + " characters; split it into separate instructions");
        }
        return t;
    }
}
