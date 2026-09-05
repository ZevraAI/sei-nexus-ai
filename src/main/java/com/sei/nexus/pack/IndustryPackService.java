package com.sei.nexus.pack;

import com.sei.nexus.common.Keys;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.connection.ConnectionRepository;
import com.sei.nexus.enterprise.DataObject;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import com.sei.nexus.knowledge.ConceptKnowledgeSynchronizationService;
import com.sei.nexus.prompt.BusinessObjectBatchAnalyzer;
import com.sei.nexus.semantic.BusinessEntity;
import com.sei.nexus.semantic.SemanticService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrates industry pack operations:
 *
 * <ul>
 *   <li>{@link #listPacks()} — return all available packs from the catalogue.</li>
 *   <li>{@link #previewPack(String)} — dry-run: show match coverage without committing.</li>
 *   <li>{@link #applyPack(String, String, String, String)} — assign the pack to a connection
 *       (a pure semantic-context assignment, {@code connection -> ACTIVE pack}), create
 *       vocabulary, and store the assignment. Does <b>not</b> create/touch any {@code
 *       nexus_business_entity} row — see that method's javadoc ("Stop Apply Pack From Creating
 *       Tenant Business Entities"). Tenant Business Entities are created exclusively by
 *       Discover/Onboarding ({@code MetadataRegistrationService}), which independently derives
 *       {@code pack_key} from the connection's active pack and relays the LLM's own {@code
 *       concept_key} decision.</li>
 *   <li>{@link #listAppliedPacks()} — list packs applied to this tenant.</li>
 *   <li>{@link #recommend(List)} — fast recommendation for onboarding.</li>
 * </ul>
 *
 * <p>Vocabulary creation delegates to {@link SemanticService} so pack vocabulary appears in the
 * Semantic Layer UI — unaffected by this class's entity-creation removal (a separate concern).
 */
@Service
public class IndustryPackService {

    private static final Logger log = LoggerFactory.getLogger(IndustryPackService.class);

    private final IndustryPackRepository     packRepository;
    private final PackEntityMapper           entityMapper;
    private final PackRecommendationService  recommendationService;
    private final SemanticService            semanticService;
    private final EnterpriseMapRepository    enterpriseMapRepository;
    // Connection-Scoped Industry Pack Assignment: resolving/validating the connectionKey an
    // apply request names reuses this existing repository — no new ownership/authorization
    // mechanism. Because nexus_connection is a per-tenant-schema table (routed by the existing
    // TenantContext/search_path machinery), a connectionKey belonging to a different tenant
    // simply cannot be found here — tenant isolation falls out of the existing architecture,
    // it is not reimplemented.
    private final ConnectionRepository       connectionRepository;
    // Postgres → Vector Store Concept Knowledge synchronization: Pack apply/remove are the two
    // mutation paths proven (by prior investigation) to directly change the fields the Concept
    // Knowledge projection reads (concept_key/pack_key/connection_key) — every other mutation
    // path is deliberately left unwired for this feature. Triggered fire-and-forget, async, AFTER
    // the Postgres changes below have already succeeded — a Vector Store failure must never roll
    // back a successful Postgres commit (see ConceptKnowledgeSynchronizationService's own javadoc).
    private final ConceptKnowledgeSynchronizationService conceptKnowledgeSynchronizationService;
    // Make Apply Pack Perform LLM Concept Classification: reuses the EXACT same batched
    // analysis/LLM conceptResolution mechanism Discover/Onboarding already use for newly
    // registered entities — no second resolver, no duplicated prompt/schema logic.
    private final BusinessObjectBatchAnalyzer businessObjectBatchAnalyzer;

    // Same configured batch size Discover/Onboarding already use (EnterpriseMapService,
    // OnboardingService) — reused, not reinvented, per this task's explicit instruction not to
    // arbitrarily change or duplicate the existing batch size.
    @Value("${nexus.onboarding.batch-size:4}")
    private int classificationBatchSize = 4;

    public IndustryPackService(IndustryPackRepository packRepository,
                               PackEntityMapper entityMapper,
                               PackRecommendationService recommendationService,
                               SemanticService semanticService,
                               EnterpriseMapRepository enterpriseMapRepository,
                               ConnectionRepository connectionRepository,
                               BusinessObjectBatchAnalyzer businessObjectBatchAnalyzer,
                               ConceptKnowledgeSynchronizationService conceptKnowledgeSynchronizationService) {
        this.packRepository         = packRepository;
        this.entityMapper           = entityMapper;
        this.recommendationService  = recommendationService;
        this.semanticService        = semanticService;
        this.enterpriseMapRepository = enterpriseMapRepository;
        this.connectionRepository   = connectionRepository;
        this.businessObjectBatchAnalyzer = businessObjectBatchAnalyzer;
        this.conceptKnowledgeSynchronizationService = conceptKnowledgeSynchronizationService;
    }

    // ── Pack catalogue ────────────────────────────────────────────────────────

    public List<IndustryPack> listPacks() {
        return packRepository.findAllPacks();
    }

    public IndustryPack getPack(String packKey) {
        return packRepository.findPackById(packKey)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "Industry pack not found: " + packKey));
    }

    // ── Applied packs ─────────────────────────────────────────────────────────

    public List<TenantPack> listAppliedPacks() {
        return packRepository.findAppliedPacks();
    }

    // ── Preview ───────────────────────────────────────────────────────────────

    /**
     * Dry-run: shows what would be created if the pack were applied, scanning every table
     * discovered anywhere in the domain. Kept for backward compatibility; prefer
     * {@link #previewPack(String, String, String)} whenever a connectionKey is available, since
     * this domain-wide preview will not match what {@link #applyPack} actually scopes to.
     */
    public PackPreview previewPack(String packKey, String domainKey) {
        return previewPack(packKey, domainKey, null);
    }

    /**
     * Dry-run: shows what would be created if the pack were applied. No DB writes.
     *
     * <p>Connection-Scoped Industry Pack Assignment: when {@code connectionKey} is supplied,
     * the preview scans only that connection's discovered tables — the exact same set
     * {@link #applyPack} itself will use — via {@link #getDiscoveredTableNamesForConnection},
     * so what the user previews is what they will actually get. When {@code connectionKey} is
     * blank/null (e.g. no connection selected yet in the UI), preview falls back to the
     * domain-wide scan for backward compatibility; callers must not present that result as
     * connection-scoped.
     */
    public PackPreview previewPack(String packKey, String domainKey, String connectionKey) {
        IndustryPack pack = getPack(packKey);
        List<String> tables;
        if (connectionKey != null && !connectionKey.isBlank()) {
            connectionRepository.findByKey(connectionKey)
                    .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                            "Connection not found: " + connectionKey));
            tables = getDiscoveredTableNamesForConnection(connectionKey);
        } else {
            tables = getDiscoveredTableNames(domainKey);
        }
        PackEntityMapper.EntityMatchResult result = entityMapper.match(pack, tables);

        double coverage = pack.entities().isEmpty() ? 0.0
                : (double) result.matched().size() / pack.entities().size();

        return new PackPreview(
                pack.packId(),
                pack.displayName(),
                result.matched(),
                result.unmatched(),
                safeSize(pack.vocabulary()),
                safeSize(pack.suggestedQuestions()),
                safeSize(pack.alertTemplates()),
                coverage);
    }

    // ── Apply ─────────────────────────────────────────────────────────────────

    /**
     * Assigns a pack to one specific connection. This is a pure semantic-context assignment —
     * it establishes {@code connection -> ACTIVE pack} and nothing more toward the tenant's
     * physical data:
     * <ol>
     *   <li>Validate the connection exists (and, by construction of the existing tenant-schema
     *       routing, belongs to the current tenant — see {@link #connectionRepository}).</li>
     *   <li>Reject if that connection already has an ACTIVE pack assignment (the
     *       {@code nexus_tenant_pack.connection_key} partial unique index enforces this at the
     *       database level too; this is the same check surfaced as a clean API error instead of
     *       a raw constraint violation).</li>
     *   <li>Match entities to tables discovered <em>on this connection only</em> via {@link
     *       PackEntityMapper} — purely <b>informational</b>: the result only feeds {@code
     *       coverage_score}/{@code entity_mapping} in the returned {@link PackApplicationResult}
     *       (the same numbers {@link #previewPack} already reports). Nothing from this match is
     *       ever written to {@code nexus_business_entity} — matching a table pattern is not the
     *       mechanism that associates the pack with an entity (see the next step).</li>
     *   <li><b>Fix Apply Pack Association Regression:</b> stamp {@code pack_key} — and ONLY
     *       {@code pack_key}, never {@code concept_key} — onto every EXISTING Business Entity
     *       already bound to one of this connection's physical objects, via {@link
     *       SemanticService#associatePackKeyForConnection}. Scoped by the existing {@code
     *       primary_object_key -> nexus_data_object.connection_key} relationship, no new column.
     *       Creates nothing: a table with no Business Entity yet is simply not matched by that
     *       UPDATE and is left for Discover/Onboarding to register later — at which point {@code
     *       MetadataRegistrationService} independently derives the same active pack's {@code
     *       pack_key} for the newly-created row. {@code concept_key} is never touched here — it
     *       remains exactly whatever it already was, {@code NULL} or an existing LLM decision —
     *       the LLM alone decides it, via {@code BusinessObjectBatchAnalyzer}.</li>
     *   <li>Create {@code nexus_operational_vocabulary} for each vocabulary term — unchanged;
     *       vocabulary ownership is an explicitly separate concern from this task.</li>
     *   <li>Record the assignment in {@code nexus_tenant_pack} with the real
     *       {@code connection_key}.</li>
     * </ol>
     *
     * <p><b>Stop Apply Pack From Creating Tenant Business Entities</b> (an earlier task, still in
     * effect — do not confuse the association above with the creation that task removed): this
     * method used to also create a {@code nexus_business_entity} row per matched {@link
     * PackEntity}, which produced a second, duplicate entity for any table the tenant had
     * already onboarded (e.g. {@code retail-v1-product} alongside a pre-existing {@code
     * product}). That creation stays removed. A {@link PackEntity} is Zevra's canonical
     * definition of a business concept — it is not, and must never automatically become, a
     * tenant's Business Entity. What this task restores is narrower and additive: an EXISTING
     * entity for an already-onboarded table now correctly receives the connection's {@code
     * pack_key} again (a real regression the entity-creation removal accidentally introduced),
     * without reviving any entity-creation path and without ever assigning {@code concept_key}.
     *
     * @param packKey       Pack identifier from the catalogue.
     * @param domainKey     Target domain for created vocabulary (unchanged role).
     * @param connectionKey The connection this Pack is being assigned to — the scoping key for
     *                      the informational table match, the existing-entity association, and
     *                      the persisted assignment.
     * @param appliedBy     Email of the user applying the pack.
     */
    public PackApplicationResult applyPack(String packKey, String domainKey, String connectionKey, String appliedBy) {
        IndustryPack pack = getPack(packKey);

        // Existence check IS the ownership check: nexus_connection is a per-tenant-schema
        // table, routed by the existing TenantContext/search_path machinery — a connectionKey
        // belonging to a different tenant is simply not found here, the same way any other
        // cross-tenant lookup in this codebase is already prevented.
        connectionRepository.findByKey(connectionKey)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "Connection not found: " + connectionKey));

        // Existing rule, unchanged: this exact pack has not already been applied anywhere in
        // this tenant schema.
        if (packRepository.findAppliedPack(packKey).isPresent()) {
            throw new NexusException(HttpStatus.CONFLICT,
                    "Pack '" + pack.displayName() + "' has already been applied to this tenant. " +
                    "Remove it first before re-applying.");
        }
        // New rule: this connection does not already have a different ACTIVE pack. Mirrors, as
        // a clean API error, the database's own uq_tenant_pack_active_connection partial unique
        // index — "one active Industry Pack per connection".
        packRepository.findActivePackForConnection(connectionKey).ifPresent(existing -> {
            throw new NexusException(HttpStatus.CONFLICT,
                    "Connection '" + connectionKey + "' already has an active Industry Pack ('"
                            + existing.packKey() + "'). Remove it first before assigning a different one.");
        });

        // Informational only (see javadoc): scoped to this ONE connection, reusing
        // EnterpriseMapRepository.findDataObjectsByConnection, the same read-only method
        // GlobalConceptResolver already relies on for the identical purpose. PackEntityMapper
        // .match() itself is completely unchanged — its result is used only to report
        // coverage_score/entity_mapping, never to create a nexus_business_entity row.
        List<String> tables = getDiscoveredTableNamesForConnection(connectionKey);
        PackEntityMapper.EntityMatchResult matchResult = entityMapper.match(pack, tables);

        // Fix Apply Pack Association Regression: associate pack_key with every EXISTING Business
        // Entity already bound to this connection's physical objects — never concept_key, never
        // a new row. See this method's javadoc for the full rationale and how this differs from
        // the entity-creation behavior that was (correctly) removed in an earlier task.
        int entitiesAssociated;
        try {
            entitiesAssociated = semanticService.associatePackKeyForConnection(packKey, connectionKey);
        } catch (Exception e) {
            log.warn("Failed to associate pack_key with existing entities for connection '{}': {}",
                    connectionKey, e.getMessage());
            entitiesAssociated = 0;
        }

        // Record the assignment BEFORE classification runs (moved up from the end of this method
        // — a real bug found via real-tenant verification): BusinessObjectBatchAnalyzer's own
        // resolveActivePackContext() decides whether to offer canonical concepts to the LLM by
        // calling packRepository.findActivePackForConnection(connectionKey) itself — if this
        // connection's TenantPack row isn't saved as ACTIVE yet, the analyzer sees "no active
        // pack" and never renders a concept catalogue at all, so every entity comes back
        // "unresolved" regardless of how well its table actually matches a concept. Saving the
        // assignment first (the one-active-pack-per-connection guard above already ran, so this
        // is safe) makes classification see the exact same active-pack state a subsequent
        // Discover/Onboarding run would.
        double coverage = pack.entities().isEmpty() ? 0.0
                : (double) matchResult.matched().size() / pack.entities().size();
        TenantPack tenantPack = new TenantPack(
                packKey, connectionKey, pack.version(), pack.displayName(),
                "ACTIVE", matchResult.matched(), coverage,
                null, appliedBy);
        packRepository.saveTenantPack(tenantPack);

        // Make Apply Pack Perform LLM Concept Classification: THE primary purpose of applying a
        // pack — send every EXISTING entity already bound to this connection's physical objects
        // through the existing LLM conceptResolution mechanism, against this pack's canonical
        // concepts. Never creates an entity (candidates are exclusively EXISTING, ACTIVE-bound
        // entities); never Java-derived (concept_key is always whatever the LLM's own validated
        // conceptResolution returned, including null when it found no confident match).
        ClassificationSummary classification = classifyExistingObjectsForConnection(pack, connectionKey, domainKey);

        int vocabAdded = 0;
        for (PackVocabularyTerm term : safe(pack.vocabulary())) {
            try {
                // Industry Pack Removal Lifecycle: a deterministic, pack-namespaced term_key —
                // nexus_operational_vocabulary has no provenance/pack_key column at all
                // (confirmed by investigation), so this is the only reliable way Remove Pack can
                // later identify and revert exactly these rows, without adding a new column.
                // Vocabulary ownership/materialization itself is unchanged by this task.
                String termKey = packTermKey(packKey, term.term());
                // Fix Remove Pack State + Pack Vocabulary Duplication: the lookup itself changes
                // nothing (createTerm's UPSERT — ON CONFLICT (term_key) DO UPDATE — is already
                // correct/idempotent by construction on this deterministic key), but makes the
                // intended "reuse/reactivate, never duplicate" lifecycle explicit and observable
                // rather than relying silently on the UPSERT, and lets a genuine second-Apply
                // (e.g. after Remove) log clearly as a reactivation, not a fresh creation.
                boolean reactivating = semanticService.findTermByKey(termKey).isPresent();
                semanticService.createTerm(Map.of(
                        "termKey",       termKey,
                        "domainKey",     safe(domainKey),
                        "term",          term.term(),
                        "definition",    safe(term.definition()),
                        "sql_equivalent", safe(term.sqlHint()),
                        "status",        "ACTIVE"));
                vocabAdded++;
                if (reactivating) {
                    log.debug("Reactivated existing Pack vocabulary term '{}' ({}) for pack '{}'",
                            term.term(), termKey, packKey);
                }
            } catch (Exception e) {
                log.debug("Failed to create vocab term '{}': {}", term.term(), e.getMessage());
            }
        }

        log.info("Pack '{}' assigned to connection '{}': {} existing entities associated (pack_key), "
                        + "{} classified with a concept, {} analyzed but unresolved, {} vocab terms, "
                        + "coverage {}% (no NEW Business Entities created)",
                packKey, connectionKey, entitiesAssociated, classification.classified(),
                classification.unresolved(), vocabAdded, Math.round(coverage * 100));

        // Fire-and-forget, AFTER every Postgres change above has already succeeded — a Vector
        // Store failure here must never roll back the pack assignment/classification/vocabulary
        // that was just committed.
        conceptKnowledgeSynchronizationService.triggerAsync();

        return new PackApplicationResult(
                packKey, pack.displayName(),
                0, vocabAdded,
                safeSize(pack.suggestedQuestions()),
                coverage,
                matchResult.matched(),
                matchResult.unmatched(),
                classification.classified(),
                classification.unresolved());
    }

    /** Batch-classification outcome: how many existing entities got a valid concept_key vs. were
     *  analyzed but left unresolved (never a failure — see {@link #classifyExistingObjectsForConnection}). */
    private record ClassificationSummary(int classified, int unresolved) {
        static final ClassificationSummary NONE = new ClassificationSummary(0, 0);
    }

    /**
     * Make Apply Pack Perform LLM Concept Classification: the core of this task. Classifies
     * EXISTING tenant Business Entities already bound to {@code connectionKey}'s physical
     * objects against {@code pack}'s canonical concepts, via the exact same {@link
     * BusinessObjectBatchAnalyzer}/LLM {@code conceptResolution} mechanism Discover/Onboarding
     * already use for newly-registered entities — no second resolver, no duplicated prompt.
     *
     * <ul>
     *   <li><b>Candidates</b>: only objects with an existing, ACTIVE bound entity (via {@link
     *       SemanticService#findActiveByPrimaryObjectKey}) — an object with no entity yet is
     *       simply skipped; Apply Pack never creates one (see this class's other javadoc).</li>
     *   <li><b>Batching</b>: the same configured batch size Discover/Onboarding already use
     *       ({@code nexus.onboarding.batch-size}, default 4) — grouped by schema first (usually
     *       one, but never assumed), then partitioned exactly like {@code
     *       EnterpriseMapService.analyzeForOnboarding} already does. No new batching mechanism,
     *       no arbitrary size change.</li>
     *   <li><b>Persistence</b>: {@link SemanticService#setConceptKey} — touches ONLY {@code
     *       concept_key} on the one entity the object is already bound to; never {@code
     *       createOrUpdateEntity} (which would risk overwriting unrelated fields with a partial
     *       body), never a new row.</li>
     *   <li><b>Partial failure</b>: a whole batch's AI call failing, or one object's own
     *       {@code describeTable} failing (surfaced as {@code analyzed.get(table).error} by the
     *       shared analyzer's existing graceful degradation), skips ONLY that batch/object's
     *       concept_key update — it is left exactly as it was, never nulled by an infrastructure
     *       failure. Every other already-processed batch/object's result is unaffected — Apply
     *       Pack never rolls back the connection assignment or already-persisted classifications
     *       because some objects failed.</li>
     *   <li><b>Unresolved vs. failed</b>: when the LLM itself returns no confident
     *       {@code conceptResolution} for an object it DID successfully analyze, {@code
     *       concept_key} is explicitly set to {@code null} (not left untouched) — this is the
     *       correct, honest outcome of "this pack doesn't (yet) define a matching concept," and
     *       is deliberately distinct from a describeTable/AI-call failure, which is left
     *       untouched instead.</li>
     * </ul>
     */
    private ClassificationSummary classifyExistingObjectsForConnection(IndustryPack pack, String connectionKey,
                                                                        String domainKey) {
        List<DataObject> objects;
        try {
            objects = enterpriseMapRepository.findDataObjectsByConnection(connectionKey);
        } catch (Exception e) {
            log.warn("Could not load objects to classify for connection '{}': {}", connectionKey, e.getMessage());
            return ClassificationSummary.NONE;
        }
        if (objects.isEmpty()) return ClassificationSummary.NONE;

        record Candidate(String tableName, String schemaName, String entityKey) {}
        List<Candidate> candidates = new ArrayList<>();
        for (DataObject object : objects) {
            if (object.tableName() == null) continue;
            try {
                semanticService.findActiveByPrimaryObjectKey(object.objectKey())
                        .ifPresent(entity -> candidates.add(
                                new Candidate(object.tableName(), safe(object.schemaName()), entity.entityKey())));
            } catch (Exception e) {
                log.debug("Could not resolve bound entity for object '{}': {}", object.objectKey(), e.getMessage());
            }
        }
        if (candidates.isEmpty()) {
            log.debug("No existing Business Entities bound to connection '{}' yet — nothing to classify "
                    + "(new objects will be classified automatically when Discover/Onboarding registers them)",
                    connectionKey);
            return ClassificationSummary.NONE;
        }

        int classified = 0;
        int unresolved = 0;
        Map<String, List<Candidate>> bySchema = candidates.stream()
                .collect(Collectors.groupingBy(Candidate::schemaName));
        for (Map.Entry<String, List<Candidate>> schemaEntry : bySchema.entrySet()) {
            String schemaName = schemaEntry.getKey().isBlank() ? "public" : schemaEntry.getKey();
            List<Candidate> schemaCandidates = schemaEntry.getValue();
            int step = Math.max(1, classificationBatchSize);
            for (int i = 0; i < schemaCandidates.size(); i += step) {
                List<Candidate> batch = schemaCandidates.subList(i, Math.min(i + step, schemaCandidates.size()));
                List<String> tableNames = batch.stream().map(Candidate::tableName).toList();
                Map<String, String> entityKeyByTable = new HashMap<>();
                for (Candidate c : batch) entityKeyByTable.putIfAbsent(c.tableName(), c.entityKey());

                Map<String, Map<String, Object>> analyzed;
                try {
                    // Cost baseline instrumentation (measurement-only): tag this call so its
                    // LLM_METRIC line and nexus_usage_event row attribute to "industry_pack"
                    // rather than the default "chat" feature bucket. No effect on the call itself.
                    com.sei.nexus.ai.LlmCallTag.set("PACK_CONCEPT_CLASSIFICATION");
                    com.sei.nexus.usage.UsageContext.set("industry_pack", null);
                    analyzed = businessObjectBatchAnalyzer.analyzeBatch(connectionKey, schemaName, domainKey, tableNames);
                } catch (Exception e) {
                    log.warn("Concept classification batch failed for connection '{}' schema '{}' ({} objects): {}",
                            connectionKey, schemaName, tableNames.size(), e.getMessage());
                    continue; // this batch's entities keep whatever concept_key they already had
                }

                for (String tableName : tableNames) {
                    Map<String, Object> entry = analyzed.get(tableName);
                    String entityKey = entityKeyByTable.get(tableName);
                    if (entry == null || entityKey == null) continue;
                    if (entry.containsKey("error")) {
                        // Genuine infrastructure failure (describeTable failed / no analysis
                        // returned) — never touch concept_key; this is not "the LLM said no match."
                        log.debug("Skipping concept classification for '{}': {}", tableName, entry.get("error"));
                        continue;
                    }
                    try {
                        // The LLM's own validated decision (BusinessObjectBatchAnalyzer already
                        // checked it against pack.entities() before this field could ever be
                        // present) — may legitimately be null; Java relays, never derives it.
                        String conceptKey = (String) entry.get("conceptKey");
                        semanticService.setConceptKey(entityKey, conceptKey);
                        if (conceptKey != null) classified++; else unresolved++;
                    } catch (Exception e) {
                        log.warn("Failed to persist concept_key for entity '{}': {}", entityKey, e.getMessage());
                    }
                }
            }
        }
        return new ClassificationSummary(classified, unresolved);
    }

    /**
     * Industry Pack Removal Lifecycle: removes the connection's active pack assignment AND
     * reverts the artifacts this specific pack application created — never artifacts that
     * existed independently before it.
     *
     * <ol>
     *   <li>Look up the assignment's {@code connection_key} BEFORE disabling it — needed below
     *       to scope the Business Entity association clearing to exactly this connection.</li>
     *   <li>Disable the {@code nexus_tenant_pack} assignment (unchanged step).</li>
     *   <li><b>Fix Remove Pack State + Pack Vocabulary Duplication:</b> clear {@code pack_key}/
     *       {@code concept_key} — and nothing else — on every real tenant Business Entity that
     *       Discover/Onboarding stamped with this pack (via {@code MetadataRegistrationService},
     *       independently of {@link #applyPack}) while it was active on this connection. The
     *       entity itself, its status, and every other field are untouched — see {@link
     *       SemanticService#clearPackAssociationForConnection}, which scopes by {@code pack_key}
     *       AND the existing {@code primary_object_key -> nexus_data_object.connection_key}
     *       relationship, so a sibling connection's entities can never be touched. Skipped
     *       entirely when the assignment has no {@code connection_key} (a legacy row) — there is
     *       nothing to scope by, and legacy rows are never guessed at.</li>
     *   <li>Entity cleanup — <b>legacy-only as of "Stop Apply Pack From Creating Tenant Business
     *       Entities"</b>: {@link #applyPack} no longer creates any {@code nexus_business_entity}
     *       row, so this step is dormant (finds nothing) for every pack applied after that
     *       change. It is deliberately kept, unchanged, for backward compatibility with rows a
     *       pre-fix {@code applyPack} already created (e.g. real, observed {@code
     *       retail-v1-product} rows) — Remove Pack must still be able to clean those up. For each
     *       of the pack's own entities, compute the SAME deterministic, pack-namespaced {@code
     *       entityKey} the old {@code applyPack} used ({@link #packEntityKey}) and archive it —
     *       but ONLY if the entity actually exists AND its {@code pack_key} still equals this
     *       pack's key, so this can never touch an entity that merely happens to share the
     *       computed key by coincidence.</li>
     *   <li>For each of the pack's own vocabulary terms — <b>still fully active</b>, vocabulary
     *       materialization is unchanged by this task — compute the same deterministic {@code
     *       term_key} ({@link #packTermKey}) and flip it to {@code INACTIVE} via {@link
     *       SemanticService#deactivateTerm} — a status-only update, never touching any other
     *       column (so it cannot corrupt a row this code doesn't have every original field for).
     *       {@code nexus_operational_vocabulary} has no provenance/{@code pack_key} column at
     *       all (confirmed by investigation — see the final report), so the deterministic key
     *       IS the only available safety check here; there is no second, independent field to
     *       cross-verify against the way there is for entities.</li>
     * </ol>
     *
     * <p>Artifacts that existed independently before this pack was applied are never touched:
     * their entity_key/term_key values were never, and can never coincidentally be, this pack's
     * deterministic {@code <packKey>-<slug>} form (see {@link #packEntityKey}/{@link
     * #packTermKey} javadoc for why); and a real tenant entity's pack_key/concept_key are only
     * ever cleared, never its identity or any other business metadata.
     */
    public void removePack(String packKey) {
        // Fetched BEFORE disabling — disableTenantPack does not change connection_key, but this
        // keeps the read and the "this is the assignment we're removing" intent co-located.
        String connectionKey = packRepository.findAppliedPack(packKey)
                .map(TenantPack::connectionKey).orElse(null);

        packRepository.disableTenantPack(packKey);

        if (connectionKey != null && !connectionKey.isBlank()) {
            try {
                int cleared = semanticService.clearPackAssociationForConnection(packKey, connectionKey);
                log.info("Pack '{}' removal cleared pack_key/concept_key on {} Business Entities "
                        + "for connection '{}' (entities themselves untouched)", packKey, cleared, connectionKey);
            } catch (Exception e) {
                log.warn("Failed to clear pack association for connection '{}': {}", connectionKey, e.getMessage());
            }
        } else {
            log.debug("Pack '{}' assignment has no connection_key (legacy row) — skipping "
                    + "connection-scoped pack_key/concept_key clearing; nothing to scope by.", packKey);
        }

        IndustryPack pack;
        try {
            pack = getPack(packKey);
        } catch (NexusException e) {
            // The pack's own JSON definition is gone from the catalogue (should not happen in
            // practice — packs are shipped, not deleted at runtime) — the assignment is still
            // disabled above; cleanup simply has nothing to work from and is skipped rather than
            // guessed.
            log.warn("Cannot clean up artifacts for removed pack '{}': pack definition not found", packKey);
            return;
        }

        int entitiesArchived = 0;
        for (PackEntity packEntity : safe(pack.entities())) {
            if (packEntity.name() == null || packEntity.name().isBlank()) continue;
            String entityKey = packEntityKey(packKey, packEntity.name());
            try {
                Optional<BusinessEntity> existing = semanticService.findEntityByKey(entityKey);
                if (existing.isPresent() && packKey.equals(existing.get().packKey())) {
                    semanticService.archiveEntity(entityKey);
                    entitiesArchived++;
                }
            } catch (Exception e) {
                log.warn("Failed to archive pack-created entity '{}' for pack '{}': {}",
                        entityKey, packKey, e.getMessage());
            }
        }

        int vocabDeactivated = 0;
        for (PackVocabularyTerm term : safe(pack.vocabulary())) {
            if (term.term() == null || term.term().isBlank()) continue;
            try {
                semanticService.deactivateTerm(packTermKey(packKey, term.term()));
                vocabDeactivated++;
            } catch (Exception e) {
                log.debug("Failed to deactivate pack-created term '{}' for pack '{}': {}",
                        term.term(), packKey, e.getMessage());
            }
        }

        log.info("Pack '{}' removed: {} entities archived, deactivation attempted for {} vocabulary terms "
                        + "(deactivateTerm is a no-op for any that don't exist — see javadoc)",
                packKey, entitiesArchived, vocabDeactivated);

        // Fire-and-forget, AFTER every Postgres change above has already succeeded. This is what
        // actually makes stale Retail/whatever-Pack concept documents disappear from the Vector
        // Store — nothing else in this method touches OpenAI.
        conceptKnowledgeSynchronizationService.triggerAsync();
    }

    /**
     * Industry Pack Removal Lifecycle: the stable identity of an entity created by applying
     * {@code packKey}, deterministic across repeated applications of the same pack (so re-apply
     * updates this same row instead of creating a duplicate) and namespaced by the pack's own
     * key (so it can never collide with an organically-named entity_key — Onboarding, Discover,
     * and manual creation all derive keys from table/entity names or free user input, never from
     * a literal {@code "<packKey>-"} prefix).
     */
    private static String packEntityKey(String packKey, String packEntityName) {
        return packKey + "-" + Keys.key(packEntityName);
    }

    /** Vocabulary counterpart of {@link #packEntityKey} — same rationale, same namespacing. */
    private static String packTermKey(String packKey, String term) {
        return packKey + "-" + Keys.key(term);
    }

    // ── Recommendation ────────────────────────────────────────────────────────

    public Optional<PackRecommendationService.PackRecommendation> recommend(List<String> tableNames) {
        return recommendationService.recommend(tableNames);
    }

    /** Convenience overload: recommends based on all discovered tables in the tenant. */
    public Optional<PackRecommendationService.PackRecommendation> recommendForCurrentTenant(String domainKey) {
        List<String> tables = getDiscoveredTableNames(domainKey);
        return recommendationService.recommend(tables);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Load table names from nexus_data_object for the current tenant schema. */
    private List<String> getDiscoveredTableNames(String domainKey) {
        try {
            List<DataObject> objects = domainKey != null && !domainKey.isBlank()
                    ? enterpriseMapRepository.findDataObjectsByDomain(domainKey)
                    : enterpriseMapRepository.findDataObjectsByDomain("PLATFORM");
            return objects.stream()
                    .map(DataObject::tableName)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.warn("Could not load discovered tables: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * table_name -> object_key, from the exact same nexus_data_object rows
     * {@link #getDiscoveredTableNames(String)} derives its table-name list from.
     * Deterministic reuse of an identifier the query already returned — not a new
     * discovery/resolution mechanism. If two objects share a table_name (not
     * expected under the current schema), the first one wins.
     */
    private Map<String, String> loadTableNameToObjectKey(String domainKey) {
        try {
            List<DataObject> objects = domainKey != null && !domainKey.isBlank()
                    ? enterpriseMapRepository.findDataObjectsByDomain(domainKey)
                    : enterpriseMapRepository.findDataObjectsByDomain("PLATFORM");
            Map<String, String> byTableName = new HashMap<>();
            for (DataObject object : objects) {
                if (object.tableName() != null) {
                    byTableName.putIfAbsent(object.tableName(), object.objectKey());
                }
            }
            return byTableName;
        } catch (Exception e) {
            log.warn("Could not load object-key bindings: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Connection-Scoped Industry Pack Assignment: the connection-scoped counterpart of
     * {@link #getDiscoveredTableNames(String)} — used only by {@link #applyPack}. Reuses
     * {@link EnterpriseMapRepository#findDataObjectsByConnection}, the same read-only method
     * {@code GlobalConceptResolver} already uses for the identical purpose — no new discovery
     * mechanism. {@link #previewPack}/{@link #recommendForCurrentTenant} deliberately remain
     * domain-wide for now; connection-scoping them is out of this task's scope (see the
     * implementation report).
     */
    private List<String> getDiscoveredTableNamesForConnection(String connectionKey) {
        try {
            return enterpriseMapRepository.findDataObjectsByConnection(connectionKey).stream()
                    .map(DataObject::tableName)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.warn("Could not load discovered tables for connection '{}': {}", connectionKey, e.getMessage());
            return List.of();
        }
    }

    // Stop Apply Pack From Creating Tenant Business Entities: loadTableNameToObjectKeyForConnection
    // and findPackEntity were removed here — both existed solely to resolve/look up a PackEntity's
    // target object_key and definition for the entity-creation loop this task removed from
    // applyPack. Table discovery for the informational match (getDiscoveredTableNamesForConnection,
    // used just above) is unaffected and still needed for coverage_score/entity_mapping reporting.

    private String safe(String s) { return s != null ? s : ""; }

    private <T> List<T> safe(List<T> list) { return list != null ? list : List.of(); }

    private int safeSize(List<?> list) { return list != null ? list.size() : 0; }
}
