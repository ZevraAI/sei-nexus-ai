package com.sei.nexus.semanticmodel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.enterprise.DataColumn;
import com.sei.nexus.enterprise.DataObject;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import com.sei.nexus.enterprise.ValueDomain;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Transforms the Enterprise Map persistence model ({@code DataObject}/{@code DataColumn})
 * into Zevra's canonical semantic model ({@link BusinessObject}/{@link BusinessAttribute}/
 * {@link Relationship}) — ADR-0003 semantic model, Phase 1A.
 *
 * <p>The Enterprise Map remains the authoritative business catalog; this component is a
 * derivation over it — a projection now, and the single home for enrichment/curation
 * later. It is the sole boundary through which the semantic model is produced; AgentBrain
 * will consume its output (Phase 1B) and depend on nothing else from the Enterprise Map.
 *
 * <p><b>Phase 1A is purely additive.</b> This assembler is not yet wired into AgentBrain,
 * the ExecutionContract, the prompt pipeline, or the Runtime — runtime behavior is
 * unchanged.
 */
@Service
public class EnterpriseSemanticAssembler {

    private final EnterpriseMapRepository enterpriseMap;
    private final ObjectMapper            objectMapper;

    // Two constructors exist (the second is a test convenience), so the injection point must be
    // marked explicitly — otherwise Spring cannot choose and falls back to a non-existent no-arg
    // constructor at startup.
    @org.springframework.beans.factory.annotation.Autowired
    public EnterpriseSemanticAssembler(EnterpriseMapRepository enterpriseMap,
                                       ObjectMapper objectMapper) {
        this.enterpriseMap = enterpriseMap;
        this.objectMapper  = objectMapper;
    }

    /** Backward-compatible convenience (tests): parses persisted value-domain JSON with a default mapper. */
    public EnterpriseSemanticAssembler(EnterpriseMapRepository enterpriseMap) {
        this(enterpriseMap, new ObjectMapper());
    }

    /**
     * Assembles the semantic model for the given connection scope (a ZevraAgent's
     * connection keys): the approved (non-archived) business objects reachable through
     * those connections, each composed of its business attributes.
     */
    public SemanticModel assemble(List<String> connectionKeys) {
        return project(enterpriseMap.findDataObjectsByConnectionKeys(connectionKeys));
    }

    /**
     * Assembles the semantic model for a business-domain scope: the approved (non-archived)
     * business objects belonging to those domains, each composed of its business attributes.
     *
     * <p>Selection primitive only. Which scope to assemble — and how domain and connection scope
     * combine — is a business-reasoning decision owned by {@code AgentBrain}; this component just
     * projects the requested scope.
     */
    public SemanticModel assembleByDomains(List<String> domainKeys) {
        return project(enterpriseMap.findDataObjectsByDomainKeys(domainKeys));
    }

    /** Projects Enterprise Map objects into the canonical semantic model. */
    private SemanticModel project(List<DataObject> objects) {
        List<BusinessObject> businessObjects = new ArrayList<>();
        Map<String, PhysicalTable>  objectTargets    = new LinkedHashMap<>();
        Map<String, PhysicalColumn> attributeTargets = new LinkedHashMap<>();
        // Value domains are looked up once per key — several columns can share one enum type
        // across the object list. This is projection of owned metadata, not discovery.
        Map<String, Optional<ValueDomain>> domainCache = new LinkedHashMap<>();

        for (DataObject object : objects) {
            List<BusinessAttribute> attributes = new ArrayList<>();
            for (DataColumn column : enterpriseMap.findColumnsByObject(object.objectKey())) {
                attributes.add(new BusinessAttribute(
                        column.columnKey(),                 // stable semantic identity
                        attributeName(column),
                        deriveRole(column)));
                // execution-plane raw material — never placed on the semantic types
                attributeTargets.put(column.columnKey(), new PhysicalColumn(
                        object.connectionKey(), object.schemaName(), object.tableName(),
                        column.columnName(), column.dataType(),
                        valueDomainOf(object, column, domainCache)));
            }
            businessObjects.add(new BusinessObject(
                    object.objectKey(),                     // stable semantic identity
                    objectName(object),
                    object.purpose(),                       // Tier-1 identity aid
                    guidance(object),
                    attributes,
                    List.of()));                            // relationships: Phase 3
            objectTargets.put(object.objectKey(), new PhysicalTable(
                    object.connectionKey(), object.schemaName(), object.tableName()));
        }
        return new SemanticModel(businessObjects, objectTargets, attributeTargets);
    }

    // ── value-domain projection (owned by the Enterprise Map, projected here) ──

    /**
     * Projects a column's persisted value domain into the semantic {@link ColumnValueDomain}.
     * The domain was discovered at scan time and is owned by the Enterprise Map, linked by the
     * stable {@code DataColumn.valueDomainKey}; this reads and parses that owned metadata — it
     * never samples or discovers values. {@code null} when the column has no domain.
     */
    private ColumnValueDomain valueDomainOf(DataObject object, DataColumn column,
                                            Map<String, Optional<ValueDomain>> cache) {
        if (column.valueDomainKey() == null || column.valueDomainKey().isBlank()) return null;
        ValueDomain domain = cache
                .computeIfAbsent(column.valueDomainKey(), enterpriseMap::findValueDomainByKey)
                .orElse(null);
        if (domain == null) return null;
        List<String> values = parseValues(domain.domainValuesJson());
        if (values.isEmpty()) return null;
        return new ColumnValueDomain(object.tableName(), column.columnName(),
                domain.isAuthoritative(), values);
    }

    private List<String> parseValues(String domainValuesJson) {
        if (domainValuesJson == null || domainValuesJson.isBlank()) return List.of();
        try {
            return objectMapper.readValue(domainValuesJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();   // malformed persisted JSON must never break assembly
        }
    }

    // ── projection helpers ─────────────────────────────────────────────────────

    /**
     * Derives the initial analytical role from existing Enterprise Map column metadata.
     * Precedence: identifier → dimension (status/filterable) → measure (numeric) → attribute.
     * This thin heuristic is refined by curation in later phases.
     */
    private AttributeRole deriveRole(DataColumn column) {
        if (column.isIdentifier())                       return AttributeRole.IDENTIFIER;
        if (column.isStatus() || column.isFilterable())  return AttributeRole.DIMENSION;
        if (isNumeric(column.dataType()))                return AttributeRole.MEASURE;
        return AttributeRole.ATTRIBUTE;
    }

    private static boolean isNumeric(String dataType) {
        if (dataType == null) return false;
        String t = dataType.toLowerCase(Locale.ROOT);
        return t.contains("int") || t.contains("numeric") || t.contains("decimal")
                || t.contains("real") || t.contains("double") || t.contains("float")
                || t.contains("money");
    }

    private static String attributeName(DataColumn column) {
        return (column.businessMeaning() != null && !column.businessMeaning().isBlank())
                ? column.businessMeaning() : column.columnName();
    }

    private static String objectName(DataObject object) {
        return (object.businessName() != null && !object.businessName().isBlank())
                ? object.businessName() : object.entityName();
    }

    private static String guidance(DataObject object) {
        StringBuilder sb = new StringBuilder();
        appendIf(sb, object.usageGuidance());
        appendIf(sb, object.filterGuidance());
        appendIf(sb, object.avoidGuidance());
        return sb.toString().trim();
    }

    private static void appendIf(StringBuilder sb, String s) {
        if (s != null && !s.isBlank()) sb.append(s.trim()).append(' ');
    }
}
