package com.sei.nexus.pack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** An alert rule template bundled with an industry pack. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PackAlertTemplate(
        String  name,
        String  description,
        String  metricHint,        // guidance for the metric SQL, not enforced
        String  thresholdType,     // ABOVE | BELOW
        Double  thresholdValue,
        String  severity           // LOW | MEDIUM | HIGH | CRITICAL
) {}
