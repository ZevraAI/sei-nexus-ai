package com.sei.nexus.alert;

import com.sei.nexus.common.Keys;
import com.sei.nexus.temporal.AnomalyEvent;
import com.sei.nexus.temporal.OperationalBaseline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrates proactive alert delivery:
 *  1. Finds enabled rules for the baseline that just triggered an anomaly
 *  2. Enforces per-rule cooldown so users aren't spammed
 *  3. Evaluates the severity condition against the anomaly
 *  4. Composes an AI-generated message
 *  5. Delegates delivery to the channel service
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final AlertRuleRepository       ruleRepository;
    private final AlertDeliveryRepository   deliveryRepository;
    private final AlertComposerService      composer;
    private final NotificationDeliveryService notificationService;

    public AlertService(AlertRuleRepository ruleRepository,
                        AlertDeliveryRepository deliveryRepository,
                        AlertComposerService composer,
                        NotificationDeliveryService notificationService) {
        this.ruleRepository      = ruleRepository;
        this.deliveryRepository  = deliveryRepository;
        this.composer            = composer;
        this.notificationService = notificationService;
    }

    /**
     * Called immediately after an anomaly is persisted.
     * Evaluates all enabled alert rules for the baseline and fires matching ones.
     */
    public void evaluateAndDeliver(OperationalBaseline baseline, AnomalyEvent anomaly) {
        if (baseline == null || anomaly == null) return;

        List<AlertRule> rules = ruleRepository.findEnabledByBaselineKey(baseline.baselineKey());
        if (rules.isEmpty()) return;

        for (AlertRule rule : rules) {
            try {
                if (conditionMet(rule, anomaly) && !inCooldown(rule)) {
                    String message = composer.compose(rule, anomaly, baseline);
                    notificationService.deliver(rule, message, anomaly);
                    log.info("Alert delivered for rule '{}' baseline '{}' severity '{}'",
                            rule.ruleName(), baseline.baselineKey(), anomaly.severity());
                }
            } catch (Exception e) {
                log.error("Alert evaluation failed for rule {}: {}", rule.ruleKey(), e.getMessage());
            }
        }
    }

    // ── Rule management ───────────────────────────────────────────────────────

    public AlertRule createRule(Map<String, Object> body, String userEmail) {
        AlertRule rule = new AlertRule(
                Keys.uniqueKey("arule"),
                required(body, "ruleName"),
                (String) body.get("baselineKey"),
                (String) body.get("agentKey"),
                (String) body.get("kpiKey"),
                (String) body.get("metricName"),
                strOr(body, "condition",          "ANY_ANOMALY"),
                strOr(body, "severityThreshold",  "MEDIUM"),
                strOr(body, "channel",            "IN_APP"),
                (String) body.get("slackWebhook"),
                (String) body.get("emailTo"),
                body.containsKey("cooldownMinutes")
                        ? ((Number) body.get("cooldownMinutes")).intValue() : 60,
                body.containsKey("enabled")
                        ? Boolean.TRUE.equals(body.get("enabled")) : true,
                userEmail,
                Instant.now(),
                Instant.now());
        ruleRepository.save(rule);
        return rule;
    }

    public AlertRule updateRule(String ruleKey, Map<String, Object> body) {
        AlertRule existing = ruleRepository.findByKey(ruleKey)
                .orElseThrow(() -> new com.sei.nexus.common.NexusException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "Alert rule not found: " + ruleKey));
        AlertRule updated = new AlertRule(
                ruleKey,
                strOr(body, "ruleName",           existing.ruleName()),
                strOr(body, "baselineKey",         existing.baselineKey()),
                strOr(body, "agentKey",            existing.agentKey()),
                strOr(body, "kpiKey",              existing.kpiKey()),
                strOr(body, "metricName",          existing.metricName()),
                strOr(body, "condition",           existing.condition()),
                strOr(body, "severityThreshold",   existing.severityThreshold()),
                strOr(body, "channel",             existing.channel()),
                body.containsKey("slackWebhook") ? (String) body.get("slackWebhook") : existing.slackWebhook(),
                body.containsKey("emailTo")       ? (String) body.get("emailTo")       : existing.emailTo(),
                body.containsKey("cooldownMinutes")
                        ? ((Number) body.get("cooldownMinutes")).intValue() : existing.cooldownMinutes(),
                body.containsKey("enabled")
                        ? Boolean.TRUE.equals(body.get("enabled")) : existing.enabled(),
                existing.createdBy(),
                existing.createdAt(),
                Instant.now());
        ruleRepository.save(updated);
        return updated;
    }

    public void deleteRule(String ruleKey) {
        ruleRepository.delete(ruleKey);
    }

    public List<AlertRule> listRules() {
        return ruleRepository.findAll();
    }

    /** Sends a test alert immediately ignoring cooldown — useful for setup verification. */
    public void testRule(String ruleKey) {
        AlertRule rule = ruleRepository.findByKey(ruleKey)
                .orElseThrow(() -> new com.sei.nexus.common.NexusException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "Alert rule not found: " + ruleKey));
        String testMessage = "This is a test alert from Zevra for rule '" + rule.ruleName() +
                "'. Your alert channel is configured correctly.";
        notificationService.deliver(rule, testMessage, null);
        log.info("Test alert sent for rule {}", ruleKey);
    }

    // ── Alert history ─────────────────────────────────────────────────────────

    public List<AlertDelivery> getAlerts(int limit) {
        return deliveryRepository.findRecent(limit);
    }

    public int getUnreadCount() {
        return deliveryRepository.countUnread();
    }

    public void markRead(String deliveryKey) {
        deliveryRepository.markRead(deliveryKey);
    }

    public void markAllRead() {
        deliveryRepository.markAllRead();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private boolean conditionMet(AlertRule rule, AnomalyEvent anomaly) {
        String condition = rule.condition();
        String severity  = anomaly.severity() != null ? anomaly.severity() : "LOW";
        int    sevOrdinal = severityOrdinal(severity);
        int    threshold  = severityOrdinal(rule.severityThreshold());

        return switch (condition) {
            case "ANY_ANOMALY"      -> true;
            case "ABOVE_WARNING"    -> sevOrdinal >= 1; // MEDIUM+
            case "ABOVE_CRITICAL"   -> sevOrdinal >= 3; // CRITICAL only
            case "BELOW_WARNING"    -> sevOrdinal <  1;
            case "BELOW_CRITICAL"   -> sevOrdinal <  3;
            default                 -> sevOrdinal >= threshold;
        };
    }

    private boolean inCooldown(AlertRule rule) {
        Optional<Instant> last = ruleRepository.lastDeliveryTime(rule.ruleKey());
        if (last.isEmpty()) return false;
        long minutesSince = ChronoUnit.MINUTES.between(last.get(), Instant.now());
        return minutesSince < rule.cooldownMinutes();
    }

    private int severityOrdinal(String severity) {
        if (severity == null) return 0;
        return switch (severity.toUpperCase()) {
            case "LOW"      -> 0;
            case "MEDIUM"   -> 1;
            case "HIGH"     -> 2;
            case "CRITICAL" -> 3;
            default         -> 0;
        };
    }

    private String required(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new com.sei.nexus.common.NexusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, key + " is required");
        }
        return v.toString();
    }

    private String strOr(Map<String, Object> body, String key, String fallback) {
        Object v = body.get(key);
        return (v != null && !v.toString().isBlank()) ? v.toString() : fallback;
    }
}
