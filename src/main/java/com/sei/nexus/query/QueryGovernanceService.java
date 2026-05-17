package com.sei.nexus.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.common.Keys;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.connection.ConnectionRepository;
import com.sei.nexus.connection.NexusConnection;
import com.sei.nexus.enterprise.DataObject;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import com.sei.nexus.sql.DynamicSqlService;
import com.sei.nexus.sql.SqlSafetyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.regex.*;

@Service
public class QueryGovernanceService {

    private static final Logger log = LoggerFactory.getLogger(QueryGovernanceService.class);

    @Value("${nexus.query-governance.max-sync-rows:500}")
    private long maxSyncRows;
    @Value("${nexus.query-governance.max-async-rows:10000}")
    private long maxAsyncRows;
    @Value("${nexus.query-governance.sync-timeout-seconds:30}")
    private int syncTimeout;
    @Value("${nexus.query-governance.async-timeout-seconds:90}")
    private int asyncTimeout;
    @Value("${nexus.query-governance.point-lookup-timeout-seconds:10}")
    private int pointLookupTimeout;
    @Value("${nexus.query-governance.max-joins:4}")
    private int maxJoins;
    @Value("${nexus.query-governance.default-row-limit:100}")
    private int defaultRowLimit;
    @Value("${nexus.query-governance.max-row-limit:500}")
    private int maxRowLimit;

    public record GovernanceResult(
        String executionKey, String classification, String route, String riskLevel,
        String approvedSql, String decisionReason, long estimatedRows, int rowLimit, int timeoutSeconds
    ) {}

    private final SqlSafetyService sqlSafetyService;
    private final DynamicSqlService dynamicSqlService;
    private final QueryExecutionRepository executionRepository;
    private final ConnectionRepository connectionRepository;
    private final EnterpriseMapRepository enterpriseMapRepository;
    private final ObjectMapper objectMapper;

    public QueryGovernanceService(SqlSafetyService sqlSafetyService,
                                   DynamicSqlService dynamicSqlService,
                                   QueryExecutionRepository executionRepository,
                                   ConnectionRepository connectionRepository,
                                   EnterpriseMapRepository enterpriseMapRepository,
                                   ObjectMapper objectMapper) {
        this.sqlSafetyService = sqlSafetyService;
        this.dynamicSqlService = dynamicSqlService;
        this.executionRepository = executionRepository;
        this.connectionRepository = connectionRepository;
        this.enterpriseMapRepository = enterpriseMapRepository;
        this.objectMapper = objectMapper;
    }

    public GovernanceResult govern(String runKey, int stepNo, String agentKey,
            String connectionKey, String objectKeys, String sql, boolean forceAsync) {

        // Strip trailing semicolons before any processing
        sql = sql.stripTrailing();
        while (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).stripTrailing();
        }

        // 1. Safety check
        var safety = sqlSafetyService.validate(sql);
        if (!safety.safe()) {
            return saveAndReturn(runKey, stepNo, connectionKey, objectKeys, sql,
                "BLOCKED", "BLOCK", "CRITICAL", sql, safety.reason(), -1, defaultRowLimit, syncTimeout);
        }

        // 2. Load connection — try exact key first, then name match so the AI
        //    can use a display name and still resolve to the right connection.
        NexusConnection conn = connectionRepository.findByKeyOrName(connectionKey)
            .orElseThrow(() -> new NexusException(HttpStatus.BAD_REQUEST, "Connection not found: " + connectionKey));

        // 3. Validate tables in SQL are on allow-list
        List<String> sqlTables = extractTableNames(sql);
        for (String tbl : sqlTables) {
            if (!isTableAllowed(conn, tbl)) {
                return saveAndReturn(runKey, stepNo, connectionKey, objectKeys, sql,
                    "BLOCKED", "BLOCK", "CRITICAL", sql,
                    "Table not on connection allow-list: " + tbl, -1, defaultRowLimit, syncTimeout);
            }
        }

        // 4. Check join count
        int joinCount = countJoins(sql);
        if (joinCount > maxJoins) {
            return saveAndReturn(runKey, stepNo, connectionKey, objectKeys, sql,
                "BLOCKED", "BLOCK", "HIGH", sql,
                "Too many JOINs: " + joinCount + " (max " + maxJoins + ")", -1, defaultRowLimit, syncTimeout);
        }

