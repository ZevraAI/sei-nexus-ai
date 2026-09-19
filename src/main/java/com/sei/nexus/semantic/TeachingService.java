package com.sei.nexus.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.connection.ConnectionRepository;
import com.sei.nexus.connection.NexusConnection;
import com.sei.nexus.knowledge.ConceptKnowledgeMaterializationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /TeachZevra (Part B — Explicit Teaching). Backs a dedicated Teaching UI/modal, NOT a free-form
 * chat teaching mode: form submit → Teaching LLM structured proposal → user-confirmed Preview →
 * persist. Java owns validation, concept/connection catalog authority, and persistence; the
 * Teaching LLM owns only wording (definition) and SQL-pattern phrasing — it may echo but never
 * invent the concept identity (see {@link #buildProposal}'s post-call validation).
 */
@Service
public class TeachingService {

    private static final Logger log = LoggerFactory.getLogger(TeachingService.class);

    /** Documented Part B promotion-eligibility decision: explicit, user-confirmed teaching is
     *  higher-trust than an inferred query-success term, so it starts at a higher initial
     *  confidence than the default 0.5 — but still goes through the SAME nightly promotion
     *  review/thresholds as every other learned mapping (PROMOTE_MIN_USES=10,
     *  PROMOTE_MIN_CONFIDENCE=0.80 in SemanticLearningService), never bypassing it. This is the
     *  conservative option: no immediate promotion, no separate threshold, just a higher starting
     *  point that a single admin/positive-feedback reinforcement more easily clears. */
    static final double EXPLICIT_TEACHING_INITIAL_CONFIDENCE = 0.8;

    private static final String TEACHING_SYSTEM_PROMPT = """
            You are Zevra's Teaching contract. A user has submitted a business-knowledge
            definition through the /TeachZevra form. Your ONLY job is to turn their submitted
            fields into a clean, well-phrased business_term/definition (and, if they supplied a
            business rule or SQL expression, a normalized sql_pattern) — you do NOT invent or
            change the concept, and you do NOT invent a SQL pattern the user didn't imply.

            Return strict JSON with exactly these fields:
              - businessTerm: the business term, cleaned up (trim, consistent casing) but not
                reworded into a different term.
              - definition: a clear one-to-two sentence business definition, based on the user's
                submitted meaning — do not add facts the user didn't state.
              - sqlPattern: a normalized SQL predicate/expression if the user supplied a business
                rule or SQL expression; null if they supplied none. Never invent SQL from the
                definition alone.
              - conceptKey: return EXACTLY the concept key you were given below — never a
                different one, never invented, never left blank.
              - scope: return EXACTLY the scope you were given below (TENANT or CONNECTION).
            """;

    private final AzureOpenAiClient aiClient;
    private final ObjectMapper objectMapper;
    private final ConceptKnowledgeMaterializationService conceptService;
    private final ConnectionRepository connectionRepository;
    private final LearnedMappingRepository mappingRepository;

    public TeachingService(AzureOpenAiClient aiClient,
                            ObjectMapper objectMapper,
                            ConceptKnowledgeMaterializationService conceptService,
                            ConnectionRepository connectionRepository,
                            LearnedMappingRepository mappingRepository) {
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
        this.conceptService = conceptService;
        this.connectionRepository = connectionRepository;
        this.mappingRepository = mappingRepository;
    }

    // ── Dropdown data sources — reuse existing catalogs, never a parallel one ─────────────────

    /** Tenant-scoped canonical concept catalog — identical source SemanticController's
     *  /semantic/concepts already uses ({@link ConceptKnowledgeMaterializationService
     *  #listConceptCatalog()}). Never LLM-discovered, never free text. */
    public List<Map<String, String>> listConceptOptions() {
        return conceptService.listConceptCatalog();
    }

    /** The current tenant's authorized connections. There is no per-user connection ACL in this
     *  codebase (confirmed by investigation) — connection authorization here, as everywhere else
     *  in Zevra, is enforced at the tenant-schema boundary via {@code TenantContext}, and {@link
     *  ConnectionRepository#findAll()} is already tenant-scoped through the tenant-routed JDBC
     *  connection. Java determines this list; the user only selects from it. */
    public List<Map<String, String>> listConnectionOptions() {
        return connectionRepository.findAll().stream()
                .map(c -> {
                    Map<String, String> row = new LinkedHashMap<String, String>();
                    row.put("connectionKey", c.connectionKey());
                    row.put("name", c.name());
                    return row;
                })
                .toList();
    }

    // ── Form validation — required fields, deterministic, no semantic judgment ────────────────

    /** Rejects a submission missing any required field. Never silently defaults or infers a
     *  missing value. */
    public void validate(TeachingProposalRequest req) {
        if (req == null) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "Teaching request is required");
        }
        requireNonBlank(req.conceptKey(), "conceptKey");
        requireNonBlank(req.businessTerm(), "businessTerm");
        requireNonBlank(req.businessMeaning(), "businessMeaning");
        if (req.scope() == null) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "scope is required");
        }
        validateConceptKeyKnown(req.conceptKey());
        if (req.scope() == TeachingScope.CONNECTION) {
            requireNonBlank(req.connectionKey(), "connectionKey is required when scope is CONNECTION");
            validateConnectionAuthorized(req.connectionKey());
        }
    }

    private void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new NexusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
        }
    }

    private void validateConceptKeyKnown(String conceptKey) {
        boolean known = listConceptOptions().stream()
                .anyMatch(c -> conceptKey.equals(c.get("conceptKey")));
        if (!known) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "Unknown concept key: " + conceptKey);
        }
    }

    private void validateConnectionAuthorized(String connectionKey) {
        boolean authorized = listConnectionOptions().stream()
                .anyMatch(c -> connectionKey.equals(c.get("connectionKey")));
        if (!authorized) {
            throw new NexusException(HttpStatus.FORBIDDEN,
                    "Connection is not authorized for this tenant: " + connectionKey);
        }
    }

    // ── Teaching LLM contract → structured Learning Proposal (NOT persisted) ──────────────────

    /**
     * Calls the dedicated Teaching contract (strict JSON schema, no TermExtractor, no File
     * Search) and returns a proposal for the user to preview. Rejects — never silently
     * substitutes — a returned {@code conceptKey} that doesn't exactly equal the user-submitted
     * one.
     */
    public TeachingProposal buildProposal(TeachingProposalRequest req) {
        validate(req);

        String userContent = """
                Concept key (must be echoed back exactly): %s
                Scope (must be echoed back exactly): %s
                Business term: %s
                Business meaning/definition: %s
                Examples: %s
                Business rule / SQL expression (if any): %s
                Notes: %s
                """.formatted(
                req.conceptKey(),
                req.scope().name(),
                req.businessTerm(),
                req.businessMeaning(),
                blankToNone(req.examples()),
                blankToNone(req.businessRuleSql()),
                blankToNone(req.notes()));

        // Telemetry hardening (Phase 0): this call had no LlmCallTag — it would persist as
        // UNTAGGED/NULL call_type, indistinguishable from any other untagged call.
        com.sei.nexus.ai.LlmCallTag.set("TEACHING_PROPOSAL");
        String raw = aiClient.respondWithStrictJson(
                List.of(new ChatMessage("user", userContent)),
                TEACHING_SYSTEM_PROMPT,
                "teaching_proposal",
                teachingProposalJsonSchema());

        TeachingProposal proposal = parseProposal(raw, req);

        // The non-negotiable guard: Java validates the LLM's conceptKey matches exactly.
        if (!req.conceptKey().equals(proposal.conceptKey())) {
            log.warn("Teaching LLM returned a mismatched conceptKey ('{}' vs submitted '{}') — rejected",
                    proposal.conceptKey(), req.conceptKey());
            throw new NexusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Teaching proposal rejected: model concept key did not match the submitted concept");
        }
        return proposal;
    }

    private TeachingProposal parseProposal(String raw, TeachingProposalRequest req) {
        try {
            JsonNode node = objectMapper.readTree(raw);
            String businessTerm = node.path("businessTerm").asText(req.businessTerm());
            String definition   = node.path("definition").asText(req.businessMeaning());
            String sqlPattern   = node.path("sqlPattern").isNull() || !node.hasNonNull("sqlPattern")
                    ? null : node.path("sqlPattern").asText(null);
            String conceptKey   = node.path("conceptKey").asText(null);
            return new TeachingProposal(businessTerm, definition, sqlPattern, conceptKey,
                    req.scope(), req.scope() == TeachingScope.CONNECTION ? req.connectionKey() : null);
        } catch (Exception e) {
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to parse Teaching proposal: " + e.getMessage());
        }
    }

    private String blankToNone(String s) {
        return (s == null || s.isBlank()) ? "(none)" : s;
    }

    private Map<String, Object> teachingProposalJsonSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("businessTerm", Map.of("type", "string"));
        properties.put("definition", Map.of("type", "string"));
        properties.put("sqlPattern", Map.of("type", List.of("string", "null")));
        properties.put("conceptKey", Map.of("type", "string"));
        properties.put("scope", Map.of("type", "string", "enum", List.of("TENANT", "CONNECTION")));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("businessTerm", "definition", "sqlPattern", "conceptKey", "scope"));
        schema.put("additionalProperties", false);
        return schema;
    }

    // ── Confirmed teaching → persistence (reuses nexus_learned_mapping, no parallel table) ────

    /**
     * Persists a user-confirmed teaching proposal. Re-validates the concept (and, for
     * connection-scoped teaching, the connection) server-side — defense in depth against a
     * tampered/stale client-held proposal — and NEVER persists on a validation failure (no
     * fabricated "learned" status).
     */
    public LearnedMapping confirmTeaching(TeachingProposal proposal) {
        if (proposal == null) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "Teaching proposal is required");
        }
        validateConceptKeyKnown(proposal.conceptKey());
        if (proposal.scope() == TeachingScope.CONNECTION) {
            validateConnectionAuthorized(proposal.connectionKey());
        }

        LearnedMapping mapping = new LearnedMapping(
                null, null, proposal.businessTerm(), proposal.sqlPattern(),
                null, "EXPLICIT_TEACHING", EXPLICIT_TEACHING_INITIAL_CONFIDENCE, 0,
                Instant.now(), false, null, null, proposal.conceptKey());

        LearnedMapping saved = proposal.scope() == TeachingScope.CONNECTION
                ? mappingRepository.insertExplicitTeaching(mapping, proposal.connectionKey())
                : mappingRepository.insertExplicitTeaching(mapping, null);

        log.info("Explicit teaching confirmed: term='{}' conceptKey='{}' scope={} connectionKey={}",
                saved.businessTerm(), saved.conceptKey(), proposal.scope(), proposal.connectionKey());
        return saved;
    }
}
