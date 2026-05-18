package com.sei.nexus.alert;

import com.sei.nexus.common.Keys;
import com.sei.nexus.temporal.AnomalyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Delivers alert messages to Slack, email, and the in-app notification store.
 * Each delivery is persisted in nexus_alert_delivery regardless of channel.
 */
@Service
public class NotificationDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryService.class);

    private final AlertDeliveryRepository deliveryRepository;
    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String mailFrom;

    @Value("${nexus.alerts.app-url:https://app.zevra.ai}")
    private String appUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public NotificationDeliveryService(AlertDeliveryRepository deliveryRepository,
                                        JavaMailSender mailSender) {
        this.deliveryRepository = deliveryRepository;
        this.mailSender         = mailSender;
    }

    /**
     * Delivers to all configured channels for the rule and records the delivery.
     */
    public void deliver(AlertRule rule, String message, AnomalyEvent anomaly) {
        String channel = rule.channel() != null ? rule.channel() : "IN_APP";

        // In-app: always saved regardless of channel setting
        saveDelivery(rule, anomaly, "IN_APP", message, "UNREAD");

        if ("SLACK".equals(channel) || "ALL".equals(channel)) {
            sendSlack(rule, message, anomaly);
        }
        if ("EMAIL".equals(channel) || "ALL".equals(channel)) {
            sendEmail(rule, message, anomaly);
        }
    }

    // ── In-app ────────────────────────────────────────────────────────────────

    private void saveDelivery(AlertRule rule, AnomalyEvent anomaly,
                               String channel, String message, String status) {
        AlertDelivery delivery = new AlertDelivery(
                Keys.uniqueKey("alert"),
                rule.ruleKey(),
                rule.ruleName(),
                anomaly != null ? anomaly.anomalyKey() : null,
                channel,
                anomaly != null ? anomaly.metricName() : rule.metricName(),
                toBD(anomaly != null ? anomaly.observedValue()  : null),
                toBD(anomaly != null ? anomaly.baselineValue()  : null),
                toBD(anomaly != null ? anomaly.deviationPct()   : null),
                anomaly != null ? anomaly.severity() : null,
                message,
                status,
                Instant.now(),
                null);
        try {
            deliveryRepository.save(delivery);
        } catch (Exception e) {
            log.error("Failed to save in-app alert delivery: {}", e.getMessage());
        }
    }

    // ── Slack ─────────────────────────────────────────────────────────────────

    private void sendSlack(AlertRule rule, String message, AnomalyEvent anomaly) {
        if (rule.slackWebhook() == null || rule.slackWebhook().isBlank()) {
            log.warn("Slack channel configured but no webhook URL for rule {}", rule.ruleKey());
            return;
        }
        try {
            String severityEmoji = severityEmoji(anomaly != null ? anomaly.severity() : "MEDIUM");
            String payload = buildSlackPayload(severityEmoji, rule, message, anomaly);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(rule.slackWebhook()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                log.info("Slack alert delivered for rule {}", rule.ruleKey());
            } else {
                log.warn("Slack webhook returned {}: {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.error("Slack delivery failed for rule {}: {}", rule.ruleKey(), e.getMessage());
        }
    }

    private String buildSlackPayload(String emoji, AlertRule rule, String message, AnomalyEvent anomaly) {
        String severity  = anomaly != null ? anomaly.severity() : "MEDIUM";
        String metric    = anomaly != null && anomaly.metricName() != null ? anomaly.metricName() : rule.ruleName();
        String current   = anomaly != null && anomaly.observedValue()  != null ? String.format("%.2f", anomaly.observedValue())  : "—";
        String baseline  = anomaly != null && anomaly.baselineValue()  != null ? String.format("%.2f", anomaly.baselineValue())  : "—";
        String deviation = anomaly != null && anomaly.deviationPct()   != null ? String.format("%.1f%%", anomaly.deviationPct()) : "—";
        String color     = "CRITICAL".equals(severity) ? "#DC2626" :
                           "HIGH"    .equals(severity) ? "#EA580C" :
                           "MEDIUM"  .equals(severity) ? "#D97706" : "#6B7280";

        // Escape quotes in message for JSON safety
        String safeMsg = message.replace("\"", "\\\"").replace("\n", "\\n");

        return String.format("""
                {
                  "attachments": [{
                    "color": "%s",
                    "blocks": [
                      {
                        "type": "header",
                        "text": {"type": "plain_text", "text": "%s Zevra Alert: %s", "emoji": true}
                      },
                      {
                        "type": "section",
                        "text": {"type": "mrkdwn", "text": "%s"}
                      },
                      {
                        "type": "section",
                        "fields": [
                          {"type": "mrkdwn", "text": "*Metric*\\n%s"},
                          {"type": "mrkdwn", "text": "*Severity*\\n%s"},
                          {"type": "mrkdwn", "text": "*Current Value*\\n%s"},
                          {"type": "mrkdwn", "text": "*Baseline*\\n%s"},
                          {"type": "mrkdwn", "text": "*Deviation*\\n%s"}
                        ]
                      },
                      {
                        "type": "actions",
                        "elements": [{
                          "type": "button",
                          "text": {"type": "plain_text", "text": "Investigate in Zevra"},
                          "url": "%s",
                          "style": "primary"
                        }]
                      }
                    ]
                  }]
                }
                """,
                color, emoji, escapeJson(rule.ruleName()), safeMsg,
                escapeJson(metric), severity, current, baseline, deviation, appUrl);
    }

    // ── Email ─────────────────────────────────────────────────────────────────

    private void sendEmail(AlertRule rule, String message, AnomalyEvent anomaly) {
        if (rule.emailTo() == null || rule.emailTo().isBlank()) {
            log.warn("Email channel configured but no recipients for rule {}", rule.ruleKey());
            return;
        }
        if (mailFrom == null || mailFrom.isBlank()) {
            log.warn("Email delivery skipped — SMTP not configured (SMTP_USERNAME not set)");
            return;
        }
        try {
            String[] recipients = rule.emailTo().split(",\\s*");
            for (String to : recipients) {
                if (to.isBlank()) continue;
                MimeMessage mime = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
                helper.setFrom(mailFrom, "Zevra Alerts");
                helper.setTo(to.trim());
                helper.setSubject(buildEmailSubject(rule, anomaly));
                helper.setText(buildEmailHtml(rule, message, anomaly), true);
                mailSender.send(mime);
                log.info("Email alert sent to {} for rule {}", to.trim(), rule.ruleKey());
            }
        } catch (Exception e) {
            log.error("Email delivery failed for rule {}: {}", rule.ruleKey(), e.getMessage());
        }
    }

    private String buildEmailSubject(AlertRule rule, AnomalyEvent anomaly) {
        String sev = anomaly != null && anomaly.severity() != null ? "[" + anomaly.severity() + "] " : "";
        return sev + "Zevra Alert: " + rule.ruleName();
    }

    private String buildEmailHtml(AlertRule rule, String message, AnomalyEvent anomaly) {
        String severity  = anomaly != null ? anomaly.severity() : "MEDIUM";
        String metric    = anomaly != null && anomaly.metricName() != null ? anomaly.metricName() : rule.ruleName();
        String current   = anomaly != null && anomaly.observedValue() != null ? String.format("%.2f", anomaly.observedValue())  : "—";
        String deviation = anomaly != null && anomaly.deviationPct()  != null ? String.format("%.1f%%", anomaly.deviationPct()) : "—";
        String badgeColor = "CRITICAL".equals(severity) ? "#DC2626" :
                            "HIGH"    .equals(severity) ? "#EA580C" :
                            "MEDIUM"  .equals(severity) ? "#D97706" : "#6B7280";
        String emoji = severityEmoji(severity);

        return """
                <!DOCTYPE html>
                <html><head><meta charset="UTF-8"/></head>
                <body style="margin:0;padding:0;background:#F5F5FA;font-family:'Inter',Arial,sans-serif">
                  <table width="100%%" cellpadding="0" cellspacing="0" style="background:#F5F5FA;padding:32px 0">
                    <tr><td align="center">
                      <table width="600" cellpadding="0" cellspacing="0" style="background:white;border-radius:16px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,0.08)">
                        <!-- Header -->
                        <tr><td style="background:linear-gradient(135deg,#0D1117,#1a2235);padding:28px 36px">
                          <table width="100%%" cellpadding="0" cellspacing="0"><tr>
                            <td><img src="%s" alt="Zevra" style="height:28px"/></td>
                            <td align="right"><span style="background:%s;color:white;padding:4px 12px;border-radius:20px;font-size:12px;font-weight:700">%s %s</span></td>
                          </tr></table>
                        </td></tr>
                        <!-- Content -->
                        <tr><td style="padding:32px 36px">
                          <h1 style="margin:0 0 8px;font-size:20px;font-weight:700;color:#111827">%s</h1>
                          <p style="margin:0 0 24px;font-size:14px;color:#6B7280">Operational anomaly detected</p>
                          <div style="background:#F9FAFB;border:1px solid #E5E7EB;border-radius:12px;padding:20px;margin-bottom:24px">
                            <p style="margin:0;font-size:14px;color:#374151;line-height:1.6">%s</p>
                          </div>
                          <!-- Stats grid -->
                          <table width="100%%" cellpadding="0" cellspacing="0" style="margin-bottom:28px">
                            <tr>
                              <td width="33%%" style="padding:12px;background:#F9FAFB;border-radius:8px;margin:4px;text-align:center">
                                <div style="font-size:11px;color:#9CA3AF;font-weight:600;text-transform:uppercase;letter-spacing:.06em;margin-bottom:4px">Metric</div>
                                <div style="font-size:14px;font-weight:600;color:#111827">%s</div>
                              </td>
                              <td width="4px"></td>
                              <td width="33%%" style="padding:12px;background:#F9FAFB;border-radius:8px;text-align:center">
                                <div style="font-size:11px;color:#9CA3AF;font-weight:600;text-transform:uppercase;letter-spacing:.06em;margin-bottom:4px">Current</div>
                                <div style="font-size:14px;font-weight:700;color:%s">%s</div>
                              </td>
                              <td width="4px"></td>
                              <td width="33%%" style="padding:12px;background:#F9FAFB;border-radius:8px;text-align:center">
                                <div style="font-size:11px;color:#9CA3AF;font-weight:600;text-transform:uppercase;letter-spacing:.06em;margin-bottom:4px">Deviation</div>
                                <div style="font-size:14px;font-weight:700;color:%s">%s</div>
                              </td>
                            </tr>
                          </table>
                          <a href="%s" style="display:inline-block;background:#111827;color:white;padding:12px 24px;border-radius:8px;text-decoration:none;font-size:14px;font-weight:600">Investigate in Zevra →</a>
                        </td></tr>
                        <!-- Footer -->
                        <tr><td style="padding:20px 36px;border-top:1px solid #F3F4F6;font-size:12px;color:#9CA3AF">
                          This alert was triggered by rule <strong>%s</strong>. Manage your alert rules in Zevra.
                        </td></tr>
                      </table>
                    </td></tr>
                  </table>
                </body></html>
                """.formatted(
                appUrl + "/logo.png",
                badgeColor, emoji, severity,
                rule.ruleName(),
                escapeHtml(message),
                escapeHtml(metric),
                badgeColor, current,
                badgeColor, deviation,
                appUrl + "/#/chat",
                escapeHtml(rule.ruleName()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String severityEmoji(String severity) {
        if (severity == null) return "⚠️";
        return switch (severity.toUpperCase()) {
            case "CRITICAL" -> "🚨";
            case "HIGH"     -> "🔴";
            case "MEDIUM"   -> "🟡";
            default         -> "⚪";
        };
    }

    private BigDecimal toBD(Double d) {
        return d != null ? BigDecimal.valueOf(d) : null;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
