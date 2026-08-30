package com.sei.nexus.reasoning;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.runtime.ExecutionReference;
import com.sei.nexus.runtime.GovernedSqlRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Observability for the conversational evidence-reuse decision — {@code
 * CONVERSATION_EVIDENCE_REUSE}. Proves the log line reports whether Turn N reused the prior
 * turn's result set or triggered a new Planner-driven execution, without ever logging the
 * question text, the evaluator's rationale, or any row/business data (same discipline as {@code
 * AzureOpenAiClientFileSearchMetricTest}'s {@code FILE_SEARCH_METRIC} — facts only, never
 * content). Real Logback {@link ListAppender}, no Mockito, no network.
 */
class ReasoningEngineEvidenceReuseObservabilityTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger engineLogger;

    @BeforeEach
    void attachAppender() {
        engineLogger = (Logger) LoggerFactory.getLogger(ReasoningEngine.class);
        appender = new ListAppender<>();
        appender.start();
        engineLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        engineLogger.detachAppender(appender);
    }

    private static ExecutionReference priorExecution() {
        return new ExecutionReference(
                "exec-q1-pos", null, "conv-observability-test", "run-q1", "conn-5780d333",
                Instant.now(), Instant.now(), 120L, "EXECUTE_SYNC", 12,
                List.of("po_number", "status"),
                "[{\"po_number\":\"PO-1000\",\"status\":\"received\"}]",
                "SELECT po_number, status FROM retail_core.purchase_orders",
                "contract-q1", "hash-q1", List.of("retail_core.purchase_orders"),
                Map.of(), Map.of(), List.of());
    }

    private ReasoningEngine engineWith(ReasoningEvaluator evaluator, ReasoningPlanner planner,
                                       GovernedSqlRuntime runtime) {
        ReasoningRepository fakeRepository = new ReasoningRepository(null) {
            @Override public void saveSession(ReasoningSession s) { }
            @Override public void updateSessionStatus(String sessionKey, String status,
                    String conclusion, Double confidence, Instant concludedAt) { }
            @Override public void saveStep(ReasoningStep step) { }
        };
        return new ReasoningEngine(planner, evaluator,
                new ReasoningEventBus(new ObjectMapper()), fakeRepository, runtime, new ObjectMapper());
    }

    private String lastMessage() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("CONVERSATION_EVIDENCE_REUSE"))
                .reduce((first, last) -> last)
                .orElseThrow(() -> new AssertionError("no CONVERSATION_EVIDENCE_REUSE log line was emitted"));
    }

    @Test
    void logsReuseTrueWhenEvidenceIsSufficient() {
        ReasoningEvaluator evaluator = new ReasoningEvaluator(null, null) {
            @Override public EvaluationResult evaluate(String question, EvidenceStore evidence) {
                return new EvaluationResult("SUFFICIENT", "SECRET_RATIONALE_TEXT should never be logged");
            }
        };
        ReasoningPlanner planner = new ReasoningPlanner(null, null) {
            @Override public StepPlan nextStep(String question, String schemaCtx, EvidenceStore evidence) {
                throw new AssertionError("planner must not be invoked when evidence is reused");
            }
        };
        GovernedSqlRuntime runtime = new GovernedSqlRuntime(null, null, null, null, null, null, null, null) {
            @Override public Outcome execute(Request r) { throw new AssertionError("must not execute"); }
        };

        engineWith(evaluator, planner, runtime).reason(
                "SECRET_USER_QUESTION_TEXT", "SECRET_USER_QUESTION_TEXT", "rsession", "schema",
                "run-1", "user@test.com", false, null, null, false,
                "conv-observability-test", "exec-q1-pos", priorExecution());

        String logged = lastMessage();
        assertTrue(logged.contains("conversationId=conv-observability-test"));
        assertTrue(logged.contains("decision=SUFFICIENT"));
        assertTrue(logged.contains("reusedPriorResultSet=true"));
        assertFalse(logged.contains("SECRET_USER_QUESTION_TEXT"), "the question text must never be logged");
        assertFalse(logged.contains("SECRET_RATIONALE_TEXT"), "the evaluator's rationale must never be logged");
    }

    @Test
    void logsReuseFalseWhenEvidenceRequiresANewExecution() {
        ReasoningEvaluator evaluator = new ReasoningEvaluator(null, null) {
            @Override public EvaluationResult evaluate(String question, EvidenceStore evidence) {
                if (evidence.stepCount() == 1) return new EvaluationResult("NEED_MORE_DATA", "needs a filter");
                return new EvaluationResult("SUFFICIENT", "filtered evidence now available");
            }
        };
        ReasoningPlanner planner = new ReasoningPlanner(null, null) {
            @Override public StepPlan nextStep(String question, String schemaCtx, EvidenceStore evidence) {
                if (evidence.stepCount() == 1) {
                    return new StepPlan("Retrieve filtered purchase orders",
                            "SELECT po_number FROM retail_core.purchase_orders WHERE status = 'submitted'",
                            "conn-5780d333", null, "filter requested");
                }
                return null;
            }
        };
        GovernedSqlRuntime runtime = new GovernedSqlRuntime(null, null, null, null, null, null, null, null) {
            @Override public Outcome execute(Request r) {
                return new Outcome(Status.EXECUTED, null, null,
                        List.of(Map.of("po_number", "PO-1010")),
                        "[{\"po_number\":\"PO-1010\"}]", 10L, List.of(), null, null, null);
            }
        };

        engineWith(evaluator, planner, runtime).reason(
                "I want only submitted", "I want only submitted", "rsession", "schema",
                "run-2", "user@test.com", false, null, null, false,
                "conv-observability-test", "exec-q1-pos", priorExecution());

        String logged = lastMessage();
        assertTrue(logged.contains("decision=NEED_MORE_DATA"));
        assertTrue(logged.contains("reusedPriorResultSet=false"));
    }
}
