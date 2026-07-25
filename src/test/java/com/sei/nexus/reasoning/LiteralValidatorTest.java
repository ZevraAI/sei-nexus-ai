package com.sei.nexus.reasoning;

import com.sei.nexus.semanticmodel.ColumnValueDomain;
import com.sei.nexus.reasoning.LiteralValidator.RejectionDecision;
import com.sei.nexus.reasoning.LiteralValidator.Result;
import com.sei.nexus.reasoning.LiteralValidator.Violation;
import com.sei.nexus.reasoning.ReasoningPlanner.LiteralBinding;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PRO-33 — deterministic literal validation (PRO-32 §5): existence checks
 * against persisted domains, hard-vs-advisory enforcement by domain authority,
 * the verbatim exemption, the one-re-prompt retry policy, and the
 * literal_bindings contract parsing. Rejects-never-rewrites throughout.
 */
class LiteralValidatorTest {

    private static Map<String, ColumnValueDomain> scope() {
        Map<String, ColumnValueDomain> scope = new HashMap<>();
        ColumnValueDomain state = new ColumnValueDomain("stores", "state_province", false,
                List.of("California", "Texas"));
        ColumnValueDomain status = new ColumnValueDomain("stores", "status", true,
                List.of("open", "closed"));
        ColumnValueDomain country = new ColumnValueDomain("stores", "country", false,
                List.of("United States", "Canada"));
        scope.put("stores.state_province", state);
        scope.put("state_province", state);
        scope.put("stores.status", status);
        scope.put("status", status);
        scope.put("stores.country", country);
        scope.put("country", country);
        return scope;
    }

    // ── The motivating demos: TX → Texas, CA → California, USA → United States ─

    @Test
    void validatedBindingsPass_txCaUsa() {
        Result r = LiteralValidator.validate(
                "SELECT name FROM retail_core.stores WHERE state_province = 'Texas'",
                List.of(new LiteralBinding("TX", "stores.state_province", "Texas"),
                        new LiteralBinding("CA", "stores.state_province", "California"),
                        new LiteralBinding("USA", "stores.country", "United States")),
                scope(), "show me all TX and CA stores in the USA");

        assertTrue(r.valid());
    }

    @Test
    void bindingToNonExistentValueIsViolationWithLegalList() {
        // The model kept the user's abbreviation instead of choosing a stored value
        Result r = LiteralValidator.validate(null,
                List.of(new LiteralBinding("USA", "stores.country", "US of A")),
                scope(), "orders in the USA");

        assertEquals(1, r.violations().size());
        Violation v = r.violations().get(0);
        assertEquals("stores.country", v.column());
        assertFalse(v.authoritative());
        assertEquals("'US of A' is not a legal value of stores.country; "
                + "legal values: [United States, Canada]", v.message());
    }

    // ── SQL extraction: equality, IN lists, aliases, escaped quotes ───────────

    @Test
    void inventedSqlLiteralOnAuthoritativeDomainIsHardViolation() {
        Result r = LiteralValidator.validate(
                "SELECT * FROM stores WHERE status IN ('open', 'archived')",
                List.of(), scope(), "which stores are archived stores?".replace("archived", "shut"));

        assertEquals(1, r.violations().size());
        assertTrue(r.violations().get(0).authoritative());
        assertEquals("'archived'", "'" + r.violations().get(0).literal() + "'");
    }

    @Test
    void aliasQualifiedColumnFallsBackToBareLookup() {
        Result r = LiteralValidator.validate(
                "SELECT s.name FROM retail_core.stores s WHERE s.state_province = 'Texass'",
                List.of(), scope(), "stores question");

        assertEquals(1, r.violations().size());
        assertEquals("stores.state_province", r.violations().get(0).column());
    }

    @Test
    void escapedQuotesUnescapeBeforeMembershipCheck() {
        Map<String, ColumnValueDomain> s = new HashMap<>();
        s.put("owner_name", new ColumnValueDomain("stores", "owner_name", false, List.of("O'Brien")));

        assertTrue(LiteralValidator.validate(
                "SELECT * FROM stores WHERE owner_name = 'O''Brien'",
                List.of(), s, "who is obrien").valid());
    }

