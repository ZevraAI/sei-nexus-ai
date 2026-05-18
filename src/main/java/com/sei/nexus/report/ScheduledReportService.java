package com.sei.nexus.report;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.alert.NotificationDeliveryService;
import com.sei.nexus.chat.ChatRequest;
import com.sei.nexus.chat.ChatResponse;
import com.sei.nexus.chat.ChatService;
import com.sei.nexus.common.Keys;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.tenant.TenantContext;
import com.sei.nexus.tenant.TenantRepository;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ScheduledReportService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledReportService.class);

    private final ScheduledReportRepository reportRepository;
    private final ChatService               chatService;
    private final ReportHtmlComposer        htmlComposer;
    private final JavaMailSender            mailSender;
    private final TenantRepository          tenantRepository;
    private final ObjectMapper              objectMapper;

    @Value("${spring.mail.username:}")
    private String mailFrom;

    @Value("${nexus.alerts.app-url:http://localhost:5176}")
    private String appUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public ScheduledReportService(ScheduledReportRepository reportRepository,
                                   ChatService chatService,
                                   ReportHtmlComposer htmlComposer,
                                   JavaMailSender mailSender,
                                   TenantRepository tenantRepository,
                                   ObjectMapper objectMapper) {
        this.reportRepository = reportRepository;
        this.chatService      = chatService;
        this.htmlComposer     = htmlComposer;
        this.mailSender       = mailSender;
        this.tenantRepository = tenantRepository;
        this.objectMapper     = objectMapper;
    }

    // ── Scheduler — checks every minute across all tenant schemas ─────────────

    @Scheduled(fixedDelay = 60_000)
    public void runDueReports() {
        List<String> schemas = new ArrayList<>();
        schemas.add(TenantContext.PUBLIC_SCHEMA);
        try {
            tenantRepository.findAll().stream()
                    .filter(t -> "ACTIVE".equals(t.status()))
                    .map(com.sei.nexus.tenant.Tenant::schemaName)
                    .forEach(schemas::add);
        } catch (Exception e) {
            log.warn("Could not load tenant list for report scheduler: {}", e.getMessage());
        }

        for (String schema : schemas) {
            TenantContext.set(schema);
            try {
                List<ScheduledReport> due = reportRepository.findDue(Instant.now());
                if (due.isEmpty()) continue;
                log.info("Running {} due report(s) in schema '{}'", due.size(), schema);
                for (ScheduledReport report : due) {
                    try {
                        executeReport(report, report.createdBy() != null ? report.createdBy() : "system");
                    } catch (Exception e) {
                        log.error("Report '{}' failed in schema {}: {}", report.name(), schema, e.getMessage());
                        reportRepository.updateRunResult(
                                report.reportKey(), "FAILED", e.getMessage(),
                                Instant.now(),
                                ReportScheduleHelper.computeNextRunAt(report, Instant.now()));
                    }
                }
            } finally {
                TenantContext.clear();
            }
        }
    }

    // ── Run a report (also called from controller for on-demand preview) ───────

    public ReportRunResult executeReport(ScheduledReport report, String runAsEmail) {
        log.info("Executing report '{}' for user {}", report.name(), runAsEmail);

        List<String> questions = parseQuestions(report.questionsJson());
        if (questions.isEmpty()) {
            throw new NexusException(HttpStatus.BAD_REQUEST,
                    "Report has no questions configured");
        }

        List<ReportSection> sections = new ArrayList<>();
        List<String> errors          = new ArrayList<>();

        for (int i = 0; i < questions.size(); i++) {
            String question = questions.get(i);
            try {
                ChatRequest req = new ChatRequest(report.agentKey(), Keys.conversationKey(), question);
                ChatResponse resp = chatService.ask(req, runAsEmail);

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> queryData = resp.queryData() != null
                        ? (List<Map<String, Object>>) resp.queryData() : List.of();

                sections.add(new ReportSection(i + 1, question, resp.answer(), queryData,
                        resp.decision() != null ? resp.decision().type() : null));
            } catch (Exception e) {
                log.warn("Question {} failed in report '{}': {}", i + 1, report.name(), e.getMessage());
                sections.add(new ReportSection(i + 1, question,
                        "Unable to retrieve answer: " + e.getMessage(), List.of(), "ERROR"));
                errors.add("Q" + (i + 1) + ": " + e.getMessage());
            }
        }

        String schedDesc = ReportScheduleHelper.describe(report);
        String channel   = report.channel() != null ? report.channel() : "EMAIL";

        if ("EMAIL".equals(channel) || "BOTH".equals(channel)) {
            deliverEmail(report, sections, schedDesc);
        }
        if ("SLACK".equals(channel) || "BOTH".equals(channel)) {
            deliverSlack(report, sections, schedDesc);
        }

        String status  = errors.isEmpty() ? "SUCCESS" : "PARTIAL";
        String message = errors.isEmpty() ? null : String.join("; ", errors);

        reportRepository.updateRunResult(
                report.reportKey(), status, message,
                Instant.now(),
                ReportScheduleHelper.computeNextRunAt(report, Instant.now()));

        return new ReportRunResult(status, sections.size(), errors, schedDesc);
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    public ScheduledReport createReport(Map<String, Object> body, String userEmail) {
        String questionsJson = toQuestionsJson(body);
        Instant now  = Instant.now();
        ScheduledReport r = new ScheduledReport(
                Keys.uniqueKey("report"),
                required(body, "name"),
                (String) body.get("description"),
                questionsJson,
                (String) body.get("agentKey"),
                strOr(body, "scheduleType",  "WEEKLY"),
                strOr(body, "scheduleTime",  "08:00"),
                (String) body.get("scheduleDayOfWeek"),
                body.containsKey("scheduleDayOfMonth")
                        ? ((Number) body.get("scheduleDayOfMonth")).intValue() : null,
                strOr(body, "timezone",      "UTC"),
                strOr(body, "channel",       "EMAIL"),
                (String) body.get("slackWebhook"),
                (String) body.get("emailTo"),
                "ACTIVE",
                null,
                null,   // next_run_at computed below
                null, null,
                userEmail, now, now);

        Instant nextRun = ReportScheduleHelper.computeNextRunAt(r, now);
        ScheduledReport withNext = new ScheduledReport(
                r.reportKey(), r.name(), r.description(), r.questionsJson(), r.agentKey(),
                r.scheduleType(), r.scheduleTime(), r.scheduleDayOfWeek(), r.scheduleDayOfMonth(),
                r.timezone(), r.channel(), r.slackWebhook(), r.emailTo(), r.status(),
                null, nextRun, null, null, r.createdBy(), r.createdAt(), r.updatedAt());

        reportRepository.save(withNext);
        return withNext;
    }

    public ScheduledReport updateReport(String reportKey, Map<String, Object> body) {
        ScheduledReport existing = reportRepository.findByKey(reportKey)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "Report not found: " + reportKey));

        String newType    = strOr(body, "scheduleType",  existing.scheduleType());
        String newTime    = strOr(body, "scheduleTime",  existing.scheduleTime());
        String newDow     = body.containsKey("scheduleDayOfWeek")
                ? (String) body.get("scheduleDayOfWeek") : existing.scheduleDayOfWeek();
        Integer newDom    = body.containsKey("scheduleDayOfMonth")
                ? ((Number) body.get("scheduleDayOfMonth")).intValue() : existing.scheduleDayOfMonth();

        ScheduledReport updated = new ScheduledReport(
                reportKey,
                strOr(body, "name",         existing.name()),
                body.containsKey("description")
                        ? (String) body.get("description") : existing.description(),
                body.containsKey("questions")
                        ? toQuestionsJson(body) : existing.questionsJson(),
                body.containsKey("agentKey")
                        ? (String) body.get("agentKey") : existing.agentKey(),
                newType, newTime, newDow, newDom,
                strOr(body, "timezone",     existing.timezone()),
                strOr(body, "channel",      existing.channel()),
                body.containsKey("slackWebhook")
                        ? (String) body.get("slackWebhook") : existing.slackWebhook(),
                body.containsKey("emailTo")
                        ? (String) body.get("emailTo") : existing.emailTo(),
                strOr(body, "status",       existing.status()),
                existing.lastRunAt(), existing.nextRunAt(),
                existing.lastRunStatus(), existing.lastRunMessage(),
                existing.createdBy(), existing.createdAt(), Instant.now());

        // Recompute next run only if schedule changed
        ScheduledReport final_ = updated;
        if (!newType.equals(existing.scheduleType())
                || !newTime.equals(existing.scheduleTime())
                || (newDow != null && !newDow.equals(existing.scheduleDayOfWeek()))
                || (newDom != null && !newDom.equals(existing.scheduleDayOfMonth()))) {
            Instant next = ReportScheduleHelper.computeNextRunAt(updated, Instant.now());
            final_ = new ScheduledReport(
                    updated.reportKey(), updated.name(), updated.description(),
                    updated.questionsJson(), updated.agentKey(),
                    updated.scheduleType(), updated.scheduleTime(),
                    updated.scheduleDayOfWeek(), updated.scheduleDayOfMonth(),
                    updated.timezone(), updated.channel(), updated.slackWebhook(),
                    updated.emailTo(), updated.status(),
                    updated.lastRunAt(), next,
                    updated.lastRunStatus(), updated.lastRunMessage(),
                    updated.createdBy(), updated.createdAt(), updated.updatedAt());
        }

        reportRepository.save(final_);
        return final_;
    }

    public void deleteReport(String reportKey) {
        reportRepository.delete(reportKey);
    }

    public List<ScheduledReport> listReports() {
        return reportRepository.findAll();
    }

    // ── Delivery ──────────────────────────────────────────────────────────────

    private void deliverEmail(ScheduledReport report, List<ReportSection> sections, String schedDesc) {
        if (report.emailTo() == null || report.emailTo().isBlank()) {
            log.warn("Email delivery configured but no recipients for report {}", report.reportKey());
            return;
        }
        if (mailFrom == null || mailFrom.isBlank()) {
            log.warn("Email delivery skipped for report '{}' — SMTP not configured", report.name());
            return;
        }
        try {
            String html = htmlComposer.composeEmail(report, sections, schedDesc);
            String subject = report.name() + " — " + schedDesc;
            for (String to : report.emailTo().split(",\\s*")) {
                if (to.isBlank()) continue;
                MimeMessage mime = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
                helper.setFrom(mailFrom, "Zevra Reports");
                helper.setTo(to.trim());
                helper.setSubject(subject);
                helper.setText(html, true);
                mailSender.send(mime);
                log.info("Report '{}' emailed to {}", report.name(), to.trim());
            }
        } catch (Exception e) {
            log.error("Email delivery failed for report '{}': {}", report.name(), e.getMessage());
        }
    }

    private void deliverSlack(ScheduledReport report, List<ReportSection> sections, String schedDesc) {
        if (report.slackWebhook() == null || report.slackWebhook().isBlank()) {
            log.warn("Slack delivery configured but no webhook for report {}", report.reportKey());
            return;
        }
        try {
            String text    = htmlComposer.composeSlackText(report, sections, schedDesc);
            String payload = buildSlackPayload(text, report);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(report.slackWebhook()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                log.info("Report '{}' delivered to Slack", report.name());
            } else {
                log.warn("Slack returned {} for report '{}'", resp.statusCode(), report.name());
            }
        } catch (Exception e) {
            log.error("Slack delivery failed for report '{}': {}", report.name(), e.getMessage());
        }
    }

    private String buildSlackPayload(String text, ScheduledReport report) {
        String safeText = text.replace("\"", "\\\"").replace("\n", "\\n");
        return String.format("""
                {
                  "blocks": [
                    {"type":"section","text":{"type":"mrkdwn","text":"%s"}},
                    {"type":"actions","elements":[
                      {"type":"button","text":{"type":"plain_text","text":"Open in Zevra"},
                       "url":"%s/#/chat","style":"primary"}
                    ]}
                  ]
                }
                """, safeText, appUrl);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<String> parseQuestions(String json) {
        try {
            if (json == null || json.isBlank() || json.equals("[]")) return List.of();
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Could not parse questions JSON: {}", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private String toQuestionsJson(Map<String, Object> body) {
        try {
            Object qs = body.get("questions");
            if (qs == null) return "[]";
            return objectMapper.writeValueAsString(qs);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String required(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new NexusException(HttpStatus.BAD_REQUEST, key + " is required");
        }
        return v.toString();
    }

    private String strOr(Map<String, Object> body, String key, String fallback) {
        Object v = body.get(key);
        return (v != null && !v.toString().isBlank()) ? v.toString() : fallback;
    }

    // ── Inner result type ─────────────────────────────────────────────────────

    public record ReportRunResult(
            String       status,
            int          sectionCount,
            List<String> errors,
            String       scheduleDescription
    ) {}
}
