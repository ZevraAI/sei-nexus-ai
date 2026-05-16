package com.sei.nexus.query;

import com.sei.nexus.common.NexusException;
import com.sei.nexus.run.RunRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping
public class QueryExecutionController {

    private final QueryExecutionRepository executionRepository;
    private final RunRepository runRepository;

    public QueryExecutionController(QueryExecutionRepository executionRepository,
                                     RunRepository runRepository) {
        this.executionRepository = executionRepository;
        this.runRepository = runRepository;
    }

    /**
     * GET /query-executions/{executionKey}
     * Returns the full execution record as a map.
     */
    @GetMapping("/query-executions/{executionKey}")
    public ResponseEntity<Map<String, Object>> getExecution(@PathVariable String executionKey) {
        QueryExecution qe = executionRepository.findByKey(executionKey)
            .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                "Query execution not found: " + executionKey));
        return ResponseEntity.ok(toMap(qe));
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private Map<String, Object> toMap(QueryExecution qe) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("executionKey", qe.executionKey());
        m.put("runKey", qe.runKey());
        m.put("stepNo", qe.stepNo());
        m.put("connectionKey", qe.connectionKey());
        m.put("objectKeys", qe.objectKeys());
        m.put("classification", qe.classification());
        m.put("route", qe.route());
        m.put("riskLevel", qe.riskLevel());
        m.put("status", qe.status());
        m.put("estimatedRows", qe.estimatedRows());
        m.put("estimatedCost", qe.estimatedCost());
        m.put("timeoutSeconds", qe.timeoutSeconds());
        m.put("rowLimit", qe.rowLimit());
        m.put("originalSql", qe.originalSql());
        m.put("approvedSql", qe.approvedSql());
        m.put("decisionReason", qe.decisionReason());
        m.put("errorMessage", qe.errorMessage());
        m.put("resultJson", qe.resultJson());
        m.put("createdAt", qe.createdAt());
        m.put("startedAt", qe.startedAt());
        m.put("completedAt", qe.completedAt());
        return m;
    }
}
