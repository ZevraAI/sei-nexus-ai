package com.sei.nexus.alert;

import com.sei.nexus.auth.UserAccount;
import com.sei.nexus.common.NexusException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for the Proactive Intelligence alert system.
 *
 * Alert rules  : /alert-rules   (CRUD + test)
 * Deliveries   : /alerts        (list + unread count + mark read)
 */
@RestController
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    // ── Alert Rules ───────────────────────────────────────────────────────────

    /** GET /alert-rules — list all rules for this tenant */
    @GetMapping("/alert-rules")
    public ResponseEntity<List<AlertRule>> listRules() {
        return ResponseEntity.ok(alertService.listRules());
    }

    /** POST /alert-rules — create a new alert rule */
    @PostMapping("/alert-rules")
    public ResponseEntity<AlertRule> createRule(@RequestBody Map<String, Object> body) {
        String email = currentUserEmail();
        AlertRule rule = alertService.createRule(body, email);
        return ResponseEntity.status(HttpStatus.CREATED).body(rule);
    }

    /** PUT /alert-rules/{ruleKey} — update an existing rule */
    @PutMapping("/alert-rules/{ruleKey}")
    public ResponseEntity<AlertRule> updateRule(@PathVariable String ruleKey,
                                                @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(alertService.updateRule(ruleKey, body));
    }

    /** DELETE /alert-rules/{ruleKey} — remove a rule */
    @DeleteMapping("/alert-rules/{ruleKey}")
    public ResponseEntity<Void> deleteRule(@PathVariable String ruleKey) {
        alertService.deleteRule(ruleKey);
        return ResponseEntity.noContent().build();
    }

    /** POST /alert-rules/{ruleKey}/test — send a test alert immediately */
    @PostMapping("/alert-rules/{ruleKey}/test")
    public ResponseEntity<Map<String, String>> testRule(@PathVariable String ruleKey) {
        alertService.testRule(ruleKey);
        return ResponseEntity.ok(Map.of("status", "test_sent", "rule_key", ruleKey));
    }

    // ── Alert Deliveries (in-app notifications) ───────────────────────────────

    /** GET /alerts?limit=50 — recent alert deliveries for this tenant */
    @GetMapping("/alerts")
    public ResponseEntity<List<AlertDelivery>> listAlerts(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(alertService.getAlerts(limit));
    }

    /** GET /alerts/unread-count — number of unread alerts (for bell badge) */
    @GetMapping("/alerts/unread-count")
    public ResponseEntity<Map<String, Integer>> unreadCount() {
        return ResponseEntity.ok(Map.of("count", alertService.getUnreadCount()));
    }

    /** POST /alerts/{deliveryKey}/read — mark one alert as read */
    @PostMapping("/alerts/{deliveryKey}/read")
    public ResponseEntity<Void> markRead(@PathVariable String deliveryKey) {
        alertService.markRead(deliveryKey);
        return ResponseEntity.noContent().build();
    }

    /** POST /alerts/read-all — mark all alerts as read */
    @PostMapping("/alerts/read-all")
    public ResponseEntity<Void> markAllRead() {
        alertService.markAllRead();
        return ResponseEntity.noContent().build();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private String currentUserEmail() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof UserAccount u) return u.email();
        throw new NexusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
    }
}
