package com.sei.nexus.alert;

import java.math.BigDecimal;
import java.time.Instant;

public record AlertDelivery(
        String     deliveryKey,
        String     ruleKey,
        String     ruleName,
        String     anomalyKey,
        String     channel,
        String     metricName,
        BigDecimal currentValue,
        BigDecimal baselineValue,
        BigDecimal deviationPct,
        String     severity,
        String     messageText,
        String     status,   // UNREAD | READ | FAILED
        Instant    sentAt,
        Instant    readAt
) {}
