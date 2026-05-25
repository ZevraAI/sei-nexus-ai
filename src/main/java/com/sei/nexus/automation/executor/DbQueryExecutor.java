package com.sei.nexus.automation.executor;

import com.sei.nexus.automation.ExecutionContext;
import com.sei.nexus.automation.StepExecutor;
import com.sei.nexus.automation.VariableResolver;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.sql.DynamicSqlService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * DB_QUERY node — executes a parameterised SQL template against a named connection.
 *
 * Expected node config keys:
 *   connectionKey  — the nexus_connection key to query against
 *   sql            — SQL template with {{variable}} placeholders
 *   maxRows        — optional, default 100
 *
 * Output: List<Map<String,Object>> — the result rows
 */
@Component
public class DbQueryExecutor implements StepExecutor {

    private final DynamicSqlService dynamicSql;

    public DbQueryExecutor(DynamicSqlService dynamicSql) {
        this.dynamicSql = dynamicSql;
    }

    @Override
    public String nodeType() {
        return "DB_QUERY";
    }

    @Override
    public Object execute(String nodeId, Map<String, Object> config, ExecutionContext ctx) throws Exception {
        String connectionKey = getString(config, "connectionKey");
        String sqlTemplate   = getString(config, "sql");
        int maxRows = config.containsKey("maxRows")
                ? Integer.parseInt(config.get("maxRows").toString())
                : 100;

        if (connectionKey == null || connectionKey.isBlank())
            throw new NexusException(HttpStatus.BAD_REQUEST, "DB_QUERY node '" + nodeId + "' missing connectionKey");
        if (sqlTemplate == null || sqlTemplate.isBlank())
            throw new NexusException(HttpStatus.BAD_REQUEST, "DB_QUERY node '" + nodeId + "' missing sql");

        // Resolve {{variable}} placeholders in the SQL
        String resolvedSql = VariableResolver.resolve(sqlTemplate, ctx).toString();

        List<Map<String, Object>> rows = dynamicSql.executeQuery(connectionKey, resolvedSql, maxRows);
        return rows;
    }

    private String getString(Map<String, Object> config, String key) {
        Object v = config.get(key);
        return v == null ? null : v.toString();
    }
}
