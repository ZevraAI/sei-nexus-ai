package com.sei.nexus.temporal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.common.Keys;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.sql.SqlSafetyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Service
public class BaselineService {

    private static final Logger log = LoggerFactory.getLogger(BaselineService.class);

    private final TemporalRepository temporalRepository;
    private final AnomalyDetector anomalyDetector;
    private final SqlSafetyService sqlSafetyService;
    private final ObjectMapper objectMapper;

    public BaselineService(TemporalRepository temporalRepository,
                           AnomalyDetector anomalyDetector,
                           SqlSafetyService sqlSafetyService,
                           ObjectMapper objectMapper) {
        this.temporalRepository = temporalRepository;
        this.anomalyDetector = anomalyDetector;
        this.sqlSafetyService = sqlSafetyService;
        this.objectMapper = objectMapper;
    }

    /** Runs every hour and checks all baselines whose next_due_at has passed. */
    @Scheduled(fixedDelay = 3_600_000)
    public void refreshDueBaselines() {
        List<OperationalBaseline> due = temporalRepository.findDueBaselines(Instant.now());
        log.info("Refreshing {} due baselines", due.size());
        for (OperationalBaseline b : due) {
            try {
                anomalyDetector.checkBaseline(b);
            } catch (Exception e) {
                log.warn("Failed to refresh baseline {}: {}", b.baselineKey(), e.getMessage());
            }
        }
    }

    /**
     * Creates a new operational baseline from a request map.
     * Validates measurement SQL for safety before persisting.
     */
    public OperationalBaseline createBaseline(Map<String, Object> req) {
        String sql = (String) req.get("measurement_sql");
        if (sql != null && !sql.isBlank()) {
            var safety = sqlSafetyService.validate(sql);
            if (!safety.safe()) {
                throw new NexusException(HttpStatus.BAD_REQUEST, "Unsafe SQL: " + safety.reason());
            }
        }
        String baselineKey = Keys.uniqueKey("baseline");
        Instant nextDue = Instant.now().plus(1, ChronoUnit.DAYS);
        String window = req.containsKey("measurement_window")
                ? (String) req.get("measurement_window") : "DAILY";
        OperationalBaseline b = new OperationalBaseline(
                baselineKey,
                (String) req.get("domain_key"),
                (String) req.get("agent_key"),
                (String) req.get("kpi_key"),
                (String) req.get("metric_name"),
                sql,
                (String) req.get("connection_key"),
                null, null, null,
                window,
                "[]",
                null,
                nextDue,
                "ACTIVE",
                Instant.now());
        temporalRepository.saveBaseline(b);
        // Run initial measurement as best-effort
        try {
            anomalyDetector.checkBaseline(b);
        } catch (Exception ignored) {
            log.debug("Initial baseline measurement skipped for {}", baselineKey);
        }
        return b;
    }

    /**
     * Returns a formatted anomaly context string for use in LLM prompts.
     */
    public String getAnomalyContext(List<String> domainKeys) {
        return anomalyDetector.getAnomalyContext(domainKeys);
    }
}