        // 5. Load data objects to check identifiers, safe filters, large table flag
        List<DataObject> objects = loadObjects(objectKeys);
        boolean hasIdentifierFilter = hasIdentifierFilter(sql, objects);
        boolean hasSafeFilter = hasSafeFilter(sql, objects);
        boolean isLargeTable = objects.stream().anyMatch(DataObject::largeTable);

        // 6. Classify
        String classification = classify(sql, joinCount, hasIdentifierFilter, hasSafeFilter, isLargeTable);

        // 7. Estimate rows
        long estimatedRows = -1;
        try { estimatedRows = dynamicSqlService.estimateRowCount(connectionKey, sql); } catch (Exception e) {
            log.warn("Row estimation failed for run {}: {}", runKey, e.getMessage());
        }

        // 8. Determine row limit
        int rowLimit = Math.min(determineRowLimit(classification), maxRowLimit);

        // 9. Apply row limit to SQL
        String approvedSql = applyRowLimit(sql, rowLimit, conn.connectionType());

        // 10. Determine route
        String route = determineRoute(classification, estimatedRows, isLargeTable, hasSafeFilter, forceAsync);
        String riskLevel = determineRisk(classification, estimatedRows);
        int timeout = determineTimeout(classification);
        String reason = buildReason(classification, route, estimatedRows, rowLimit);

