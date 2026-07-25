package com.sei.nexus.semanticmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.enterprise.DataColumn;
import com.sei.nexus.enterprise.DataObject;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import com.sei.nexus.enterprise.ValueDomain;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unified Answer Engine, Phase 3 — Step 3b. Value domains are Enterprise-Map metadata,
 * discovered at scan time and linked to a business column by {@code DataColumn.valueDomainKey}.
 * The {@link EnterpriseSemanticAssembler} is their canonical projector: it reads the owned
 * metadata and parses it into the semantic {@link ColumnValueDomain} — it never samples or
 * discovers values. Downstream, rendering simply renders it.
 *
 * <p>Hand-rolled fakes; no database. The fake repository proves the assembler consults the
 * Enterprise Map (the owner) exactly once per distinct domain key (projection, cached).
 */
class ValueDomainProjectionTest {

    static class FakeMap extends EnterpriseMapRepository {
        final List<DataColumn> columns;
        final Map<String, ValueDomain> domains;
        int domainLookups = 0;
        FakeMap(List<DataColumn> columns, Map<String, ValueDomain> domains) {
            super(null); this.columns = columns; this.domains = domains;
        }
        @Override public List<DataObject> findDataObjectsByConnectionKeys(List<String> keys) {
            return List.of(new DataObject("obj-store", "PLATFORM", "stores", "conn-1",
                    "retail_core", "stores", "Stores", "store master", "id", "status", "", "status",
                    "", "", "", 100, false, "SCANNED", 1, Instant.now(), Instant.now()));
        }
        @Override public List<DataColumn> findColumnsByObject(String objectKey) { return columns; }
        @Override public Optional<ValueDomain> findValueDomainByKey(String key) {
            domainLookups++;
            return Optional.ofNullable(domains.get(key));
        }
    }

    private static DataColumn col(String key, String name, String type, String valueDomainKey) {
        return new DataColumn(key, "obj-store", name, type, false, "",
                false, "status".equals(name), false, false, true,
                type, valueDomainKey, "DECLARED", Instant.now(), Instant.now());
    }

    private static ValueDomain enumDomain(String key, String json, boolean authoritative) {
        return new ValueDomain(key, "conn-1", "retail_core", "store_status",
                authoritative ? "ENUM" : "OBSERVED", authoritative, json, Instant.now());
    }

    @Test
    void projectsPersistedValueDomainOntoThePhysicalColumn() {
        FakeMap repo = new FakeMap(
                List.of(col("c-status", "status", "USER-DEFINED", "vd-1"),
                        col("c-name",   "name",   "text",         null)),
                Map.of("vd-1", enumDomain("vd-1",
                        "[\"open\",\"temporarily_closed\",\"closed\"]", true)));

        SemanticModel model = new EnterpriseSemanticAssembler(repo, new ObjectMapper())
                .assemble(List.of("conn-1"));

        ColumnValueDomain vd = model.attributeTargets().get("c-status").valueDomain();
        assertNotNull(vd, "the column's owned value domain is projected onto its physical column");
        assertEquals("stores", vd.table());
        assertEquals("status", vd.column());
        assertTrue(vd.authoritative(), "ENUM domains project as authoritative");
        assertEquals(List.of("open", "temporarily_closed", "closed"), vd.values());

        assertNull(model.attributeTargets().get("c-name").valueDomain(),
                "a column with no valueDomainKey has no domain — the assembler never invents one");
    }

    @Test
    void domainMetadataIsReadOncePerKeyNotPerColumn() {
        // two columns share the same domain key
        FakeMap repo = new FakeMap(
                List.of(col("c-a", "status_a", "USER-DEFINED", "vd-1"),
                        col("c-b", "status_b", "USER-DEFINED", "vd-1")),
                Map.of("vd-1", enumDomain("vd-1", "[\"a\",\"b\"]", true)));

        new EnterpriseSemanticAssembler(repo, new ObjectMapper()).assemble(List.of("conn-1"));

        assertEquals(1, repo.domainLookups,
                "the owned metadata is read once per distinct domain key — projection, cached");
    }

    @Test
    void malformedPersistedJsonNeverBreaksAssembly() {
        FakeMap repo = new FakeMap(
                List.of(col("c-status", "status", "USER-DEFINED", "vd-bad")),
                Map.of("vd-bad", enumDomain("vd-bad", "{not-an-array}", true)));

        SemanticModel model = new EnterpriseSemanticAssembler(repo, new ObjectMapper())
                .assemble(List.of("conn-1"));

        assertNull(model.attributeTargets().get("c-status").valueDomain(),
                "malformed persisted JSON yields no domain, not a failure");
    }

    @Test
    void observedDomainProjectsAsNonAuthoritative() {
        FakeMap repo = new FakeMap(
                List.of(col("c-state", "state", "text", "vd-obs")),
                Map.of("vd-obs", enumDomain("vd-obs", "[\"TX\",\"CA\"]", false)));

        SemanticModel model = new EnterpriseSemanticAssembler(repo, new ObjectMapper())
                .assemble(List.of("conn-1"));

        assertFalse(model.attributeTargets().get("c-state").valueDomain().authoritative(),
                "observed (sampled) domains project as advisory, not authoritative");
    }
}
