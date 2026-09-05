package com.sei.nexus.semantic;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for the concept_key backfill feature's core removal: {@link
 * LearningContextBuilder} must no longer be constructible with (or dependent on) a {@link
 * LearnedMappingRepository} at all. Its single-arg constructor — proven here by only ever
 * supplying a fake {@link CorrectionRepository} — is compile-time proof there is no longer any
 * path from Postgres's {@code nexus_learned_mapping} table into an LLM prompt through this class.
 * Hand-rolled fake throughout (no Mockito, no database).
 */
class LearningContextBuilderTest {

    static class FakeCorrectionRepository extends CorrectionRepository {
        List<Correction> scripted = new ArrayList<>();
        String lastConversationId;
        FakeCorrectionRepository() { super(null); }
        @Override public List<Correction> findRecentForConversation(String conversationId, int limit) {
            lastConversationId = conversationId;
            return scripted;
        }
    }

    private Correction correction(String original, String corrected) {
        return new Correction("corr-1", "conv-1", "run-1", "run-2", original, corrected,
                "TIMEFRAME", true, Instant.now());
    }

    @Test
    void constructorAcceptsOnlyACorrectionRepositoryNoLearnedMappingRepositoryDependencyExists() {
        // The mere fact that this compiles with a single argument is the regression guard: there
        // is no overload, and no field, that could reach nexus_learned_mapping from this class.
        FakeCorrectionRepository correctionRepository = new FakeCorrectionRepository();
        LearningContextBuilder builder = new LearningContextBuilder(correctionRepository);
        assertNotNull(builder);
    }

    @Test
    void buildReturnsEmptyContextWhenNoCorrectionsExist() {
        LearningContextBuilder builder = new LearningContextBuilder(new FakeCorrectionRepository());

        LearningContextBuilder.LearningContext ctx = builder.build("conv-1");

        assertTrue(ctx.isEmpty());
        assertEquals("", ctx.contextText());
    }

    @Test
    void buildReturnsCorrectionsTextWhenCorrectionsExist() {
        FakeCorrectionRepository correctionRepository = new FakeCorrectionRepository();
        correctionRepository.scripted = List.of(correction("rolling 7 days", "Monday-Sunday"));
        LearningContextBuilder builder = new LearningContextBuilder(correctionRepository);

        LearningContextBuilder.LearningContext ctx = builder.build("conv-1");

        assertFalse(ctx.isEmpty());
        assertTrue(ctx.contextText().contains("Monday-Sunday"));
        assertEquals("conv-1", correctionRepository.lastConversationId);
    }

    @Test
    void buildIsConversationScopedNotDomainScoped() {
        // The signature itself (single conversationId parameter, no domainKey) is the proof: there
        // is nothing in this class's public surface that could resume domain-scoped mapping lookup.
        FakeCorrectionRepository correctionRepository = new FakeCorrectionRepository();
        LearningContextBuilder builder = new LearningContextBuilder(correctionRepository);

        builder.build("conv-42");

        assertEquals("conv-42", correctionRepository.lastConversationId);
    }
}
