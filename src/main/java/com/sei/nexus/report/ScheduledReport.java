package com.sei.nexus.report;

import java.time.Instant;

public record ScheduledReport(
        String  reportKey,
        String  name,
        String  description,
        String  questionsJson,        // JSON array of question strings
        String  agentKey,
        String  scheduleType,         // DAILY | WEEKLY | MONTHLY
        String  scheduleTime,         // HH:mm  e.g. "08:00"
        String  scheduleDayOfWeek,    // MON–SUN (WEEKLY only)
        Integer scheduleDayOfMonth,   // 1–28   (MONTHLY only)
        String  timezone,             // e.g. "America/New_York"
        String  channel,              // EMAIL | SLACK | BOTH
        String  slackWebhook,
        String  emailTo,              // comma-separated
        String  status,               // ACTIVE | PAUSED | ARCHIVED
        Instant lastRunAt,
        Instant nextRunAt,
        String  lastRunStatus,        // SUCCESS | FAILED
        String  lastRunMessage,
        String  createdBy,
        Instant createdAt,
        Instant updatedAt
) {}
