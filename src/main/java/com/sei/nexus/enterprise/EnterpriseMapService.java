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
    // Textual source types eligible for OBSERVED value-domain probing (PRO-24)
    private static final Set<String> OBSERVABLE_TEXT_TYPES =
            Set.of("character varying", "character", "text", "varchar", "bpchar", "char",
                   "varchar2", "nvarchar2");

    // OBSERVED value-domain discovery (PRO-24): cardinality gate — a column with
    // more distinct values than this gets no domain, by construction.
    @org.springframework.beans.factory.annotation.Value("${nexus.metadata.observed-domain-max-values:25}")
    private int observedDomainMaxValues = 25;

    // Per-table probe budget: at most this many columns are sampled per scan.
    @org.springframework.beans.factory.annotation.Value("${nexus.metadata.observed-columns-per-table:8}")
    private int observedColumnsPerTable = 8;

    private final EnterpriseMapRepository repository;
    private final ConnectionRepository connectionRepository;
    private final DynamicSqlService dynamicSqlService;
    private final SqlSafetyService sqlSafetyService;
    private final AzureOpenAiClient aiClient;
    private final ObjectMapper objectMapper;
    private final com.sei.nexus.semantic.EntityCandidateService entityCandidates;

    public EnterpriseMapService(EnterpriseMapRepository repository,
                                 ConnectionRepository connectionRepository,
                                 DynamicSqlService dynamicSqlService,
                                 SqlSafetyService sqlSafetyService,
                                 AzureOpenAiClient aiClient,
                                 ObjectMapper objectMapper,
                                 com.sei.nexus.semantic.EntityCandidateService entityCandidates) {
        this.repository = repository;
        this.connectionRepository = connectionRepository;
        this.dynamicSqlService = dynamicSqlService;
        this.sqlSafetyService = sqlSafetyService;
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
        this.entityCandidates = entityCandidates;
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

        // Load all data objects for the agent's domains
        List<DataObject> dataObjects = new ArrayList<>();
        for (String dk : agentDomainKeys) {
            dataObjects.addAll(repository.findDataObjectsByDomain(dk));
        }

        // Narrow to the agent's approved connection keys when present.
        // If the filter produces zero results (e.g. the connection was deleted and
        // a new one added with a different key), fall back to all domain objects so
        // the agent can still answer questions — a stale connection key on the agent
        // record should not silently block all queries.
        if (agentConnectionKeys != null && !agentConnectionKeys.isEmpty()) {
            List<DataObject> filtered = dataObjects.stream()
                    .filter(o -> agentConnectionKeys.contains(o.connectionKey()))
                    .collect(java.util.stream.Collectors.toList());
            if (!filtered.isEmpty()) {
                dataObjects = filtered;
            }
            // else: filtered is empty — keep all domain objects as fallback
        }

        // Load operational notes for those domains
        List<OperationalNote> notes = new ArrayList<>();
        for (String dk : agentDomainKeys) {
            notes.addAll(repository.findNotesByDomain(dk));
        }

        // Resolve a fallback connection key once — used when a data object's stored
        // connection has been deleted. Avoids re-querying on every iteration.
        String fallbackConnKey = null;

        // Value domains looked up once per key — several columns can share one
        // enum type across the object list (PRO-10/PRO-24).
        Map<String, Optional<ValueDomain>> domainCache = new HashMap<>();

        // Build entity context string. Each object's rendered section is also
        // captured as a discrete block (object_key → block text) so the context
        // assembler (ChatService) can reorder blocks by relevance before applying
        // its token budget. Rendering here stays question-agnostic: concatenating
        // the block values in map order reproduces entityContext exactly.
        Map<String, String> entityBlocks = new LinkedHashMap<>();
        StringBuilder entityContext = new StringBuilder();
        for (DataObject obj : dataObjects) {
            int blockStart = entityContext.length();
            // If the data object's stored connection no longer exists, substitute the
            // first active connection so the AI always receives a usable key.
            String effectiveConnKey = obj.connectionKey();
            if (connectionRepository.findByKey(effectiveConnKey).isEmpty()) {
                if (fallbackConnKey == null) {
                    fallbackConnKey = connectionRepository.findAll().stream()
                            .findFirst()
                            .map(c -> c.connectionKey())
                            .orElse(effectiveConnKey);
                }
                effectiveConnKey = fallbackConnKey;
                log.warn("Data object '{}' references deleted connection '{}'; substituting '{}' in context",
                        obj.tableName(), obj.connectionKey(), effectiveConnKey);
            }

            entityContext.append("Table: ").append(obj.schemaName()).append(".").append(obj.tableName())
                    .append(" (").append(obj.businessName()).append(")\n");
            entityContext.append("Purpose: ").append(orEmpty(obj.purpose())).append("\n");
            entityContext.append("connection_key=").append(effectiveConnKey)
                         .append(" (use this exact value in your SQL plan)\n");
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
                    // For domain-linked columns, enrich the type label with the
                    // legal/observed values so the planner never guesses literals.
                    // Authoritative (enum) domains REPLACE the opaque type (PRO-10):
                    //   status (store_status: open | temporarily_closed | ...)
                    // OBSERVED domains keep the type and append the sample (PRO-24):
                    //   state_province (character varying; observed: Texas | ...)
                    String typeLabel = col.dataType();
                    if (col.valueDomainKey() != null) {
                        Optional<ValueDomain> domain = domainCache.computeIfAbsent(
                                col.valueDomainKey(), repository::findValueDomainByKey);
                        if (domain.isPresent()) {
                            String rendered = renderValueDomain(domain.get());
                            if (!rendered.isBlank()) {
                                typeLabel = "OBSERVED".equalsIgnoreCase(domain.get().source())
                                        ? col.dataType() + "; " + rendered
                                        : rendered;
                            }
                        }
                    }
                    entityContext.append("  - ").append(col.columnName())
                            .append(" (").append(typeLabel).append(")");
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
            entityBlocks.put(obj.objectKey(), entityContext.substring(blockStart));
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
        ctx.put("entityBlocks", entityBlocks);
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

                // PRO-22 tier 1/2: offer existing-entity candidates so the AI can
                // reuse instead of minting a drifted duplicate. Zero prompt cost
                // when no candidates exist (both blocks render empty).
                var candidates = entityCandidates.retrieve(domainKey, tableName);

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
                        """ + entityCandidates.resolutionContract(candidates);

                String userMessage = "Domain: " + domainKey + "\nTable schema:\n" + schemaText
                        + entityCandidates.renderPromptBlock(candidates);
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
                        // Null in snapshots created before V034 — restored as absent.
                        (String) colMap.get("udtName"),
                        (String) colMap.get("valueDomainKey"),
                        // Null in snapshots created before V035 — treat as inferred.
                        colMap.get("roleSource") != null
                                ? (String) colMap.get("roleSource") : DataColumn.ROLE_INFERRED,
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

        // ── Semantic Role resolution (PRO-29) ────────────────────────────────
        // The persisted role is the source of truth for discovery decisions;
        // producers are advisory into it. Precedence per column:
        //   CONFIRMED (human)  — kept verbatim, never recomputed
        //   fresh declaration  — request roles merged with inference (DECLARED)
        //   persisted DECLARED — kept verbatim (a rescan without a request must
        //                        not lose an onboarding declaration)
        //   otherwise          — name-hint inference (INFERRED, recomputable)
        Map<String, RoleAssignment> roles = new HashMap<>();
        for (Map<String, Object> col : schemaInfo) {
            String colName = (String) col.getOrDefault("column_name", col.get("columnName"));
            if (colName == null) continue;
            roles.put(colName, resolveRole(colName, existingCols.get(colName.toLowerCase()),
                    explicitIdentifiers, explicitStatus, explicitExceptions, explicitFilters));
        }

        // ── Value-domain discovery (PRO-10) ─────────────────────────────────
        // When the table has USER-DEFINED (enum) columns, one batched pg_enum
        // query resolves the legal values of every enum type this table uses.
        // Domains are upserted by natural key (connection, schema, type) so
        // re-scans refresh rather than duplicate. Non-fatal by design: a
        // discovery failure must never break the column scan itself.
        Map<String, String> domainKeyByUdtName = new HashMap<>();
        try {
            Set<String> usedEnumTypes = new java.util.LinkedHashSet<>();
            for (Map<String, Object> col : schemaInfo) {
                String dt  = String.valueOf(col.getOrDefault("data_type", col.get("dataType")));
                String udt = (String) col.get("udt_name");
                if ("USER-DEFINED".equalsIgnoreCase(dt) && udt != null && !udt.isBlank()) {
                    usedEnumTypes.add(udt);
                }
            }
            if (!usedEnumTypes.isEmpty()) {
                Map<String, List<String>> enums =
                        dynamicSqlService.listEnumDomains(connectionKey, schemaName);
                for (String enumType : usedEnumTypes) {
                    List<String> values = enums.get(enumType);
                    if (values == null || values.isEmpty()) continue;
                    String domainKey = repository.upsertValueDomain(new ValueDomain(
                            Keys.uniqueKey("vdom"), connectionKey, schemaName,
                            enumType, "ENUM", true,
                            objectMapper.writeValueAsString(values), null));
                    domainKeyByUdtName.put(enumType, domainKey);
                }
            }
        } catch (Exception e) {
            log.warn("Value-domain discovery failed for {}.{}: {}",
                    schemaName, tableName, e.getMessage());
        }

        // ── OBSERVED value-domain discovery (PRO-24) ─────────────────────────
        // Eligibility consumes the RESOLVED Semantic Role (PRO-29) — the
        // persisted, stewardable assignment — never transient inference
        // directly. A human CONFIRMED veto (status=false or sensitive=true)
        // therefore blocks the probe durably; a persisted DECLARED role keeps
        // probing on request-less rescans. The probe IS the cardinality gate:
        // cap+1 rows back means high-cardinality, no domain. Domains persist as
        // source=OBSERVED, non-authoritative, named "<table>.<column>" so the
        // natural key never collides across tables. Non-fatal like enum discovery.
        Map<String, String> domainKeyByColumnName = new HashMap<>();
        try {
            int probed = 0;
            for (Map<String, Object> col : schemaInfo) {
                if (probed >= observedColumnsPerTable) break;
                String colName = (String) col.getOrDefault("column_name", col.get("columnName"));
                if (colName == null) continue;
                String dt = String.valueOf(col.getOrDefault("data_type", col.get("dataType")))
                        .toLowerCase();

                RoleAssignment role = roles.get(colName);
                if (role == null) continue;
                boolean textual = OBSERVABLE_TEXT_TYPES.contains(dt);
                // Discriminator = status role, or a filterable role that was
                // declared or human-confirmed (inferred filterable alone is too
                // broad — it includes every date-hinted and identifier column).
                boolean discriminator = role.status()
                        || (role.filterable() && !DataColumn.ROLE_INFERRED.equals(role.source()));
                if (!textual || role.identifier() || role.sensitive() || !discriminator) continue;

                probed++;
                List<String> values = dynamicSqlService.listDistinctValues(
                        connectionKey, schemaName, tableName, colName,
                        observedDomainMaxValues + 1);
                if (values.isEmpty() || values.size() > observedDomainMaxValues) continue;

                // Content classification (PRO-27): name gates decided the probe;
                // the sampled values themselves decide persistence. Fail-closed —
                // PII/free-text/identifier-shaped samples never become domains.
                // The reason is loggable; the values are not logged.
                String rejection = SampleContentClassifier.rejectReason(values);
                if (rejection != null) {
                    log.info("Observed domain rejected for {}.{}.{}: {}",
                            schemaName, tableName, colName, rejection);
                    continue;
                }

                List<String> sorted = new ArrayList<>(values);
                java.util.Collections.sort(sorted);
                String domainKey = repository.upsertValueDomain(new ValueDomain(
                        Keys.uniqueKey("vdom"), connectionKey, schemaName,
                        tableName + "." + colName, "OBSERVED", false,
                        objectMapper.writeValueAsString(sorted), null));
                domainKeyByColumnName.put(colName, domainKey);
            }
        } catch (Exception e) {
            log.warn("Observed value-domain discovery failed for {}.{}: {}",
                    schemaName, tableName, e.getMessage());
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

            // Semantic Role from the single resolution above (PRO-29) — the
            // loop persists the resolved role, it never recomputes it. Human
            // CONFIRMED assignments survive every rescan by construction.
            RoleAssignment role = roles.getOrDefault(colName,
                    resolveRole(colName, existing, explicitIdentifiers, explicitStatus,
                            explicitExceptions, explicitFilters));
            boolean isIdentifier = role.identifier();
            boolean isStatus     = role.status();
            boolean isError      = role.error();
            boolean isFilterable = role.filterable();
            boolean isSensitive  = role.sensitive();

            // Preserve user overrides on businessMeaning
            String businessMeaning = (existing != null && existing.businessMeaning() != null
                    && !existing.businessMeaning().isBlank())
                    ? existing.businessMeaning()
                    : (String) col.getOrDefault("columnComment", "");

            String columnKey = existing != null
                    ? existing.columnKey()
                    : Keys.uniqueKey("col");

            // Value-domain binding: link the column to its discovered domain —
            // enum (PRO-10) takes precedence, then OBSERVED (PRO-24). When this
            // scan produced no domain (non-Postgres source or transient discovery
            // failure), keep the existing link rather than erasing it.
            String udtName = (String) col.get("udt_name");
            if ((udtName == null || udtName.isBlank()) && existing != null) {
                udtName = existing.udtName();
            }
            String valueDomainKey = udtName != null ? domainKeyByUdtName.get(udtName) : null;
            if (valueDomainKey == null) {
                valueDomainKey = domainKeyByColumnName.get(colName);
            }
            if (valueDomainKey == null && existing != null) {
                valueDomainKey = existing.valueDomainKey();
            }

            DataColumn dataColumn = new DataColumn(
                    columnKey, objectKey, colName, dataType, nullable,
                    businessMeaning, isIdentifier, isStatus, isError, isSensitive, isFilterable,
                    udtName, valueDomainKey, role.source(),
                    existing != null ? existing.createdAt() : now, now);

            repository.saveColumn(dataColumn);
            columns.add(dataColumn);
        }

        return columns;
    }

    /** A column's resolved Semantic Role — the persisted source of truth for
     *  Business Value Discovery decisions (PRO-28/PRO-29). */
    record RoleAssignment(boolean identifier, boolean status, boolean error,
                          boolean sensitive, boolean filterable, String source) {}

    /**
     * Resolves one column's Semantic Role with the PRO-28 precedence:
     * human CONFIRMED > fresh declaration > persisted DECLARED > inference.
     * Producers (name hints, declarations) are advisory into the persisted role;
     * only this method decides what the role IS for this scan.
     */
    private RoleAssignment resolveRole(String colName, DataColumn existing,
            Set<String> explicitIdentifiers, Set<String> explicitStatus,
            Set<String> explicitExceptions, Set<String> explicitFilters) {

        // Human assertions are authoritative — never recomputed away.
        if (existing != null && DataColumn.ROLE_CONFIRMED.equals(existing.roleSource())) {
            return new RoleAssignment(existing.isIdentifier(), existing.isStatus(),
                    existing.isError(), existing.isSensitive(), existing.isFilterable(),
                    DataColumn.ROLE_CONFIRMED);
        }

        boolean declaredNow = explicitIdentifiers.contains(colName)
                || explicitStatus.contains(colName)
                || explicitExceptions.contains(colName)
                || explicitFilters.contains(colName);

        // A previously persisted declaration survives request-less rescans.
        if (!declaredNow && existing != null
                && DataColumn.ROLE_DECLARED.equals(existing.roleSource())) {
            return new RoleAssignment(existing.isIdentifier(), existing.isStatus(),
                    existing.isError(), existing.isSensitive(), existing.isFilterable(),
                    DataColumn.ROLE_DECLARED);
        }

        // Inference (always recomputable), merged with any fresh declaration.
        String colLower = colName.toLowerCase();
        boolean identifier = inferFromHints(colLower, IDENTIFIER_HINTS)
                || explicitIdentifiers.contains(colName);
        boolean status     = inferFromHints(colLower, STATUS_HINTS)
                || explicitStatus.contains(colName);
        boolean error      = inferFromHints(colLower, ERROR_HINTS)
                || explicitExceptions.contains(colName);
        boolean filterable = inferFromHints(colLower, FILTERABLE_HINTS)
                || status || identifier
                || explicitFilters.contains(colName);
        boolean sensitive  = isSensitiveName(colLower);

        return new RoleAssignment(identifier, status, error, sensitive, filterable,
                declaredNow ? DataColumn.ROLE_DECLARED : DataColumn.ROLE_INFERRED);
    }

    /** True when a column name suggests sensitive content (shared by role
     *  inference and the OBSERVED probe eligibility gate — sensitive columns
     *  are never sampled). */
    private static boolean isSensitiveName(String colLower) {
        return colLower.contains("password") || colLower.contains("secret")
                || colLower.contains("ssn") || colLower.contains("credit")
                || colLower.contains("card") || colLower.contains("token");
    }

    /**
     * Renders a value domain as a compact type label for the LLM context.
     * Authoritative domains name their type: {@code store_status: open | …}
     * (PRO-10). OBSERVED domains render as {@code observed: Texas | …} — the
     * "observed" label is the honesty marker for non-authoritative, sampled
     * values; the table-qualified domain name would be redundant next to its
     * own column (PRO-24). Long domains are capped to keep the entity context
     * within its truncation budget.
     */
    private String renderValueDomain(ValueDomain domain) {
        final int maxValues = 20;
        String label = "OBSERVED".equalsIgnoreCase(domain.source())
                ? "observed" : domain.domainName();
        try {
            List<String> values = objectMapper.readValue(
                    domain.domainValuesJson(), new TypeReference<List<String>>() {});
            if (values.isEmpty()) return label;
            List<String> shown = values.size() > maxValues
                    ? values.subList(0, maxValues) : values;
            String joined = String.join(" | ", shown);
            if (values.size() > maxValues) joined += " | …";
            return label + ": " + joined;
        } catch (Exception e) {
            return label;
        }
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
                m.put("udtName", c.udtName());
                m.put("valueDomainKey", c.valueDomainKey());
                m.put("roleSource", c.roleSource());
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
