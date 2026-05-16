package com.sei.nexus.enterprise;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.common.Keys;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.connection.ConnectionRepository;
import com.sei.nexus.sql.DynamicSqlService;
import com.sei.nexus.sql.SqlSafetyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class EnterpriseMapService {

    private static final Logger log = LoggerFactory.getLogger(EnterpriseMapService.class);

    private static final Set<String> IDENTIFIER_HINTS =
            Set.of("id", "key", "no", "num", "number", "code", "ref", "uuid", "pk");
    private static final Set<String> STATUS_HINTS =
            Set.of("status", "state", "type", "stage", "phase", "flag", "ind", "indicator");
    private static final Set<String> ERROR_HINTS =
            Set.of("err", "error", "exception", "msg", "message", "reason", "reject", "fail");
    private static final Set<String> FILTERABLE_HINTS =
            Set.of("date", "time", "at", "on", "created", "updated", "modified", "timestamp");

    private final EnterpriseMapRepository repository;
    private final ConnectionRepository connectionRepository;
    private final DynamicSqlService dynamicSqlService;
    private final SqlSafetyService sqlSafetyService;
    private final AzureOpenAiClient aiClient;
    private final ObjectMapper objectMapper;

    public EnterpriseMapService(EnterpriseMapRepository repository,
                                 ConnectionRepository connectionRepository,
                                 DynamicSqlService dynamicSqlService,
                                 SqlSafetyService sqlSafetyService,
                                 AzureOpenAiClient aiClient,
                                 ObjectMapper objectMapper) {
        this.repository = repository;
        this.connectionRepository = connectionRepository;
        this.dynamicSqlService = dynamicSqlService;
        this.sqlSafetyService = sqlSafetyService;
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Create or update a data object
    // -------------------------------------------------------------------------

    @Transactional
    public DataObject createOrUpdateObject(Map<String, Object> request, String userEmail) {
        String domainKey     = required(request, "domainKey");
        String connectionKey = required(request, "connectionKey");
        String schemaName    = required(request, "schemaName");
        String tableName     = required(request, "tableName");

        // Validate connection exists
        connectionRepository.findByKey(connectionKey)
                .orElseThrow(() -> new NexusException(HttpStatus.BAD_REQUEST,
                        "Connection not found: " + connectionKey));

        // Validate table is on connection allow-list
        validateTableAllowed(connectionKey, schemaName, tableName);

        String objectKey = Keys.key(domainKey + "-" + connectionKey + "-" + tableName);

        // Determine version number
        Optional<DataObject> existing = repository.findDataObjectByKey(objectKey);
        int versionNo = existing.map(o -> o.versionNo() + 1).orElse(1);

        Instant now = Instant.now();
        DataObject obj = new DataObject(
                objectKey,
                domainKey,
                (String) request.getOrDefault("entityName", tableName),
                connectionKey,
                schemaName,
                tableName,
                (String) request.getOrDefault("businessName", tableName),
                (String) request.getOrDefault("purpose", ""),
                (String) request.getOrDefault("identifierColumns", ""),
                (String) request.getOrDefault("statusColumns", ""),
                (String) request.getOrDefault("exceptionColumns", ""),
                (String) request.getOrDefault("safeFilterColumns", ""),
                (String) request.getOrDefault("usageGuidance", ""),
                (String) request.getOrDefault("filterGuidance", ""),
                (String) request.getOrDefault("avoidGuidance", ""),
                request.get("rowLimit") != null ? ((Number) request.get("rowLimit")).intValue() : 500,
                Boolean.TRUE.equals(request.get("largeTable")),
                "PENDING",
                versionNo,
                existing.map(DataObject::createdAt).orElse(now),
                now);

        repository.saveDataObject(obj);

        // Scan columns from live DB
        List<DataColumn> columns = scanAndSaveColumns(objectKey, connectionKey, schemaName, tableName,
                request, existing.isPresent());

        // Update scan status
        jdbc_updateScanStatus(objectKey, "SCANNED");

        // Create version snapshot
        createVersionSnapshot(obj, columns, versionNo, "CREATE_OR_UPDATE");

        return repository.findDataObjectByKey(objectKey).orElse(obj);
    }

    // -------------------------------------------------------------------------
    // Scan object columns from live DB
    // -------------------------------------------------------------------------

    @Transactional
    public DataObject scanObject(String objectKey) {
        DataObject obj = repository.findDataObjectByKey(objectKey)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "Data object not found: " + objectKey));

        jdbc_updateScanStatus(objectKey, "PENDING");

        // Re-scan, preserving existing column overrides
        List<DataColumn> columns = scanAndSaveColumns(objectKey,
                obj.connectionKey(), obj.schemaName(), obj.tableName(),
                Map.of(), true);

        jdbc_updateScanStatus(objectKey, "SCANNED");

        int newVersion = obj.versionNo() + 1;
        jdbc_incrementVersion(objectKey, newVersion);

        DataObject updated = repository.findDataObjectByKey(objectKey).orElse(obj);
        createVersionSnapshot(updated, columns, newVersion, "RESCAN");

        return updated;
    }

    // -------------------------------------------------------------------------
    // Operational context for LLM
    // -------------------------------------------------------------------------

    public Map<String, Object> operationalContext(List<String> agentDomainKeys,
                                                   List<String> agentConnectionKeys,
                                                   String question) {
        Map<String, Object> ctx = new LinkedHashMap<>();

        // Load data objects for agent's domains
        List<DataObject> dataObjects = new ArrayList<>();
        for (String dk : agentDomainKeys) {
            dataObjects.addAll(repository.findDataObjectsByDomain(dk));
        }
        // Also include objects reachable via connection keys
        if (agentConnectionKeys != null) {
            for (DataObject o : new ArrayList<>(dataObjects)) {
                if (!agentConnectionKeys.contains(o.connectionKey())) {
                    dataObjects.remove(o);
                }
            }
        }

        // Load operational notes for those domains
        List<OperationalNote> notes = new ArrayList<>();
        for (String dk : agentDomainKeys) {
            notes.addAll(repository.findNotesByDomain(dk));
        }

        // Build entity context string
        StringBuilder entityContext = new StringBuilder();
        for (DataObject obj : dataObjects) {
            entityContext.append("Table: ").append(obj.schemaName()).append(".").append(obj.tableName())
                    .append(" (").append(obj.businessName()).append(")\n");
            entityContext.append("Purpose: ").append(orEmpty(obj.purpose())).append("\n");
            entityContext.append("Connection: ").append(obj.connectionKey()).append("\n");
            if (obj.identifierColumns() != null && !obj.identifierColumns().isBlank()) {
                entityContext.append("Identifier columns: ").append(obj.identifierColumns()).append("\n");
            }
            if (obj.statusColumns() != null && !obj.statusColumns().isBlank()) {
                entityContext.append("Status columns: ").append(obj.statusColumns()).append("\n");
            }
            if (obj.usageGuidance() != null && !obj.usageGuidance().isBlank()) {
                entityContext.append("Usage: ").append(obj.usageGuidance()).append("\n");
            }
            if (obj.filterGuidance() != null && !obj.filterGuidance().isBlank()) {
                entityContext.append("Filter guidance: ").append(obj.filterGuidance()).append("\n");
            }
            if (obj.avoidGuidance() != null && !obj.avoidGuidance().isBlank()) {
                entityContext.append("Avoid: ").append(obj.avoidGuidance()).append("\n");
            }
            if (obj.rowLimit() != null) {
                entityContext.append("Row limit: ").append(obj.rowLimit()).append("\n");
            }

            // Columns
            List<DataColumn> cols = repository.findColumnsByObject(obj.objectKey());
            if (!cols.isEmpty()) {
                entityContext.append("Columns:\n");
                for (DataColumn col : cols) {
                    entityContext.append("  - ").append(col.columnName())
                            .append(" (").append(col.dataType()).append(")");
                    if (col.businessMeaning() != null && !col.businessMeaning().isBlank()) {
                        entityContext.append(": ").append(col.businessMeaning());
                    }
                    List<String> flags = new ArrayList<>();
                    if (col.isIdentifier()) flags.add("identifier");
                    if (col.isStatus())     flags.add("status");
                    if (col.isError())      flags.add("error");
                    if (col.isSensitive())  flags.add("sensitive");
                    if (col.isFilterable()) flags.add("filterable");
                    if (!flags.isEmpty()) {
                        entityContext.append(" [").append(String.join(", ", flags)).append("]");
                    }
                    entityContext.append("\n");
                }
            }
            entityContext.append("\n");
        }

        // Operational notes
        StringBuilder notesContext = new StringBuilder();
        for (OperationalNote note : notes) {
            notesContext.append("NOTE: ").append(note.title()).append("\n")
                    .append(note.noteText()).append("\n\n");
        }

        ctx.put("dataObjects", dataObjects);
        ctx.put("notes", notes);
        ctx.put("entityContext", entityContext.toString());
        ctx.put("notesContext", notesContext.toString());

        return ctx;
    }

    // -------------------------------------------------------------------------
    // Analyze tables for onboarding
    // -------------------------------------------------------------------------

    public Map<String, Object> analyzeForOnboarding(Map<String, Object> request) {
        String domainKey     = required(request, "domainKey");
        String connectionKey = required(request, "connectionKey");
        String schemaName    = required(request, "schemaName");

        @SuppressWarnings("unchecked")
        List<String> tableNames = (List<String>) request.get("tableNames");
        if (tableNames == null || tableNames.isEmpty()) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "tableNames is required");
        }

        connectionRepository.findByKey(connectionKey)
                .orElseThrow(() -> new NexusException(HttpStatus.BAD_REQUEST,
                        "Connection not found: " + connectionKey));

        List<Map<String, Object>> tableDrafts = new ArrayList<>();

        for (String tableName : tableNames) {
            try {
                List<Map<String, Object>> schemaInfo = dynamicSqlService.describeTable(
                        connectionKey, schemaName, tableName);

                String schemaText = buildSchemaText(schemaName, tableName, schemaInfo);

                String systemPrompt = """
                        You are an enterprise data analyst helping onboard a new data source to SEI Nexus.
                        Analyze the table schema and provide a structured understanding of the data object.

                        Respond with valid JSON only matching this structure:
                        {
                          "entityName": "...",
                          "businessName": "...",
                          "purpose": "...",
                          "identifierColumns": ["..."],
                          "statusColumns": ["..."],
                          "exceptionColumns": ["..."],
                          "safeFilterColumns": ["..."],
                          "usageGuidance": "...",
                          "filterGuidance": "...",
                          "avoidGuidance": "...",
                          "lifecycleStates": ["..."],
                          "vocabularySuggestions": [{"term": "...", "definition": "..."}],
                          "relationshipHints": ["..."],
                          "readinessScore": 0.0
                        }
                        """;

                String userMessage = "Domain: " + domainKey + "\nTable schema:\n" + schemaText;
                String analysisJson = aiClient.chatWithJson(
                        List.of(ChatMessage.user(userMessage)), systemPrompt);

                Map<String, Object> analysis = parseJson(analysisJson);
                Map<String, Object> draft = new LinkedHashMap<>();
                draft.put("tableName", tableName);
                draft.put("schemaName", schemaName);
                draft.put("connectionKey", connectionKey);
                draft.put("domainKey", domainKey);
                draft.put("columns", schemaInfo);
                draft.putAll(analysis);
                tableDrafts.add(draft);

            } catch (Exception e) {
                log.warn("Failed to analyze table {}.{}: {}", schemaName, tableName, e.getMessage());
                Map<String, Object> errorDraft = new LinkedHashMap<>();
                errorDraft.put("tableName", tableName);
                errorDraft.put("error", e.getMessage());
                tableDrafts.add(errorDraft);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("domainKey", domainKey);
        result.put("connectionKey", connectionKey);
        result.put("schemaName", schemaName);
        result.put("tables", tableDrafts);
        return result;
    }

    // -------------------------------------------------------------------------
    // Simulate prompt against draft
    // -------------------------------------------------------------------------

    public Map<String, Object> simulate(String prompt, Map<String, Object> draft) {
        if (prompt == null || prompt.isBlank()) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "prompt is required");
        }

        String systemPrompt = """
                You are a SQL generation assistant for SEI Nexus.
                Given a table schema description (draft) and a user prompt, generate a safe, read-only SQL query.
                Respond with valid JSON:
                {
                  "sql": "SELECT ...",
                  "meaning": "Plain-English description of what the query returns",
                  "confidence": 0.0
                }
                """;

        String userMessage = "Draft schema:\n" + toJson(draft) + "\n\nUser prompt: " + prompt;
        String responseJson = aiClient.chatWithJson(List.of(ChatMessage.user(userMessage)), systemPrompt);
        Map<String, Object> parsed = parseJson(responseJson);

        String sql = (String) parsed.get("sql");
        Map<String, Object> result = new LinkedHashMap<>(parsed);

        if (sql != null && !sql.isBlank()) {
            try {
                sqlSafetyService.validate(sql);
                result.put("safe", true);
            } catch (Exception e) {
                result.put("safe", false);
                result.put("safetyViolation", e.getMessage());
            }
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Rollback to previous version
    // -------------------------------------------------------------------------

    @Transactional
    public DataObject rollback(String objectKey, int versionNo) {
        DataObject current = repository.findDataObjectByKey(objectKey)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "Data object not found: " + objectKey));

        String snapshotJson = repository.findVersionSnapshot(objectKey, versionNo)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "Version " + versionNo + " not found for object: " + objectKey));

        try {
            Map<String, Object> snapshot = objectMapper.readValue(snapshotJson,
                    new TypeReference<Map<String, Object>>() {});

            @SuppressWarnings("unchecked")
            Map<String, Object> objMap = (Map<String, Object>) snapshot.get("object");

            int newVersion = current.versionNo() + 1;
            Instant now = Instant.now();

            DataObject restored = new DataObject(
                    objectKey,
                    (String) objMap.get("domainKey"),
                    (String) objMap.get("entityName"),
                    (String) objMap.get("connectionKey"),
                    (String) objMap.get("schemaName"),
                    (String) objMap.get("tableName"),
                    (String) objMap.get("businessName"),
                    (String) objMap.get("purpose"),
                    (String) objMap.get("identifierColumns"),
                    (String) objMap.get("statusColumns"),
                    (String) objMap.get("exceptionColumns"),
                    (String) objMap.get("safeFilterColumns"),
                    (String) objMap.get("usageGuidance"),
                    (String) objMap.get("filterGuidance"),
                    (String) objMap.get("avoidGuidance"),
                    objMap.get("rowLimit") != null ? ((Number) objMap.get("rowLimit")).intValue() : 500,
                    Boolean.TRUE.equals(objMap.get("largeTable")),
                    "SCANNED",
                    newVersion,
                    current.createdAt(),
                    now);

            repository.saveDataObject(restored);

            // Restore columns from snapshot
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> columnMaps =
                    (List<Map<String, Object>>) snapshot.getOrDefault("columns", List.of());
            for (Map<String, Object> colMap : columnMaps) {
                DataColumn col = new DataColumn(
                        (String) colMap.get("columnKey"),
                        objectKey,
                        (String) colMap.get("columnName"),
                        (String) colMap.get("dataType"),
                        Boolean.TRUE.equals(colMap.get("isNullable")),
                        (String) colMap.get("businessMeaning"),
                        Boolean.TRUE.equals(colMap.get("isIdentifier")),
                        Boolean.TRUE.equals(colMap.get("isStatus")),
                        Boolean.TRUE.equals(colMap.get("isError")),
                        Boolean.TRUE.equals(colMap.get("isSensitive")),
                        Boolean.TRUE.equals(colMap.get("isFilterable")),
                        now, now);
                repository.saveColumn(col);
            }

            List<DataColumn> restoredCols = repository.findColumnsByObject(objectKey);
            createVersionSnapshot(restored, restoredCols, newVersion,
                    "ROLLBACK_TO_V" + versionNo);

            return repository.findDataObjectByKey(objectKey).orElse(restored);

        } catch (NexusException e) {
            throw e;
        } catch (Exception e) {
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Rollback failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Catalog search
    // -------------------------------------------------------------------------

    public List<Map<String, Object>> searchCatalog(String connectionKey, String schemaName, String query) {
        connectionRepository.findByKey(connectionKey)
                .orElseThrow(() -> new NexusException(HttpStatus.BAD_REQUEST,
                        "Connection not found: " + connectionKey));

        try {
            return dynamicSqlService.listTables(connectionKey, schemaName, query);
        } catch (Exception e) {
            log.warn("Catalog search failed for connection {}: {}", connectionKey, e.getMessage());
            return List.of();
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private List<DataColumn> scanAndSaveColumns(String objectKey, String connectionKey,
                                                 String schemaName, String tableName,
                                                 Map<String, Object> request,
                                                 boolean preserveOverrides) {
        List<Map<String, Object>> schemaInfo;
        try {
            schemaInfo = dynamicSqlService.describeTable(connectionKey, schemaName, tableName);
        } catch (Exception e) {
            log.warn("Failed to describe table {}.{}: {}", schemaName, tableName, e.getMessage());
            return List.of();
        }

        // Explicit column role overrides from request
        Set<String> explicitIdentifiers = splitToSet((String) request.get("identifierColumns"));
        Set<String> explicitStatus      = splitToSet((String) request.get("statusColumns"));
        Set<String> explicitExceptions  = splitToSet((String) request.get("exceptionColumns"));
        Set<String> explicitFilters     = splitToSet((String) request.get("safeFilterColumns"));

        // Load existing columns if preserving overrides
        Map<String, DataColumn> existingCols = new HashMap<>();
        if (preserveOverrides) {
            repository.findColumnsByObject(objectKey)
                    .forEach(c -> existingCols.put(c.columnName().toLowerCase(), c));
        }

        List<DataColumn> columns = new ArrayList<>();
        Instant now = Instant.now();

        for (Map<String, Object> col : schemaInfo) {
            String colName  = (String) col.get("column_name");
            if (colName == null) colName = (String) col.get("columnName"); // fallback
            if (colName == null) continue;
            String dataType = (String) col.getOrDefault("data_type",
                              col.getOrDefault("dataType", "unknown"));
            Object nullableVal = col.getOrDefault("is_nullable", col.get("isNullable"));
            boolean nullable = "YES".equalsIgnoreCase(String.valueOf(nullableVal))
                    || Boolean.TRUE.equals(nullableVal);

            String colLower = colName.toLowerCase();
            DataColumn existing = existingCols.get(colLower);

            // Infer roles from column name
            boolean isIdentifier = inferFromHints(colLower, IDENTIFIER_HINTS)
                    || explicitIdentifiers.contains(colName);
            boolean isStatus     = inferFromHints(colLower, STATUS_HINTS)
                    || explicitStatus.contains(colName);
            boolean isError      = inferFromHints(colLower, ERROR_HINTS)
                    || explicitExceptions.contains(colName);
            boolean isFilterable = inferFromHints(colLower, FILTERABLE_HINTS)
                    || isStatus || isIdentifier
                    || explicitFilters.contains(colName);
            boolean isSensitive  = colLower.contains("password") || colLower.contains("secret")
                    || colLower.contains("ssn") || colLower.contains("credit")
                    || colLower.contains("card") || colLower.contains("token");

            // Preserve user overrides on businessMeaning
            String businessMeaning = (existing != null && existing.businessMeaning() != null
                    && !existing.businessMeaning().isBlank())
                    ? existing.businessMeaning()
                    : (String) col.getOrDefault("columnComment", "");

            String columnKey = existing != null
                    ? existing.columnKey()
                    : Keys.uniqueKey("col");

            DataColumn dataColumn = new DataColumn(
                    columnKey, objectKey, colName, dataType, nullable,
                    businessMeaning, isIdentifier, isStatus, isError, isSensitive, isFilterable,
                    existing != null ? existing.createdAt() : now, now);

            repository.saveColumn(dataColumn);
            columns.add(dataColumn);
        }

        return columns;
    }

    private void createVersionSnapshot(DataObject obj, List<DataColumn> columns,
                                        int versionNo, String reason) {
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            Map<String, Object> objMap = new LinkedHashMap<>();
            objMap.put("objectKey", orEmpty(obj.objectKey()));
            objMap.put("domainKey", orEmpty(obj.domainKey()));
            objMap.put("entityName", orEmpty(obj.entityName()));
            objMap.put("connectionKey", orEmpty(obj.connectionKey()));
            objMap.put("schemaName", orEmpty(obj.schemaName()));
            objMap.put("tableName", orEmpty(obj.tableName()));
            objMap.put("businessName", orEmpty(obj.businessName()));
            objMap.put("purpose", orEmpty(obj.purpose()));
            objMap.put("identifierColumns", orEmpty(obj.identifierColumns()));
            objMap.put("statusColumns", orEmpty(obj.statusColumns()));
            objMap.put("exceptionColumns", orEmpty(obj.exceptionColumns()));
            objMap.put("safeFilterColumns", orEmpty(obj.safeFilterColumns()));
            objMap.put("usageGuidance", orEmpty(obj.usageGuidance()));
            objMap.put("filterGuidance", orEmpty(obj.filterGuidance()));
            objMap.put("avoidGuidance", orEmpty(obj.avoidGuidance()));
            objMap.put("rowLimit", obj.rowLimit());
            objMap.put("largeTable", obj.largeTable());
            snapshot.put("object", objMap);

            List<Map<String, Object>> colMaps = columns.stream().map(c -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("columnKey", c.columnKey());
                m.put("columnName", c.columnName());
                m.put("dataType", c.dataType());
                m.put("isNullable", c.isNullable());
                m.put("businessMeaning", c.businessMeaning());
                m.put("isIdentifier", c.isIdentifier());
                m.put("isStatus", c.isStatus());
                m.put("isError", c.isError());
                m.put("isSensitive", c.isSensitive());
                m.put("isFilterable", c.isFilterable());
                return m;
            }).collect(Collectors.toList());
            snapshot.put("columns", colMaps);

            String snapshotJson = objectMapper.writeValueAsString(snapshot);
            repository.saveDataObjectVersion(obj.objectKey(), versionNo, snapshotJson, reason);
        } catch (Exception e) {
            log.warn("Failed to create version snapshot for {}: {}", obj.objectKey(), e.getMessage());
        }
    }

    private void validateTableAllowed(String connectionKey, String schemaName, String tableName) {
        connectionRepository.findByKey(connectionKey).ifPresent(conn -> {
            String allowedTables = conn.allowedTables();
            if (allowedTables != null && !allowedTables.isBlank()) {
                String qualified = schemaName + "." + tableName;
                boolean allowed = false;
                for (String entry : allowedTables.split(",")) {
                    if (entry.trim().equalsIgnoreCase(qualified) || entry.trim().equals("*")) {
                        allowed = true;
                        break;
                    }
                }
                if (!allowed) {
                    throw new NexusException(HttpStatus.FORBIDDEN,
                            "Table " + qualified + " is not on the allow-list for connection " + connectionKey);
                }
            }
        });
    }

    private String buildSchemaText(String schemaName, String tableName,
                                    List<Map<String, Object>> schemaInfo) {
        StringBuilder sb = new StringBuilder();
        sb.append("Schema: ").append(schemaName).append(", Table: ").append(tableName).append("\n");
        sb.append("Columns:\n");
        for (Map<String, Object> col : schemaInfo) {
            Object nullableVal = col.getOrDefault("is_nullable", col.get("isNullable"));
            boolean isNullable = "YES".equalsIgnoreCase(String.valueOf(nullableVal))
                    || Boolean.TRUE.equals(nullableVal);
            sb.append("  ").append(col.getOrDefault("column_name", col.get("columnName")))
              .append(" ").append(col.getOrDefault("data_type", col.get("dataType")))
              .append(isNullable ? " NULL" : " NOT NULL");
            Object comment = col.get("columnComment");
            if (comment != null && !comment.toString().isBlank()) {
                sb.append(" -- ").append(comment);
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private boolean inferFromHints(String colLower, Set<String> hints) {
        for (String hint : hints) {
            if (colLower.contains(hint)) return true;
        }
        return false;
    }

    private Set<String> splitToSet(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Set.of(csv.split(",")).stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
    }

    private void jdbc_updateScanStatus(String objectKey, String status) {
        // Delegated to repository via direct jdbc in service to avoid cyclic dependency
        repository.findDataObjectByKey(objectKey).ifPresent(obj -> {
            DataObject updated = new DataObject(
                    obj.objectKey(), obj.domainKey(), obj.entityName(), obj.connectionKey(),
                    obj.schemaName(), obj.tableName(), obj.businessName(), obj.purpose(),
                    obj.identifierColumns(), obj.statusColumns(), obj.exceptionColumns(),
                    obj.safeFilterColumns(), obj.usageGuidance(), obj.filterGuidance(),
                    obj.avoidGuidance(), obj.rowLimit(), obj.largeTable(),
                    status, obj.versionNo(), obj.createdAt(), Instant.now());
            repository.saveDataObject(updated);
        });
    }

    private void jdbc_incrementVersion(String objectKey, int newVersion) {
        repository.findDataObjectByKey(objectKey).ifPresent(obj -> {
            DataObject updated = new DataObject(
                    obj.objectKey(), obj.domainKey(), obj.entityName(), obj.connectionKey(),
                    obj.schemaName(), obj.tableName(), obj.businessName(), obj.purpose(),
                    obj.identifierColumns(), obj.statusColumns(), obj.exceptionColumns(),
                    obj.safeFilterColumns(), obj.usageGuidance(), obj.filterGuidance(),
                    obj.avoidGuidance(), obj.rowLimit(), obj.largeTable(),
                    obj.scanStatus(), newVersion, obj.createdAt(), Instant.now());
            repository.saveDataObject(updated);
        });
    }

    private String required(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null || val.toString().isBlank()) {
            throw new NexusException(HttpStatus.BAD_REQUEST, key + " is required");
        }
        return val.toString();
    }

    private String orEmpty(String s) {
        return s != null ? s : "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse JSON: {}", e.getMessage());
            return Map.of();
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
