package com.sei.nexus.response;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AnswerFaithfulness}: a second, narrow LLM judgment compares a composed answer to its
 * evidence; only a finding triggers one re-composition. Java orchestrates — it never edits an
 * answer or judges truth itself. These tests script the checker's verdict (hand-rolled fake
 * composer, no network) to prove the orchestration, its bounds, and that it fails open.
 */
class AnswerFaithfulnessTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A composer whose every call returns the next scripted string (the last repeats); "THROW" fails. */
    static class ScriptedComposer extends NaturalLanguageComposer {
        final List<String> script;
        final List<String> prompts = new ArrayList<>();
        int calls = 0;
        ScriptedComposer(String... script) { super(null); this.script = List.of(script); }
        @Override public String compose(CompositionRequest req) {
            prompts.add(req.userPrompt());
            String next = script.get(Math.min(calls++, script.size() - 1));
            if ("THROW".equals(next)) throw new RuntimeException("simulated model failure");
            return next;
        }
    }

    private static String verdict(boolean consistent, String issues) {
        return "{\"consistent\":" + consistent + ",\"issues\":\"" + issues + "\"}";
    }

    // ── parse(): only a clear, specific inconsistency is acted on ───────────────────────────────

    @Test
    void aClearInconsistencyWithNamedIssuesIsReported() {
        AnswerFaithfulness.Verdict v = AnswerFaithfulness.parse(MAPPER, verdict(false, "says three, evidence shows five"));
        assertFalse(v.consistent());
        assertEquals("says three, evidence shows five", v.issues());
    }

    @Test
    void consistentTrueIsOk() {
        assertTrue(AnswerFaithfulness.parse(MAPPER, verdict(true, "")).consistent());
    }

    @Test
    void anInconsistentVerdictThatNamesNothingGivesTheWriterNothingToFixSoItIsTreatedAsOk() {
        assertTrue(AnswerFaithfulness.parse(MAPPER, verdict(false, "")).consistent());
        assertTrue(AnswerFaithfulness.parse(MAPPER, verdict(false, "   ")).consistent());
    }

    @Test
    void malformedMissingOrNullInputIsOkFailOpen() {
        for (String bad : new String[]{null, "", "not json", "{", "{\"issues\":\"x\"}", "{\"consistent\":\"maybe\",\"issues\":\"x\"}"}) {
            assertTrue(AnswerFaithfulness.parse(MAPPER, bad).consistent(), "must fail open for: " + bad);
        }
    }

    // ── ensure(): bounded orchestration ─────────────────────────────────────────────────────────

    @Test
    void aConsistentAnswerIsReturnedUntouchedWithNoRecomposition() {
        ScriptedComposer composer = new ScriptedComposer(verdict(true, ""));
        AtomicInteger recomposed = new AtomicInteger();

        String out = AnswerFaithfulness.ensure(composer, MAPPER, "q", "evidence", "answer", "FIRST",
                note -> { recomposed.incrementAndGet(); return "SECOND"; });

        assertEquals("FIRST", out);
        assertEquals(0, recomposed.get());
        assertEquals(1, composer.calls, "exactly one checker call");
    }

    @Test
    void anInconsistentAnswerIsRecomposedExactlyOnceWithTheFindingStatedAsAnObservation() {
        ScriptedComposer composer = new ScriptedComposer(verdict(false, "answer says three; evidence shows five rows"));
        List<String> notes = new ArrayList<>();

        String out = AnswerFaithfulness.ensure(composer, MAPPER, "q", "evidence", "answer", "FIRST",
                note -> { notes.add(note); return "SECOND"; });

        assertEquals("SECOND", out);
        assertEquals(1, notes.size(), "bounded: one re-composition, never a loop");
        assertEquals(1, composer.calls, "the corrected answer is not checked again");
        assertTrue(notes.get(0).contains("VERIFICATION FEEDBACK (not from the user)"));
        assertTrue(notes.get(0).contains("The reviewer can be mistaken"));
        assertTrue(notes.get(0).contains("answer says three; evidence shows five rows"),
                "the checker's own finding is relayed verbatim");
    }

    @Test
    void theCheckerSeesTheQuestionTheEvidenceTheAnswerAndTodaysDateAsAPlainFact() {
        ScriptedComposer composer = new ScriptedComposer(verdict(true, ""));

        AnswerFaithfulness.check(composer, MAPPER, "How many?", "EVIDENCE-TEXT", "ANSWER-TEXT",
                java.time.LocalDate.of(2026, 10, 18));

        String prompt = composer.prompts.get(0);
        assertTrue(prompt.contains("QUESTION:\nHow many?"));
        assertTrue(prompt.contains("TODAY: 2026-10-18"), "the one runtime fact it cannot know itself");
        assertTrue(prompt.contains("EVIDENCE:\nEVIDENCE-TEXT"));
        assertTrue(prompt.contains("ANSWER:\nANSWER-TEXT"));
    }

    @Test
    void withNoDateGivenTheTodayLineIsOmittedAndNothingIsInvented() {
        ScriptedComposer composer = new ScriptedComposer(verdict(true, ""));

        AnswerFaithfulness.check(composer, MAPPER, "q", "e", "a", null);

        assertFalse(composer.prompts.get(0).contains("TODAY:"));
    }

    @Test
    void thePublicCheckSuppliesTheCurrentDate() {
        ScriptedComposer composer = new ScriptedComposer(verdict(true, ""));

        AnswerFaithfulness.check(composer, MAPPER, "q", "e", "a");

        assertTrue(composer.prompts.get(0).contains("TODAY: " + java.time.LocalDate.now()));
    }

    @Test
    void theCheckerIsToldNeverToInferTodayFromDatesInsideTheEvidence() {
        // Observed live: with no date, the checker called a past date "not in the past" and the
        // re-composition then turned a correct answer into a wrong one.
        String p = AnswerFaithfulness.SYSTEM_PROMPT;
        assertTrue(p.contains("never infer it"));
        assertTrue(p.contains("from the dates inside the EVIDENCE"));
        assertTrue(p.contains("ONLY against the TODAY value provided"));
        assertTrue(p.contains("do not judge it at all"));
    }

    @Test
    void theCorrectionNoteIsAdvisoryTheWriterKeepsAnythingTheEvidenceSupports() {
        String note = AnswerFaithfulness.correctionNote(new AnswerFaithfulness.Verdict(false, "says three"));

        assertTrue(note.contains("possible problems: says three"), "the finding is relayed verbatim");
        assertTrue(note.contains("The reviewer can be mistaken"));
        assertTrue(note.contains("correct a statement ONLY if the evidence contradicts it"));
        assertTrue(note.contains("keep any statement the evidence supports"));
        assertFalse(note.contains("did not match it"), "no longer asserts the reviewer is right");
    }

    @Test
    void aFailingCheckerKeepsTheOriginalAnswerAndNeverThrows() {
        ScriptedComposer composer = new ScriptedComposer("THROW");

        String out = AnswerFaithfulness.ensure(composer, MAPPER, "q", "e", "a", "FIRST", note -> "SECOND");

        assertEquals("FIRST", out, "a verification outage must never block or degrade answering");
    }

    @Test
    void aFailingRecompositionKeepsTheOriginalAnswer() {
        ScriptedComposer composer = new ScriptedComposer(verdict(false, "wrong count"));

        String out = AnswerFaithfulness.ensure(composer, MAPPER, "q", "e", "a", "FIRST",
                note -> { throw new RuntimeException("model down"); });

        assertEquals("FIRST", out);
    }

    @Test
    void aNullRecompositionKeepsTheOriginalAnswer() {
        ScriptedComposer composer = new ScriptedComposer(verdict(false, "wrong count"));

        assertEquals("FIRST", AnswerFaithfulness.ensure(composer, MAPPER, "q", "e", "a", "FIRST", note -> null));
    }

    @Test
    void anEmptyAnswerOrAMissingComposerIsNeverChecked() {
        ScriptedComposer composer = new ScriptedComposer(verdict(false, "x"));

        assertTrue(AnswerFaithfulness.check(composer, MAPPER, "q", "e", "").consistent());
        assertTrue(AnswerFaithfulness.check(composer, MAPPER, "q", "e", null).consistent());
        assertTrue(AnswerFaithfulness.check(null, MAPPER, "q", "e", "answer").consistent());
        assertEquals(0, composer.calls);
    }

    // ── contract ────────────────────────────────────────────────────────────────────────────────

    @Test
    void theSchemaRequiresBothFieldsAndForbidsExtras() {
        var schema = AnswerFaithfulness.verdictJsonSchema();
        assertEquals(List.of("consistent", "issues"), schema.get("required"));
        assertEquals(Boolean.FALSE, schema.get("additionalProperties"));
    }

    @Test
    void thePromptIsDomainNeutralAndTellsTheCheckerNotToGuessAtWhatItCannotVerify() {
        String p = AnswerFaithfulness.SYSTEM_PROMPT.toLowerCase(Locale.ROOT);
        for (String w : List.of("supplier", "purchase order", "retail", "product", "warehouse",
                "inventory", "invoice", "patient", "shipment", "store")) {
            assertFalse(Pattern.compile("\\b" + Pattern.quote(w) + "\\b").matcher(p).find(),
                    "prompt must not mention '" + w + "'");
        }
        assertTrue(p.contains("do not flag"));
        assertTrue(p.contains("cannot verify"));
    }
}
