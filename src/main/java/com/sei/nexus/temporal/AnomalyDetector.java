package com.sei.nexus.temporal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sei.nexus.common.Keys;
import com.sei.nexus.sql.DynamicSqlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AnomalyDetector {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetector.class);

    private final TemporalRepository temporalRepository;
    private final DynamicSqlService dynamicSqlService;
    private final ObjectMapper objectMapper;

    public AnomalyDetector(TemporalRepository temporalRepository,
                            DynamicSqlService dynamicSqlService,
                            ObjectMapper objectMapper) {
        this.temporalRepository = temporalRepository;
        this.dynamicSqlService = dynamicSqlService;
        this.objectMapper = objectMapper;
    }

    /**
     * Checks a baseline by executing its measurement SQL, computing statistics,
     * updating trend data, and detecting anomalies.
     *
     * @return the created AnomalyEvent if an anomaly was detected, otherwise null
     */
    public AnomalyEvent checkBaseline(OperationalBaseline baseline) {
        try {
            List<Map<String, Object>> rows = dynamicSqlService.executeQuery(
                    baseline.connectionKey(), baseline.measurementSql(), 1);
            if (rows.isEmpty()) return null;

            double newValue  = parseValue(rows.get(0));
            String trendData = updateTrendData(baseline.trendData(), newValue);
            List<Double> history = extractHistory(trendData);
            double avg    = history.stream().mapToDouble(d -> d).average().orElse(newValue);
            double stddev = computeStddev(history, avg);
            Instant nextDue = computeNextDue(baseline.measurementWindow());

            temporalRepository.updateBaselineValues(
                    baseline.baselineKey(), newValue, avg, stddev, trendData,
                    Instant.now(), nextDue);

            if (stddev > 0) {
                double zScore       = Math.abs((newValue - avg) / stddev);
                double deviationPct = avg != 0 ? Math.abs((newValue - avg) / avg * 100) : 0;
                String severity     = zScore > 3 ? "CRITICAL" : zScore > 2 ? "HIGH" : zScore > 1.5 ? "MEDIUM" : "LOW";

                if (zScore > 1.5 || deviationPct > 20) {
                    String       anomalyKey = Keys.uniqueKey("anomaly");
                    AnomalyEvent event      = new AnomalyEvent(
                            anomalyKey, baseline.baselineKey(), baseline.domainKey(),
                            null, Instant.now(), baseline.metricName(),
                            avg, newValue, deviationPct, zScore, severity, "OPEN", null);
                    temporalRepository.saveAnomaly(event);
                    log.info("Anomaly detected for baseline {}: {} severity, z={}",
                            baseline.baselineKey(), severity, zScore);
                    return event;
                }
            }
        } catch (Exception e) {
            log.warn("Baseline check failed for {}: {}", baseline.baselineKey(), e.getMessage());
        }
        return null;
    }

    /**
     * Builds a formatted anomaly context string for the specified domains, used in LLM prompts.
     */
    public String getAnomalyContext(List<String> domainKeys) {
        if (domainKeys == null || domainKeys.isEmpty()) return "";
        try {
            List<AnomalyEvent> anomalies = temporalRepository.findRecentAnomalies(domainKeys, 5);
            if (anomalies.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("=== Recent Anomalies ===\n");
            for (AnomalyEvent a : anomalies) {
                sb.append(String.format("- %s [%s severity]: baseline=%.2f, observed=%.2f, deviation=%.1f%%\n",
                        a.metricName(), a.severity(),
                        a.baselineValue() != null ? a.baselineValue() : 0.0,
                        a.observedValue() != null ? a.observedValue() : 0.0,
                        a.deviationPct() != null ? a.deviationPct() : 0.0));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to retrieve anomaly context: {}", e.getMessage());
            return "";
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private double parseValue(Map<String, Object> row) {
        Object v = row.values().iterator().next();
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(v.toString());
    }

    private String updateTrendData(String existing, double newValue) throws Exception {
        JsonNode node = (existing != null && !existing.isBlank())
                ? objectMapper.readTree(existing)
                : objectMapper.createArrayNode();
        ArrayNode arr = node.isArray()
                ? (ArrayNode) node
                : objectMapper.createArrayNode();
        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("ts", Instant.now().toString());
        entry.put("value", newValue);
        arr.add(entry);
        while (arr.size() > 90) arr.remove(0);
        return objectMapper.writeValueAsString(arr);
    }

    private List<Double> extractHistory(String trendData) throws Exception {
        List<Double> result = new ArrayList<>();
        if (trendData == null || trendData.isBlank()) return result;
        JsonNode arr = objectMapper.readTree(trendData);
        for (JsonNode n : arr) {
            if (n.has("value")) result.add(n.get("value").asDouble());
        }
        return result;
    }

    private double computeStddev(List<Double> vals, double avg) {
        if (vals.size() < 2) return 0;
        double sumSq = vals.stream().mapToDouble(v -> (v - avg) * (v - avg)).sum();
        return Math.sqrt(sumSq / vals.size());
    }

    private Instant computeNextDue(String window) {
        if (window == null) return Instant.now().plus(1, ChronoUnit.DAYS);
        return switch (window.toUpperCase()) {
            case "WEEKLY" -> Instant.now().plus(7, ChronoUnit.DAYS);
            case "MONTHLY" -> Instant.now().plus(30, ChronoUnit.DAYS);
            default -> Instant.now().plus(1, ChronoUnit.DAYS);
        };
    }
}
