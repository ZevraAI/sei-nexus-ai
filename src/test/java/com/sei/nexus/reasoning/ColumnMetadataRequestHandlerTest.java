package com.sei.nexus.reasoning;

import com.sei.nexus.agentbrain.ExecutionBindings;
import com.sei.nexus.agentbrain.ExecutionContract;
import com.sei.nexus.agentbrain.PromptAssembler;
import com.sei.nexus.agentbrain.PromptContextBuilder;
import com.sei.nexus.agentbrain.SemanticView;
import com.sei.nexus.enterprise.DataColumn;
import com.sei.nexus.enterprise.DataObject;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import com.sei.nexus.semanticmodel.AttributeRole;
import com.sei.nexus.semanticmodel.BusinessAttribute;
import com.sei.nexus.semanticmodel.BusinessObject;
import com.sei.nexus.semanticmodel.EnterpriseSemanticAssembler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Missing-Column Metadata Request — {@link ColumnMetadataRequestHandler}'s exact-match
 * validation and retrieval.
 *
 * <p>Java's ONLY roles here: (1) confirm the requested object exists by exact identity — first
 * against the request's already-resolved {@link ExecutionContract} (fast path), then, when not
 * found there, against the authoritative Enterprise Map catalog for the SAME already-approved
 * connections (the circular-dependency fix — see class javadoc on {@link
 * ColumnMetadataRequestHandler}); (2) retrieve its authoritative columns; (3) return nothing when
 * it exists in neither. No fuzzy matching, no ranking, no question inspection anywhere.
 *
 * <p>Fixtures are domain-neutral by construction (generic "line item"/"order" objects,
 * deliberately not tied to any one industry vocabulary) — the mechanism must work for any
 * tenant, industry, object, or column, per the feature's own requirement.
 */
class ColumnMetadataRequestHandlerTest {

    private static final String CONN = "conn-1";

    /** The common case for most tests: an empty Enterprise Map fallback catalog — proves the
     *  fallback never turns a genuinely-nonexistent object into a false positive. */
    private ColumnMetadataRequestHandler handler;

