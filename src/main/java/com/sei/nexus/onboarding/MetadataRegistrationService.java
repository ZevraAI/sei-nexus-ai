package com.sei.nexus.onboarding;

import com.sei.nexus.connection.NexusConnection;
import com.sei.nexus.enterprise.EnterpriseMapService;
import com.sei.nexus.pack.IndustryPackRepository;
import com.sei.nexus.pack.TenantPack;
import com.sei.nexus.semantic.BusinessEntity;
import com.sei.nexus.semantic.EntityCandidateService;
import com.sei.nexus.semantic.RelationshipDiscoveryService;
import com.sei.nexus.semantic.SemanticService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The canonical Metadata Registration Pipeline (PRO-21).
 *
 * <p>Every onboarding mechanism — the first-run Onboarding Wizard, the Semantic
 * Layer's "Discover from DB" modal, and any future API/bulk entry point — must
 * execute this pipeline after user approval, so metadata registration is
 * identical regardless of entry point. Per approved entity:
 * <ol>
 *   <li><b>Register physical:</b> data object (deterministic key, allow-list
 *       validated), column scan with role inference, value-domain discovery
 *       (PRO-10), scan status, version snapshot — all via
 *       {@link EnterpriseMapService#createOrUpdateObject}.</li>
 *   <li><b>Register semantic, linked:</b> business entity with
 *       {@code primary_object_key}; vocabulary with {@code entity_key} and
 *       entity-scoped term keys.</li>
 * </ol>
 * then, once per batch, relationship discovery over the completed
 * table→entity index.
 *
 * <p>Failure semantics: per-entity, non-fatal — but when physical registration
 * fails, semantic registration for that table is <b>skipped</b> rather than
 * producing an unlinked entity (the NULL-binding defect class documented in
 * CANONICAL_METADATA_ONBOARDING_PIPELINE.md §11).
 *
 * <p>Bootstrap operations (suggested questions, default agent, completion flag)
 * are NOT part of this pipeline — they remain exclusive to the wizard
 * ({@link OnboardingService#applySelections}).
 */
@Service
public class MetadataRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(MetadataRegistrationService.class);

    // Reuse decisions below this confidence are ignored — same gate family as the
    // learning-promotion threshold (LearningContextBuilder, conf >= 0.55).
    private static final double REUSE_CONFIDENCE_THRESHOLD = 0.55;

    private final EnterpriseMapService         enterpriseMapService;
    private final SemanticService              semanticService;
    private final RelationshipDiscoveryService relationshipDiscovery;
    private final EntityCandidateService       entityCandidates;
    private final com.sei.nexus.enterprise.BusinessValueRepository businessValues;
    // Connection-Scoped Industry Pack Semantic Assignment: pack_key is derived from the
    // connection's active Industry Pack — never from the LLM, never from table names. Nullable
    // exactly like businessValues above (see the null-guard at resolveActivePackKey): tests that
    // don't care about pack assignment keep using the 4-arg constructor unchanged.
    private final IndustryPackRepository packRepository;

    @org.springframework.beans.factory.annotation.Autowired
    public MetadataRegistrationService(EnterpriseMapService enterpriseMapService,
                                        SemanticService semanticService,
                                        RelationshipDiscoveryService relationshipDiscovery,
                                        EntityCandidateService entityCandidates,
                                        com.sei.nexus.enterprise.BusinessValueRepository businessValues,
                                        IndustryPackRepository packRepository) {
        this.enterpriseMapService  = enterpriseMapService;
        this.semanticService       = semanticService;
        this.relationshipDiscovery = relationshipDiscovery;
        this.entityCandidates      = entityCandidates;
        this.businessValues        = businessValues;
        this.packRepository        = packRepository;
    }

    /** Backward-compatible convenience (tests): no Business Value persistence (⇒ step 5 is a
     *  no-op) and no pack lookup (⇒ pack_key is never populated — see resolveActivePackKey). */
    public MetadataRegistrationService(EnterpriseMapService enterpriseMapService,
                                        SemanticService semanticService,
                                        RelationshipDiscoveryService relationshipDiscovery,
                                        EntityCandidateService entityCandidates) {
        this(enterpriseMapService, semanticService, relationshipDiscovery, entityCandidates, null, null);
    }

    /** Per-batch outcome record: counts plus per-step failure descriptions. */
    public record RegistrationResult(int objectsCreated,
                                     int entitiesCreated,
                                     int vocabCreated,
                                     int relationshipsDiscovered,
                                     List<String> failures) {}

    /**
     * Executes the pipeline for a batch of approved entity drafts.
     *
     * <p>Request shape (identical for every entry point):
     * {@code connectionKey, schemaName, domainKey, entities[]} where each entity
     * carries {@code approved, tableName, entityKey, entityName, purpose,
     * operationalMeaning, investigationHints, vocabulary[]} and optionally the
     * AI-analyzed data-object fields ({@code businessName, identifierColumns,
     * statusColumns, exceptionColumns, safeFilterColumns, usageGuidance,
     * filterGuidance, avoidGuidance}) — all of which have existing consumers in
     * {@link EnterpriseMapService#createOrUpdateObject}.
     */
    @SuppressWarnings("unchecked")
    public RegistrationResult register(Map<String, Object> request, String userEmail) {
        String connectionKey = (String) request.get("connectionKey");
        String schemaName    = (String) request.get("schemaName");
        String domainKey     = (String) request.get("domainKey");
        List<Map<String, Object>> entities =
                (List<Map<String, Object>>) request.getOrDefault("entities", List.of());

        int objectsCreated  = 0;
        int entitiesCreated = 0;
        int vocabCreated    = 0;
        List<String> failures = new ArrayList<>();

        // Optimization B (onboarding performance investigation): every entity in
        // this batch shares the same connection (connectionKey is batch-level, not
        // per-entity), so resolve it once here instead of once per entity inside
        // EnterpriseMapService.createOrUpdateObject. Any resolution failure is
        // captured and replayed per-entity below with the exact failure message
        // the old per-call lookup would have produced, so behavior is unchanged.
        NexusConnection connection = null;
        Exception connectionError  = null;
        if (connectionKey != null && !connectionKey.isBlank()) {
            try {
                connection = enterpriseMapService.resolveConnection(connectionKey);
            } catch (Exception e) {
                connectionError = e;
            }
        }

        // Connection-Scoped Industry Pack Semantic Assignment: pack_key is resolved once per
        // batch (same "resolve once, not once per entity" shape as the connection lookup above)
        // from the connection's ACTIVE Industry Pack assignment — never from the LLM, never
        // guessed from table names. No active pack (or no packRepository, e.g. the 4-arg test
        // constructor) simply leaves this null, which then makes the pack_key write below a
        // no-op for every entity in the batch — byte-identical to today's behavior.
        String activePackKey = resolveActivePackKey(connectionKey);

        long applyStart = System.currentTimeMillis();
        for (Map<String, Object> entity : entities) {
            if (!Boolean.TRUE.equals(entity.get("approved"))) continue;

            String tableName  = (String) entity.get("tableName");
            String entityKey  = (String) entity.getOrDefault("entityKey", slugify(tableName));
            String entityName = (String) entity.getOrDefault("entityName", toTitleCase(tableName));
            String purpose    = (String) entity.getOrDefault("purpose", "");
            String opMeaning  = (String) entity.getOrDefault("operationalMeaning", "");
            String hints      = (String) entity.getOrDefault("investigationHints", "");

            // 1. Register physical: data object + columns + value domains + version.
            //    Failure here skips semantic registration for this table — an entity
            //    without primary_object_key is invisible to planner schema, PRO-19
            //    bindings, and graph table labels, so creating it silently would
            //    reproduce the Discover-path defect.
            String objectKey;
            if (connectionError != null) {
                log.warn("Failed to register data object for {}: {}", tableName, connectionError.getMessage());
                failures.add("data object " + tableName + ": " + connectionError.getMessage());
                continue;
            }
            try {
                Map<String, Object> objBody = new LinkedHashMap<>();
                objBody.put("domainKey",     domainKey);
                objBody.put("connectionKey", connectionKey);
                objBody.put("schemaName",    schemaName);
                objBody.put("tableName",     tableName);
                objBody.put("entityName",    entityName);
                objBody.put("businessName",  strOrDefault(entity.get("businessName"), entityName + "s"));
                objBody.put("purpose",       purpose);
                putCsvIfPresent(objBody, "identifierColumns", entity.get("identifierColumns"));
                putCsvIfPresent(objBody, "statusColumns",     entity.get("statusColumns"));
                putCsvIfPresent(objBody, "exceptionColumns",  entity.get("exceptionColumns"));
                putCsvIfPresent(objBody, "safeFilterColumns", entity.get("safeFilterColumns"));
                putStrIfPresent(objBody, "usageGuidance",     entity.get("usageGuidance"));
                putStrIfPresent(objBody, "filterGuidance",    entity.get("filterGuidance"));
                putStrIfPresent(objBody, "avoidGuidance",     entity.get("avoidGuidance"));
                // Optimization A: the Analyze phase already fetched this table's live
                // schema (describeTable) — carry it through so Apply doesn't fetch it
                // again. Absent for callers that never analyzed (e.g. Discover-from-DB),
                // which fall back to a live describeTable() call unchanged.
                if (entity.get("columns") != null) {
                    objBody.put("columns", entity.get("columns"));
                }

                var dataObj = connection != null
                        ? enterpriseMapService.createOrUpdateObject(objBody, userEmail, connection)
                        : enterpriseMapService.createOrUpdateObject(objBody, userEmail);
                objectKey = dataObj.objectKey();
                objectsCreated++;
            } catch (Exception e) {
                log.warn("Failed to register data object for {}: {}", tableName, e.getMessage());
                failures.add("data object " + tableName + ": " + e.getMessage());
                continue;
            }

            // 2. Business Entity selection (PRO-22): tier 0 deterministic binding
            //    lookup, then validation of the AI's constrained REUSE decision,
            //    then CREATE with a collision guard. Integrity checks only — the
            //    reuse-vs-create judgment was made by the AI over the offered set.
            EntitySelection selection = selectEntity(domainKey, objectKey, tableName,
                    entity, entityKey, failures);
            String resolvedKey  = selection.entityKey();
            // A reused entity keeps its curated name — a drifted AI name must not
            // rename the existing concept; descriptions/meanings refresh as usual.
            String resolvedName = selection.reused() ? selection.existingName() : entityName;

            // 3. Register semantic, linked: entity bound to its table.
            try {
                Map<String, Object> entityBody = new LinkedHashMap<>();
                entityBody.put("entityKey",          resolvedKey);
                entityBody.put("entityName",         resolvedName);
                entityBody.put("description",        purpose);
                entityBody.put("operationalMeaning", opMeaning);
                entityBody.put("investigationHints", hints);
                entityBody.put("domainKey",          domainKey);
                entityBody.put("status",             "ACTIVE");
                entityBody.put("primaryObjectKey",   objectKey);
                // Grouping Foundation Fix: the AI-generated category from the shared
                // onboarding analysis (analyzeTableBatch) — identical regardless of
                // whether this table was AI-recommended or added via Browse All, since
                // both pass through that one analysis step. Absent ⇒ omitted here, so
                // SemanticService/SemanticRepository's existing COALESCE preserves
                // whatever group_label (if any) the entity already has.
                putStrIfPresent(entityBody, "groupLabel", entity.get("category"));
                // Connection-Scoped Industry Pack Semantic Assignment:
                //   pack_key    — always the connection's active pack; never absent because of
                //                 what the caller supplied, only because there is no active pack.
                //   concept_key — the LLM's own validated decision from BusinessObjectBatchAnalyzer
                //                 (see its "conceptResolution" handling), carried through the
                //                 draft/review step when the caller supplies it. Never assigned
                //                 here, never inferred from table names — Java only relays and
                //                 persists a value the model already produced and this pipeline's
                //                 caller already validated at analysis time.
                // Both use putStrIfPresent, so an absent/blank value is simply omitted — the
                // existing UPSERT_ENTITY COALESCE then preserves whatever value (if any) the
                // entity already has. Neither field can ever be erased by an analysis that didn't
                // resolve one.
                putStrIfPresent(entityBody, "packKey", activePackKey);
                putStrIfPresent(entityBody, "conceptKey", entity.get("conceptKey"));
                semanticService.createOrUpdateEntity(entityBody, userEmail);
                entitiesCreated++;
            } catch (Exception e) {
                log.warn("Failed to register entity {}: {}", resolvedKey, e.getMessage());
                failures.add("entity " + resolvedKey + ": " + e.getMessage());
            }

            // 4. Vocabulary bound to the resolved entity; term keys scoped per
            //    entity so the same business word on two entities never collides.
            List<Map<String, Object>> vocab =
                    (List<Map<String, Object>>) entity.getOrDefault("vocabulary", List.of());
            for (Map<String, Object> term : vocab) {
                if (!Boolean.TRUE.equals(term.get("approved"))) continue;
                try {
                    Map<String, Object> termBody = new LinkedHashMap<>();
                    termBody.put("termKey",       slugify((String) term.get("term")) + "-" + resolvedKey);
                    termBody.put("term",          term.get("term"));
                    termBody.put("definition",    term.get("definition"));
                    termBody.put("sqlEquivalent", term.getOrDefault("sqlEquivalent", ""));
                    termBody.put("domainKey",     domainKey);
                    termBody.put("entityKey",     resolvedKey);
                    termBody.put("status",        "ACTIVE");
                    semanticService.createTerm(termBody);
                    vocabCreated++;
                } catch (Exception e) {
                    log.warn("Failed to register vocab term: {}", e.getMessage());
                    failures.add("term " + term.get("term") + ": " + e.getMessage());
                }
            }

            // 5. Business Values + mappings (semantic layer over Value Domains). Optional: present
            //    only when the review step produced them; absent ⇒ no-op (fully backward compatible).
            //    Physical values are referenced by (value_domain_key, physical_value); none are stored here.
            persistBusinessValues(entity, userEmail, failures);
        }
        log.info("onboarding.performance stage=apply elapsedMs={}", System.currentTimeMillis() - applyStart);

        // 4. Relationship discovery — once per batch, after all entities exist,
        //    so the table→entity index it consumes is complete.
        int relationships = 0;
        if (connectionKey != null && !connectionKey.isBlank()) {
            long relStart = System.currentTimeMillis();
            try {
                String schema = schemaName != null && !schemaName.isBlank() ? schemaName : "public";
                relationships = relationshipDiscovery.discoverAndPersist(connectionKey, schema, domainKey);
                log.info("Auto-discovered {} relationships for domain '{}'", relationships, domainKey);
            } catch (Exception e) {
                log.warn("Relationship auto-discovery failed (non-fatal): {}", e.getMessage());
                failures.add("relationship discovery: " + e.getMessage());
            } finally {
                log.info("onboarding.performance stage=relationships elapsedMs={}",
                        System.currentTimeMillis() - relStart);
            }
        }

        return new RegistrationResult(objectsCreated, entitiesCreated, vocabCreated,
                relationships, List.copyOf(failures));
    }

    // ── Business Entity selection (PRO-22) ───────────────────────────────────

    /** Outcome of entity selection: the identity to register under, and — when an
     *  existing entity is reused — its curated name, which must be preserved. */
    record EntitySelection(String entityKey, String existingName, boolean reused) {}

    /**
     * Selects the Business Entity identity for a freshly registered data object:
     * <ol>
     *   <li><b>Tier 0:</b> an ACTIVE entity already bound to this object key is
     *       reused unconditionally — deterministic identity outranks any AI
     *       decision.</li>
     *   <li><b>Tier 2 validation:</b> an AI {@code entityResolution} REUSE decision
     *       is honored only if the key was in the candidate set retrievable for this
     *       table, the entity exists, is ACTIVE, is in this domain, its binding is
     *       empty or already this object, and confidence clears the threshold.</li>
     *   <li><b>CREATE (default):</b> the drafted key, suffixed deterministically if
     *       it collides with an entity bound to a <em>different</em> table — a name
     *       collision must never silently merge two distinct concepts.</li>
     * </ol>
     * Every rejected reuse degrades to CREATE and is recorded — selection never
     * blocks registration.
     */
    private EntitySelection selectEntity(String domainKey, String objectKey, String tableName,
            Map<String, Object> entity, String draftedKey, List<String> failures) {

        // Tier 0 — deterministic binding lookup, before any AI opinion
        var bound = entityCandidates.findBoundEntity(objectKey);
        if (bound.isPresent()) {
            return new EntitySelection(bound.get().entityKey(), bound.get().entityName(), true);
        }

        // Tier 2 — validate the AI's constrained decision, if one was made
        Object resolution = entity.get("entityResolution");
        if (resolution instanceof Map<?, ?> res
                && "REUSE".equalsIgnoreCase(String.valueOf(res.get("decision")))) {
            String selectedKey = res.get("entityKey") != null
                    ? String.valueOf(res.get("entityKey")).trim() : null;
            double confidence = parseConfidence(res.get("confidence"));

            if (selectedKey == null || selectedKey.isBlank()) {
                failures.add("entity reuse for " + tableName + " rejected: no entityKey returned");
            } else if (confidence < REUSE_CONFIDENCE_THRESHOLD) {
                failures.add("entity reuse for " + tableName + " rejected: confidence "
                        + confidence + " below " + REUSE_CONFIDENCE_THRESHOLD);
            } else {
                ReuseValidation validation = validateReuse(domainKey, objectKey, tableName, selectedKey);
                if (validation.rejectionReason() == null) {
                    return new EntitySelection(selectedKey, validation.entity().entityName(), true);
                }
                failures.add("entity reuse for " + tableName + " rejected: " + validation.rejectionReason());
            }
        }

        // CREATE — collision guard: never let a drifted name overwrite an entity
        // that is bound to a different table (uniqueSlug pattern).
        String key = draftedKey;
        int suffix = 2;
        while (true) {
            var existing = entityCandidates.findEntity(key);
            if (existing.isEmpty()) break;
            String otherBinding = existing.get().primaryObjectKey();
            if (otherBinding == null || otherBinding.isBlank() || otherBinding.equals(objectKey)) break;
            key = draftedKey + "-" + suffix++;
        }
        if (!key.equals(draftedKey)) {
            failures.add("entity key collision for " + tableName + ": '" + draftedKey
                    + "' is bound to another table; created as '" + key + "'");
        }
        return new EntitySelection(key, null, false);
    }

    /** Outcome of {@link #validateReuse}: the entity fetched during validation (so the
     *  caller never re-fetches it), plus a rejection reason when validation fails. */
    private record ReuseValidation(BusinessEntity entity, String rejectionReason) {
        static ReuseValidation ok(BusinessEntity entity)   { return new ReuseValidation(entity, null); }
        static ReuseValidation rejected(String reason)     { return new ReuseValidation(null, reason); }
    }

    /** Integrity checks for an AI-selected reuse key; the entity looked up here is
     *  handed back so the caller (selectEntity) never re-queries it (Optimization C). */
    private ReuseValidation validateReuse(String domainKey, String objectKey, String tableName, String selectedKey) {
        boolean offered = entityCandidates.retrieve(domainKey, tableName).stream()
                .anyMatch(c -> c.entityKey().equals(selectedKey));
        if (!offered) return ReuseValidation.rejected("'" + selectedKey + "' was not in the offered candidate set");

        var existing = entityCandidates.findEntity(selectedKey);
        if (existing.isEmpty()) return ReuseValidation.rejected("'" + selectedKey + "' does not exist");
        if ("ARCHIVED".equalsIgnoreCase(existing.get().status())) {
            return ReuseValidation.rejected("'" + selectedKey + "' is archived");
        }
        if (domainKey != null && !domainKey.equals(existing.get().domainKey())) {
            return ReuseValidation.rejected("'" + selectedKey + "' belongs to domain " + existing.get().domainKey());
        }
        String binding = existing.get().primaryObjectKey();
        if (binding != null && !binding.isBlank() && !binding.equals(objectKey)) {
            return ReuseValidation.rejected("'" + selectedKey + "' is already bound to " + binding + " — refusing to rebind");
        }
        return ReuseValidation.ok(existing.get());
    }

    private static double parseConfidence(Object value) {
        if (value == null) return 0.0;
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /** Nullable confidence for Business Value metadata (distinct from the 0.0-defaulting entity gate). */
    private static Double parseConfidenceNullable(Object value) {
        if (value == null) return null;
        try { return Double.parseDouble(String.valueOf(value)); } catch (NumberFormatException e) { return null; }
    }

    /**
     * Persists the reviewed Business Values and their physical-value mappings for one entity
     * (semantic layer over Value Domains). Optional and defensive: absent arrays ⇒ no-op, so every
     * existing apply payload is unaffected. Applies the deterministic governance rules — physical→
     * concept uniqueness/conflict and the cross-application / low-confidence approval requirement —
     * before writing. Stores no physical values (mappings reference them by natural key).
     */
    @SuppressWarnings("unchecked")
    private void persistBusinessValues(Map<String, Object> entity, String userEmail, List<String> failures) {
        if (businessValues == null) return;

        for (Map<String, Object> bv : (List<Map<String, Object>>) entity.getOrDefault("businessValues", List.of())) {
            if (!Boolean.TRUE.equals(bv.get("approved"))) continue;
            try {
                String attr = (String) bv.get("businessAttributeKey");
                String name = (String) bv.get("name");
                String key  = strOrDefault(bv.get("businessValueKey"),
                        "bv-" + slugify(name) + "-" + slugify(strOrDefault(attr, "attr")));
                String source = strOrDefault(bv.get("source"), com.sei.nexus.enterprise.BusinessValue.SOURCE_AI);
                String status = strOrDefault(bv.get("approvalStatus"),
                        com.sei.nexus.enterprise.BusinessValue.SOURCE_MANUAL.equals(source)
                                ? com.sei.nexus.enterprise.BusinessValue.STATUS_APPROVED
                                : com.sei.nexus.enterprise.BusinessValue.STATUS_PENDING);
                businessValues.saveBusinessValue(new com.sei.nexus.enterprise.BusinessValue(
                        key, attr, name, (String) bv.get("description"), source,
                        parseConfidenceNullable(bv.get("confidence")), status, userEmail, null, null, null, null));
            } catch (Exception e) {
                failures.add("business value " + bv.get("name") + ": " + e.getMessage());
            }
        }

        for (Map<String, Object> m : (List<Map<String, Object>>) entity.getOrDefault("businessValueMappings", List.of())) {
            if (!Boolean.TRUE.equals(m.get("approved"))) continue;
            try {
                String vdk = (String) m.get("valueDomainKey");
                String pv  = String.valueOf(m.get("physicalValue"));
                String bvk = (String) m.get("businessValueKey");
                boolean crossApp = Boolean.TRUE.equals(m.get("crossApplication"));
                var proposed = new com.sei.nexus.enterprise.BusinessValueMapping(
                        "bvm-" + slugify(vdk) + "-" + slugify(pv), vdk, pv, bvk,
                        strOrDefault(m.get("source"), com.sei.nexus.enterprise.BusinessValue.SOURCE_AI),
                        parseConfidenceNullable(m.get("confidence")),
                        com.sei.nexus.enterprise.BusinessValue.STATUS_PENDING, crossApp,
                        userEmail, null, null, null, null);

                var existing = businessValues.findMapping(vdk, pv).orElse(null);
                if (com.sei.nexus.enterprise.BusinessValueGovernance.conflictsWith(proposed, existing)) {
                    failures.add("business value mapping " + vdk + "/" + pv
                            + " conflicts with existing concept " + existing.businessValueKey());
                    continue;
                }
                String status = com.sei.nexus.enterprise.BusinessValueGovernance.requiresCustomerApproval(proposed)
                        ? com.sei.nexus.enterprise.BusinessValue.STATUS_PENDING
                        : com.sei.nexus.enterprise.BusinessValue.STATUS_APPROVED;
                businessValues.saveMapping(new com.sei.nexus.enterprise.BusinessValueMapping(
                        proposed.mappingKey(), vdk, pv, bvk, proposed.source(), proposed.confidence(),
                        status, crossApp, userEmail, null, null, null, null));
            } catch (Exception e) {
                failures.add("business value mapping: " + e.getMessage());
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Accepts the AI's list form or an already-joined string; stores CSV, the
     *  format {@code scanAndSaveColumns} splits on. */
    private static void putCsvIfPresent(Map<String, Object> body, String key, Object value) {
        if (value == null) return;
        String csv;
        if (value instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object o : list) if (o != null && !o.toString().isBlank()) parts.add(o.toString().trim());
            csv = String.join(",", parts);
        } else {
            csv = value.toString().trim();
        }
        if (!csv.isBlank()) body.put(key, csv);
    }

    private static void putStrIfPresent(Map<String, Object> body, String key, Object value) {
        if (value != null && !value.toString().isBlank()) body.put(key, value.toString());
    }

    /**
     * Connection-Scoped Industry Pack Semantic Assignment: the connection's ACTIVE Industry Pack
     * key, or {@code null} when there isn't one (no assignment, lookup failure, or this instance
     * was built without a {@link IndustryPackRepository} — the 4-arg test convenience
     * constructor). Mirrors {@link #businessValues}'s null-guard exactly: absence is a normal,
     * fully-supported state, never an error.
     */
    private String resolveActivePackKey(String connectionKey) {
        if (packRepository == null || connectionKey == null || connectionKey.isBlank()) return null;
        try {
            return packRepository.findActivePackForConnection(connectionKey)
                    .map(TenantPack::packKey)
                    .orElse(null);
        } catch (Exception e) {
            log.debug("Could not resolve active pack for connection '{}': {}", connectionKey, e.getMessage());
            return null;
        }
    }

    private static String strOrDefault(Object value, String def) {
        return value != null && !value.toString().isBlank() ? value.toString() : def;
    }

    /** Single Java slugify for entity/term identity (moved from OnboardingService;
     *  length cap now measured on the transformed string, not the input). */
    static String slugify(String input) {
        if (input == null) return "entity";
        String slug = input.toLowerCase()
                           .replaceAll("[^a-z0-9]+", "-")
                           .replaceAll("^-+|-+$", "");
        return slug.substring(0, Math.min(slug.length(), 80));
    }

    static String toTitleCase(String input) {
        if (input == null || input.isBlank()) return "Entity";
        String[] words = input.replace('_', ' ').replace('-', ' ').split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) {
                sb.append(Character.toUpperCase(w.charAt(0)));
                if (w.length() > 1) sb.append(w.substring(1).toLowerCase());
                sb.append(' ');
            }
        }
        return sb.toString().trim();
    }
}
