package com.sei.nexus.chat;

import com.sei.nexus.agentbrain.PromptContext;
import com.sei.nexus.memory.DocumentChunk;
import com.sei.nexus.reasoning.OperationalFinding;
import com.sei.nexus.run.NexusRun;
import com.sei.nexus.semantic.LearnedMapping;
import com.sei.nexus.semantic.ResolvedQuestion;
import com.sei.nexus.semantic.SemanticLearningService;
import com.sei.nexus.semantic.SemanticService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concept-Key Semantic Anchor design — the new "LEARNED BUSINESS KNOWLEDGE FOR THIS CONCEPT"
 * context section {@code ChatService} renders from {@code
 * ResolvedBusinessModel#resolvedConceptKeys()}. Exercised directly against the private {@code
 * buildContextSummary} method via reflection (this repo's hand-rolled-fake convention — no
 * Mockito, no Spring context, no database), with every other {@code ChatService} dependency left
 * {@code null} since this section's rendering depends only on {@code resolvedConceptKeys} and
 * {@code semanticLearningService}.
 *
 * <p>What this deliberately proves: the section is EVIDENCE ONLY — every returned mapping is
 * rendered verbatim, Java never ranks, chooses, or filters among them beyond the exact-key lookup
 * already covered by {@code LearnedMappingRepositoryConceptKeyTest}; a request with no
 * concept-scoped learning renders no section at all (the same zero-cost guarantee RESOLUTIONS/
 * LITERAL CANDIDATES already follow).
 */
class ChatServiceConceptScopedLearnedKnowledgeContextTest {

    /** Scripts {@link SemanticLearningService#findPromotedByConceptKeys} without touching Postgres. */
    static class FakeSemanticLearningService extends SemanticLearningService {
        List<LearnedMapping> scripted = List.of();
        List<String> seenConceptKeys;
        FakeSemanticLearningService() { super(null, null, null, null, null, null, null, null); }
        @Override public List<LearnedMapping> findPromotedByConceptKeys(List<String> conceptKeys) {
            seenConceptKeys = conceptKeys;
            return scripted;
        }
    }

    private static LearnedMapping mapping(String term, String sqlPattern, String conceptKey) {
        Instant now = Instant.now();
        return new LearnedMapping("m-" + term, "domain", term, sqlPattern, "run-1", "QUERY_SUCCESS",
                0.9, 12, now, true, now, now, conceptKey);
    }

    private static ChatService chatServiceWith(FakeSemanticLearningService learningService) {
        // 36-arg constructor — every dependency this section's rendering does not touch is null.
        return new ChatService(
                null, null, null, null, null, null, null, null, null, null, null, null, // 1-12
                null, null, null, null, null, null, null, null, null, null,             // 13-22
                learningService,                                                        // 23
                null, null, null, null, null, null, null, null, null, null,             // 24-33
                null, null, null);                                                      // 34-36
    }

    /** Reflectively invokes the 14-arg {@code buildContextSummary} overload that carries
     *  {@code resolvedConceptKeys}. */
    private static String buildContext(ChatService chatService, List<String> resolvedConceptKeys) throws Exception {
        Method m = ChatService.class.getDeclaredMethod("buildContextSummary",
                String.class, List.class, PromptContext.class, SemanticService.SemanticContext.class,
                List.class, String.class, boolean.class, List.class, com.sei.nexus.agent.NexusAgent.class,
                ResolvedQuestion.class, String.class, boolean.class, java.util.Set.class, List.class);
        m.setAccessible(true);
        return (String) m.invoke(chatService,
                "show me all open orders", List.<DocumentChunk>of(), new PromptContext(List.of()), null,
                List.<OperationalFinding>of(), "", false, List.<NexusRun>of(), null,
                ResolvedQuestion.empty("show me all open orders"), null, true, java.util.Set.<String>of(),
                resolvedConceptKeys);
    }

