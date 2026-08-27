package com.sei.nexus.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-path parity — proves {@code OnboardingService.analyzeTableBatch()} and
 * {@code EnterpriseMapService.analyzeForOnboarding()} are not merely similar but actually run
 * through the SAME execution mechanism. This is the regression guard for the incident that
 * started this whole convergence: {@code category} (and other fields) existing in only one of
 * the two prompts.
 *
 * <p>Multi-Table Analysis Hardening extracted the actual prompt-building/AI-call/parsing/
 * defaulting logic out of both services and into {@link BusinessObjectBatchAnalyzer} — neither
 * service builds its own prompt from {@link BusinessObjectAnalysisContract} directly any more;
 * both delegate to the one shared analyzer instead. So parity is now enforced structurally: both
 * source files must reference {@code BusinessObjectBatchAnalyzer} (never each other), and the
 * shared analyzer itself must embed the canonical contract's field schema, rules, defaults, and
 * failure stub — verified once, in one place, rather than duplicated per caller.
 */
class AnalysisPathParityTest {

    private static String readFile(String relativePath) throws Exception {
        return java.nio.file.Files.readString(java.nio.file.Path.of(relativePath));
    }

    @Test
    void onboardingDelegatesToTheSharedBatchAnalyzerNotItsOwnCopy() throws Exception {
        String src = readFile("src/main/java/com/sei/nexus/onboarding/OnboardingService.java");
        assertTrue(src.contains("BusinessObjectBatchAnalyzer"),
                "OnboardingService.analyzeTableBatch() must delegate to the shared batch analyzer");
        // OnboardingService happens to hold an (otherwise-unrelated, pre-existing) reference to
        // EnterpriseMapService — the concern here is narrower and specific: its own analysis
        // method must never call EnterpriseMapService's analysis method.
        assertFalse(src.contains("enterpriseMapService.analyzeForOnboarding"),
                "OnboardingService must never call EnterpriseMapService.analyzeForOnboarding() directly");
    }

    @Test
    void discoverDelegatesToTheSharedBatchAnalyzerNotItsOwnCopy() throws Exception {
        String src = readFile("src/main/java/com/sei/nexus/enterprise/EnterpriseMapService.java");
        assertTrue(src.contains("BusinessObjectBatchAnalyzer"),
                "EnterpriseMapService.analyzeForOnboarding() must delegate to the shared batch analyzer");
        assertFalse(src.contains("onboardingService.analyzeTableBatch")
                        || src.contains("onboardingService.startAnalysisJob"),
                "EnterpriseMapService must never call OnboardingService's analysis methods directly");
    }

    @Test
    void theSharedBatchAnalyzerItselfEmbedsTheCanonicalContract() throws Exception {
        String src = readFile("src/main/java/com/sei/nexus/prompt/BusinessObjectBatchAnalyzer.java");
        assertTrue(src.contains("BusinessObjectAnalysisContract.FIELD_SCHEMA"),
                "the shared analyzer must build its prompt from the canonical field schema");
        assertTrue(src.contains("BusinessObjectAnalysisContract.RULES"),
                "the shared analyzer must build its prompt from the canonical rules");
        assertTrue(src.contains("BusinessObjectAnalysisContract.applyCanonicalDefaults"),
                "the shared analyzer must apply the canonical defaults, not an ad hoc category-only check");
        assertTrue(src.contains("BusinessObjectAnalysisContract.canonicalStub"),
                "the shared analyzer's failure stub must build on the canonical stub");
        // Connection-Scoped Industry Pack Semantic Assignment: pack-aware concept resolution
        // lives entirely inside this one shared analyzer, not duplicated per caller — so this
        // same structural guarantee automatically covers "Onboarding gets it" and "Discover gets
        // it" without a separate integration test for each (see
        // BusinessObjectBatchAnalyzerConceptResolutionTest for the behavior itself).
        assertTrue(src.contains("resolveActivePackContext"),
                "pack-aware concept resolution must live in the one shared analyzer both paths use");
    }
}
