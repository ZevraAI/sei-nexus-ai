package com.sei.nexus.onboarding;

import com.sei.nexus.reasoning.ReasoningEventBus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-Sent Events endpoint streaming onboarding-analysis job progress.
 *
 * <p>Reuses {@link ReasoningEventBus} directly (the same bus Chat's reasoning
 * trace streams through) — it's generic on an opaque run key, and an onboarding
 * job id (prefixed {@code onbjob-}, distinct from chat's {@code run-} ids) is
 * just another key. No separate bus/buffer/emitter bookkeeping needed.
 *
 * <p>Clients connect with:
 * <pre>
 *   fetch('/api/v1/onboarding/analyze/{jobId}/stream', {headers: authHeader})
 * </pre>
 *
 * <p>Event types published during a job (see {@code OnboardingService.runAnalysisJob}):
 * <pre>
 *   job_started     – {"tablesTotal": 15}
 *   table_started   – {"table": "orders"}
 *   table_completed – {"table": "orders"}
 *   table_failed    – {"table": "orders"}
 *   job_complete    – {}   (stream closes after this)
 *   job_failed      – {"error": "..."}  (stream closes after this)
 * </pre>
 *
 * <p>The bus's 5-minute replay buffer is sized for Chat's shorter runs and is
 * <b>not</b> relied on for onboarding correctness — a job can outlast it, and
 * refresh timing is unpredictable. Callers should always seed/reconcile state
 * from {@code GET /onboarding/analyze/{jobId}} (the DB-authoritative view) on
 * connect/reattach, treating this stream purely as the live-update channel.
 */
@RestController
@RequestMapping("/onboarding/analyze")
public class OnboardingJobStreamController {

    private final ReasoningEventBus eventBus;

    public OnboardingJobStreamController(ReasoningEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @GetMapping(value = "/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String jobId) {
        return eventBus.subscribe(jobId);
    }
}