    @Test
    void noConceptScopedMappingsProducesNoNewSection() throws Exception {
        FakeSemanticLearningService learning = new FakeSemanticLearningService();
        learning.scripted = List.of();
        ChatService chatService = chatServiceWith(learning);

        String ctx = buildContext(chatService, List.of("purchase-order"));

        assertFalse(ctx.contains("LEARNED BUSINESS KNOWLEDGE"),
                "no promoted mapping for the resolved concept ⇒ no section rendered at all");
    }

    @Test
    void emptyResolvedConceptKeysNeverQueriesLearningAndRendersNoSection() throws Exception {
        FakeSemanticLearningService learning = new FakeSemanticLearningService();
        ChatService chatService = chatServiceWith(learning);

        String ctx = buildContext(chatService, List.of());

        assertNull(learning.seenConceptKeys, "no resolved concept keys ⇒ the lookup must never even run");
        assertFalse(ctx.contains("LEARNED BUSINESS KNOWLEDGE"));
    }

    @Test
    void renderedSectionPreservesEveryReturnedMappingVerbatim() throws Exception {
        FakeSemanticLearningService learning = new FakeSemanticLearningService();
        learning.scripted = List.of(
                mapping("open orders", "status IN ('draft','submitted','acknowledged','partially_received')", "purchase-order"),
                mapping("urgent orders", "priority = 'HIGH'", "purchase-order"),
                mapping("late orders", "due_date < CURRENT_DATE", "purchase-order"));
        ChatService chatService = chatServiceWith(learning);

        String ctx = buildContext(chatService, List.of("purchase-order"));

        assertEquals(List.of("purchase-order"), learning.seenConceptKeys,
                "the exact, already-resolved concept key(s) must be passed through unmodified");
        assertTrue(ctx.contains("=== LEARNED BUSINESS KNOWLEDGE FOR THIS CONCEPT ==="));
        assertTrue(ctx.contains("Concept: purchase-order"));
        // All three mappings rendered — Java never selects/ranks/filters among them.
        assertTrue(ctx.contains("Business term: open orders"));
        assertTrue(ctx.contains("status IN ('draft','submitted','acknowledged','partially_received')"));
        assertTrue(ctx.contains("Business term: urgent orders"));
        assertTrue(ctx.contains("priority = 'HIGH'"));
        assertTrue(ctx.contains("Business term: late orders"));
        assertTrue(ctx.contains("due_date < CURRENT_DATE"));
    }

    @Test
    void sectionIsExplicitlyLabeledAsEvidenceNotAnAppliedDecision() throws Exception {
        FakeSemanticLearningService learning = new FakeSemanticLearningService();
        learning.scripted = List.of(mapping("open orders", "status IN (...)", "purchase-order"));
        ChatService chatService = chatServiceWith(learning);

        String ctx = buildContext(chatService, List.of("purchase-order"));

        assertTrue(ctx.contains("EVIDENCE"),
                "the section must tell the model these are candidates to evaluate, not an already-applied resolution");
        assertFalse(ctx.toLowerCase().contains("therefore use"),
                "Java must never instruct the model to use a specific mapping");
    }

    @Test
    void regressionQuestionProducesConceptScopedEvidenceInThePrompt() throws Exception {
        // The exact reported scenario: "show me all open orders" resolves to concept
        // purchase-order at Stage 1, and the learned "open purchase orders" evidence must reach
        // the prompt — without Java ever asserting that the two phrases mean the same thing.
        FakeSemanticLearningService learning = new FakeSemanticLearningService();
        learning.scripted = List.of(
                mapping("open purchase orders", "status IN ('draft','submitted','acknowledged','partially_received')",
                        "purchase-order"));
        ChatService chatService = chatServiceWith(learning);

        String ctx = buildContext(chatService, List.of("purchase-order"));

        assertTrue(ctx.contains("Business term: open purchase orders"));
        assertFalse(ctx.contains("\"open orders\" means \"open purchase orders\""),
                "Java must never itself assert the semantic equivalence — that is Agent Brain's decision");
    }
}