        return saveAndReturn(runKey, stepNo, connectionKey, objectKeys, sql,
            classification, route, riskLevel, approvedSql, reason, estimatedRows, rowLimit, timeout);
    }

    private GovernanceResult saveAndReturn(String runKey, int stepNo, String connectionKey,
            String objectKeys, String originalSql, String classification, String route,
            String riskLevel, String approvedSql, String reason, long estimatedRows,
            int rowLimit, int timeoutSeconds) {
        String executionKey = "exec-" + Keys.uniqueKey("exec");
        String status = "BLOCK".equals(route) ? "BLOCKED" : "PLANNED";
        QueryExecution qe = new QueryExecution(executionKey, runKey, stepNo, connectionKey, objectKeys,
            classification, route, riskLevel, status,
            estimatedRows < 0 ? null : estimatedRows,
            estimatedRows < 0 ? null : estimatedRows,
            timeoutSeconds, rowLimit,
            originalSql, approvedSql, reason, null, null, Instant.now(), null, null);
        executionRepository.save(qe);
        return new GovernanceResult(executionKey, classification, route, riskLevel,
            approvedSql, reason, estimatedRows, rowLimit, timeoutSeconds);
    }

    // Extract table names from SQL using regex on FROM/JOIN clauses
    private List<String> extractTableNames(String sql) {
        List<String> tables = new ArrayList<>();
        Pattern p = Pattern.compile("(?i)\\b(?:FROM|JOIN)\\s+([\\w\\.]+)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(sql);
        while (m.find()) tables.add(m.group(1).toUpperCase());
        return tables;
    }

    private int countJoins(String sql) {
        Pattern p = Pattern.compile("(?i)\\bJOIN\\b");
        Matcher m = p.matcher(sql);
        int count = 0;
        while (m.find()) count++;
        return count;
    }

    private boolean isTableAllowed(NexusConnection conn, String tableName) {
        if (conn.allowedTables() == null || conn.allowedTables().isBlank()) return true;
        String upper = tableName.toUpperCase();
        for (String allowed : conn.allowedTables().split(",")) {
            if (upper.contains(allowed.trim().toUpperCase())) return true;
        }
        return false;
    }

    private List<DataObject> loadObjects(String objectKeys) {
        if (objectKeys == null || objectKeys.isBlank()) return List.of();
        List<DataObject> result = new ArrayList<>();
        for (String key : objectKeys.split(",")) {
            enterpriseMapRepository.findDataObjectByKey(key.trim()).ifPresent(result::add);
        }
        return result;
    }

    private boolean hasIdentifierFilter(String sql, List<DataObject> objects) {
        if (objects.isEmpty()) return false;
        String upper = sql.toUpperCase();
        for (DataObject obj : objects) {
            if (obj.identifierColumns() == null) continue;
            for (String col : obj.identifierColumns().split(",")) {
                if (upper.contains("WHERE") && upper.contains(col.trim().toUpperCase())) return true;
            }
        }
        return false;
    }

    private boolean hasSafeFilter(String sql, List<DataObject> objects) {
        if (objects.isEmpty()) return sql.toUpperCase().contains("WHERE");
        String upper = sql.toUpperCase();
        for (DataObject obj : objects) {
            if (obj.safeFilterColumns() == null) continue;
            for (String col : obj.safeFilterColumns().split(",")) {
                if (upper.contains(col.trim().toUpperCase())) return true;
            }
        }
        return sql.toUpperCase().contains("WHERE");
    }

    private String classify(String sql, int joinCount, boolean hasId, boolean hasSafe, boolean large) {
        String upper = sql.toUpperCase();
        boolean hasAgg = upper.matches(".*\\b(COUNT|SUM|AVG|MIN|MAX)\\s*\\(.*") || upper.contains("GROUP BY");
        if (hasId) return "POINT_LOOKUP";
        if (joinCount > 0) return "JOIN_INVESTIGATION";
        if (hasAgg) return "AGGREGATION";
        if (!hasSafe && large) return "HIGH_RISK_SCAN";
        return "BOUNDED_LIST";
    }

    private int determineRowLimit(String classification) {
        return switch (classification) {
            case "POINT_LOOKUP" -> Math.min(50, maxRowLimit);
            case "BOUNDED_LIST" -> defaultRowLimit;
            case "AGGREGATION" -> Math.min(200, maxRowLimit);
            case "JOIN_INVESTIGATION" -> defaultRowLimit;
            default -> defaultRowLimit;
        };
    }

    private String applyRowLimit(String sql, int rowLimit, String connType) {
        String trimmed = sql.stripTrailing();
        if ("ORACLE".equalsIgnoreCase(connType)) {
            if (!trimmed.toUpperCase().contains("FETCH FIRST")) {
                return trimmed + " FETCH FIRST " + rowLimit + " ROWS ONLY";
            }
        } else {
            if (!trimmed.toUpperCase().contains("LIMIT")) {
                return trimmed + " LIMIT " + rowLimit;
            }
        }
        return trimmed;
    }

    private String determineRoute(String cls, long rows, boolean large, boolean safe, boolean force) {
        if ("HIGH_RISK_SCAN".equals(cls) && !safe) return "ASK_FOR_FILTER";
        if ("BLOCKED".equals(cls)) return "BLOCK";
        if (force) return "EXECUTE_ASYNC";
        if (rows > 0 && rows > maxSyncRows) return "EXECUTE_ASYNC";
        return "EXECUTE_SYNC";
    }

    private String determineRisk(String cls, long rows) {
        return switch (cls) {
            case "POINT_LOOKUP" -> "LOW";
            case "BOUNDED_LIST" -> rows > maxSyncRows ? "MEDIUM" : "LOW";
            case "AGGREGATION" -> "MEDIUM";
            case "JOIN_INVESTIGATION" -> "MEDIUM";
            case "HIGH_RISK_SCAN" -> "HIGH";
            case "BLOCKED" -> "CRITICAL";
            default -> "MEDIUM";
        };
    }

    private int determineTimeout(String cls) {
        return "POINT_LOOKUP".equals(cls) ? pointLookupTimeout : syncTimeout;
    }

    private String buildReason(String cls, String route, long rows, int limit) {
        return String.format("Classified as %s. Route: %s. Estimated rows: %d. Row limit applied: %d.",
            cls, route, rows, limit);
    }

    // Async executor: polls QUEUED executions and runs them
    @Scheduled(fixedDelay = 5000)
    public void processQueuedExecutions() {
        List<QueryExecution> queued = executionRepository.findQueued(5);
        for (QueryExecution qe : queued) {
            runAsync(qe);
        }
    }

    @Async
    public void runAsync(QueryExecution qe) {
        executionRepository.updateStatus(qe.executionKey(), "RUNNING", Instant.now(), null, null);
        try {
            List<Map<String, Object>> rows = dynamicSqlService.executeQuery(
                qe.connectionKey(), qe.approvedSql(), qe.rowLimit() != null ? qe.rowLimit() : defaultRowLimit);
            String json = objectMapper.writeValueAsString(rows);
            executionRepository.updateResult(qe.executionKey(), json, "SUCCESS", Instant.now());
        } catch (Exception e) {
            log.error("Async execution {} failed: {}", qe.executionKey(), e.getMessage());
            executionRepository.updateStatus(qe.executionKey(), "FAILED", null, Instant.now(), e.getMessage());
        }
    }
}
