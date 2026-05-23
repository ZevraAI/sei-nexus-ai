package com.sei.nexus.reasoning;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe event bus for streaming reasoning progress to connected SSE clients.
 *
 * <p>Supports two access patterns:
 * <ol>
 *   <li><b>Connected clients</b> — subscribe before or during a run; events are pushed immediately.</li>
 *   <li><b>Late-joining clients</b> — subscribe after the run completes; buffered events are replayed.</li>
 * </ol>
 *
 * <p>Events are buffered per run key for 5 minutes, then purged.
 */
@Component
public class ReasoningEventBus {

    private static final Logger log = LoggerFactory.getLogger(ReasoningEventBus.class);
    private static final long   BUFFER_TTL_SECONDS = 300L; // 5 minutes

    private record BufferedEvent(Map<String, Object> payload, Instant timestamp) {}

    // runKey → buffered events (for late-joining clients)
    private final ConcurrentHashMap<String, List<BufferedEvent>> buffer   = new ConcurrentHashMap<>();
    // runKey → active SseEmitters
    private final ConcurrentHashMap<String, List<SseEmitter>>    emitters = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    public ReasoningEventBus(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Subscribe a new SSE emitter to a run's event stream.
     * Buffered events are replayed immediately on connect.
     */
    public SseEmitter subscribe(String runKey) {
        SseEmitter emitter = new SseEmitter(BUFFER_TTL_SECONDS * 1000);

        // Replay buffered events to catch late-joining clients
        List<BufferedEvent> existing = buffer.getOrDefault(runKey, List.of());
        for (BufferedEvent be : existing) {
            if (!send(emitter, be.payload())) return emitter; // broken connection
        }

        // Register for future events
        emitters.computeIfAbsent(runKey, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(runKey, emitter));
        emitter.onTimeout(()     -> removeEmitter(runKey, emitter));
        return emitter;
    }

    /**
     * Publish an event for a run. Buffers it and pushes to all subscribed clients.
     *
     * @param runKey  Run identifier.
     * @param type    Event type (step_started | step_completed | evaluation | answer_ready).
     * @param data    Arbitrary event payload — must be JSON-serialisable.
     */
    public void publish(String runKey, String type, Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type",      type);
        payload.put("run_key",   runKey);
        payload.put("timestamp", Instant.now().toString());
        payload.putAll(data);

        // Buffer for late joiners
        buffer.computeIfAbsent(runKey, k -> Collections.synchronizedList(new ArrayList<>()))
              .add(new BufferedEvent(payload, Instant.now()));

        // Push to all live emitters
        List<SseEmitter> live = emitters.getOrDefault(runKey, List.of());
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : live) {
            if (!send(emitter, payload)) dead.add(emitter);
        }
        dead.forEach(e -> removeEmitter(runKey, e));
    }

    /** Mark a run's stream as complete — all connected emitters will close. */
    public void complete(String runKey) {
        List<SseEmitter> live = emitters.remove(runKey);
        if (live != null) live.forEach(e -> {
            try { e.complete(); } catch (Exception ignored) {}
        });
    }

    // ── Housekeeping ──────────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 60_000)
    public void purgeExpiredBuffers() {
        Instant cutoff = Instant.now().minusSeconds(BUFFER_TTL_SECONDS);
        buffer.entrySet().removeIf(entry -> {
            List<BufferedEvent> events = entry.getValue();
            return events.isEmpty() || events.stream()
                    .allMatch(e -> e.timestamp().isBefore(cutoff));
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean send(SseEmitter emitter, Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            emitter.send(SseEmitter.event().data(json));
            return true;
        } catch (Exception e) {
            log.debug("SSE send failed (client disconnected): {}", e.getMessage());
            return false;
        }
    }

    private void removeEmitter(String runKey, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(runKey);
        if (list != null) list.remove(emitter);
    }
}
