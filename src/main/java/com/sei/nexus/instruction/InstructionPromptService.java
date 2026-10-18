package com.sei.nexus.instruction;

import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.LlmCallTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Chooses which approved instructions go into a question's prompt, so the prompt stays the same size
 * however many instructions exist: the always-apply instructions (capped) plus the few approved
 * instructions nearest to the question by embedding similarity, above a floor, within a character
 * budget. Which of them actually applies is left to the model reading the prompt; this class only
 * narrows. A tenant with no approved instructions costs nothing (no embedding call).
 */
@Service
public class InstructionPromptService {

    private static final Logger log = LoggerFactory.getLogger(InstructionPromptService.class);

    static final String HEADER = "ORGANISATION INSTRUCTIONS - standing preferences approved by this "
            + "organisation. Follow each one that applies to this question; ignore the others:";

    private final InstructionRepository repository;
    private final AzureOpenAiClient aiClient;
    private final int topK;
    private final double minSimilarity;
    private final int maxChars;

    public InstructionPromptService(InstructionRepository repository, AzureOpenAiClient aiClient,
                                    @Value("${nexus.instructions.top-k:5}") int topK,
                                    @Value("${nexus.instructions.min-similarity:0.30}") double minSimilarity,
                                    @Value("${nexus.instructions.max-chars:2000}") int maxChars) {
        this.repository = repository;
        this.aiClient = aiClient;
        this.topK = topK;
        this.minSimilarity = minSimilarity;
        this.maxChars = maxChars;
    }

    /** The prompt block for this question, or "" when no instruction applies (or on any failure). */
    public String forQuestion(String question) {
        try {
            int approved = repository.countApproved();
            if (approved == 0) return "";
            List<String> always = repository.findAlwaysApplied(InstructionService.MAX_ALWAYS_APPLY);
            List<String> related = List.of();
            if (question != null && !question.isBlank() && approved > always.size()) {
                LlmCallTag.set("INSTRUCTION_RETRIEVAL_EMBEDDING");
                float[] embedding = aiClient.embed(question).embedding();
                related = repository.searchNearest(embedding, topK, minSimilarity).stream()
                        .map(InstructionRepository.Scored::instructionText).toList();
            }
            return render(always, related, maxChars);
        } catch (Exception e) {
            // Instructions refine an answer; losing them must not fail the question.
            log.warn("Could not load organisation instructions; answering without them: {}", e.getMessage());
            return "";
        }
    }

    /** Always-apply first, then related; duplicates dropped; stops before exceeding the budget. */
    static String render(List<String> always, List<String> related, int maxChars) {
        List<String> ordered = new ArrayList<>();
        for (String s : always) if (!ordered.contains(s)) ordered.add(s);
        for (String s : related) if (!ordered.contains(s)) ordered.add(s);
        StringBuilder sb = new StringBuilder();
        int used = HEADER.length();
        int included = 0;
        for (String s : ordered) {
            String line = "\n- " + s;
            if (used + line.length() > maxChars) break;
            sb.append(line);
            used += line.length();
            included++;
        }
        return included == 0 ? "" : HEADER + sb;
    }
}
