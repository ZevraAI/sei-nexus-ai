package com.sei.nexus.report;

import java.util.List;
import java.util.Map;

/**
 * Holds the result of running a single question in the context of a report.
 * Collected for all questions then assembled into the final report document.
 */
public record ReportSection(
        int                          sectionNumber,
        String                       question,
        String                       answer,
        List<Map<String, Object>>    queryData,   // raw rows for the HTML table
        String                       decisionType
) {}
