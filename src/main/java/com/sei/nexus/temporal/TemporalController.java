package com.sei.nexus.temporal;

import com.sei.nexus.common.NexusException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/temporal")
public class TemporalController {

    private final TemporalRepository temporalRepository;
    private final BaselineService baselineService;
    private final AnomalyDetector anomalyDetector;

    public TemporalController(TemporalRepository temporalRepository,
                               BaselineService baselineService,
                               AnomalyDetector anomalyDetector) {
        this.temporalRepository = temporalRepository;
        this.baselineService = baselineService;
        this.anomalyDetector = anomalyDetector;
    }

    /** GET /temporal/baselines?domainKey= */
    @GetMapping("/baselines")
    public ResponseEntity<List<OperationalBaseline>> listBaselines(
            @RequestParam String domainKey) {
        return ResponseEntity.ok(temporalRepository.findBaselinesByDomain(domainKey));
    }

    /** POST /temporal/baselines */
    @PostMapping("/baselines")
    public ResponseEntity<OperationalBaseline> createBaseline(
            @RequestBody Map<String, Object> req) {
        OperationalBaseline b = baselineService.createBaseline(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(b);
    }

    /** POST /temporal/baselines/{baselineKey}/refresh */
    @PostMapping("/baselines/{baselineKey}/refresh")
    public ResponseEntity<Map<String, String>> refreshBaseline(
            @PathVariable String baselineKey) {
        OperationalBaseline baseline = temporalRepository.findBaselineByKey(baselineKey)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "Baseline not found: " + baselineKey));
        anomalyDetector.checkBaseline(baseline);
        return ResponseEntity.ok(Map.of("baseline_key", baselineKey, "status", "refreshed"));
    }

    /** GET /temporal/anomalies?domainKey=&status= */
    @GetMapping("/anomalies")
    public ResponseEntity<List<AnomalyEvent>> listAnomalies(
            @RequestParam String domainKey,
            @RequestParam(defaultValue = "OPEN") String status) {
        return ResponseEntity.ok(temporalRepository.findAnomaliesByDomain(domainKey, status));
    }

    /** GET /temporal/anomalies/{anomalyKey} */
    @GetMapping("/anomalies/{anomalyKey}")
    public ResponseEntity<AnomalyEvent> getAnomaly(@PathVariable String anomalyKey) {
        // There's no findByKey in the repository; we'd need to add it,
        // or return a 501. Add a simple query approach via a thin delegate.
        throw new NexusException(HttpStatus.NOT_IMPLEMENTED,
                "Use GET /temporal/anomalies?domainKey= to list anomalies. " +
                "Single anomaly lookup not implemented — add findAnomalyByKey to TemporalRepository if required.");
    }

    /** PATCH /temporal/anomalies/{anomalyKey} */
    @PatchMapping("/anomalies/{anomalyKey}")
    public ResponseEntity<Map<String, String>> updateAnomaly(
            @PathVariable String anomalyKey,
            @RequestBody Map<String, Object> body) {
        String status = (String) body.get("status");
        if (status == null || status.isBlank()) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "status is required");
        }
        String findingKey = (String) body.get("finding_key");
        temporalRepository.updateAnomalyStatus(anomalyKey, status, findingKey);
        return ResponseEntity.ok(Map.of("anomaly_key", anomalyKey, "status", status));
    }
}
