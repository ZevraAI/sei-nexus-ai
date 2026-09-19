package com.sei.nexus.semantic;

/**
 * The /TeachZevra form submission — every field the user filled in, before any LLM call. Concept
 * is a dropdown value from the existing canonical concept catalog ({@code
 * ConceptKnowledgeMaterializationService#listConceptCatalog()}) — never free text, never
 * LLM-discovered. Connection is required only when {@code scope == CONNECTION}, and must be one
 * of the caller's own tenant's authorized connections (validated server-side against {@link
 * com.sei.nexus.connection.ConnectionRepository#findAll()} — the current tenant's active
 * connections; Java determines the allowed list, the user only selects from it).
 */
public record TeachingProposalRequest(
        String conceptKey,
        String businessTerm,
        String businessMeaning,
        TeachingScope scope,
        String connectionKey,     // required iff scope == CONNECTION, else must be null/blank
        String examples,          // optional
        String businessRuleSql,   // optional — a business rule / SQL expression the user supplies
        String notes              // optional
) {}
