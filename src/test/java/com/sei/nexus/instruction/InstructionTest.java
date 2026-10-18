package com.sei.nexus.instruction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.EmbeddingResult;
import com.sei.nexus.common.NexusException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstructionTest {

    // ── fakes ────────────────────────────────────────────────────────────────

    private static final class FakeAi extends AzureOpenAiClient {
        int embeds = 0;
        boolean fail = false;
        FakeAi() { super(new ObjectMapper(), null); }
        @Override public EmbeddingResult embed(String text) {
            embeds++;
            if (fail) throw new RuntimeException("embedding down");
            return new EmbeddingResult(new float[]{1f}, 1);
        }
    }

    private static final class FakeRepo extends InstructionRepository {
        final List<Instruction> rows = new ArrayList<>();
        List<String> always = List.of();
        List<Scored> nearest = List.of();
        int approvedCount = 0;
        int alwaysCount = 0;
        int approves = 0;
        FakeRepo() { super(null); }
        @Override public void insert(String id, String text, String by) {
            rows.add(new Instruction(id, text, "PENDING", false, by, null, null, null));
        }
        @Override public Optional<Instruction> findById(String id) {
            return rows.stream().filter(r -> r.instructionId().equals(id)).findFirst();
        }
        @Override public int approve(String id, String text, boolean a, float[] e, String r) {
            approves++;
            return 1;
        }
        @Override public int countApproved() { return approvedCount; }
        @Override public int countApprovedAlways() { return alwaysCount; }
        @Override public List<String> findAlwaysApplied(int limit) { return always; }
        @Override public List<Scored> searchNearest(float[] q, int k, double min) { return nearest; }
    }

    private static Instruction pending(String id, String text) {
        return new Instruction(id, text, "PENDING", false, "u", null, null, null);
    }

    // ── submission ───────────────────────────────────────────────────────────

    @Test
    void submitStoresATrimmedPendingInstruction() {
        FakeRepo repo = new FakeRepo();
        Instruction i = new InstructionService(repo, new FakeAi()).submit("  show descriptions  ", "a@b.com");
        assertEquals("show descriptions", i.instructionText());
        assertEquals("PENDING", i.status());
        assertEquals("a@b.com", i.submittedBy());
    }

    @Test
    void blankAndOverlongTextAreRejected() {
        InstructionService svc = new InstructionService(new FakeRepo(), new FakeAi());
        assertThrows(NexusException.class, () -> svc.submit("  ", "u"));
        assertThrows(NexusException.class, () -> svc.submit("x".repeat(InstructionService.MAX_TEXT_CHARS + 1), "u"));
    }

    // ── approval ─────────────────────────────────────────────────────────────

    @Test
    void approvalEmbedsTheFinalText() {
        FakeRepo repo = new FakeRepo();
        repo.rows.add(pending("i1", "show descriptions"));
        FakeAi ai = new FakeAi();
        new InstructionService(repo, ai).approve("i1", null, false, "admin");
        assertEquals(1, ai.embeds);
        assertEquals(1, repo.approves);
    }

    @Test
    void ifEmbeddingFailsTheInstructionIsNotApproved() {
        FakeRepo repo = new FakeRepo();
        repo.rows.add(pending("i1", "show descriptions"));
        FakeAi ai = new FakeAi();
        ai.fail = true;
        assertThrows(NexusException.class, () -> new InstructionService(repo, ai).approve("i1", null, false, "admin"));
        assertEquals(0, repo.approves);
    }

    @Test
    void alwaysApplyIsCapped() {
        FakeRepo repo = new FakeRepo();
        repo.rows.add(pending("i1", "x"));
        repo.alwaysCount = InstructionService.MAX_ALWAYS_APPLY;
        assertThrows(NexusException.class, () -> new InstructionService(repo, new FakeAi()).approve("i1", null, true, "admin"));
        assertEquals(0, repo.approves);
        // a normal (non-always) approval is still allowed at the cap
        new InstructionService(repo, new FakeAi()).approve("i1", null, false, "admin");
        assertEquals(1, repo.approves);
    }

    // ── what reaches the prompt ──────────────────────────────────────────────

    @Test
    void noApprovedInstructionsMeansNoBlockAndNoEmbeddingCall() {
        FakeAi ai = new FakeAi();
        assertEquals("", new InstructionPromptService(new FakeRepo(), ai, 5, 0.3, 2000).forQuestion("q"));
        assertEquals(0, ai.embeds);
    }

    @Test
    void alwaysApplyAndRelatedInstructionsAreRenderedAlwaysFirst() {
        FakeRepo repo = new FakeRepo();
        repo.approvedCount = 3;
        repo.always = List.of("answer in USD");
        repo.nearest = List.of(new InstructionRepository.Scored("show item description", 0.6));
        String block = new InstructionPromptService(repo, new FakeAi(), 5, 0.3, 2000).forQuestion("show order lines");
        assertTrue(block.startsWith(InstructionPromptService.HEADER));
        assertTrue(block.indexOf("answer in USD") < block.indexOf("show item description"));
    }

    @Test
    void onlyAlwaysApplyInstructionsMeansNoEmbeddingCall() {
        FakeRepo repo = new FakeRepo();
        repo.approvedCount = 1;
        repo.always = List.of("answer in USD");
        FakeAi ai = new FakeAi();
        new InstructionPromptService(repo, ai, 5, 0.3, 2000).forQuestion("q");
        assertEquals(0, ai.embeds);
    }

    @Test
    void theBlockNeverExceedsTheBudgetHoweverManyInstructionsMatch() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 50; i++) many.add("instruction number " + i + " " + "y".repeat(80));
        String block = InstructionPromptService.render(List.of(), many, 600);
        assertTrue(block.length() <= 600, "length " + block.length());
        assertTrue(block.contains("instruction number 0"));
        assertFalse(block.contains("instruction number 49"));
    }

    @Test
    void duplicatesAreSentOnce() {
        String block = InstructionPromptService.render(List.of("a"), List.of("a", "b"), 2000);
        assertEquals(2, block.split("\n- ").length - 1);
    }

    @Test
    void aFailureLoadingInstructionsNeverFailsTheQuestion() {
        FakeRepo repo = new FakeRepo();
        repo.approvedCount = 2;
        FakeAi ai = new FakeAi();
        ai.fail = true;
        assertEquals("", new InstructionPromptService(repo, ai, 5, 0.3, 2000).forQuestion("q"));
    }
}
