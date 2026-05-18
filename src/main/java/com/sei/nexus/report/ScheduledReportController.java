package com.sei.nexus.report;

import com.sei.nexus.auth.UserAccount;
import com.sei.nexus.common.NexusException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/reports")
public class ScheduledReportController {

    private final ScheduledReportService reportService;

    public ScheduledReportController(ScheduledReportService reportService) {
        this.reportService = reportService;
    }

    /** GET /reports — list all scheduled reports for this tenant */
    @GetMapping
    public ResponseEntity<List<ScheduledReport>> listReports() {
        return ResponseEntity.ok(reportService.listReports());
    }

    /** POST /reports — create a new scheduled report */
    @PostMapping
    public ResponseEntity<ScheduledReport> createReport(@RequestBody Map<String, Object> body) {
        ScheduledReport report = reportService.createReport(body, currentUserEmail());
        return ResponseEntity.status(HttpStatus.CREATED).body(report);
    }

    /** PUT /reports/{reportKey} — update an existing report */
    @PutMapping("/{reportKey}")
    public ResponseEntity<ScheduledReport> updateReport(@PathVariable String reportKey,
                                                         @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(reportService.updateReport(reportKey, body));
    }

    /** DELETE /reports/{reportKey} — archive a report */
    @DeleteMapping("/{reportKey}")
    public ResponseEntity<Void> deleteReport(@PathVariable String reportKey) {
        reportService.deleteReport(reportKey);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /reports/{reportKey}/run — execute a report immediately.
     * Used for on-demand preview and testing delivery configuration.
     */
    @PostMapping("/{reportKey}/run")
    public ResponseEntity<Map<String, Object>> runReport(@PathVariable String reportKey) {
        ScheduledReport report = reportService.listReports().stream()
                .filter(r -> r.reportKey().equals(reportKey))
                .findFirst()
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "Report not found: " + reportKey));

        ScheduledReportService.ReportRunResult result =
                reportService.executeReport(report, currentUserEmail());

        return ResponseEntity.ok(Map.of(
                "status",              result.status(),
                "sections_ran",        result.sectionCount(),
                "errors",              result.errors(),
                "schedule_description", result.scheduleDescription()));
    }

    private String currentUserEmail() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof UserAccount u) return u.email();
        throw new NexusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
    }
}
