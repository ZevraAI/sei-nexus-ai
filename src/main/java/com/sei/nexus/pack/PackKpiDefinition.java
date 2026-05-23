package com.sei.nexus.pack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A KPI template bundled with an industry pack. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PackKpiDefinition(
        String  name,
        String  formulaDescription,
        String  targetRange,
        Double  alertThreshold,   // null = no automatic alert
        String  alertSeverity     // LOW | MEDIUM | HIGH | CRITICAL
) {}
