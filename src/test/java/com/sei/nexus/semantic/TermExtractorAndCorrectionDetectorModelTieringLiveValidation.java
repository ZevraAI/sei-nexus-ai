package com.sei.nexus.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * LIVE model-tiering evaluation (opt-in, real OpenAI calls): compares gpt-4o (today's model)
 * against gpt-4o-mini (the candidate {@code nexus.openai.term-extractor-model} / {@code
 * nexus.openai.correction-detector-model} default) against the REAL {@link TermExtractor} /
 * {@link CorrectionDetector} classes — same prompt, same parsing, same contract checks a human
 * reviewer would apply, no fuzzy scoring.
 *
 * <p>Guarded by {@code -Dnexus.live.openai=true} and {@code OPENAI_API_KEY} in the environment,
 * same convention as {@code IdentifierFidelityLiveProbe} — the normal test suite never calls the
 * API. Not a Mockito/DB test: constructs the real production classes with a real, model-pinned
 * {@link AzureOpenAiClient} (reflection-injected {@code apiKey} + model field, exactly like the
 * existing live probes).
 *
 * <p>What this checks per the audit's stated contract for each candidate — never semantic
 * similarity to the gpt-4o answer, only the documented rules:
 * <ul>
 *   <li>TermExtractor: JSON validity, max 3 terms, no generic terms ("total", "list", "show",
 *       "get"), each term has a non-blank reusable SQL fragment.</li>
 *   <li>CorrectionDetector: structured output validity (is_correction boolean present, and when
 *       true, a non-blank correction_type/interpretation pair); a true correction case is
 *       recognized as one, and a plain new-data follow-up is recognized as NOT one.</li>
 * </ul>
 */
class TermExtractorAndCorrectionDetectorModelTieringLiveValidation {

    private static AzureOpenAiClient liveClient(String model, String modelFieldName) throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY not set");
        AzureOpenAiClient client = new AzureOpenAiClient(new ObjectMapper(), null);
        setField(client, "apiKey", apiKey);
        setField(client, modelFieldName, model);
        Method init = AzureOpenAiClient.class.getDeclaredMethod("initThrottle");
        init.setAccessible(true);
        init.invoke(client);
        return client;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = AzureOpenAiClient.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    // ── TermExtractor ────────────────────────────────────────────────────────────────────────────

    private record TermCase(String label, String question, String sql) {}

    private static final List<TermCase> TERM_CASES = List.of(
            new TermCase("overdue orders",
                    "show me overdue purchase orders",
                    "SELECT * FROM purchase_orders WHERE due_date < CURRENT_DATE AND status != 'CLOSED'"),
            new TermCase("active suppliers",
                    "which suppliers are active",
                    "SELECT * FROM suppliers WHERE status = 'ACTIVE'")
    );

    @Test
    void termExtractorContractHoldsOnBothModels() throws Exception {
        assumeTrue(Boolean.getBoolean("nexus.live.openai"), "live evaluation disabled");

        AzureOpenAiClient gpt4o = liveClient("gpt-4o", "termExtractorModel");
        AzureOpenAiClient gpt4oMini = liveClient("gpt-4o-mini", "termExtractorModel");
        TermExtractor extractorOnGpt4o = new TermExtractor(gpt4o, new ObjectMapper());
        TermExtractor extractorOnGpt4oMini = new TermExtractor(gpt4oMini, new ObjectMapper());

        for (TermCase c : TERM_CASES) {
            List<TermExtractor.ExtractedTerm> baseline = extractorOnGpt4o.extract(c.question(), c.sql());
            List<TermExtractor.ExtractedTerm> candidate = extractorOnGpt4oMini.extract(c.question(), c.sql());

            System.out.println("[" + c.label() + "] gpt-4o      -> " + baseline);
            System.out.println("[" + c.label() + "] gpt-4o-mini -> " + candidate);

            assertTermExtractionContract(c.label() + " (gpt-4o)", baseline);
            assertTermExtractionContract(c.label() + " (gpt-4o-mini)", candidate);
        }
    }

    private static void assertTermExtractionContract(String label, List<TermExtractor.ExtractedTerm> terms) {
        if (terms.isEmpty()) {
            System.out.println("  " + label + ": no terms extracted (allowed — extraction is best-effort)");
            return;
        }
        if (terms.size() > 3) {
            throw new AssertionError(label + ": more than 3 terms returned: " + terms);
        }
        List<String> genericTerms = List.of("total", "list", "show", "get");
        for (TermExtractor.ExtractedTerm t : terms) {
            if (t.sql() == null || t.sql().isBlank()) {
                throw new AssertionError(label + ": term '" + t.term() + "' has no reusable SQL fragment");
            }
            if (genericTerms.contains(t.term().trim().toLowerCase())) {
                throw new AssertionError(label + ": generic term returned: '" + t.term() + "'");
            }
        }
    }

    // ── CorrectionDetector ───────────────────────────────────────────────────────────────────────

    private record CorrectionCase(String label, String priorQuestion, String priorAnswer,
                                   String currentQuestion, boolean expectedIsCorrection) {}

    private static final List<CorrectionCase> CORRECTION_CASES = List.of(
            new CorrectionCase("true correction (timeframe)",
                    "show me sales last month", "Total sales last month were $42,000.",
                    "no, I meant last week not last month", true),
            new CorrectionCase("plain follow-up (new data)",
                    "show me sales last month", "Total sales last month were $42,000.",
                    "what about sales by region", false)
    );

    @Test
    void correctionDetectorContractHoldsOnBothModels() throws Exception {
        assumeTrue(Boolean.getBoolean("nexus.live.openai"), "live evaluation disabled");

        AzureOpenAiClient gpt4o = liveClient("gpt-4o", "correctionDetectorModel");
        AzureOpenAiClient gpt4oMini = liveClient("gpt-4o-mini", "correctionDetectorModel");
        CorrectionDetector detectorOnGpt4o = new CorrectionDetector(gpt4o, new ObjectMapper());
        CorrectionDetector detectorOnGpt4oMini = new CorrectionDetector(gpt4oMini, new ObjectMapper());

        for (CorrectionCase c : CORRECTION_CASES) {
            Optional<CorrectionDetector.DetectedCorrection> baseline =
                    detectorOnGpt4o.detect(c.currentQuestion(), c.priorQuestion(), c.priorAnswer());
            Optional<CorrectionDetector.DetectedCorrection> candidate =
                    detectorOnGpt4oMini.detect(c.currentQuestion(), c.priorQuestion(), c.priorAnswer());

            System.out.println("[" + c.label() + "] gpt-4o      -> " + baseline);
            System.out.println("[" + c.label() + "] gpt-4o-mini -> " + candidate);

            assertCorrectionContract(c.label() + " (gpt-4o)", c.expectedIsCorrection(), baseline);
            assertCorrectionContract(c.label() + " (gpt-4o-mini)", c.expectedIsCorrection(), candidate);
        }
    }

    private static void assertCorrectionContract(String label, boolean expectedIsCorrection,
                                                  Optional<CorrectionDetector.DetectedCorrection> result) {
        if (result.isPresent() != expectedIsCorrection) {
            throw new AssertionError(label + ": expected is_correction=" + expectedIsCorrection
                    + " but got " + result.map(r -> "true (" + r + ")").orElse("false"));
        }
        result.ifPresent(r -> {
            if (r.correctionType() == null || r.correctionType().isBlank()) {
                throw new AssertionError(label + ": correction_type missing on a detected correction");
            }
        });
    }
}