    @BeforeEach
    void setUp() {
        handler = handlerWithCatalog(List.of(), Map.of());
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    /** Builds one {@link BusinessObject} bound to {@code table}, with one attribute per column. */
    private static BusinessObject object(String objectKey, String businessName, String table,
                                         List<String> columns,
                                         Map<String, ExecutionBindings.ExecutionTarget> attributeBindingsOut) {
        List<BusinessAttribute> attributes = new ArrayList<>();
        for (String c : columns) {
            String attrKey = objectKey + "." + c;
            attributes.add(new BusinessAttribute(attrKey, c, AttributeRole.ATTRIBUTE));
            attributeBindingsOut.put(attrKey, new ExecutionBindings.ExecutionTarget("SQL", CONN, "public", table, c));
        }
        return new BusinessObject(objectKey, businessName, null, null, attributes, List.of());
    }

    private static ExecutionContract twoObjectContract() {
        Map<String, ExecutionBindings.ExecutionTarget> attributeBindings = new LinkedHashMap<>();
        BusinessObject lineItems = object("obj-line-items", "Order Line Item", "order_lines",
                List.of("id", "ordered_qty", "order_id"), attributeBindings);
        BusinessObject orders = object("obj-orders", "Order", "orders",
                List.of("id", "status"), attributeBindings);

        Map<String, ExecutionBindings.ExecutionTarget> objectBindings = Map.of(
                "obj-line-items", new ExecutionBindings.ExecutionTarget("SQL", CONN, "public", "order_lines", null),
                "obj-orders", new ExecutionBindings.ExecutionTarget("SQL", CONN, "public", "orders", null));

        Set<ExecutionBindings.ApprovedAsset> assets = Set.of(
                new ExecutionBindings.ApprovedAsset(CONN, "ORDER_LINES"),
                new ExecutionBindings.ApprovedAsset(CONN, "ORDERS"));

        return new ExecutionContract("ctr-1", Instant.now(), "agent-1", List.of(CONN), "hash",
                new SemanticView(List.of(lineItems, orders)),
                new ExecutionBindings(objectBindings, attributeBindings, assets));
    }

    /** A contract with an EMPTY resolved object set (e.g. Concept-Scoped Narrowing selected
     *  nothing) but still carrying the investigation's approved connection — exactly the
     *  circular-dependency scenario: nothing is resolved yet, but connections are approved. */
    private static ExecutionContract emptyContractForConnection() {
        return new ExecutionContract("ctr-empty", Instant.now(), "agent-1", List.of(CONN), "hash",
                new SemanticView(List.of()), new ExecutionBindings(Map.of(), Map.of(), Set.of()));
    }

    private static DataObject dataObject(String objectKey, String businessName, String table) {
        return new DataObject(objectKey, "domain-1", table, CONN, "public", table, businessName,
                null, null, null, null, null, null, null, null, null, false, "SCANNED", 1,
                Instant.now(), Instant.now());
    }

    private static DataColumn dataColumn(String objectKey, String columnName) {
        return new DataColumn(objectKey + "." + columnName, objectKey, columnName, "text", true,
                null, false, false, false, false, false, null, null,
                DataColumn.ROLE_INFERRED, Instant.now(), Instant.now());
    }

    /** A handler whose Enterprise Map fallback catalog is exactly {@code objects}/{@code columnsByObjectKey}. */
    private static ColumnMetadataRequestHandler handlerWithCatalog(List<DataObject> objects,
                                                                    Map<String, List<DataColumn>> columnsByObjectKey) {
        return handlerWithCatalog(objects, columnsByObjectKey, null);
    }

    /** Same as above, additionally recording every {@code connectionKeys} argument the fallback
     *  actually queried the Enterprise Map with, into {@code seenConnectionKeys} (nullable). */
    private static ColumnMetadataRequestHandler handlerWithCatalog(List<DataObject> objects,
            Map<String, List<DataColumn>> columnsByObjectKey, List<List<String>> seenConnectionKeys) {
        EnterpriseMapRepository fakeRepo = new EnterpriseMapRepository(null) {
            @Override
            public List<DataObject> findDataObjectsByConnectionKeys(List<String> connectionKeys) {
                if (seenConnectionKeys != null) seenConnectionKeys.add(connectionKeys);
                return objects;
            }
            @Override
            public List<DataColumn> findColumnsByObject(String objectKey) {
                return columnsByObjectKey.getOrDefault(objectKey, List.of());
            }
        };
        return new ColumnMetadataRequestHandler(new PromptContextBuilder(), new PromptAssembler(),
                new EnterpriseSemanticAssembler(fakeRepo));
    }

    // ── Scenario: Agent can request columns for an already-resolved object (fast path) ────────

    @Test
    void resolvesColumnsForAnAlreadyResolvedObjectByPhysicalTableName() {
        Optional<String> result = handler.resolveColumns(twoObjectContract(), "order_lines");

        assertTrue(result.isPresent());
        assertTrue(result.get().contains("ordered_qty"));
        assertTrue(result.get().contains("order_id"));
    }

    @Test
    void resolvesColumnsByExactBusinessNameToo() {
        Optional<String> result = handler.resolveColumns(twoObjectContract(), "Order Line Item");

        assertTrue(result.isPresent());
        assertTrue(result.get().contains("ordered_qty"));
    }

    @Test
    void resolvesColumnsBySchemaQualifiedTableName() {
        Optional<String> result = handler.resolveColumns(twoObjectContract(), "public.order_lines");

        assertTrue(result.isPresent());
        assertTrue(result.get().contains("ordered_qty"));
    }

    // ── Scenario: Java retrieves columns only for the explicitly requested object ─────────────

    @Test
    void returnsOnlyTheRequestedObjectsColumnsNeverAnotherObjects() {
        Optional<String> result = handler.resolveColumns(twoObjectContract(), "orders");

        assertTrue(result.isPresent());
        assertTrue(result.get().contains("status"), "the requested object's own columns are present");
        assertFalse(result.get().contains("ordered_qty"),
                "a different object's columns must never leak into this object's metadata response");
    }

    // ── Scenario: Java cannot use this mechanism to semantically search for columns ───────────

    @Test
    void neverFuzzyMatchesABusinessTermToAnObject() {
        // "quantity" is not the physical table, the schema-qualified table, or the business
        // name of anything in this contract or catalog — only an exact match is ever accepted.
        Optional<String> result = handler.resolveColumns(twoObjectContract(), "quantity");
        assertTrue(result.isEmpty());
    }

    @Test
    void neverMatchesAPartialOrCaseVariantSubstring() {
        // Close-but-not-exact should not resolve — proves no substring/fuzzy search is performed.
        Optional<String> result = handler.resolveColumns(twoObjectContract(), "order_line");
        assertTrue(result.isEmpty());
    }

    @Test
    void neverSingularizesOrPluralizesToFindAMatch() {
        // The confirmed production defect this regression pins: a table named "order_lines"
        // (plural) exists, but a singular request must still be rejected outright — Java must
        // never singularize/pluralize/normalize a requested identity to find a match. Fixing
        // this class of mismatch is Agent Brain's job (better prompt instructions), never a
        // Java-side matching heuristic.
        Optional<String> singular = handler.resolveColumns(twoObjectContract(), "order_line");
        assertTrue(singular.isEmpty(), "a singular request must not match a pluralized object identity");

        Map<String, ExecutionBindings.ExecutionTarget> attributeBindings = new LinkedHashMap<>();
        BusinessObject singularNamedTable = object("obj-widget", "Widgets", "widgets",
                List.of("id", "name"), attributeBindings);
        ExecutionContract contract = new ExecutionContract("ctr-3", Instant.now(), "agent-1", List.of(CONN), "hash",
                new SemanticView(List.of(singularNamedTable)),
                new ExecutionBindings(
                        Map.of("obj-widget", new ExecutionBindings.ExecutionTarget(
                                "SQL", CONN, "public", "widgets", null)),
                        attributeBindings,
                        Set.of(new ExecutionBindings.ApprovedAsset(CONN, "WIDGETS"))));

        // A plural physical table ("widgets") with a plural business name ("Widgets") — the
        // singular form ("widget") must still be rejected, never resolved by stripping/adding
        // an 's'.
        assertTrue(handler.resolveColumns(contract, "widget").isEmpty(),
                "'widget' must not resolve to 'widgets' — no singular/plural normalization in Java");
        assertTrue(handler.resolveColumns(contract, "widgets").isPresent(),
                "the exact plural identity, as actually shown, still resolves normally");
    }

    @Test
    void matchIsCaseInsensitiveButStillExact() {
        Optional<String> result = handler.resolveColumns(twoObjectContract(), "ORDER_LINES");
        assertTrue(result.isPresent());
    }

    // ── Scenario: requesting an unresolved/unapproved object is rejected ──────────────────────

    @Test
    void rejectsAnObjectNotInTheResolvedContractOrTheEnterpriseCatalog() {
        Optional<String> result = handler.resolveColumns(twoObjectContract(), "some_other_table");
        assertTrue(result.isEmpty());
    }

    @Test
    void rejectsGracefullyWhenContractIsNull() {
        Optional<String> result = handler.resolveColumns(null, "order_lines");
        assertTrue(result.isEmpty());
    }

    @Test
    void rejectsGracefullyWhenRequestedObjectIsBlank() {
        assertTrue(handler.resolveColumns(twoObjectContract(), "").isEmpty());
        assertTrue(handler.resolveColumns(twoObjectContract(), null).isEmpty());
    }

    // ── Domain neutrality: the same mechanism, unmodified, works for a wholly different
    //     industry vocabulary (healthcare) — no code path here mentions any one domain ────────

    @Test
    void worksIdenticallyForAnUnrelatedIndustryVocabulary() {
        Map<String, ExecutionBindings.ExecutionTarget> attributeBindings = new LinkedHashMap<>();
        BusinessObject encounters = object("obj-encounters", "Patient Encounter", "patient_encounters",
                List.of("id", "wait_time_minutes", "department"), attributeBindings);
        ExecutionContract contract = new ExecutionContract("ctr-2", Instant.now(), "agent-1", List.of(CONN), "hash",
                new SemanticView(List.of(encounters)),
                new ExecutionBindings(
                        Map.of("obj-encounters", new ExecutionBindings.ExecutionTarget(
                                "SQL", CONN, "public", "patient_encounters", null)),
                        attributeBindings,
                        Set.of(new ExecutionBindings.ApprovedAsset(CONN, "PATIENT_ENCOUNTERS"))));

        Optional<String> result = handler.resolveColumns(contract, "patient_encounters");

        assertTrue(result.isPresent());
        assertTrue(result.get().contains("wait_time_minutes"));
    }

    // ── Scenario: Agent Brain can request metadata for an object not yet in ExecutionContract
    //     but present in the authoritative Enterprise Map — the circular-dependency fix ────────

    @Test
    void resolvesAnObjectNotInTheContractButPresentInTheEnterpriseCatalogByTableName() {
        // "order_lines" is resolved (fast path); "products_catalog" is NOT in the contract at
        // all — e.g. Concept-Scoped Narrowing never selected it — but it IS a real, approved-
        // connection object in the Enterprise Map.
        ColumnMetadataRequestHandler h = handlerWithCatalog(
                List.of(dataObject("obj-catalog", "Products Catalog", "products_catalog")),
                Map.of("obj-catalog", List.of(
                        dataColumn("obj-catalog", "id"), dataColumn("obj-catalog", "label"))));

        Optional<String> result = h.resolveColumns(twoObjectContract(), "products_catalog");

        assertTrue(result.isPresent());
        assertTrue(result.get().contains("label"));
    }

    @Test
    void fallbackWorksEvenWhenTheContractHasNoResolvedObjectsAtAll() {
        // The exact circular-dependency scenario: Concept-Scoped Narrowing resolved nothing, so
        // the fast path over the (empty) contract can never succeed — the fallback is the only
        // way this request can ever be satisfied.
        ColumnMetadataRequestHandler h = handlerWithCatalog(
                List.of(dataObject("obj-catalog", "Products Catalog", "products_catalog")),
                Map.of("obj-catalog", List.of(dataColumn("obj-catalog", "id"))));

        Optional<String> result = h.resolveColumns(emptyContractForConnection(), "products_catalog");

        assertTrue(result.isPresent());
    }

    @Test
    void fallbackExactBusinessNameLookupSucceeds() {
        ColumnMetadataRequestHandler h = handlerWithCatalog(
                List.of(dataObject("obj-catalog", "Products Catalog", "products_catalog")),
                Map.of("obj-catalog", List.of(dataColumn("obj-catalog", "id"))));

        assertTrue(h.resolveColumns(twoObjectContract(), "Products Catalog").isPresent());
    }

    @Test
    void fallbackSchemaQualifiedIdentityWorks() {
        ColumnMetadataRequestHandler h = handlerWithCatalog(
                List.of(dataObject("obj-catalog", "Products Catalog", "products_catalog")),
                Map.of("obj-catalog", List.of(dataColumn("obj-catalog", "id"))));

        assertTrue(h.resolveColumns(twoObjectContract(), "public.products_catalog").isPresent());
    }

    @Test
    void fallbackCaseDifferencesDoNotIncorrectlyRejectAnExactIdentity() {
        ColumnMetadataRequestHandler h = handlerWithCatalog(
                List.of(dataObject("obj-catalog", "Products Catalog", "products_catalog")),
                Map.of("obj-catalog", List.of(dataColumn("obj-catalog", "id"))));

        assertTrue(h.resolveColumns(twoObjectContract(), "PRODUCTS_CATALOG").isPresent());
    }

    @Test
    void fallbackRejectsAnUnknownObjectWithoutSubstitutingAnother() {
        ColumnMetadataRequestHandler h = handlerWithCatalog(
                List.of(dataObject("obj-catalog", "Products Catalog", "products_catalog")),
                Map.of("obj-catalog", List.of(dataColumn("obj-catalog", "id"))));

        Optional<String> result = h.resolveColumns(twoObjectContract(), "totally_unknown_object");

        assertTrue(result.isEmpty(), "an object absent from both the contract and the enterprise "
                + "catalog must be rejected, never substituted with a similarly-available one");
    }

    @Test
    void fallbackNeverFuzzyOrSemanticallyMatchesTheEnterpriseCatalog() {
        ColumnMetadataRequestHandler h = handlerWithCatalog(
                List.of(dataObject("obj-catalog", "Products Catalog", "products_catalog")),
                Map.of("obj-catalog", List.of(dataColumn("obj-catalog", "id"))));

        // "product" (singular, partial) must not resolve to "products_catalog" — no substring,
        // prefix, or synonym matching is ever performed, in the fallback any more than the fast
        // path.
        assertTrue(h.resolveColumns(twoObjectContract(), "product").isEmpty());
        assertTrue(h.resolveColumns(twoObjectContract(), "catalog").isEmpty());
    }

    @Test
    void fallbackNeverWidensAccessBeyondTheContractsAlreadyApprovedConnections() {
        List<List<String>> seenConnectionKeys = new ArrayList<>();
        ColumnMetadataRequestHandler h = handlerWithCatalog(
                List.of(dataObject("obj-catalog", "Products Catalog", "products_catalog")),
                Map.of("obj-catalog", List.of(dataColumn("obj-catalog", "id"))),
                seenConnectionKeys);

        h.resolveColumns(twoObjectContract(), "products_catalog");

        assertEquals(1, seenConnectionKeys.size());
        assertEquals(List.of(CONN), seenConnectionKeys.get(0),
                "the fallback must query the Enterprise Map with exactly the contract's own "
                        + "already-approved connection keys — never a wider or different set");
    }

    @Test
    void multipleMetadataRequestsAcrossFastPathAndFallbackBothWork() {
        ColumnMetadataRequestHandler h = handlerWithCatalog(
                List.of(dataObject("obj-catalog", "Products Catalog", "products_catalog")),
                Map.of("obj-catalog", List.of(dataColumn("obj-catalog", "id"), dataColumn("obj-catalog", "label"))));

        // "order_lines" is resolved in the contract (fast path); "products_catalog" is not
        // (fallback) — both requests, for different objects, must independently succeed.
        Optional<String> fastPath = h.resolveColumns(twoObjectContract(), "order_lines");
        Optional<String> fallback = h.resolveColumns(twoObjectContract(), "products_catalog");

        assertTrue(fastPath.isPresent());
        assertTrue(fastPath.get().contains("ordered_qty"));
        assertTrue(fallback.isPresent());
        assertTrue(fallback.get().contains("label"));
    }
}
