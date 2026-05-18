package com.sei.nexus.alert;

import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.temporal.AnomalyEvent;
import com.sei.nexus.temporal.OperationalBaseline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Generates a concise, professional natural-language alert message for a detected anomaly.
 * The message is used across all channels — Slack, email, and in-app.
 */
@Service
public class AlertComposerService {

    private static final Logger log = LoggerFactory.getLogger(AlertComposerService.class);

    private final AzureOpenAiClient aiClient;

    public AlertComposerService(AzureOpenAiClient aiClient) {
        this.aiClient = aiClient;
    }

    /**
     * Composes a plain-English alert message from the anomaly and its baseline context.
     * Falls back to a deterministic template if the AI call fails.
     */
    public String compose(AlertRule rule, AnomalyEvent anomaly, OperationalBaseline baseline) {
        try {
            String prompt = buildPrompt(rule, anomaly, baseline);
            return aiClient.chat(List.of(ChatMessage.user(prompt)), """
                    You are Zevra, an enterprise operational intelligence platform.
                    Write a concise, professional alert message for a business analyst.
                    Use 2-3 sentences maximum. Include:
                    1. The metric name and what changed (with exact numbers)
                    2. Why it matters operationally
                    3. One specific recommended action
                    Do not use markdown. Write in plain professional English.
                    Start directly with the finding — no preamble.
                    """);
        } catch (Exception e) {
            log.warn("AI alert composition failed, using template: {}", e.getMessage());
            return buildFallbackMessage(rule, anomaly, baseline);
        }
    }

    private String buildPrompt(AlertRule rule, AnomalyEvent anomaly, OperationalBaseline baseline) {
        return String.format("""
                Metric: %s
                Current value: %s
                Baseline average: %s
                Deviation: %s%% (%s z-score)
                Severity: %s
                Measurement window: %s
                Alert rule: %s
                """,
                anomaly.metricName(),
                anomaly.observedValue() != null ? String.format("%.2f", anomaly.observedValue()) : "N/A",
                anomaly.baselineValue()  != null ? String.format("%.2f", anomaly.baselineValue())  : "N/A",
                anomaly.deviationPct()   != null ? String.format("%.1f", anomaly.deviationPct())   : "N/A",
                anomaly.deviationStddev()!= null ? String.format("%.1f", anomaly.deviationStddev()): "N/A",
                anomaly.severity(),
                baseline.measurementWindow(),
                rule.ruleName());
    }

    /** Deterministic fallback — always works even if the AI is unavailable. */
    private String buildFallbackMessage(AlertRule rule, AnomalyEvent anomaly, OperationalBaseline baseline) {
        String metric  = anomaly.metricName() != null ? anomaly.metricName() : "metric";
        String current = anomaly.observedValue() != null ? String.format("%.2f", anomaly.observedValue()) : "N/A";
        String avg     = anomaly.baselineValue()  != null ? String.format("%.2f", anomaly.baselineValue())  : "N/A";
        String pct     = anomaly.deviationPct()   != null ? String.format("%.1f%%", anomaly.deviationPct()) : "significantly";
        String sev     = anomaly.severity() != null ? anomaly.severity() : "UNKNOWN";

        return String.format(
                "%s alert: %s is currently %s, which is %s above the baseline average of %s. " +
                "This %s deviation warrants immediate review.",
                sev, metric, current, pct, avg, sev.toLowerCase());
    }
}
