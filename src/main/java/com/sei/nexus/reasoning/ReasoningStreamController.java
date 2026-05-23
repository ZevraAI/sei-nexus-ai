package com.sei.nexus.reasoning;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-Sent Events endpoint that streams reasoning progress to the frontend.
 *
 * <p>Clients connect with:
 * <pre>
 *   const es = new EventSource('/api/v1/chat/runs/{runKey}/stream');
 *   es.onmessage = (e) => { const event = JSON.parse(e.data); ... };
 * </pre>
 *
 * <p>Event types published during a run:
 * <pre>
 *   step_started    – {"stepNo":1,"description":"Querying revenue by month"}
 *   step_completed  – {"stepNo":1,"rowCount":12,"summary":"12 months retrieved"}
 *   evaluation      – {"stepNo":1,"decision":"NEED_MORE_DATA","rationale":"..."}
 *   answer_ready    – {"answer":"..."}  (stream closes after this)
 * </pre>
 *
 * <p>Late-joining clients (connecting after the run has finished) receive a
 * replay of all buffered events immediately on connect.
 */
@RestController
@RequestMapping("/chat/runs")
public class ReasoningStreamController {

    private final ReasoningEventBus eventBus;

    public ReasoningStreamController(ReasoningEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @GetMapping(value = "/{runKey}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String runKey) {
        return eventBus.subscribe(runKey);
    }
}
