package com.sei.nexus.sql;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** ADR-0003 A13 — the canonical shared SQL table-reference extractor. */
class SqlTableReferenceExtractorTest {

    private final SqlTableReferenceExtractor ex = new SqlTableReferenceExtractor();

    @Test
    void extractsFromAndJoinInCanonicalForm() {
        assertEquals(Set.of("ORDERS"), ex.referencedTables("SELECT id FROM orders"));
        assertEquals(Set.of("PUBLIC.ORDERS"), ex.referencedTables("SELECT id FROM public.orders WHERE id = 5"));
        assertTrue(ex.referencedTables(
                        "SELECT * FROM orders o JOIN customers c ON c.id = o.customer_id")
                .containsAll(Set.of("ORDERS", "CUSTOMERS")));
    }

    @Test
    void canonicalStripsQuotesTrimsAndUpperCases() {
        assertEquals("ORDERS", ex.canonical("\"orders\""));
        assertEquals("PUBLIC.ORDERS", ex.canonical("  public.orders  "));
        assertEquals("", ex.canonical(null));
    }

    @Test
    void nullAndBlankAreSafe() {
        assertTrue(ex.referencedTables(null).isEmpty());
        assertTrue(ex.referencedTables("   ").isEmpty());
    }
}