    @Test
    void columnsOutsideTheScopeAreNeverGated() {
        Result r = LiteralValidator.validate(
                "SELECT * FROM stores WHERE customer_name = 'Acme Corp'",
                List.of(), scope(), "show acme");
        assertTrue(r.valid());                    // no domain ⇒ no gate (PRO-32 §5)
    }

    // ── Verbatim exemption: user-supplied ground truth is never vetoed ────────

    @Test
    void literalCopiedVerbatimFromQuestionIsExempt() {
        Result r = LiteralValidator.validate(
                "SELECT * FROM stores WHERE state_province = 'TX'",
                List.of(), scope(), "show me all TX stores");
        assertTrue(r.valid());
    }

    // ── Zero-cost: empty scope validates everything instantly ────────────────

    @Test
    void emptyScopeIsAlwaysValid() {
        assertTrue(LiteralValidator.validate(
                "WHERE status = 'garbage'", List.of(), Map.of(), "q").valid());
        assertTrue(LiteralValidator.validate(
                "WHERE status = 'garbage'", List.of(), null, "q").valid());
    }

    // ── Retry policy (PRO-32 §5: reject once, then tier-dependent) ────────────

    @Test
    void firstViolationRejectsWithLegalListMessage() {
        Set<String> memory = new HashSet<>();
        Violation v = new Violation("stores.status", "archived", true, List.of("open", "closed"));

        RejectionDecision d = LiteralValidator.decide(List.of(v), memory);

        assertTrue(d.reject());
        assertFalse(d.hardBlock());
        assertTrue(d.message().contains("'archived' is not a legal value of stores.status"));
        assertTrue(d.message().contains("[open, closed]"));
    }

    @Test
    void repeatViolationOnAuthoritativeDomainHardBlocks() {
        Set<String> memory = new HashSet<>();
        Violation v = new Violation("stores.status", "archived", true, List.of("open", "closed"));

        LiteralValidator.decide(List.of(v), memory);            // first: reject + remember
        RejectionDecision second = LiteralValidator.decide(List.of(v), memory);

        assertTrue(second.hardBlock());
        assertTrue(second.reject());
    }

    @Test
    void repeatViolationOnObservedDomainIsAdvisoryExhausted() {
        Set<String> memory = new HashSet<>();
        Violation v = new Violation("stores.state_province", "Texass", false,
                List.of("California", "Texas"));

        LiteralValidator.decide(List.of(v), memory);            // advisory rejection
        RejectionDecision second = LiteralValidator.decide(List.of(v), memory);

        assertFalse(second.reject());                           // execute honestly
        assertFalse(second.hardBlock());
    }

    // ── literal_bindings contract parsing (planner side) ──────────────────────

    @Test
    void parseLiteralBindingsAcceptsWellFormedEntriesAndSkipsJunk() {
        List<?> raw = List.of(
                Map.of("surface", "TX", "column", "stores.state_province", "value", "Texas"),
                Map.of("surface", "", "column", "c", "value", "v"),      // blank surface
                Map.of("column", "c", "value", "v"),                     // missing surface
                "not a map");

        List<LiteralBinding> parsed = ReasoningPlanner.parseLiteralBindings(raw);

        assertEquals(1, parsed.size());
        assertEquals(new LiteralBinding("TX", "stores.state_province", "Texas"), parsed.get(0));
    }

    @Test
    void parseLiteralBindingsIsEmptyForAbsentOrMalformedField() {
        assertTrue(ReasoningPlanner.parseLiteralBindings(null).isEmpty());
        assertTrue(ReasoningPlanner.parseLiteralBindings("nope").isEmpty());
        assertTrue(ReasoningPlanner.parseLiteralBindings(Map.of()).isEmpty());
    }

    // ── Backward compatibility: pre-PRO-33 StepPlan shape still works ─────────

    @Test
    void fiveArgStepPlanHasNoBindings() {
        var plan = new ReasoningPlanner.StepPlan("d", "SELECT 1", "conn", "", "r");
        assertTrue(plan.literalBindings().isEmpty());
    }
}
