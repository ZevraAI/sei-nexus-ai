package com.sei.nexus.temporal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.alert.AlertService;
import com.sei.nexus.common.Keys;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.sql.SqlSafetyService;
import com.sei.nexus.tenant.TenantContext;
import com.sei.nexus.tenant.TenantRepository;
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

    private final TemporalRepository  temporalRepository;
    private final AnomalyDetector     anomalyDetector;
    private final SqlSafetyService    sqlSafetyService;
    private final ObjectMapper        objectMapper;
    private final AlertService        alertService;
    private final TenantRepository    tenantRepository;

    public BaselineService(TemporalRepository temporalRepository,
                           AnomalyDetector anomalyDetector,
                           SqlSafetyService sqlSafetyService,
                           ObjectMapper objectMapper,
                           AlertService alertService,
                           TenantRepository tenantRepository) {
        this.temporalRepository = temporalRepository;
        this.anomalyDetector    = anomalyDetector;
        this.sqlSafetyService   = sqlSafetyService;
        this.objectMapper       = objectMapper;
        this.alertService       = alertService;
        this.tenantRepository   = tenantRepository;
    }

    /**
     * Runs every hour.
     *
     * Iterates over every active tenant schema (plus the public default workspace)
     * so baselines configured in any tenant are evaluated and can trigger alerts.
     */
    @Scheduled(fixedDelay = 3_600_000)
    public void refreshDueBaselines() {
        // Collect all active schemas — public workspace + every provisioned tenant
        List<String> schemas = new java.util.ArrayList<>();
        schemas.add(TenantContext.PUBLIC_SCHEMA);
        try {
            tenantRepository.findAll().stream()
                    .filter(t -> "ACTIVE".equals(t.status()))
                    .map(com.sei.nexus.tenant.Tenant::schemaName)
                    .forEach(schemas::add);
        } catch (Exception e) {
            log.warn("Could not load tenant list for baseline refresh: {}", e.getMessage());
        }

        int totalDue = 0;
        for (String schema : schemas) {
            TenantContext.set(schema);
            try {
                List<OperationalBaseline> due = temporalRepository.findDueBaselines(Instant.now());
                if (due.isEmpty()) continue;
                totalDue += due.size();
                log.info("Refreshing {} baselines in schema '{}'", due.size(), schema);

                for (OperationalBaseline baseline : due) {
                    try {
                        AnomalyEvent anomaly = anomalyDetector.checkBaseline(baseline);
                        if (anomaly != null) {
                            // Fire alert rules for this baseline synchronously — keeps the
                            // delivery in the same TenantContext so in-app rows land in the
                            // correct schema.
                            alertService.evaluateAndDeliver(baseline, anomaly);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to refresh baseline {} in schema {}: {}",
                                baseline.baselineKey(), schema, e.getMessage());
                    }
                }
            } finally {
                // Always reset to public so the next schema starts clean
                TenantContext.clear();
            }
        }
        log.info("Baseline refresh complete — {} due across {} schemas", totalDue, schemas.size());
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
        Instant nextDue    = Instant.now().plus(1, ChronoUnit.DAYS);
        String window      = req.containsKey("measurement_window")
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
                window, "[]", null, nextDue,
                "ACTIVE", Instant.now());

        temporalRepository.saveBaseline(b);

        // Run initial measurement best-effort so the user immediately sees a value
        try {
            AnomalyEvent anomaly = anomalyDetector.checkBaseline(b);
            if (anomaly != null) alertService.evaluateAndDeliver(b, anomaly);
        } catch (Exception ignored) {
            log.debug("Initial baseline measurement skipped for {}", baselineKey);
        }
        return b;
    }

    /** Returns a formatted anomaly context string for LLM prompts. */
    public String getAnomalyContext(List<String> domainKeys) {
        return anomalyDetector.getAnomalyContext(domainKeys);
    }
}
