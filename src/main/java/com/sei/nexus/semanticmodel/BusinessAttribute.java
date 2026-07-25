package com.sei.nexus.semanticmodel;

/**
 * The first-class semantic atom of Zevra's business model (ADR-0003 semantic model,
 * Phase 1A): a named business concept a user references (e.g. "On Hand Quantity"),
 * belonging to exactly one {@link BusinessObject}.
 *
 * <p>It is independent of persistence (a {@code DataColumn} is one way it is realized,
 * not its identity) and carries <b>no execution detail</b> — no connection, schema,
 * table, or column. Those live in the execution plane, keyed by {@link #attributeKey()}.
 *
 * @param attributeKey immutable semantic identity — never the display name (Invariant 2)
 * @param businessName human business name
 * @param role         primary analytical role
 */
public record BusinessAttribute(
        String        attributeKey,
        String        businessName,
        AttributeRole role
) {}
